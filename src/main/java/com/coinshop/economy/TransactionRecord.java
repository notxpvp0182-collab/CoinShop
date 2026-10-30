package com.coinshop.economy;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents an audited Coin transaction or admin adjustment.
 */
public record TransactionRecord(
        UUID playerUuid,
        String playerName,
        String actionType,
        long amount,
        long balanceBefore,
        long balanceAfter,
        String details,
        Instant timestamp
) {
}
