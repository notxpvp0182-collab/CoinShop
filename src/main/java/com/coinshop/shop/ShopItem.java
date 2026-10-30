package com.coinshop.shop;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a purchasable item entry inside a category's shop file.
 *
 * Price Safety:
 * - UNSET_PRICE (-1L) marks an item whose price was omitted during addhanditem or misconfigured.
 * - Any item with price <= 0L is strictly non-purchasable (isPriceValid() == false), preventing accidental free purchases.
 *
 * Display vs Reward Separation:
 * - rewardItem holds the exact serialized ItemStack (preserving custom names, lore, enchantments, NBT/components).
 * - displayName, lore, material, and amount configure the shop GUI presentation independently without corrupting rewardItem.
 */
public final class ShopItem {

    public static final long UNSET_PRICE = -1L;

    private final String id;
    private final String categoryId;
    private int slot;
    private int page;
    private long price;
    private int amount;
    private Material displayMaterial;
    private String displayName;
    private List<String> displayLore;
    private String permission;
    private RewardType rewardType;
    private ItemStack rewardItem;
    private List<String> commands;
    private int stockLimit;
    private int currentStock;
    private int playerLimit;
    private int cooldownSeconds;
    private boolean requireConfirmation;

    public ShopItem(
            String id,
            String categoryId,
            int slot,
            int page,
            long price,
            int amount,
            Material displayMaterial,
            String displayName,
            List<String> displayLore,
            String permission,
            RewardType rewardType,
            ItemStack rewardItem,
            List<String> commands,
            int stockLimit,
            int currentStock,
            int playerLimit,
            int cooldownSeconds,
            boolean requireConfirmation
    ) {
        this.id = id;
        this.categoryId = categoryId;
        this.slot = slot;
        this.page = Math.max(1, page);
        this.price = price <= 0L ? UNSET_PRICE : price;
        this.amount = Math.max(1, Math.min(64, amount));
        this.displayMaterial = displayMaterial != null ? displayMaterial : Material.STONE;
        this.displayName = displayName != null ? displayName : "";
        this.displayLore = displayLore != null ? new ArrayList<>(displayLore) : new ArrayList<>();
        this.permission = permission != null ? permission : "";
        this.rewardType = rewardType != null ? rewardType : RewardType.ITEM;
        this.rewardItem = rewardItem != null ? rewardItem.clone() : null;
        this.commands = commands != null ? new ArrayList<>(commands) : new ArrayList<>();
        this.stockLimit = stockLimit;
        this.currentStock = currentStock;
        this.playerLimit = playerLimit;
        this.cooldownSeconds = Math.max(0, cooldownSeconds);
        this.requireConfirmation = requireConfirmation;
    }

    /**
     * Strictly requires a positive price (> 0) for an item to be purchasable.
     * UNSET (-1) or 0 or negative prices can never be bought.
     */
    public boolean isPriceValid() {
        return price > 0L;
    }

    public String getId() {
        return id;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public int getSlot() {
        return slot;
    }

    public void setSlot(int slot) {
        this.slot = slot;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = Math.max(1, page);
    }

    public long getPrice() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price <= 0L ? UNSET_PRICE : price;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = Math.max(1, Math.min(64, amount));
        if (this.rewardItem != null) {
            this.rewardItem.setAmount(this.amount);
        }
    }

    public Material getDisplayMaterial() {
        return displayMaterial;
    }

    public void setDisplayMaterial(Material displayMaterial) {
        if (displayMaterial != null && displayMaterial != Material.AIR) {
            this.displayMaterial = displayMaterial;
        }
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName != null ? displayName : "";
    }

    public List<String> getDisplayLore() {
        return List.copyOf(displayLore);
    }

    public void setDisplayLore(List<String> displayLore) {
        this.displayLore = displayLore != null ? new ArrayList<>(displayLore) : new ArrayList<>();
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission != null ? permission : "";
    }

    public RewardType getRewardType() {
        return rewardType;
    }

    public void setRewardType(RewardType rewardType) {
        if (rewardType != null) {
            this.rewardType = rewardType;
        }
    }

    /**
     * Returns a defensive clone of the reward ItemStack, or builds a clean fallback stack if none was serialized.
     */
    public ItemStack getRewardItemClone() {
        if (rewardItem != null) {
            ItemStack clone = rewardItem.clone();
            clone.setAmount(amount);
            return clone;
        }
        return new ItemStack(displayMaterial, amount);
    }

    public boolean hasExactRewardItem() {
        return rewardItem != null;
    }

    public ItemStack getRawRewardItem() {
        return rewardItem != null ? rewardItem.clone() : null;
    }

    public void setRewardItem(ItemStack rewardItem) {
        this.rewardItem = rewardItem != null ? rewardItem.clone() : null;
    }

    public List<String> getCommands() {
        return List.copyOf(commands);
    }

    public void setCommands(List<String> commands) {
        this.commands = commands != null ? new ArrayList<>(commands) : new ArrayList<>();
    }

    public int getStockLimit() {
        return stockLimit;
    }

    public synchronized int getCurrentStock() {
        return currentStock;
    }

    public synchronized boolean consumeStock() {
        if (stockLimit < 0) {
            return true;
        }
        if (currentStock <= 0) {
            return false;
        }
        currentStock--;
        return true;
    }

    public synchronized void refundStock() {
        if (stockLimit >= 0 && currentStock < stockLimit) {
            currentStock++;
        }
    }

    public int getPlayerLimit() {
        return playerLimit;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public boolean isRequireConfirmation() {
        return requireConfirmation;
    }
}
