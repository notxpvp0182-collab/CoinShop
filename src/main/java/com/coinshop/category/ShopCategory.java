package com.coinshop.category;

import org.bukkit.Material;

import java.util.List;

/**
 * Represents a shop category loaded from categories/<id>.yml.
 */
public final class ShopCategory {

    private final String id;
    private boolean enabled;
    private String displayName;
    private Material material;
    private int slot;
    private String permission;
    private boolean hideIfNoPermission;
    private String shopFileName;
    private String guiTitle;
    private int guiSize;
    private boolean fillerEnabled;
    private Material fillerMaterial;
    private String fillerName;
    private List<String> lore;

    public ShopCategory(
            String id,
            boolean enabled,
            String displayName,
            Material material,
            int slot,
            String permission,
            boolean hideIfNoPermission,
            String shopFileName,
            String guiTitle,
            int guiSize,
            boolean fillerEnabled,
            Material fillerMaterial,
            String fillerName,
            List<String> lore
    ) {
        this.id = id;
        this.enabled = enabled;
        this.displayName = displayName;
        this.material = material != null ? material : Material.CHEST;
        this.slot = slot;
        this.permission = permission != null ? permission : "";
        this.hideIfNoPermission = hideIfNoPermission;
        this.shopFileName = shopFileName != null && !shopFileName.isBlank() ? shopFileName : (id + ".yml");
        this.guiTitle = guiTitle != null ? guiTitle : ("&8✦ &#FFAA00" + id + " &8✦");
        this.guiSize = (guiSize >= 18 && guiSize <= 54 && guiSize % 9 == 0) ? guiSize : 54;
        this.fillerEnabled = fillerEnabled;
        this.fillerMaterial = fillerMaterial != null ? fillerMaterial : Material.GRAY_STAINED_GLASS_PANE;
        this.fillerName = fillerName != null ? fillerName : " ";
        this.lore = lore != null ? List.copyOf(lore) : List.of();
    }

    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Material getMaterial() {
        return material;
    }

    public void setMaterial(Material material) {
        if (material != null && material != Material.AIR) {
            this.material = material;
        }
    }

    public int getSlot() {
        return slot;
    }

    public void setSlot(int slot) {
        this.slot = slot;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission != null ? permission : "";
    }

    public boolean isHideIfNoPermission() {
        return hideIfNoPermission;
    }

    public String getShopFileName() {
        return shopFileName;
    }

    public String getGuiTitle() {
        return guiTitle;
    }

    public void setGuiTitle(String guiTitle) {
        this.guiTitle = guiTitle;
    }

    public int getGuiSize() {
        return guiSize;
    }

    public boolean isFillerEnabled() {
        return fillerEnabled;
    }

    public Material getFillerMaterial() {
        return fillerMaterial;
    }

    public String getFillerName() {
        return fillerName;
    }

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> lore) {
        this.lore = lore != null ? List.copyOf(lore) : List.of();
    }
}
