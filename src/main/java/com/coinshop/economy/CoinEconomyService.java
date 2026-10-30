package com.coinshop.economy;

import com.coinshop.storage.StorageProvider;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Authoritative Coin economy service.
 *
 * Concurrency & Safety Guarantees:
 * - Per-UUID ReentrantLock prevents race conditions and double-spending.
 * - Deterministic lock ordering on transfers prevents deadlocks when two players gift each other simultaneously.
 * - Rejects negative or zero amounts for deposit/withdraw/transfer.
 * - Rejects negative balances and arithmetic overflow above maxBalance.
 */
public final class CoinEconomyService {

    private final StorageProvider storage;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, Long> balanceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor;

    private volatile long startingBalance;
    private volatile long maxBalance;
    private volatile CurrencyFormatter formatter;

    public CoinEconomyService(
            StorageProvider storage,
            Logger logger,
            long startingBalance,
            long maxBalance,
            CurrencyFormatter formatter
    ) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.startingBalance = Math.max(0L, startingBalance);
        this.maxBalance = Math.max(1L, maxBalance);
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CoinShop-Storage-Thread");
            t.setDaemon(true);
            return t;
        });
    }

    public void loadInitialCache() throws Exception {
        Map<UUID, Long> loaded = storage.loadAllBalances();
        balanceCache.putAll(loaded);
        logger.info("Loaded " + loaded.size() + " player Coin balances into cache.");
    }

    public void updateSettings(long startingBalance, long maxBalance, CurrencyFormatter formatter) {
        this.startingBalance = Math.max(0L, startingBalance);
        this.maxBalance = Math.max(1L, maxBalance);
        this.formatter = Objects.requireNonNull(formatter, "formatter");
    }

    public CurrencyFormatter getFormatter() {
        return formatter;
    }

    private ReentrantLock getLock(UUID uuid) {
        return locks.computeIfAbsent(uuid, k -> new ReentrantLock());
    }

    /**
     * Returns the exact current balance for a player UUID.
     */
    public long getBalance(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return balanceCache.computeIfAbsent(uuid, id -> {
            try {
                return storage.getBalance(id, startingBalance);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to read balance from storage for " + id, e);
                return startingBalance;
            }
        });
    }

    public boolean hasBalance(UUID uuid, long amount) {
        if (amount < 0L) {
            return false;
        }
        return getBalance(uuid) >= amount;
    }

    /**
     * Deposits a strictly positive amount of Coins into the player's account.
     */
    public EconomyResponse deposit(UUID uuid, String playerName, long amount, String reason) {
        Objects.requireNonNull(uuid, "uuid");
        if (amount <= 0L) {
            return EconomyResponse.failure(
                    EconomyResponse.ResultType.INVALID_AMOUNT,
                    getBalance(uuid),
                    "Deposit amount must be greater than zero."
            );
        }

        ReentrantLock lock = getLock(uuid);
        lock.lock();
        try {
            long current = getBalance(uuid);
            long updated;
            try {
                updated = Math.addExact(current, amount);
            } catch (ArithmeticException ex) {
                return EconomyResponse.failure(
                        EconomyResponse.ResultType.OVERFLOW,
                        current,
                        "Balance overflow."
                );
            }
            if (updated > maxBalance) {
                return EconomyResponse.failure(
                        EconomyResponse.ResultType.OVERFLOW,
                        current,
                        "Balance would exceed max configured limit (" + maxBalance + ")."
                );
            }

            balanceCache.put(uuid, updated);
            persistAsync(uuid, playerName, updated, "DEPOSIT", amount, current, reason);
            return EconomyResponse.success(current, updated, amount);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Withdraws a strictly positive amount of Coins from the player's account.
     */
    public EconomyResponse withdraw(UUID uuid, String playerName, long amount, String reason) {
        Objects.requireNonNull(uuid, "uuid");
        if (amount <= 0L) {
            return EconomyResponse.failure(
                    EconomyResponse.ResultType.INVALID_AMOUNT,
                    getBalance(uuid),
                    "Withdraw amount must be greater than zero."
            );
        }

        ReentrantLock lock = getLock(uuid);
        lock.lock();
        try {
            long current = getBalance(uuid);
            if (current < amount) {
                return EconomyResponse.failure(
                        EconomyResponse.ResultType.INSUFFICIENT_FUNDS,
                        current,
                        "Insufficient Coins."
                );
            }
            long updated = current - amount;
            balanceCache.put(uuid, updated);
            persistAsync(uuid, playerName, updated, "WITHDRAW", amount, current, reason);
            return EconomyResponse.success(current, updated, amount);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets a player's Coin balance to a non-negative value up to maxBalance.
     */
    public EconomyResponse setBalance(UUID uuid, String playerName, long newBalance, String reason) {
        Objects.requireNonNull(uuid, "uuid");
        if (newBalance < 0L) {
            return EconomyResponse.failure(
                    EconomyResponse.ResultType.INVALID_AMOUNT,
                    getBalance(uuid),
                    "Balance cannot be negative."
            );
        }
        if (newBalance > maxBalance) {
            return EconomyResponse.failure(
                    EconomyResponse.ResultType.OVERFLOW,
                    getBalance(uuid),
                    "Balance exceeds max configured limit (" + maxBalance + ")."
            );
        }

        ReentrantLock lock = getLock(uuid);
        lock.lock();
        try {
            long current = getBalance(uuid);
            long diff = Math.abs(newBalance - current);
            balanceCache.put(uuid, newBalance);
            persistAsync(uuid, playerName, newBalance, "SET", diff, current, reason);
            return EconomyResponse.success(current, newBalance, diff);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically transfers Coins from senderUuid to receiverUuid.
     * Uses ordered dual-lock acquisition to prevent deadlocks and race conditions.
     */
    public TransferResult transfer(
            UUID senderUuid,
            String senderName,
            UUID receiverUuid,
            String receiverName,
            long amount
    ) {
        Objects.requireNonNull(senderUuid, "senderUuid");
        Objects.requireNonNull(receiverUuid, "receiverUuid");

        if (senderUuid.equals(receiverUuid)) {
            long bal = getBalance(senderUuid);
            return new TransferResult(TransferResult.Status.SELF_TRANSFER, 0L, bal, bal);
        }
        if (amount <= 0L) {
            return new TransferResult(
                    TransferResult.Status.INVALID_AMOUNT,
                    0L,
                    getBalance(senderUuid),
                    getBalance(receiverUuid)
            );
        }

        // Deterministic lock order prevents deadlocks if A gifts B while B gifts A
        UUID first = senderUuid.compareTo(receiverUuid) < 0 ? senderUuid : receiverUuid;
        UUID second = first.equals(senderUuid) ? receiverUuid : senderUuid;

        ReentrantLock firstLock = getLock(first);
        ReentrantLock secondLock = getLock(second);

        firstLock.lock();
        try {
            secondLock.lock();
            try {
                long senderBefore = getBalance(senderUuid);
                long receiverBefore = getBalance(receiverUuid);

                if (senderBefore < amount) {
                    return new TransferResult(
                            TransferResult.Status.INSUFFICIENT_FUNDS,
                            amount,
                            senderBefore,
                            receiverBefore
                    );
                }

                long receiverAfter;
                try {
                    receiverAfter = Math.addExact(receiverBefore, amount);
                } catch (ArithmeticException ex) {
                    return new TransferResult(
                            TransferResult.Status.RECEIVER_OVERFLOW,
                            amount,
                            senderBefore,
                            receiverBefore
                    );
                }

                if (receiverAfter > maxBalance) {
                    return new TransferResult(
                            TransferResult.Status.RECEIVER_OVERFLOW,
                            amount,
                            senderBefore,
                            receiverBefore
                    );
                }

                long senderAfter = senderBefore - amount;
                balanceCache.put(senderUuid, senderAfter);
                balanceCache.put(receiverUuid, receiverAfter);

                persistAsync(
                        senderUuid,
                        senderName,
                        senderAfter,
                        "GIFT_SENT",
                        amount,
                        senderBefore,
                        "Gifted to " + (receiverName != null ? receiverName : receiverUuid)
                );
                persistAsync(
                        receiverUuid,
                        receiverName,
                        receiverAfter,
                        "GIFT_RECEIVED",
                        amount,
                        receiverBefore,
                        "Received from " + (senderName != null ? senderName : senderUuid)
                );

                return new TransferResult(TransferResult.Status.SUCCESS, amount, senderAfter, receiverAfter);
            } finally {
                secondLock.unlock();
            }
        } finally {
            firstLock.unlock();
        }
    }

    private void persistAsync(
            UUID uuid,
            String playerName,
            long newBalance,
            String actionType,
            long amount,
            long before,
            String details
    ) {
        TransactionRecord record = new TransactionRecord(
                uuid,
                playerName != null ? playerName : "Unknown",
                actionType,
                amount,
                before,
                newBalance,
                details != null ? details : "",
                Instant.now()
        );
        CompletableFuture.runAsync(() -> {
            try {
                storage.saveBalance(uuid, playerName, newBalance);
                storage.logTransaction(record);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to persist balance update for " + uuid, e);
            }
        }, ioExecutor);
    }

    public Map<UUID, Long> getTopBalances(int limit) {
        return balanceCache.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(Math.max(1, limit))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    public CompletableFuture<List<TransactionRecord>> getRecentTransactionsAsync(UUID uuid, int limit) {
        return CompletableFuture.supplyAsync(() -> storage.getRecentTransactions(uuid, limit), ioExecutor);
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        storage.close();
    }
}
