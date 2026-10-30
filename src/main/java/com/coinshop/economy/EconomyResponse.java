package com.coinshop.economy;

/**
 * Immutable result of an economy balance operation.
 */
public record EconomyResponse(
        ResultType type,
        long previousBalance,
        long newBalance,
        long amountChanged,
        String errorMessage
) {
    public enum ResultType {
        SUCCESS,
        INSUFFICIENT_FUNDS,
        INVALID_AMOUNT,
        OVERFLOW,
        STORAGE_FAILURE
    }

    public boolean isSuccess() {
        return type == ResultType.SUCCESS;
    }

    public static EconomyResponse success(long previousBalance, long newBalance, long amountChanged) {
        return new EconomyResponse(ResultType.SUCCESS, previousBalance, newBalance, amountChanged, null);
    }

    public static EconomyResponse failure(ResultType type, long currentBalance, String errorMessage) {
        return new EconomyResponse(type, currentBalance, currentBalance, 0L, errorMessage);
    }
}
