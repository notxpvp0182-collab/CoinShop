package com.coinshop.storage;

import com.coinshop.economy.TransactionRecord;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite storage implementation with WAL mode enabled for high concurrency and atomic updates.
 */
public final class SqliteStorageProvider implements StorageProvider {

    private final File dbFile;
    private final Logger logger;
    private Connection connection;

    public SqliteStorageProvider(File dbFile, Logger logger) {
        this.dbFile = dbFile;
        this.logger = logger;
    }

    @Override
    public synchronized void initialize() throws SQLException {
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create parent directory for SQLite database: " + parent);
        }
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        connection = DriverManager.getConnection(url);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS coinshop_balances (
                    uuid TEXT PRIMARY KEY,
                    player_name TEXT,
                    balance INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                );
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS coinshop_transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    uuid TEXT NOT NULL,
                    player_name TEXT,
                    action_type TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    balance_before INTEGER NOT NULL,
                    balance_after INTEGER NOT NULL,
                    details TEXT,
                    created_at INTEGER NOT NULL
                );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_tx_uuid ON coinshop_transactions(uuid);");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS coinshop_purchase_counts (
                    uuid TEXT NOT NULL,
                    category_id TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    purchase_count INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid, category_id, item_id)
                );
            """);
        }
    }

    private synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            initialize();
        }
        return connection;
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to close SQLite connection cleanly", e);
            }
        }
    }

    @Override
    public synchronized Map<UUID, Long> loadAllBalances() throws SQLException {
        Map<UUID, Long> balances = new HashMap<>();
        String sql = "SELECT uuid, balance FROM coinshop_balances";
        try (PreparedStatement ps = getConnection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    long balance = rs.getLong("balance");
                    balances.put(uuid, Math.max(0L, balance));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return balances;
    }

    @Override
    public synchronized long getBalance(UUID uuid, long defaultBalance) throws SQLException {
        String sql = "SELECT balance FROM coinshop_balances WHERE uuid = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Math.max(0L, rs.getLong("balance"));
                }
            }
        }
        return defaultBalance;
    }

    @Override
    public synchronized void saveBalance(UUID uuid, String lastKnownName, long balance) throws SQLException {
        String sql = """
            INSERT INTO coinshop_balances (uuid, player_name, balance, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                player_name = COALESCE(excluded.player_name, coinshop_balances.player_name),
                balance = excluded.balance,
                updated_at = excluded.updated_at;
        """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, lastKnownName);
            ps.setLong(3, balance);
            ps.setLong(4, Instant.now().toEpochMilli());
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void logTransaction(TransactionRecord record) {
        String sql = """
            INSERT INTO coinshop_transactions
            (uuid, player_name, action_type, amount, balance_before, balance_after, details, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?);
        """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, record.playerUuid().toString());
            ps.setString(2, record.playerName());
            ps.setString(3, record.actionType());
            ps.setLong(4, record.amount());
            ps.setLong(5, record.balanceBefore());
            ps.setLong(6, record.balanceAfter());
            ps.setString(7, record.details());
            ps.setLong(8, record.timestamp().toEpochMilli());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to log transaction for " + record.playerUuid(), e);
        }
    }

    @Override
    public synchronized List<TransactionRecord> getRecentTransactions(UUID uuid, int limit) {
        List<TransactionRecord> records = new ArrayList<>();
        String sql = """
            SELECT uuid, player_name, action_type, amount, balance_before, balance_after, details, created_at
            FROM coinshop_transactions
            WHERE uuid = ?
            ORDER BY id DESC
            LIMIT ?;
        """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new TransactionRecord(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("player_name"),
                            rs.getString("action_type"),
                            rs.getLong("amount"),
                            rs.getLong("balance_before"),
                            rs.getLong("balance_after"),
                            rs.getString("details"),
                            Instant.ofEpochMilli(rs.getLong("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to read transactions for " + uuid, e);
        }
        return records;
    }

    @Override
    public synchronized int getPlayerPurchaseCount(UUID uuid, String categoryId, String itemId) {
        String sql = "SELECT purchase_count FROM coinshop_purchase_counts WHERE uuid = ? AND category_id = ? AND item_id = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, categoryId);
            ps.setString(3, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("purchase_count");
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to read purchase count for " + uuid, e);
        }
        return 0;
    }

    @Override
    public synchronized void incrementPlayerPurchaseCount(UUID uuid, String categoryId, String itemId, int delta) {
        String sql = """
            INSERT INTO coinshop_purchase_counts (uuid, category_id, item_id, purchase_count)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid, category_id, item_id) DO UPDATE SET
                purchase_count = MAX(0, coinshop_purchase_counts.purchase_count + excluded.purchase_count);
        """;
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, categoryId);
            ps.setString(3, itemId);
            ps.setInt(4, delta);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to increment purchase count for " + uuid, e);
        }
    }
}
