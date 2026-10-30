package com.coinshop.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Display-only currency formatter.
 * Calculations never use formatted strings; all balances are exact long values.
 */
public final class CurrencyFormatter {

    private final String singularName;
    private final String pluralName;
    private final String symbol;
    private final boolean useSeparators;
    private final String suffixK;
    private final String suffixM;
    private final String suffixB;
    private final String suffixT;
    private final ThreadLocal<DecimalFormat> rawSeparatorFormat;

    public CurrencyFormatter(
            String singularName,
            String pluralName,
            String symbol,
            boolean useSeparators,
            String suffixK,
            String suffixM,
            String suffixB,
            String suffixT
    ) {
        this.singularName = singularName != null ? singularName : "Coin";
        this.pluralName = pluralName != null ? pluralName : "Coins";
        this.symbol = symbol != null ? symbol : "⛃";
        this.useSeparators = useSeparators;
        this.suffixK = suffixK != null ? suffixK : "K";
        this.suffixM = suffixM != null ? suffixM : "M";
        this.suffixB = suffixB != null ? suffixB : "B";
        this.suffixT = suffixT != null ? suffixT : "T";
        this.rawSeparatorFormat = ThreadLocal.withInitial(() -> {
            DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
            symbols.setGroupingSeparator(',');
            return new DecimalFormat("#,###", symbols);
        });
    }

    /**
     * Returns the exact numeric value, optionally with thousand separators (e.g. 1,500,000).
     */
    public String formatRaw(long amount) {
        if (!useSeparators) {
            return Long.toString(amount);
        }
        return rawSeparatorFormat.get().format(amount);
    }

    /**
     * Formats numbers into compact human-readable strings (e.g., 999 -> "999", 1000 -> "1K", 1499 -> "1.4K", 1500 -> "1.5K", 1000000 -> "1M").
     * Uses FLOOR truncation to 1 decimal place so a player with 1,499 coins is never misled into thinking they have 1.5K (1,500).
     */
    public String formatShort(long amount) {
        if (amount < 0) {
            return "-" + formatShort(Math.abs(amount));
        }
        if (amount < 1_000L) {
            return Long.toString(amount);
        }
        if (amount < 1_000_000L) {
            return formatUnit(amount, 1_000L, suffixK);
        }
        if (amount < 1_000_000_000L) {
            return formatUnit(amount, 1_000_000L, suffixM);
        }
        if (amount < 1_000_000_000_000L) {
            return formatUnit(amount, 1_000_000_000L, suffixB);
        }
        return formatUnit(amount, 1_000_000_000_000L, suffixT);
    }

    private String formatUnit(long amount, long divisor, String suffix) {
        BigDecimal value = BigDecimal.valueOf(amount)
                .divide(BigDecimal.valueOf(divisor), 1, RoundingMode.FLOOR)
                .stripTrailingZeros();
        return value.toPlainString() + suffix;
    }

    public String getCurrencyName(long amount) {
        return amount == 1L ? singularName : pluralName;
    }

    public String getSingularName() {
        return singularName;
    }

    public String getPluralName() {
        return pluralName;
    }

    public String getSymbol() {
        return symbol;
    }
}
