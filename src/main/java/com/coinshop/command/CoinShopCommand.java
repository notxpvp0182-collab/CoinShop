package com.coinshop.command;

import com.coinshop.CoinShopPlugin;
import com.coinshop.category.ShopCategory;
import com.coinshop.config.MessageManager;
import com.coinshop.economy.CoinEconomyService;
import com.coinshop.economy.CurrencyFormatter;
import com.coinshop.economy.EconomyResponse;
import com.coinshop.economy.TransactionRecord;
import com.coinshop.economy.TransferResult;
import com.coinshop.gui.GuiManager;
import com.coinshop.logging.TransactionLogger;
import com.coinshop.shop.ShopItem;
import com.coinshop.shop.ShopManager;
import com.coinshop.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Full command executor and permission-aware tab completer for /coinshop.
 */
public final class CoinShopCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final CoinShopPlugin plugin;
    private final MessageManager messageManager;
    private final CoinEconomyService economyService;
    private final ShopManager shopManager;
    private final GuiManager guiManager;
    private final TransactionLogger transactionLogger;

    public CoinShopCommand(
            CoinShopPlugin plugin,
            MessageManager messageManager,
            CoinEconomyService economyService,
            ShopManager shopManager,
            GuiManager guiManager,
            TransactionLogger transactionLogger
    ) {
        this.plugin = plugin;
        this.messageManager = messageManager;
        this.economyService = economyService;
        this.shopManager = shopManager;
        this.guiManager = guiManager;
        this.transactionLogger = transactionLogger;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sendHelp(sender);
                return true;
            }
            if (!player.hasPermission("coinshop.use")) {
                messageManager.send(player, "errors.no-permission", Map.of("%permission%", "coinshop.use"));
                return true;
            }
            guiManager.openMainMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> sendHelp(sender);
            case "shop" -> handleShop(sender, args);
            case "balance", "bal" -> handleBalance(sender, args);
            case "gift", "pay" -> handleGift(sender, args);
            case "top" -> handleTop(sender);
            case "history" -> handleHistory(sender, args);
            case "give", "add" -> handleGiveTakeSet(sender, args, "GIVE");
            case "take", "remove" -> handleGiveTakeSet(sender, args, "TAKE");
            case "set" -> handleGiveTakeSet(sender, args, "SET");
            case "addcategory" -> handleAddCategory(sender, args);
            case "removecategory" -> handleRemoveCategory(sender, args);
            case "addhanditem" -> handleAddHandItem(sender, args);
            case "edititem" -> handleEditItem(sender, args);
            case "removeitem" -> handleRemoveItem(sender, args);
            case "editor" -> handleEditor(sender, args);
            case "reload" -> handleReload(sender);
            case "validate" -> handleValidate(sender);
            case "info" -> handleInfo(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleShop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messageManager.send(sender, "errors.players-only");
            return;
        }
        if (!player.hasPermission("coinshop.use")) {
            messageManager.send(player, "errors.no-permission", Map.of("%permission%", "coinshop.use"));
            return;
        }
        if (args.length < 2) {
            guiManager.openMainMenu(player);
            return;
        }
        guiManager.openCategoryShop(player, args[1], 1);
    }

    private void handleBalance(CommandSender sender, String[] args) {
        CurrencyFormatter fmt = economyService.getFormatter();
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                messageManager.send(sender, "errors.players-only");
                return;
            }
            if (!player.hasPermission("coinshop.balance")) {
                messageManager.send(player, "errors.no-permission", Map.of("%permission%", "coinshop.balance"));
                return;
            }
            long bal = economyService.getBalance(player.getUniqueId());
            messageManager.send(player, "balance.self", Map.of(
                    "%balance_raw%", fmt.formatRaw(bal),
                    "%balance_formatted%", fmt.formatShort(bal),
                    "%currency%", fmt.getCurrencyName(bal),
                    "%symbol%", fmt.getSymbol()
            ));
            return;
        }

        if (!sender.hasPermission("coinshop.balance.others") && !sender.hasPermission("coinshop.admin")) {
            messageManager.send(sender, "errors.no-permission", Map.of("%permission%", "coinshop.balance.others"));
            return;
        }

        OfflinePlayer target = resolveOfflinePlayer(args[1]);
        if (target == null) {
            messageManager.send(sender, "errors.player-not-found", Map.of("%player%", args[1]));
            return;
        }
        long bal = economyService.getBalance(target.getUniqueId());
        String name = target.getName() != null ? target.getName() : args[1];
        messageManager.send(sender, "balance.other", Map.of(
                "%player%", name,
                "%balance_raw%", fmt.formatRaw(bal),
                "%balance_formatted%", fmt.formatShort(bal),
                "%currency%", fmt.getCurrencyName(bal),
                "%symbol%", fmt.getSymbol()
        ));
    }

    /**
     * Handles /coinshop gift <player> <amount> (supports normal numbers, K, M, B).
     */
    private void handleGift(CommandSender sender, String[] args) {
        if (!(sender instanceof Player senderPlayer)) {
            messageManager.send(sender, "errors.players-only");
            return;
        }
        if (!senderPlayer.hasPermission("coinshop.gift") && !senderPlayer.hasPermission("coinshop.admin")) {
            messageManager.send(senderPlayer, "errors.no-permission", Map.of("%permission%", "coinshop.gift"));
            return;
        }
        if (args.length < 3) {
            messageManager.send(senderPlayer, "balance.gift-usage");
            return;
        }

        Player receiverPlayer = Bukkit.getPlayerExact(args[1]);
        if (receiverPlayer == null || !receiverPlayer.isOnline()) {
            messageManager.send(senderPlayer, "errors.player-not-online", Map.of("%player%", args[1]));
            return;
        }

        if (senderPlayer.getUniqueId().equals(receiverPlayer.getUniqueId())) {
            messageManager.send(senderPlayer, "errors.cannot-gift-self");
            return;
        }

        long amount = CurrencyFormatter.parseAmount(args[2]);
        if (amount <= 0L) {
            messageManager.send(senderPlayer, "errors.invalid-amount", Map.of("%amount%", args[2]));
            return;
        }

        CurrencyFormatter fmt = economyService.getFormatter();
        TransferResult result = economyService.transfer(
                senderPlayer.getUniqueId(),
                senderPlayer.getName(),
                receiverPlayer.getUniqueId(),
                receiverPlayer.getName(),
                amount
        );

        switch (result.status()) {
            case SELF_TRANSFER -> messageManager.send(senderPlayer, "errors.cannot-gift-self");
            case INVALID_AMOUNT -> messageManager.send(senderPlayer, "errors.invalid-amount", Map.of("%amount%", args[2]));
            case INSUFFICIENT_FUNDS -> messageManager.send(senderPlayer, "balance.gift-insufficient", Map.of(
                    "%amount_raw%", fmt.formatRaw(amount),
                    "%amount_formatted%", fmt.formatShort(amount),
                    "%balance_raw%", fmt.formatRaw(result.senderNewBalance()),
                    "%balance_formatted%", fmt.formatShort(result.senderNewBalance()),
                    "%currency%", fmt.getCurrencyName(amount),
                    "%symbol%", fmt.getSymbol()
            ));
            case RECEIVER_OVERFLOW -> messageManager.send(senderPlayer, "balance.overflow");
            case SUCCESS -> {
                transactionLogger.logGift(
                        senderPlayer.getName(),
                        senderPlayer.getUniqueId(),
                        receiverPlayer.getName(),
                        receiverPlayer.getUniqueId(),
                        amount,
                        result.senderNewBalance(),
                        result.receiverNewBalance()
                );

                messageManager.send(senderPlayer, "balance.gift-sent", Map.of(
                        "%receiver%", receiverPlayer.getName(),
                        "%amount_raw%", fmt.formatRaw(amount),
                        "%amount_formatted%", fmt.formatShort(amount),
                        "%balance_raw%", fmt.formatRaw(result.senderNewBalance()),
                        "%balance_formatted%", fmt.formatShort(result.senderNewBalance()),
                        "%currency%", fmt.getCurrencyName(amount),
                        "%symbol%", fmt.getSymbol()
                ));

                messageManager.send(receiverPlayer, "balance.gift-received", Map.of(
                        "%sender%", senderPlayer.getName(),
                        "%amount_raw%", fmt.formatRaw(amount),
                        "%amount_formatted%", fmt.formatShort(amount),
                        "%balance_raw%", fmt.formatRaw(result.receiverNewBalance()),
                        "%balance_formatted%", fmt.formatShort(result.receiverNewBalance()),
                        "%currency%", fmt.getCurrencyName(amount),
                        "%symbol%", fmt.getSymbol()
                ));
            }
        }
    }

    private void handleTop(CommandSender sender) {
        if (!sender.hasPermission("coinshop.top")) {
            messageManager.send(sender, "errors.no-permission", Map.of("%permission%", "coinshop.top"));
            return;
        }
        CurrencyFormatter fmt = economyService.getFormatter();
        Map<UUID, Long> top = economyService.getTopBalances(10);
        sender.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&#FFAA00&lTop Coin Balances:"));
        int rank = 1;
        for (Map.Entry<UUID, Long> entry : top.entrySet()) {
            OfflinePlayer p = Bukkit.getOfflinePlayer(entry.getKey());
            String name = p.getName() != null ? p.getName() : entry.getKey().toString().substring(0, 8);
            long bal = entry.getValue();
            sender.sendMessage(TextUtil.colorize(
                    "&8 " + rank + ". &f" + name + " &8- &#FFAA00" + fmt.getSymbol() + fmt.formatShort(bal)
                            + " &7(" + fmt.formatRaw(bal) + ")"
            ));
            rank++;
        }
    }

    private void handleHistory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("coinshop.history") && !sender.hasPermission("coinshop.admin")) {
            messageManager.send(sender, "errors.no-permission", Map.of("%permission%", "coinshop.history"));
            return;
        }
        OfflinePlayer target;
        if (args.length >= 2) {
            target = resolveOfflinePlayer(args[1]);
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&cUsage: /coinshop history <player>"));
            return;
        }

        if (target == null) {
            messageManager.send(sender, "errors.player-not-found", Map.of("%player%", args.length >= 2 ? args[1] : ""));
            return;
        }

        String targetName = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        economyService.getRecentTransactionsAsync(target.getUniqueId(), 10).thenAccept(records ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&#FFAA00Recent Transactions for &f" + targetName + "&7:"));
                    if (records.isEmpty()) {
                        sender.sendMessage(TextUtil.colorize("&7 No recorded transactions."));
                        return;
                    }
                    for (TransactionRecord rec : records) {
                        sender.sendMessage(TextUtil.colorize(String.format(
                                "&8[%s] &#55FFFF%s &f%d &7(Bal: %d -> %d) &8%s",
                                TIME_FMT.format(rec.timestamp()),
                                rec.actionType(),
                                rec.amount(),
                                rec.balanceBefore(),
                                rec.balanceAfter(),
                                rec.details()
                        )));
                    }
                })
        );
    }

    private void handleGiveTakeSet(CommandSender sender, String[] args, String mode) {
        String perm = switch (mode) {
            case "GIVE" -> "coinshop.admin.give";
            case "TAKE" -> "coinshop.admin.take";
            default -> "coinshop.admin.set";
        };
        if (!sender.hasPermission(perm) && !sender.hasPermission("coinshop.admin")) {
            messageManager.send(sender, "errors.no-permission", Map.of("%permission%", perm));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&cUsage: /coinshop " + mode.toLowerCase() + " <player> <amount>"));
            return;
        }

        OfflinePlayer target = resolveOfflinePlayer(args[1]);
        if (target == null) {
            messageManager.send(sender, "errors.player-not-found", Map.of("%player%", args[1]));
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException ex) {
            messageManager.send(sender, "errors.invalid-amount", Map.of("%amount%", args[2]));
            return;
        }

        if (("GIVE".equals(mode) || "TAKE".equals(mode)) && amount <= 0L) {
            messageManager.send(sender, "errors.invalid-amount", Map.of("%amount%", args[2]));
            return;
        }
        if ("SET".equals(mode) && amount < 0L) {
            messageManager.send(sender, "errors.invalid-amount", Map.of("%amount%", args[2]));
            return;
        }

        String targetName = target.getName() != null ? target.getName() : args[1];
        String actor = sender.getName();
        CurrencyFormatter fmt = economyService.getFormatter();

        EconomyResponse resp = switch (mode) {
            case "GIVE" -> economyService.deposit(target.getUniqueId(), targetName, amount, "ADMIN_GIVE by " + actor);
            case "TAKE" -> economyService.withdraw(target.getUniqueId(), targetName, amount, "ADMIN_TAKE by " + actor);
            default -> economyService.setBalance(target.getUniqueId(), targetName, amount, "ADMIN_SET by " + actor);
        };

        if (!resp.isSuccess()) {
            if (resp.type() == EconomyResponse.ResultType.INSUFFICIENT_FUNDS) {
                messageManager.send(sender, "balance.insufficient-target", Map.of(
                        "%player%", targetName,
                        "%balance_raw%", fmt.formatRaw(resp.previousBalance()),
                        "%currency%", fmt.getCurrencyName(resp.previousBalance())
                ));
            } else if (resp.type() == EconomyResponse.ResultType.OVERFLOW) {
                messageManager.send(sender, "balance.overflow");
            } else {
                messageManager.send(sender, "errors.invalid-amount", Map.of("%amount%", args[2]));
            }
            return;
        }

        transactionLogger.logAdminAction(actor, mode, targetName, target.getUniqueId(), amount, resp.newBalance());

        Map<String, String> placeholders = Map.of(
                "%player%", targetName,
                "%amount_raw%", fmt.formatRaw(amount),
                "%amount_formatted%", fmt.formatShort(amount),
                "%balance_raw%", fmt.formatRaw(resp.newBalance()),
                "%balance_formatted%", fmt.formatShort(resp.newBalance()),
                "%currency%", fmt.getCurrencyName(amount),
                "%symbol%", fmt.getSymbol()
        );

        String msgKey = switch (mode) {
            case "GIVE" -> "balance.given";
            case "TAKE" -> "balance.taken";
            default -> "balance.set";
        };
        messageManager.send(sender, msgKey, placeholders);
    }

    private void handleAddCategory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("coinshop.admin.category") && !sender.hasPermission("coinshop.admin")) {
            messageManager.send(sender, "errors.no-permission", Map.of("%permission%", "coinshop.admin.category"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&cUsage: /coinshop addcategory <id>"));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!TextUtil.isValidId(id)) {
            messageManager.send(sender, "errors.invalid-id", Map.of("%id%", args[1]));
            return;
        }
        if (shopManager.getCategory(id) != null) {
            messageManager.send(sender, "errors.category-already-exists", Map.of("%category%", id));
            return;
        }
        try {
            shopManager.createCategory(id);
            messageManager.send(sender, "admin.category-created", Map.of("%category%", id));
        } catch (IOException e) {
            messageManager.send(sender, "errors.storage-error");
        }
    }

    private void handleRemoveCategory(CommandSender sender, String[] args) {
        if (!sender.hasPermission("coinshop.admin.category") && !sender.hasPermission("coinshop.admin")) {
            messageManager.send(sender, "errors.no-permission", Map.of("%permission%", "coinshop.admin.category"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&cUsage: /coinshop removecategory <id>"));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!shopManager.removeCategory(id)) {
            messageManager.send(sender, "errors.category-not-found", Map.of("%category%", id));
            return;
        }
        messageManager.send(sender, "admin.category-removed", Map.of("%category%", id));
    }

    private void handleAddHandItem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messageManager.send(sender, "errors.players-only");
            return;
        }
        if (!player.hasPermission("coinshop.admin.item") && !player.hasPermission("coinshop.admin")) {
            messageManager.send(player, "errors.no-permission", Map.of("%permission%", "coinshop.admin.item"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&cUsage: /coinshop addhanditem <category> [price] [item-id]"));
            return;
        }

        String categoryId = args[1].toLowerCase(Locale.ROOT);
        ShopCategory category = shopManager.getCategory(categoryId);
        if (category == null) {
            messageManager.send(player, "errors.category-not-found", Map.of("%category%", categoryId));
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR || held.getAmount() <= 0) {
            messageManager.send(player, "errors.empty-hand");
            return;
        }

        long price = ShopItem.UNSET_PRICE;
        if (args.length >= 3) {
            try {
                price = Long.parseLong(args[2]);
                if (price <= 0L) {
                    messageManager.send(player, "errors.invalid-amount", Map.of("%amount%", args[2]));
                    return;
                }
            } catch (NumberFormatException ex) {
                messageManager.send(player, "errors.invalid-amount", Map.of("%amount%", args[2]));
                return;
            }
        }

        String requestedId = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : null;
        if (requestedId != null && !TextUtil.isValidId(requestedId)) {
            messageManager.send(player, "errors.invalid-id", Map.of("%id%", requestedId));
            return;
        }

        try {
            ShopItem created = shopManager.addHandItemToCategory(category.getId(), held, price, requestedId);
            String priceStatus = created.isPriceValid()
                    ? economyService.getFormatter().formatRaw(created.getPrice()) + " " + economyService.getFormatter().getCurrencyName(created.getPrice())
                    : "UNSET (Marked unavailable until priced)";

            messageManager.send(player, "admin.hand-item-added", Map.of(
                    "%category%", category.getId(),
                    "%item_id%", created.getId(),
                    "%shop_file%", category.getShopFileName(),
                    "%slot%", Integer.toString(created.getSlot()),
                    "%price_status%", priceStatus
            ));
        } catch (IOException e) {
            messageManager.send(player, "errors.storage-error");
        }
    }

    private void handleEditItem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messageManager.send(sender, "errors.players-only");
            return;
        }
        if (!player.hasPermission("coinshop.admin.item") && !player.hasPermission("coinshop.admin")) {
            messageManager.send(player, "errors.no-permission", Map.of("%permission%", "coinshop.admin.item"));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&cUsage: /coinshop edititem <category> <item-id>"));
            return;
        }
        guiManager.openEditorItemProperties(player, args[1], args[2]);
    }

    private void handleRemoveItem(CommandSender sender, String[] args) {
        if (!sender.hasPermission("coinshop.admin.item") && !sender.hasPermission("coinshop.admin")) {
            messageManager.send(sender, "errors.no-permission", Map.of("%permission%", "coinshop.admin.item"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&cUsage: /coinshop removeitem <category> <item-id>"));
            return;
        }
        try {
            if (!shopManager.removeItem(args[1], args[2])) {
                messageManager.send(sender, "errors.item-not-found", Map.of("%item%", args[2], "%category%", args[1]));
                return;
            }
            messageManager.send(sender, "admin.item-removed", Map.of("%item_id%", args[2], "%category%", args[1]));
        } catch (IOException e) {
            messageManager.send(sender, "errors.storage-error");
        }
    }

    private void handleEditor(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messageManager.send(sender, "errors.players-only");
            return;
        }
        if (!player.hasPermission("coinshop.editor") && !player.hasPermission("coinshop.admin")) {
            messageManager.send(player, "errors.no-permission", Map.of("%permission%", "coinshop.editor"));
            return;
        }
        if (args.length >= 2) {
            guiManager.openEditorShopSlots(player, args[1], 1);
        } else {
            guiManager.openEditorCategories(player);
        }
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("coinshop.reload") && !sender.hasPermission("coinshop.admin")) {
            messageManager.send(sender, "errors.no-permission", Map.of("%permission%", "coinshop.reload"));
            return;
        }
        List<String> errors = plugin.reloadEverythingSafely();
        if (!errors.isEmpty()) {
            messageManager.send(sender, "admin.reload-failed");
            for (String err : errors) {
                sender.sendMessage(TextUtil.colorize("&c • " + err));
            }
            return;
        }
        messageManager.send(sender, "admin.reload-success", Map.of(
                "%categories%", Integer.toString(shopManager.getCategories().size()),
                "%items%", Integer.toString(shopManager.getTotalItemCount())
        ));
    }

    private void handleValidate(CommandSender sender) {
        if (!sender.hasPermission("coinshop.validate") && !sender.hasPermission("coinshop.admin")) {
            messageManager.send(sender, "errors.no-permission", Map.of("%permission%", "coinshop.validate"));
            return;
        }
        List<String> errors = new ArrayList<>();
        plugin.getConfigManager().reloadAndValidate(errors);
        shopManager.loadAndValidate(errors, true);

        if (errors.isEmpty()) {
            messageManager.send(sender, "admin.validate-clean", Map.of(
                    "%categories%", Integer.toString(shopManager.getCategories().size()),
                    "%items%", Integer.toString(shopManager.getTotalItemCount())
            ));
        } else {
            messageManager.send(sender, "admin.validate-errors", Map.of("%count%", Integer.toString(errors.size())));
            for (String err : errors) {
                sender.sendMessage(TextUtil.colorize("&c • " + err));
            }
        }
    }

    private void handleInfo(CommandSender sender) {
        sender.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&#FFAA00&lCoinShop v" + plugin.getPluginMeta().getVersion()));
        sender.sendMessage(TextUtil.colorize("&7 • Server: &f" + Bukkit.getName() + " " + Bukkit.getVersion()));
        sender.sendMessage(TextUtil.colorize("&7 • Java Runtime: &f" + System.getProperty("java.version")));
        sender.sendMessage(TextUtil.colorize("&7 • Storage Backend: &#55FFFF" + plugin.getConfigManager().getStorageType()));
        sender.sendMessage(TextUtil.colorize("&7 • Loaded Categories: &f" + shopManager.getCategories().size()));
        sender.sendMessage(TextUtil.colorize("&7 • Loaded Shop Items: &f" + shopManager.getTotalItemCount()));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&#FFAA00&lCoinShop Commands:"));
        sender.sendMessage(TextUtil.colorize("&8 • &#55FFFF/coinshop &7- Open the main CoinShop menu"));
        sender.sendMessage(TextUtil.colorize("&8 • &#55FFFF/coinshop shop <category> &7- Open a specific category"));
        sender.sendMessage(TextUtil.colorize("&8 • &#55FFFF/coinshop balance [player] &7- View exact Coin balance"));
        sender.sendMessage(TextUtil.colorize("&8 • &#55FFFF/coinshop gift <player> <amount> &7- Gift Coins to a player (100, 1k, 5k, 1m)"));
        sender.sendMessage(TextUtil.colorize("&8 • &#55FFFF/coinshop top &7- View top Coin balances"));
        if (sender.hasPermission("coinshop.admin") || sender.hasPermission("coinshop.editor")) {
            sender.sendMessage(TextUtil.colorize("&8 • &#FFAA00/coinshop give|take|set <player> <amount>"));
            sender.sendMessage(TextUtil.colorize("&8 • &#FFAA00/coinshop addhanditem <category> [price] [id]"));
            sender.sendMessage(TextUtil.colorize("&8 • &#FFAA00/coinshop edititem|removeitem <category> <id>"));
            sender.sendMessage(TextUtil.colorize("&8 • &#FFAA00/coinshop addcategory|removecategory <id>"));
            sender.sendMessage(TextUtil.colorize("&8 • &#FFAA00/coinshop editor [category]"));
            sender.sendMessage(TextUtil.colorize("&8 • &#FFAA00/coinshop validate | reload | history | info"));
        }
    }

    private OfflinePlayer resolveOfflinePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            return cached;
        }
        return null;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("coinshop.use")) {
                completions.add("shop");
                completions.add("help");
            }
            if (sender.hasPermission("coinshop.balance")) {
                completions.add("balance");
            }
            if (sender.hasPermission("coinshop.gift") || sender.hasPermission("coinshop.admin")) {
                completions.add("gift");
            }
            if (sender.hasPermission("coinshop.top")) {
                completions.add("top");
            }
            if (sender.hasPermission("coinshop.history") || sender.hasPermission("coinshop.admin")) {
                completions.add("history");
            }
            if (sender.hasPermission("coinshop.admin.give") || sender.hasPermission("coinshop.admin")) {
                completions.add("give");
            }
            if (sender.hasPermission("coinshop.admin.take") || sender.hasPermission("coinshop.admin")) {
                completions.add("take");
            }
            if (sender.hasPermission("coinshop.admin.set") || sender.hasPermission("coinshop.admin")) {
                completions.add("set");
            }
            if (sender.hasPermission("coinshop.admin.category") || sender.hasPermission("coinshop.admin")) {
                completions.add("addcategory");
                completions.add("removecategory");
            }
            if (sender.hasPermission("coinshop.admin.item") || sender.hasPermission("coinshop.admin")) {
                completions.add("addhanditem");
                completions.add("edititem");
                completions.add("removeitem");
            }
            if (sender.hasPermission("coinshop.editor") || sender.hasPermission("coinshop.admin")) {
                completions.add("editor");
            }
            if (sender.hasPermission("coinshop.reload") || sender.hasPermission("coinshop.admin")) {
                completions.add("reload");
            }
            if (sender.hasPermission("coinshop.validate") || sender.hasPermission("coinshop.admin")) {
                completions.add("validate");
                completions.add("info");
            }
            return filterPrefix(completions, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "shop", "removecategory", "addhanditem", "edititem", "removeitem", "editor" -> {
                    if (hasSubPermission(sender, sub)) {
                        for (ShopCategory cat : shopManager.getCategories()) {
                            completions.add(cat.getId());
                        }
                    }
                }
                case "gift", "pay" -> {
                    if (hasSubPermission(sender, sub)) {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (!p.getName().equalsIgnoreCase(sender.getName())) {
                                completions.add(p.getName());
                            }
                        }
                    }
                }
                case "balance", "bal", "give", "take", "set", "history" -> {
                    if (hasSubPermission(sender, sub)) {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            completions.add(p.getName());
                        }
                    }
                }
            }
            return filterPrefix(completions, args[1]);
        }

        if (args.length == 3) {
            if (("edititem".equals(sub) || "removeitem".equals(sub)) && hasSubPermission(sender, sub)) {
                for (ShopItem item : shopManager.getItems(args[1])) {
                    completions.add(item.getId());
                }
            } else if (("gift".equals(sub) || "pay".equals(sub)) && hasSubPermission(sender, sub)) {
                completions.addAll(List.of("100", "1k", "5k", "10k", "1m"));
            } else if ("addhanditem".equals(sub) && hasSubPermission(sender, sub)) {
                completions.addAll(List.of("100", "500", "1000", "2500", "5000"));
            } else if (("give".equals(sub) || "take".equals(sub) || "set".equals(sub)) && hasSubPermission(sender, sub)) {
                completions.addAll(List.of("100", "1000", "5000", "10000"));
            }
            return filterPrefix(completions, args[2]);
        }

        return List.of();
    }

    private boolean hasSubPermission(CommandSender sender, String sub) {
        if (sender.hasPermission("coinshop.admin")) {
            return true;
        }
        return switch (sub) {
            case "shop" -> sender.hasPermission("coinshop.use");
            case "gift", "pay" -> sender.hasPermission("coinshop.gift");
            case "balance", "bal" -> sender.hasPermission("coinshop.balance.others");
            case "history" -> sender.hasPermission("coinshop.history");
            case "give" -> sender.hasPermission("coinshop.admin.give");
            case "take" -> sender.hasPermission("coinshop.admin.take");
            case "set" -> sender.hasPermission("coinshop.admin.set");
            case "addcategory", "removecategory" -> sender.hasPermission("coinshop.admin.category");
            case "addhanditem", "edititem", "removeitem" -> sender.hasPermission("coinshop.admin.item");
            case "editor" -> sender.hasPermission("coinshop.editor");
            default -> false;
        };
    }

    private List<String> filterPrefix(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .toList();
    }
}
