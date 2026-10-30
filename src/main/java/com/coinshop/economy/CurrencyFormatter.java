package com.coinshop.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Display-only currency formatter and human-friendly amount parser (K/M/B).
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
     * Parses a user-supplied amount string supporting plain numbers and K, M, B suffixes
     * (e.g. "100" -> 100, "1k" -> 1000, "5K" -> 5000, "1.5k" -> 1500, "1m" -> 1000000, "2b" -> 2000000000).
     * Returns -1L if the input is invalid, non-positive, fractional Coin, or overflows a long.
     */
    public static long parseAmount(String rawInput) {
        if (rawInput == null) {
            return -1L;
        }
        String cleaned = rawInput.trim().replace(",", "");
        if (cleaned.isEmpty() || cleaned.startsWith("-") || cleaned.startsWith("+")) {
            return -1L;
        }

        long multiplier = 1L;
        char lastChar = Character.toUpperCase(cleaned.charAt(cleaned.length() - 1));
        String numberPart = cleaned;

        if (lastChar == 'K') {
            multiplier = 1_000L;
            numberPart = cleaned.substring(0, cleaned.length() - 1).trim();
        } else if (lastChar == 'M') {
            multiplier = 1_000_000L;
            numberPart = cleaned.substring(0, cleaned.length() - 1).trim();
        } else if (lastChar == 'B') {
            multiplier = 1_000_000_000L;
            numberPart = cleaned.substring(0, cleaned.length() - 1).trim();
        }

        if (numberPart.isEmpty()) {
            return -1L;
        }

        try {
            BigDecimal decimal = new BigDecimal(numberPart);
            if (decimal.compareTo(BigDecimal.ZERO) <= 0) {
                return -1L;
            }
            BigDecimal multiplied = decimal.multiply(BigDecimal.valueOf(multiplier));
            long exact = multiplied.longValueExact();
            return exact > 0L ? exact : -1L;
        } catch (NumberFormatException | ArithmeticException ex) {
            return -1L;
        }
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
