package com.coinshop.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Typed InventoryHolder used to identify CoinShop player and editor menus.
 * Never relies on inventory title strings, preventing renamed-chest or anvil title spoofing exploits.
 */
public final class CoinShopInventoryHolder implements InventoryHolder {

    public enum MenuType {
        MAIN_CATEGORIES,
        CATEGORY_SHOP,
        CONFIRM_PURCHASE,
        EDITOR_CATEGORIES,
        EDITOR_SHOP_SLOTS,
        EDITOR_ITEM_PROPERTIES
    }

    private final UUID viewerUuid;
    private final MenuType menuType;
    private final String categoryId;
    private final String itemId;
    private final int page;
    private Inventory inventory;

    public CoinShopInventoryHolder(
            UUID viewerUuid,
            MenuType menuType,
            String categoryId,
            String itemId,
            int page
    ) {
        this.viewerUuid = viewerUuid;
        this.menuType = menuType;
        this.categoryId = categoryId;
        this.itemId = itemId;
        this.page = Math.max(1, page);
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public UUID getViewerUuid() {
        return viewerUuid;
    }

    public MenuType getMenuType() {
        return menuType;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public String getItemId() {
        return itemId;
    }

    public int getPage() {
        return page;
    }

    public boolean isEditorMenu() {
        return menuType == MenuType.EDITOR_CATEGORIES
                || menuType == MenuType.EDITOR_SHOP_SLOTS
                || menuType == MenuType.EDITOR_ITEM_PROPERTIES;
    }
}
