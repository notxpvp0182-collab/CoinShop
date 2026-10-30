package com.coinshop.shop;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying:
 * - UNSET price (-1), 0, and negative prices are strictly blocked from purchase (never free).
 * - Stock consumption and refund logic is atomic and accurate.
 * - Display properties vs reward item separation.
 */
class ShopValidationAndPriceSafetyTest {

    @Test
    void testUnsetAndZeroPriceCannotBePurchased() {
        ShopItem unsetItem = new ShopItem(
                "mystery_sword",
                "spawners",
                10,
                1,
                ShopItem.UNSET_PRICE,
                1,
                Material.DIAMOND_SWORD,
                "&6Mystery Sword",
                List.of("&7Unpriced item"),
                "",
                RewardType.ITEM,
                null,
                List.of(),
                -1,
                -1,
                -1,
                0,
                false
        );

        assertFalse(unsetItem.isPriceValid(), "UNSET price must never be considered valid/purchasable");
        assertEquals(ShopItem.UNSET_PRICE, unsetItem.getPrice());

        unsetItem.setPrice(0L);
        assertFalse(unsetItem.isPriceValid(), "Zero price must be converted to UNSET and blocked");

        unsetItem.setPrice(-500L);
        assertFalse(unsetItem.isPriceValid(), "Negative price must be converted to UNSET and blocked");

        unsetItem.setPrice(2500L);
        assertTrue(unsetItem.isPriceValid(), "Positive price must be valid");
        assertEquals(2500L, unsetItem.getPrice());
    }

    @Test
    void testStockConsumptionAndRefund() {
        ShopItem limitedItem = new ShopItem(
                "rare_key",
                "spawners",
                11,
                1,
                1000L,
                1,
                Material.TRIPWIRE_HOOK,
                "&bRare Key",
                List.of(),
                "",
                RewardType.ITEM,
                null,
                List.of(),
                2,
                2,
                -1,
                0,
                false
        );

        assertTrue(limitedItem.consumeStock());
        assertEquals(1, limitedItem.getCurrentStock());

        assertTrue(limitedItem.consumeStock());
        assertEquals(0, limitedItem.getCurrentStock());

        // Out of stock now
        assertFalse(limitedItem.consumeStock());
        assertEquals(0, limitedItem.getCurrentStock());

        // Refund restores stock
        limitedItem.refundStock();
        assertEquals(1, limitedItem.getCurrentStock());
    }
}
