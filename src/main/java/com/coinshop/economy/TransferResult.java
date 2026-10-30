package com.coinshop.economy;

/**
 * Immutable result of an atomic player-to-player Coin gift/transfer operation.
 */
public record TransferResult(
        Status status,
        long amount,
        long senderNewBalance,
        long receiverNewBalance
) {
    public enum Status {
        SUCCESS,
        SELF_TRANSFER,
        INVALID_AMOUNT,
        INSUFFICIENT_FUNDS,
        RECEIVER_OVERFLOW
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
