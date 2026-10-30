package com.coinshop.storage;

import com.coinshop.economy.TransactionRecord;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistence contract for player Coin balances, purchase limits, and transaction logs.
 */
public interface StorageProvider {

    void initialize() throws Exception;

    void close();

    Map<UUID, Long> loadAllBalances() throws Exception;

    long getBalance(UUID uuid, long defaultBalance) throws Exception;

    void saveBalance(UUID uuid, String lastKnownName, long balance) throws Exception;

    void logTransaction(TransactionRecord record);

    List<TransactionRecord> getRecentTransactions(UUID uuid, int limit);

    int getPlayerPurchaseCount(UUID uuid, String categoryId, String itemId);

    void incrementPlayerPurchaseCount(UUID uuid, String categoryId, String itemId, int delta);
}
