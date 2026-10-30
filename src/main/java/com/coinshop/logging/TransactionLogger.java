package com.coinshop.logging;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asynchronous audit logger writing admin balance changes and player shop purchases to transactions.log.
 */
public final class TransactionLogger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final File logFile;
    private final Logger pluginLogger;
    private final ExecutorService logExecutor;
    private volatile boolean logToFile;
    private volatile boolean logAdminToConsole;

    public TransactionLogger(File dataFolder, Logger pluginLogger, boolean logToFile, boolean logAdminToConsole) {
        this.logFile = new File(dataFolder, "transactions.log");
        this.pluginLogger = pluginLogger;
        this.logToFile = logToFile;
        this.logAdminToConsole = logAdminToConsole;
        this.logExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CoinShop-AuditLogger");
            t.setDaemon(true);
            return t;
        });
    }

    public void updateSettings(boolean logToFile, boolean logAdminToConsole) {
        this.logToFile = logToFile;
        this.logAdminToConsole = logAdminToConsole;
    }

    public void logAdminAction(String actor, String action, String targetPlayer, UUID targetUuid, long amount, long newBalance) {
        String entry = String.format(
                "[%s] [ADMIN] Actor=%s | Action=%s | Target=%s (%s) | Amount=%d | NewBalance=%d",
                FORMATTER.format(Instant.now()),
                actor,
                action,
                targetPlayer,
                targetUuid,
                amount,
                newBalance
        );
        if (logAdminToConsole) {
            pluginLogger.info(entry);
        }
        if (logToFile) {
            writeAsync(entry);
        }
    }

    public void logPurchase(String playerName, UUID playerUuid, String categoryId, String itemId, long price, long newBalance) {
        String entry = String.format(
                "[%s] [PURCHASE] Player=%s (%s) | Category=%s | Item=%s | Price=%d | NewBalance=%d",
                FORMATTER.format(Instant.now()),
                playerName,
                playerUuid,
                categoryId,
                itemId,
                price,
                newBalance
        );
        if (logToFile) {
            writeAsync(entry);
        }
    }

    private void writeAsync(String line) {
        logExecutor.submit(() -> {
            try {
                File parent = logFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true))) {
                    writer.write(line);
                    writer.newLine();
                }
            } catch (IOException e) {
                pluginLogger.log(Level.WARNING, "Could not write to transactions.log", e);
            }
        });
    }

    public void shutdown() {
        logExecutor.shutdown();
        try {
            logExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
