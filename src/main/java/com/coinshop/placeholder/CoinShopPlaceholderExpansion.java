package com.coinshop.placeholder;

import com.coinshop.economy.CoinEconomyService;
import com.coinshop.economy.CurrencyFormatter;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Optional PlaceholderAPI integration.
 * Registered only when PlaceholderAPI is present on the server.
 *
 * Supported placeholders:
 * - %coinshop_balance%           -> exact numeric balance without separators (e.g. 1500000)
 * - %coinshop_balance_raw%       -> exact balance with configured thousand separators (e.g. 1,500,000)
 * - %coinshop_balance_formatted% -> compact K/M/B/T balance (e.g. 1.5M)
 * - %coinshop_symbol%            -> currency symbol (e.g. ⛃)
 * - %coinshop_currency_name%     -> singular/plural currency name for the player's balance
 */
public final class CoinShopPlaceholderExpansion extends PlaceholderExpansion {

    private final String pluginVersion;
    private final CoinEconomyService economyService;

    public CoinShopPlaceholderExpansion(String pluginVersion, CoinEconomyService economyService) {
        this.pluginVersion = pluginVersion;
        this.economyService = economyService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "coinshop";
    }

    @Override
    public @NotNull String getAuthor() {
        return "CoinShopTeam";
    }

    @Override
    public @NotNull String getVersion() {
        return pluginVersion;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        CurrencyFormatter fmt = economyService.getFormatter();
        if ("symbol".equalsIgnoreCase(params)) {
            return fmt.getSymbol();
        }
        if (player == null) {
            return "0";
        }
        long balance = economyService.getBalance(player.getUniqueId());
        return switch (params.toLowerCase()) {
            case "balance" -> Long.toString(balance);
            case "balance_raw" -> fmt.formatRaw(balance);
            case "balance_formatted" -> fmt.formatShort(balance);
            case "currency_name" -> fmt.getCurrencyName(balance);
            default -> null;
        };
    }
}
