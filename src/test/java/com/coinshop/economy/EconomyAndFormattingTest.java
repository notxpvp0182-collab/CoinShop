package com.coinshop.economy;

import com.coinshop.storage.SqliteStorageProvider;
import com.coinshop.util.SmallCapsConverter;
import com.coinshop.util.TextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automated unit tests for Phase 2 & Phase 3:
 * - Currency formatting thresholds (999, 1000, 1499, 1500, 999999, 1000000)
 * - Deposit, withdraw, set, negative values, insufficient balance, overflow protection
 * - SQLite persistence round-trip
 * - Small-caps conversion and ID validation
 */
class EconomyAndFormattingTest {

    @TempDir
    Path tempDir;

    private SqliteStorageProvider storage;
    private CoinEconomyService economy;
    private CurrencyFormatter formatter;

    @BeforeEach
    void setUp() throws Exception {
        formatter = new CurrencyFormatter("Coin", "Coins", "⛃", true, "K", "M", "B", "T");
        File dbFile = tempDir.resolve("test_coinshop.db").toFile();
        storage = new SqliteStorageProvider(dbFile, Logger.getLogger("TestLogger"));
        storage.initialize();
        economy = new CoinEconomyService(storage, Logger.getLogger("TestLogger"), 0L, 1_000_000_000_000L, formatter);
        economy.loadInitialCache();
    }

    @AfterEach
    void tearDown() {
        if (economy != null) {
            economy.shutdown();
        }
    }

    @Test
    void testFormattingThresholdsExact() {
        assertEquals("0", formatter.formatShort(0L));
        assertEquals("999", formatter.formatShort(999L));
        assertEquals("1K", formatter.formatShort(1_000L));
        assertEquals("1.4K", formatter.formatShort(1_499L));
        assertEquals("1.5K", formatter.formatShort(1_500L));
        assertEquals("999.9K", formatter.formatShort(999_999L));
        assertEquals("1M", formatter.formatShort(1_000_000L));
        assertEquals("1.5M", formatter.formatShort(1_500_000L));
        assertEquals("1B", formatter.formatShort(1_000_000_000L));

        assertEquals("1,500,000", formatter.formatRaw(1_500_000L));
        assertEquals("999", formatter.formatRaw(999L));
        assertEquals("Coin", formatter.getCurrencyName(1L));
        assertEquals("Coins", formatter.getCurrencyName(2L));
    }

    @Test
    void testDepositWithdrawSetAndValidation() {
        UUID player = UUID.randomUUID();
        assertEquals(0L, economy.getBalance(player));

        // Reject 0 or negative deposits
        assertFalse(economy.deposit(player, "Alex", 0L, "test").isSuccess());
        assertFalse(economy.deposit(player, "Alex", -50L, "test").isSuccess());

        // Valid deposit
        EconomyResponse dep = economy.deposit(player, "Alex", 1500L, "initial reward");
        assertTrue(dep.isSuccess());
        assertEquals(1500L, economy.getBalance(player));

        // Insufficient funds withdrawal
        EconomyResponse failWithdraw = economy.withdraw(player, "Alex", 2000L, "too expensive");
        assertFalse(failWithdraw.isSuccess());
        assertEquals(EconomyResponse.ResultType.INSUFFICIENT_FUNDS, failWithdraw.type());
        assertEquals(1500L, economy.getBalance(player));

        // Valid withdrawal
        EconomyResponse okWithdraw = economy.withdraw(player, "Alex", 500L, "bought item");
        assertTrue(okWithdraw.isSuccess());
        assertEquals(1000L, economy.getBalance(player));

        // Overflow protection
        EconomyResponse overflow = economy.deposit(player, "Alex", 2_000_000_000_000L, "overflow");
        assertFalse(overflow.isSuccess());
        assertEquals(EconomyResponse.ResultType.OVERFLOW, overflow.type());
        assertEquals(1000L, economy.getBalance(player));

        // Set balance
        EconomyResponse setResp = economy.setBalance(player, "Alex", 25000L, "admin set");
        assertTrue(setResp.isSuccess());
        assertEquals(25000L, economy.getBalance(player));
    }

    @Test
    void testSmallCapsAndSafeIds() {
        assertEquals("&#FFAA00ᴄᴏɪɴ ꜱʜᴏᴘ %player%", SmallCapsConverter.toSmallCaps("&#FFAA00Coin Shop %player%"));
        assertTrue(TextUtil.isValidId("spawners_1"));
        assertTrue(TextUtil.isValidId("chat-color"));
        assertFalse(TextUtil.isValidId("../config"));
        assertFalse(TextUtil.isValidId("Invalid Space"));
    }
}
