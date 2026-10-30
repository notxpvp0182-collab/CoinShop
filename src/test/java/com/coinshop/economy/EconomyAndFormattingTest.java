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
 * Automated unit tests for Economy, Formatting, Amount Parsing (K/M/B), and Player-to-Player Gifting.
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
    void testGiftAmountParsingWithKMB() {
        assertEquals(100L, CurrencyFormatter.parseAmount("100"));
        assertEquals(1_000L, CurrencyFormatter.parseAmount("1k"));
        assertEquals(1_000L, CurrencyFormatter.parseAmount("1K"));
        assertEquals(5_000L, CurrencyFormatter.parseAmount("5k"));
        assertEquals(1_500L, CurrencyFormatter.parseAmount("1.5k"));
        assertEquals(1_000_000L, CurrencyFormatter.parseAmount("1m"));
        assertEquals(2_500_000L, CurrencyFormatter.parseAmount("2.5M"));
        assertEquals(1_000_000_000L, CurrencyFormatter.parseAmount("1b"));
        assertEquals(1_000_000_000L, CurrencyFormatter.parseAmount("1B"));

        // Invalid, zero, negative, or malformed inputs must return -1L
        assertEquals(-1L, CurrencyFormatter.parseAmount("0"));
        assertEquals(-1L, CurrencyFormatter.parseAmount("-100"));
        assertEquals(-1L, CurrencyFormatter.parseAmount("-1k"));
        assertEquals(-1L, CurrencyFormatter.parseAmount("abc"));
        assertEquals(-1L, CurrencyFormatter.parseAmount("k"));
        assertEquals(-1L, CurrencyFormatter.parseAmount(""));
        assertEquals(-1L, CurrencyFormatter.parseAmount(null));
    }

    @Test
    void testPlayerToPlayerGiftTransferSafety() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();

        economy.setBalance(sender, "Alex", 10_000L, "init");
        economy.setBalance(receiver, "Steve", 500L, "init");

        // 1. Prevent gifting to self
        TransferResult selfRes = economy.transfer(sender, "Alex", sender, "Alex", 1_000L);
        assertFalse(selfRes.isSuccess());
        assertEquals(TransferResult.Status.SELF_TRANSFER, selfRes.status());
        assertEquals(10_000L, economy.getBalance(sender));

        // 2. Prevent zero or negative amounts
        assertEquals(TransferResult.Status.INVALID_AMOUNT,
                economy.transfer(sender, "Alex", receiver, "Steve", 0L).status());
        assertEquals(TransferResult.Status.INVALID_AMOUNT,
                economy.transfer(sender, "Alex", receiver, "Steve", -500L).status());

        // 3. Prevent insufficient balance transfer
        TransferResult insuff = economy.transfer(sender, "Alex", receiver, "Steve", 25_000L);
        assertFalse(insuff.isSuccess());
        assertEquals(TransferResult.Status.INSUFFICIENT_FUNDS, insuff.status());
        assertEquals(10_000L, economy.getBalance(sender));
        assertEquals(500L, economy.getBalance(receiver));

        // 4. Successful 5k gift deducts from sender and adds exact amount to receiver
        long giftAmount = CurrencyFormatter.parseAmount("5k");
        TransferResult success = economy.transfer(sender, "Alex", receiver, "Steve", giftAmount);
        assertTrue(success.isSuccess());
        assertEquals(5_000L, success.senderNewBalance());
        assertEquals(5_500L, success.receiverNewBalance());
        assertEquals(5_000L, economy.getBalance(sender));
        assertEquals(5_500L, economy.getBalance(receiver));
    }

    @Test
    void testDepositWithdrawSetAndValidation() {
        UUID player = UUID.randomUUID();
        assertEquals(0L, economy.getBalance(player));

        assertFalse(economy.deposit(player, "Alex", 0L, "test").isSuccess());
        assertFalse(economy.deposit(player, "Alex", -50L, "test").isSuccess());

        EconomyResponse dep = economy.deposit(player, "Alex", 1500L, "initial reward");
        assertTrue(dep.isSuccess());
        assertEquals(1500L, economy.getBalance(player));

        EconomyResponse failWithdraw = economy.withdraw(player, "Alex", 2000L, "too expensive");
        assertFalse(failWithdraw.isSuccess());
        assertEquals(EconomyResponse.ResultType.INSUFFICIENT_FUNDS, failWithdraw.type());
        assertEquals(1500L, economy.getBalance(player));

        EconomyResponse okWithdraw = economy.withdraw(player, "Alex", 500L, "bought item");
        assertTrue(okWithdraw.isSuccess());
        assertEquals(1000L, economy.getBalance(player));

        EconomyResponse overflow = economy.deposit(player, "Alex", 2_000_000_000_000L, "overflow");
        assertFalse(overflow.isSuccess());
        assertEquals(EconomyResponse.ResultType.OVERFLOW, overflow.type());
        assertEquals(1000L, economy.getBalance(player));

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
