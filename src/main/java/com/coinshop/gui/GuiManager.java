package com.coinshop.gui;

import com.coinshop.category.ShopCategory;
import com.coinshop.config.ConfigManager;
import com.coinshop.config.MessageManager;
import com.coinshop.economy.CoinEconomyService;
import com.coinshop.economy.CurrencyFormatter;
import com.coinshop.shop.PurchaseService;
import com.coinshop.shop.RewardType;
import com.coinshop.shop.ShopItem;
import com.coinshop.shop.ShopManager;
import com.coinshop.storage.StorageProvider;
import com.coinshop.util.SmallCapsConverter;
import com.coinshop.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and opens all CoinShop Player and Admin Editor GUIs.
 */
public final class GuiManager {

    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final ShopManager shopManager;
    private final CoinEconomyService economyService;
    private final StorageProvider storageProvider;
    private final PurchaseService purchaseService;

    public GuiManager(
            ConfigManager configManager,
            MessageManager messageManager,
            ShopManager shopManager,
            CoinEconomyService economyService,
            StorageProvider storageProvider,
            PurchaseService purchaseService
    ) {
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.shopManager = shopManager;
        this.economyService = economyService;
        this.storageProvider = storageProvider;
        this.purchaseService = purchaseService;
    }

    public void openMainMenu(Player player) {
        int size = configManager.getMainMenuSize();
        CoinShopInventoryHolder holder = new CoinShopInventoryHolder(
                player.getUniqueId(),
                CoinShopInventoryHolder.MenuType.MAIN_CATEGORIES,
                null,
                null,
                1
        );
        Inventory inv = Bukkit.createInventory(holder, size, TextUtil.colorize(styleText(configManager.getMainMenuTitle())));
        holder.setInventory(inv);

        if (configManager.isMainFillerEnabled()) {
            ItemStack filler = createSimpleItem(configManager.getMainFillerMaterial(), configManager.getMainFillerName(), List.of());
            for (int i = 0; i < size; i++) {
                inv.setItem(i, filler);
            }
        }

        for (ShopCategory category : shopManager.getCategories()) {
            if (!category.isEnabled()) {
                continue;
            }
            boolean hasPerm = category.getPermission().isBlank() || player.hasPermission(category.getPermission());
            if (!hasPerm && category.isHideIfNoPermission()) {
                continue;
            }
            if (category.getSlot() >= 0 && category.getSlot() < size) {
                inv.setItem(category.getSlot(), createSimpleItem(category.getMaterial(), category.getDisplayName(), category.getLore()));
            }
        }

        if (configManager.isBalanceItemEnabled()) {
            int slot = configManager.getBalanceItemSlot();
            if (slot >= 0 && slot < size) {
                long bal = economyService.getBalance(player.getUniqueId());
                CurrencyFormatter fmt = economyService.getFormatter();
                Map<String, String> placeholders = Map.of(
                        "%balance_raw%", fmt.formatRaw(bal),
                        "%balance_formatted%", fmt.formatShort(bal),
                        "%currency%", fmt.getCurrencyName(bal),
                        "%symbol%", fmt.getSymbol()
                );
                List<String> lore = new ArrayList<>();
                for (String line : configManager.getBalanceItemLore()) {
                    lore.add(TextUtil.applyPlaceholders(line, placeholders));
                }
                inv.setItem(slot, createSimpleItem(
                        configManager.getBalanceItemMaterial(),
                        TextUtil.applyPlaceholders(configManager.getBalanceItemName(), placeholders),
                        lore
                ));
            }
        }

        if (configManager.isCloseButtonEnabled()) {
            int slot = configManager.getCloseButtonSlot();
            if (slot >= 0 && slot < size) {
                inv.setItem(slot, createSimpleItem(
                        configManager.getCloseButtonMaterial(),
                        configManager.getCloseButtonName(),
                        configManager.getCloseButtonLore()
                ));
            }
        }

        player.openInventory(inv);
        playSound(player, configManager.getSoundName("open-menu"));
    }

    public void openCategoryShop(Player player, String categoryId, int page) {
        ShopCategory category = shopManager.getCategory(categoryId);
        if (category == null || !category.isEnabled()) {
            messageManager.send(player, "errors.category-not-found", Map.of("%category%", String.valueOf(categoryId)));
            return;
        }
        if (!category.getPermission().isBlank() && !player.hasPermission(category.getPermission())) {
            messageManager.send(player, "shop.no-category-permission");
            return;
        }

        int safePage = Math.max(1, page);
        int size = category.getGuiSize();
        CoinShopInventoryHolder holder = new CoinShopInventoryHolder(
                player.getUniqueId(),
                CoinShopInventoryHolder.MenuType.CATEGORY_SHOP,
                category.getId(),
                null,
                safePage
        );
        String title = category.getGuiTitle().replace("%page%", Integer.toString(safePage));
        Inventory inv = Bukkit.createInventory(holder, size, TextUtil.colorize(styleText(title)));
        holder.setInventory(inv);

        if (category.isFillerEnabled()) {
            ItemStack filler = createSimpleItem(category.getFillerMaterial(), category.getFillerName(), List.of());
            for (int i = size - 9; i < size; i++) {
                inv.setItem(i, filler);
            }
        }

        int usableSlots = size - 9;
        boolean hasNextPage = false;
        for (ShopItem item : shopManager.getItems(category.getId())) {
            if (item.getPage() > safePage) {
                hasNextPage = true;
            }
            if (item.getPage() == safePage && item.getSlot() >= 0 && item.getSlot() < usableSlots) {
                inv.setItem(item.getSlot(), buildShopDisplayItem(player, item));
            }
        }

        // Navigation buttons
        if (safePage > 1) {
            int prevSlot = mapNavSlot(configManager.getPrevPageSlot(), size);
            inv.setItem(prevSlot, createSimpleItem(configManager.getPrevPageMaterial(), configManager.getPrevPageName(), List.of()));
        }
        int backSlot = mapNavSlot(configManager.getBackSlot(), size);
        inv.setItem(backSlot, createSimpleItem(configManager.getBackMaterial(), configManager.getBackName(), List.of()));

        if (hasNextPage) {
            int nextSlot = mapNavSlot(configManager.getNextPageSlot(), size);
            inv.setItem(nextSlot, createSimpleItem(configManager.getNextPageMaterial(), configManager.getNextPageName(), List.of()));
        }

        player.openInventory(inv);
        playSound(player, configManager.getSoundName("open-menu"));
    }

    public void openConfirmationGui(Player player, ShopCategory category, ShopItem item, int returnPage) {
        CoinShopInventoryHolder holder = new CoinShopInventoryHolder(
                player.getUniqueId(),
                CoinShopInventoryHolder.MenuType.CONFIRM_PURCHASE,
                category.getId(),
                item.getId(),
                returnPage
        );
        Inventory inv = Bukkit.createInventory(holder, 27, TextUtil.colorize(styleText(configManager.getConfirmGuiTitle())));
        holder.setInventory(inv);

        ItemStack filler = createSimpleItem(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }

        inv.setItem(13, buildShopDisplayItem(player, item));

        CurrencyFormatter fmt = economyService.getFormatter();
        long price = item.getPrice();
        ItemStack confirmBtn = createSimpleItem(
                Material.LIME_STAINED_GLASS_PANE,
                "&#55FF55&lᴄᴏɴꜰɪʀᴍ ᴘᴜʀᴄʜᴀꜱᴇ",
                List.of(
                        "&7Click to buy this item for",
                        "&#FFAA00" + fmt.getSymbol() + fmt.formatRaw(price) + " " + fmt.getCurrencyName(price)
                )
        );
        ItemStack cancelBtn = createSimpleItem(
                Material.RED_STAINED_GLASS_PANE,
                "&#FF5555&lᴄᴀɴᴄᴇʟ",
                List.of("&7Click to return to the shop.")
        );

        inv.setItem(11, confirmBtn);
        inv.setItem(15, cancelBtn);
        player.openInventory(inv);
    }

    public void openEditorCategories(Player player) {
        CoinShopInventoryHolder holder = new CoinShopInventoryHolder(
                player.getUniqueId(),
                CoinShopInventoryHolder.MenuType.EDITOR_CATEGORIES,
                null,
                null,
                1
        );
        Inventory inv = Bukkit.createInventory(holder, 54, TextUtil.colorize("&8✦ &#FFAA00ᴄᴏɪɴꜱʜᴏᴘ ᴇᴅɪᴛᴏʀ &8✦"));
        holder.setInventory(inv);

        ItemStack filler = createSimpleItem(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, filler);
        }

        for (ShopCategory category : shopManager.getCategories()) {
            if (category.getSlot() >= 0 && category.getSlot() < 45) {
                List<String> lore = new ArrayList<>();
                lore.add("&8⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯");
                lore.add("&7ID: &#55FFFF" + category.getId());
                lore.add("&7Shop File: &fshops/" + category.getShopFileName());
                lore.add("&7Items: &f" + shopManager.getItems(category.getId()).size());
                lore.add("");
                lore.add("&#55FF55▸ Left-Click: &fEdit Category Items");
                lore.add("&#FFAA00▸ Right-Click: &fRename Display Title");
                lore.add("&#FF5555▸ Shift-Right-Click: &fDelete Category");
                lore.add("&8⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯");
                inv.setItem(category.getSlot(), createSimpleItem(category.getMaterial(), category.getDisplayName(), lore));
            }
        }

        inv.setItem(49, createSimpleItem(
                Material.EMERALD,
                "&#55FF55&l+ ᴄʀᴇᴀᴛᴇ ɴᴇᴡ ᴄᴀᴛᴇɢᴏʀʏ",
                List.of("&7Click to enter a new category ID in chat.")
        ));
        inv.setItem(53, createSimpleItem(
                Material.BARRIER,
                "&#FF5555&lᴄʟᴏꜱᴇ ᴇᴅɪᴛᴏʀ",
                List.of("&7Click to close.")
        ));

        player.openInventory(inv);
    }

    public void openEditorShopSlots(Player player, String categoryId, int page) {
        ShopCategory category = shopManager.getCategory(categoryId);
        if (category == null) {
            messageManager.send(player, "errors.category-not-found", Map.of("%category%", String.valueOf(categoryId)));
            return;
        }
        int safePage = Math.max(1, page);
        CoinShopInventoryHolder holder = new CoinShopInventoryHolder(
                player.getUniqueId(),
                CoinShopInventoryHolder.MenuType.EDITOR_SHOP_SLOTS,
                category.getId(),
                null,
                safePage
        );
        Inventory inv = Bukkit.createInventory(
                holder,
                54,
                TextUtil.colorize("&8[&#FFAA00ᴇᴅɪᴛᴏʀ&8] &f" + category.getId() + " &7(P" + safePage + ")")
        );
        holder.setInventory(inv);

        ItemStack navFiller = createSimpleItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, navFiller);
        }

        for (ShopItem item : shopManager.getItems(category.getId())) {
            if (item.getPage() == safePage && item.getSlot() >= 0 && item.getSlot() < 45) {
                ItemStack icon = item.getRewardItemClone();
                ItemMeta meta = icon.getItemMeta();
                if (meta != null) {
                    if (!item.getDisplayName().isBlank()) {
                        meta.displayName(TextUtil.colorize(item.getDisplayName()));
                    }
                    List<Component> lore = new ArrayList<>();
                    lore.add(TextUtil.colorize("&8⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯"));
                    lore.add(TextUtil.colorize("&7ID: &#55FFFF" + item.getId()));
                    lore.add(TextUtil.colorize("&7Price: " + (item.isPriceValid() ? "&#FFAA00" + item.getPrice() : "&#FF5555UNSET")));
                    lore.add(TextUtil.colorize("&7Reward Type: &f" + item.getRewardType().name()));
                    lore.add(Component.empty());
                    lore.add(TextUtil.colorize("&#55FF55▸ Left-Click: &fEdit Item Properties"));
                    lore.add(TextUtil.colorize("&#FF5555▸ Shift-Right-Click: &fRemove Item"));
                    lore.add(TextUtil.colorize("&8⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯⎯"));
                    meta.lore(lore);
                    icon.setItemMeta(meta);
                }
                inv.setItem(item.getSlot(), icon);
            }
        }

        if (safePage > 1) {
            inv.setItem(45, createSimpleItem(Material.ARROW, "&#FFAA00« Previous Page", List.of()));
        }
        inv.setItem(48, createSimpleItem(
                Material.HOPPER,
                "&#55FF55&l+ ᴀᴅᴅ ʜᴇʟᴅ ɪᴛᴇᴍ",
                List.of(
                        "&7Click to add the item currently in",
                        "&7your main hand to this category",
                        "&7(or click an item in your bottom",
                        "&7inventory while this menu is open)."
                )
        ));
        inv.setItem(49, createSimpleItem(Material.DARK_OAK_DOOR, "&#FF5555« Back to Categories", List.of()));
        inv.setItem(53, createSimpleItem(Material.ARROW, "&#FFAA00Next Page »", List.of()));

        player.openInventory(inv);
    }

    public void openEditorItemProperties(Player player, String categoryId, String itemId) {
        ShopCategory category = shopManager.getCategory(categoryId);
        ShopItem item = shopManager.getItem(categoryId, itemId);
        if (category == null || item == null) {
            messageManager.send(player, "errors.item-not-found", Map.of(
                    "%item%", String.valueOf(itemId),
                    "%category%", String.valueOf(categoryId)
            ));
            return;
        }

        CoinShopInventoryHolder holder = new CoinShopInventoryHolder(
                player.getUniqueId(),
                CoinShopInventoryHolder.MenuType.EDITOR_ITEM_PROPERTIES,
                category.getId(),
                item.getId(),
                item.getPage()
        );
        Inventory inv = Bukkit.createInventory(
                holder,
                36,
                TextUtil.colorize("&8[&#FFAA00ᴇᴅɪᴛ ɪᴛᴇᴍ&8] &f" + item.getId())
        );
        holder.setInventory(inv);

        ItemStack filler = createSimpleItem(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, filler);
        }

        inv.setItem(4, buildShopDisplayItem(player, item));

        inv.setItem(10, createSimpleItem(
                Material.GOLD_INGOT,
                "&#FFAA00&lEdit Price",
                List.of(
                        "&7Current: &f" + (item.isPriceValid() ? item.getPrice() : "UNSET"),
                        "&eClick to type a new price in chat."
                )
        ));
        inv.setItem(11, createSimpleItem(
                Material.NAME_TAG,
                "&#55FFFF&lEdit Display Name",
                List.of(
                        "&7Current: &f" + (item.getDisplayName().isBlank() ? "(Default Item Name)" : item.getDisplayName()),
                        "&eClick to type a new display name in chat."
                )
        ));
        inv.setItem(12, createSimpleItem(
                Material.ITEM_FRAME,
                "&#55FF55&lEdit Amount",
                List.of(
                        "&7Current: &f" + item.getAmount(),
                        "&eClick to type a new stack amount (1-64)."
                )
        ));
        inv.setItem(13, createSimpleItem(
                Material.COMPASS,
                "&#FFAA00&lEdit Slot",
                List.of(
                        "&7Current Page: &f" + item.getPage() + " &7| Slot: &f" + item.getSlot(),
                        "&eClick to type a new slot (0-44)."
                )
        ));
        inv.setItem(14, createSimpleItem(
                Material.TRIPWIRE_HOOK,
                "&#55FFFF&lEdit Permission",
                List.of(
                        "&7Current: &f" + (item.getPermission().isBlank() ? "(None)" : item.getPermission()),
                        "&eClick to set permission (or 'none' to clear)."
                )
        ));
        inv.setItem(15, createSimpleItem(
                Material.COMMAND_BLOCK,
                "&#FFAA00&lCycle Reward Type",
                List.of(
                        "&7Current: &#55FF55" + item.getRewardType().name(),
                        "&eClick to cycle ITEM -> COMMAND -> BOTH."
                )
        ));
        inv.setItem(16, createSimpleItem(
                Material.WRITABLE_BOOK,
                "&#55FFFF&lAdd/Clear Reward Command",
                List.of(
                        "&7Commands (" + item.getCommands().size() + "):",
                        "&f" + (item.getCommands().isEmpty() ? "(None)" : String.join(", ", item.getCommands())),
                        "&eLeft-Click: Add command in chat",
                        "&cRight-Click: Clear all commands"
                )
        ));

        inv.setItem(31, createSimpleItem(
                Material.DARK_OAK_DOOR,
                "&#55FF55« Save & Return to Category Editor",
                List.of("&7Click to save changes to disk and return.")
        ));

        player.openInventory(inv);
    }

    public ItemStack buildShopDisplayItem(Player player, ShopItem item) {
        ItemStack base = item.getRewardItemClone();
        base.setType(item.getDisplayMaterial());
        base.setAmount(Math.max(1, Math.min(64, item.getAmount())));

        ItemMeta meta = base.getItemMeta();
        if (meta == null) {
            return base;
        }

        if (item.getDisplayName() != null && !item.getDisplayName().isBlank()) {
            meta.displayName(TextUtil.colorize(item.getDisplayName()));
        }

        long balance = economyService.getBalance(player.getUniqueId());
        CurrencyFormatter fmt = economyService.getFormatter();
        long price = item.getPrice();

        String priceRaw = item.isPriceValid() ? fmt.formatRaw(price) : "UNSET";
        String priceShort = item.isPriceValid() ? fmt.formatShort(price) : "UNSET";

        String promptKey;
        int boughtCount = item.getPlayerLimit() > 0
                ? storageProvider.getPlayerPurchaseCount(player.getUniqueId(), item.getCategoryId(), item.getId())
                : 0;

        if (!item.isPriceValid()) {
            promptKey = "unset-price";
        } else if (!item.getPermission().isBlank() && !player.hasPermission(item.getPermission())) {
            promptKey = "no-permission";
        } else if (item.getStockLimit() >= 0 && item.getCurrentStock() <= 0) {
            promptKey = "out-of-stock";
        } else if (item.getPlayerLimit() > 0 && boughtCount >= item.getPlayerLimit()) {
            promptKey = "limit-reached";
        } else if (balance < price) {
            promptKey = "insufficient-funds";
        } else {
            promptKey = "available";
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%price_raw%", priceRaw);
        placeholders.put("%price_formatted%", priceShort);
        placeholders.put("%balance_raw%", fmt.formatRaw(balance));
        placeholders.put("%balance_formatted%", fmt.formatShort(balance));
        placeholders.put("%currency%", fmt.getCurrencyName(price > 0 ? price : 0));
        placeholders.put("%symbol%", fmt.getSymbol());
        placeholders.put("%action_prompt%", configManager.getPrompt(promptKey));

        List<String> finalLoreStrings = new ArrayList<>();
        for (String formatLine : configManager.getShopLoreFormat()) {
            if ("%item_lore%".equals(formatLine.trim())) {
                finalLoreStrings.addAll(item.getDisplayLore());
            } else if ("%stock_line%".equals(formatLine.trim())) {
                if (item.getStockLimit() >= 0) {
                    finalLoreStrings.add("&7Stock: &f" + item.getCurrentStock() + "&7/&f" + item.getStockLimit());
                }
            } else if ("%limit_line%".equals(formatLine.trim())) {
                if (item.getPlayerLimit() > 0) {
                    finalLoreStrings.add("&7Your Limit: &f" + boughtCount + "&7/&f" + item.getPlayerLimit());
                }
            } else {
                finalLoreStrings.add(TextUtil.applyPlaceholders(formatLine, placeholders));
            }
        }

        meta.lore(TextUtil.colorizeList(finalLoreStrings));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        base.setItemMeta(meta);
        return base;
    }

    private ItemStack createSimpleItem(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material != null ? material : Material.STONE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(TextUtil.colorize(name));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(TextUtil.colorizeList(lore));
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private int mapNavSlot(int configuredSlot, int invSize) {
        if (configuredSlot >= 0 && configuredSlot < invSize) {
            return configuredSlot;
        }
        return Math.max(0, invSize - 5);
    }

    private String styleText(String input) {
        if (configManager.isUseSmallCaps()) {
            return SmallCapsConverter.toSmallCaps(input);
        }
        return input;
    }

    private void playSound(Player player, String soundName) {
        if (soundName == null || soundName.isBlank()) {
            return;
        }
        try {
            Sound sound = Sound.valueOf(soundName.trim().toUpperCase());
            player.playSound(player.getLocation(), sound, 0.7f, 1.0f);
        } catch (IllegalArgumentException ignored) {
        }
    }

    public PurchaseService getPurchaseService() {
        return purchaseService;
    }
}
