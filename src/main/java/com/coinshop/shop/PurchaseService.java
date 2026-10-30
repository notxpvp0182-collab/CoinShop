package com.coinshop.shop;

import com.coinshop.category.ShopCategory;
import com.coinshop.config.ConfigManager;
import com.coinshop.config.MessageManager;
import com.coinshop.economy.CoinEconomyService;
import com.coinshop.economy.CurrencyFormatter;
import com.coinshop.economy.EconomyResponse;
import com.coinshop.logging.TransactionLogger;
import com.coinshop.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Authoritative server-side purchase processor.
 *
 * Security & Exploit Prevention:
 * - Re-looks up the live ShopCategory and ShopItem from ShopManager at click time (never trusts GUI lore/icons).
 * - Blocks rapid click spam via per-player timestamp gate.
 * - Rejects UNSET/zero/negative prices (prevents free-item exploits).
 * - Verifies category and item permissions on the server thread.
 * - Verifies per-item cooldowns, global stock limits, and per-player purchase limits.
 * - Verifies sufficient inventory space BEFORE charging Coins when RewardType delivers an item.
 * - Automatically refunds Coins and restores stock if reward delivery or command execution throws an exception.
 */
public final class PurchaseService {

    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final ShopManager shopManager;
    private final CoinEconomyService economyService;
    private final StorageProvider storageProvider;
    private final TransactionLogger transactionLogger;
    private final Logger logger;

    private final ConcurrentHashMap<UUID, Long> lastClickTimeMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> itemCooldownExpirations = new ConcurrentHashMap<>();

    public PurchaseService(
            ConfigManager configManager,
            MessageManager messageManager,
            ShopManager shopManager,
            CoinEconomyService economyService,
            StorageProvider storageProvider,
            TransactionLogger transactionLogger,
            Logger logger
    ) {
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.shopManager = shopManager;
        this.economyService = economyService;
        this.storageProvider = storageProvider;
        this.transactionLogger = transactionLogger;
        this.logger = logger;
    }

    public boolean attemptPurchase(Player player, String categoryId, String itemId) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long cooldownMs = configManager.getPurchaseCooldownMs();
        Long lastClick = lastClickTimeMs.get(uuid);
        if (lastClick != null && (now - lastClick) < cooldownMs) {
            messageManager.send(player, "errors.click-too-fast");
            playSound(player, configManager.getSoundName("purchase-fail"));
            return false;
        }
        lastClickTimeMs.put(uuid, now);

        // 1. Re-validate category and item from live ShopManager
        ShopCategory category = shopManager.getCategory(categoryId);
        if (category == null || !category.isEnabled()) {
            messageManager.send(player, "errors.category-not-found", Map.of("%category%", String.valueOf(categoryId)));
            playSound(player, configManager.getSoundName("purchase-fail"));
            return false;
        }

        if (!category.getPermission().isBlank() && !player.hasPermission(category.getPermission())) {
            messageManager.send(player, "shop.no-category-permission");
            playSound(player, configManager.getSoundName("purchase-fail"));
            return false;
        }

        ShopItem item = shopManager.getItem(category.getId(), itemId);
        if (item == null) {
            messageManager.send(player, "errors.item-not-found", Map.of(
                    "%item%", String.valueOf(itemId),
                    "%category%", category.getId()
            ));
            playSound(player, configManager.getSoundName("purchase-fail"));
            return false;
        }

        // 2. Price validation (never allow UNSET or <= 0)
        if (!item.isPriceValid()) {
            messageManager.send(player, "shop.price-unset");
            playSound(player, configManager.getSoundName("purchase-fail"));
            return false;
        }

        // 3. Item permission validation
        if (!item.getPermission().isBlank() && !player.hasPermission(item.getPermission())) {
            messageManager.send(player, "shop.no-item-permission");
            playSound(player, configManager.getSoundName("purchase-fail"));
            return false;
        }

        // 4. Per-item cooldown check
        String cooldownKey = uuid + ":" + category.getId() + ":" + item.getId();
        if (item.getCooldownSeconds() > 0) {
            Long expiresAt = itemCooldownExpirations.get(cooldownKey);
            if (expiresAt != null && expiresAt > now) {
                long remainingSec = Math.max(1L, (expiresAt - now + 999L) / 1000L);
                messageManager.send(player, "shop.cooldown-active", Map.of("%remaining%", Long.toString(remainingSec)));
                playSound(player, configManager.getSoundName("purchase-fail"));
                return false;
            }
        }

        // 5. Per-player purchase limit check
        if (item.getPlayerLimit() > 0) {
            int boughtCount = storageProvider.getPlayerPurchaseCount(uuid, category.getId(), item.getId());
            if (boughtCount >= item.getPlayerLimit()) {
                messageManager.send(player, "shop.limit-reached", Map.of("%limit%", Integer.toString(item.getPlayerLimit())));
                playSound(player, configManager.getSoundName("purchase-fail"));
                return false;
            }
        }

        // 6. Inventory space check BEFORE charging if item reward is involved
        RewardType rewardType = item.getRewardType();
        ItemStack rewardStack = null;
        if (rewardType == RewardType.ITEM || rewardType == RewardType.BOTH) {
            rewardStack = item.getRewardItemClone();
            if (!canFitItem(player.getInventory(), rewardStack)) {
                messageManager.send(player, "shop.inventory-full");
                playSound(player, configManager.getSoundName("purchase-fail"));
                return false;
            }
        }

        // 7. Stock check & reservation
        if (!item.consumeStock()) {
            messageManager.send(player, "shop.out-of-stock");
            playSound(player, configManager.getSoundName("purchase-fail"));
            return false;
        }

        // 8. Withdraw Coins atomically
        long price = item.getPrice();
        CurrencyFormatter fmt = economyService.getFormatter();
        EconomyResponse withdrawResp = economyService.withdraw(
                uuid,
                player.getName(),
                price,
                "PURCHASE:" + category.getId() + ":" + item.getId()
        );

        if (!withdrawResp.isSuccess()) {
            item.refundStock();
            long currentBal = economyService.getBalance(uuid);
            messageManager.send(player, "shop.insufficient-funds", Map.of(
                    "%price_raw%", fmt.formatRaw(price),
                    "%price_formatted%", fmt.formatShort(price),
                    "%balance_raw%", fmt.formatRaw(currentBal),
                    "%balance_formatted%", fmt.formatShort(currentBal),
                    "%currency%", fmt.getCurrencyName(price),
                    "%symbol%", fmt.getSymbol()
            ));
            playSound(player, configManager.getSoundName("purchase-fail"));
            return false;
        }

        // 9. Deliver rewards with automatic rollback/refund on failure
        try {
            if (rewardStack != null) {
                Map<Integer, ItemStack> leftovers = player.getInventory().addItem(rewardStack);
                if (!leftovers.isEmpty()) {
                    throw new IllegalStateException("Inventory rejected reward item during delivery.");
                }
            }

            if (rewardType == RewardType.COMMAND || rewardType == RewardType.BOTH) {
                executeRewardCommands(player, item);
            }
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Reward delivery failed for " + player.getName() + " buying " + item.getId() + "; issuing refund.", ex);
            item.refundStock();
            economyService.deposit(uuid, player.getName(), price, "REFUND_ON_FAILURE:" + category.getId() + ":" + item.getId());
            messageManager.send(player, "shop.refunded-on-failure", Map.of(
                    "%price_raw%", fmt.formatRaw(price),
                    "%currency%", fmt.getCurrencyName(price)
            ));
            playSound(player, configManager.getSoundName("purchase-fail"));
            return false;
        }

        // 10. Record limits, cooldowns, and audit logs
        if (item.getPlayerLimit() > 0) {
            storageProvider.incrementPlayerPurchaseCount(uuid, category.getId(), item.getId(), 1);
        }
        if (item.getCooldownSeconds() > 0) {
            itemCooldownExpirations.put(cooldownKey, now + (item.getCooldownSeconds() * 1000L));
        }

        transactionLogger.logPurchase(
                player.getName(),
                uuid,
                category.getId(),
                item.getId(),
                price,
                withdrawResp.newBalance()
        );

        String itemLabel = (item.getDisplayName() != null && !item.getDisplayName().isBlank())
                ? item.getDisplayName()
                : item.getDisplayMaterial().name();

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%amount%", Integer.toString(item.getAmount()));
        placeholders.put("%item%", itemLabel);
        placeholders.put("%price_raw%", fmt.formatRaw(price));
        placeholders.put("%price_formatted%", fmt.formatShort(price));
        placeholders.put("%currency%", fmt.getCurrencyName(price));
        placeholders.put("%symbol%", fmt.getSymbol());

        messageManager.send(player, "shop.purchase-success", placeholders);
        playSound(player, configManager.getSoundName("purchase-success"));
        return true;
    }

    /**
     * Substitutes ONLY approved, sanitized placeholders in configured reward commands.
     */
    private void executeRewardCommands(Player player, ShopItem item) {
        List<String> commands = item.getCommands();
        if (commands == null || commands.isEmpty()) {
            return;
        }
        String safePlayerName = player.getName().replaceAll("[^a-zA-Z0-9_.]", "");
        String safeUuid = player.getUniqueId().toString();
        String amountStr = Integer.toString(item.getAmount());
        String priceStr = Long.toString(item.getPrice());

        for (String rawCmd : commands) {
            if (rawCmd == null || rawCmd.isBlank()) {
                continue;
            }
            String cmd = rawCmd.startsWith("/") ? rawCmd.substring(1) : rawCmd;
            cmd = cmd.replace("%player%", safePlayerName)
                    .replace("%uuid%", safeUuid)
                    .replace("%amount%", amountStr)
                    .replace("%price%", priceStr)
                    .replace("%item_id%", item.getId())
                    .replace("%category_id%", item.getCategoryId());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }
    }

    public static boolean canFitItem(PlayerInventory inventory, ItemStack itemToGive) {
        if (itemToGive == null || itemToGive.getType() == Material.AIR) {
            return true;
        }
        int remaining = itemToGive.getAmount();
        int maxStack = itemToGive.getMaxStackSize();
        ItemStack[] storageContents = inventory.getStorageContents();

        for (ItemStack slotItem : storageContents) {
            if (slotItem == null || slotItem.getType() == Material.AIR) {
                remaining -= maxStack;
            } else if (slotItem.isSimilar(itemToGive) && slotItem.getAmount() < maxStack) {
                remaining -= (maxStack - slotItem.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return remaining <= 0;
    }

    private void playSound(Player player, String soundName) {
        if (soundName == null || soundName.isBlank()) {
            return;
        }
        try {
            Sound sound = Sound.valueOf(soundName.trim().toUpperCase());
            player.playSound(player.getLocation(), sound, 0.8f, 1.0f);
        } catch (IllegalArgumentException ignored) {
        }
    }

    public void cleanupPlayer(UUID uuid) {
        lastClickTimeMs.remove(uuid);
    }
}
