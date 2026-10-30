package com.coinshop.shop;

/**
 * Defines how a purchased shop item rewards the buyer.
 */
public enum RewardType {
    ITEM,
    COMMAND,
    BOTH;

    public static RewardType fromString(String value) {
        if (value == null) {
            return ITEM;
        }
        try {
            return RewardType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ITEM;
        }
    }
}
