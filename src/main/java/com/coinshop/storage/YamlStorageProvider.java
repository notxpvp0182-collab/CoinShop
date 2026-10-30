package com.coinshop.storage;

import com.coinshop.economy.TransactionRecord;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fallback YAML storage provider with atomic temp-file writes to prevent corruption on crash.
 */
public final class YamlStorageProvider implements StorageProvider {

    private final File yamlFile;
    private final Logger logger;
    private YamlConfiguration config;
    private final Map<UUID, LinkedList<TransactionRecord>> memoryHistory = new HashMap<>();

    public YamlStorageProvider(File yamlFile, Logger logger) {
        this.yamlFile = yamlFile;
        this.logger = logger;
    }

    @Override
    public synchronized void initialize() throws IOException {
        File parent = yamlFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory: " + parent);
        }
        if (!yamlFile.exists() && !yamlFile.createNewFile()) {
            throw new IOException("Could not create balances YAML file: " + yamlFile);
        }
        config = YamlConfiguration.loadConfiguration(yamlFile);
    }

    @Override
    public synchronized void close() {
        try {
            saveAtomic();
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save YAML balances on shutdown", e);
        }
    }

    private synchronized void saveAtomic() throws IOException {
        File tempFile = new File(yamlFile.getParentFile(), yamlFile.getName() + ".tmp");
        config.save(tempFile);
        Files.move(tempFile.toPath(), yamlFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public synchronized Map<UUID, Long> loadAllBalances() {
        Map<UUID, Long> result = new HashMap<>();
        ConfigurationSection players = config.getConfigurationSection("players");
        if (players == null) {
            return result;
        }
        for (String key : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long balance = Math.max(0L, players.getLong(key + ".balance", 0L));
                result.put(uuid, balance);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return result;
    }

    @Override
    public synchronized long getBalance(UUID uuid, long defaultBalance) {
        String path = "players." + uuid + ".balance";
        if (!config.contains(path)) {
            return defaultBalance;
        }
        return Math.max(0L, config.getLong(path, defaultBalance));
    }

    @Override
    public synchronized void saveBalance(UUID uuid, String lastKnownName, long balance) throws IOException {
        String base = "players." + uuid;
        if (lastKnownName != null) {
            config.set(base + ".name", lastKnownName);
        }
        config.set(base + ".balance", balance);
        saveAtomic();
    }

    @Override
    public synchronized void logTransaction(TransactionRecord record) {
        LinkedList<TransactionRecord> list = memoryHistory.computeIfAbsent(record.playerUuid(), k -> new LinkedList<>());
        list.addFirst(record);
        while (list.size() > 50) {
            list.removeLast();
        }
    }

    @Override
    public synchronized List<TransactionRecord> getRecentTransactions(UUID uuid, int limit) {
        LinkedList<TransactionRecord> list = memoryHistory.get(uuid);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(list.subList(0, Math.min(limit, list.size())));
    }

    @Override
    public synchronized int getPlayerPurchaseCount(UUID uuid, String categoryId, String itemId) {
        return config.getInt("limits." + uuid + "." + categoryId + "." + itemId, 0);
    }

    @Override
    public synchronized void incrementPlayerPurchaseCount(UUID uuid, String categoryId, String itemId, int delta) {
        String path = "limits." + uuid + "." + categoryId + "." + itemId;
        int current = config.getInt(path, 0);
        config.set(path, Math.max(0, current + delta));
        try {
            saveAtomic();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to save purchase count to YAML", e);
        }
    }
}
