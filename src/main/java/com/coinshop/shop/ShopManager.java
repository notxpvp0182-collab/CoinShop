package com.coinshop.shop;

import com.coinshop.category.ShopCategory;
import com.coinshop.item.ItemSerializer;
import com.coinshop.util.TextUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Manages loading, validation, atomic saving, and in-game modification of categories and shop items.
 * Preserves the last known-good state if a reload fails validation.
 */
public final class ShopManager {

    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final JavaPlugin plugin;
    private final File categoriesDir;
    private final File shopsDir;

    private Map<String, ShopCategory> categories = new LinkedHashMap<>();
    private Map<String, Map<String, ShopItem>> categoryItems = new LinkedHashMap<>();

    public ShopManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.categoriesDir = new File(plugin.getDataFolder(), "categories");
        this.shopsDir = new File(plugin.getDataFolder(), "shops");
    }

    public void saveDefaultResourcesIfMissing() {
        if (!categoriesDir.exists()) {
            categoriesDir.mkdirs();
            plugin.saveResource("categories/spawners.yml", false);
            plugin.saveResource("categories/chat-color.yml", false);
        }
        if (!shopsDir.exists()) {
            shopsDir.mkdirs();
            plugin.saveResource("shops/spawners.yml", false);
            plugin.saveResource("shops/chat-color.yml", false);
        }
    }

    /**
     * Validates and loads all categories and shops.
     * If validation errors are found and strictReload is true, active state is NOT replaced.
     */
    public synchronized boolean loadAndValidate(List<String> errors, boolean strictReload) {
        Map<String, ShopCategory> nextCategories = new LinkedHashMap<>();
        Map<String, Map<String, ShopItem>> nextItems = new LinkedHashMap<>();
        Map<Integer, String> mainSlotOwners = new LinkedHashMap<>();

        File[] catFiles = categoriesDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (catFiles != null) {
            for (File file : catFiles) {
                YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
                String defaultId = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
                String id = yml.getString("id", defaultId).toLowerCase(Locale.ROOT);

                if (!TextUtil.isValidId(id)) {
                    errors.add("Category file '" + file.getName() + "' has invalid ID '" + id + "'");
                    continue;
                }
                if (nextCategories.containsKey(id)) {
                    errors.add("Duplicate category ID '" + id + "' in '" + file.getName() + "'");
                    continue;
                }

                String matName = yml.getString("material", "CHEST");
                Material material = Material.matchMaterial(matName);
                if (material == null || material == Material.AIR) {
                    errors.add("Category '" + id + "' has invalid material '" + matName + "'");
                    material = Material.CHEST;
                }

                int slot = yml.getInt("slot", 0);
                if (slot < 0 || slot >= 54) {
                    errors.add("Category '" + id + "' has out-of-range slot " + slot);
                } else if (mainSlotOwners.containsKey(slot)) {
                    errors.add("Category '" + id + "' conflicts on main menu slot " + slot + " with '" + mainSlotOwners.get(slot) + "'");
                } else {
                    mainSlotOwners.put(slot, id);
                }

                String fillerMatName = yml.getString("filler.material", "GRAY_STAINED_GLASS_PANE");
                Material fillerMat = Material.matchMaterial(fillerMatName);
                if (fillerMat == null) {
                    errors.add("Category '" + id + "' has invalid filler material '" + fillerMatName + "'");
                    fillerMat = Material.GRAY_STAINED_GLASS_PANE;
                }

                String shopFileName = yml.getString("shop-file", id + ".yml");
                if (shopFileName.contains("..") || shopFileName.contains("/") || shopFileName.contains("\\")) {
                    errors.add("Category '" + id + "' has unsafe shop-file path '" + shopFileName + "'");
                    continue;
                }

                ShopCategory category = new ShopCategory(
                        id,
                        yml.getBoolean("enabled", true),
                        yml.getString("display-name", "&#FFAA00" + id),
                        material,
                        slot,
                        yml.getString("permission", ""),
                        yml.getBoolean("hide-if-no-permission", false),
                        shopFileName,
                        yml.getString("gui-title", "&8✦ &#FFAA00" + id + " &8✦"),
                        yml.getInt("gui-size", 54),
                        yml.getBoolean("filler.enabled", true),
                        fillerMat,
                        yml.getString("filler.name", " "),
                        yml.getStringList("lore")
                );

                nextCategories.put(id, category);

                File shopFile = new File(shopsDir, category.getShopFileName());
                if (!shopFile.exists()) {
                    errors.add("Category '" + id + "' references missing shop file 'shops/" + category.getShopFileName() + "'");
                    nextItems.put(id, new LinkedHashMap<>());
                } else {
                    Map<String, ShopItem> loadedShopItems = loadShopFile(category, shopFile, errors);
                    nextItems.put(id, loadedShopItems);
                }
            }
        }

        if (strictReload && !errors.isEmpty()) {
            return false;
        }

        this.categories = nextCategories;
        this.categoryItems = nextItems;
        return errors.isEmpty();
    }

    private Map<String, ShopItem> loadShopFile(ShopCategory category, File shopFile, List<String> errors) {
        Map<String, ShopItem> items = new LinkedHashMap<>();
        Map<String, String> pageSlotOwners = new LinkedHashMap<>();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(shopFile);
        ConfigurationSection itemsSec = yml.getConfigurationSection("items");
        if (itemsSec == null) {
            return items;
        }

        int maxUsableSlot = category.getGuiSize() - 9;

        for (String itemId : itemsSec.getKeys(false)) {
            if (!TextUtil.isValidId(itemId)) {
                errors.add("Shop '" + category.getId() + "' has invalid item ID '" + itemId + "'");
                continue;
            }
            ConfigurationSection sec = itemsSec.getConfigurationSection(itemId);
            if (sec == null) {
                continue;
            }

            int slot = sec.getInt("slot", 0);
            int page = Math.max(1, sec.getInt("page", 1));
            if (slot < 0 || slot >= maxUsableSlot) {
                errors.add("Shop '" + category.getId() + "' item '" + itemId + "' has slot " + slot + " outside usable range (0-" + (maxUsableSlot - 1) + ")");
            }
            String pageSlotKey = page + ":" + slot;
            if (pageSlotOwners.containsKey(pageSlotKey)) {
                errors.add("Shop '" + category.getId() + "' item '" + itemId + "' conflicts on page " + page + " slot " + slot + " with '" + pageSlotOwners.get(pageSlotKey) + "'");
            } else {
                pageSlotOwners.put(pageSlotKey, itemId);
            }

            long price = ShopItem.UNSET_PRICE;
            String rawPriceStr = sec.getString("price", "UNSET");
            if (!"UNSET".equalsIgnoreCase(rawPriceStr)) {
                long parsed = sec.getLong("price", ShopItem.UNSET_PRICE);
                if (parsed > 0L) {
                    price = parsed;
                }
            }

            int amount = sec.getInt("amount", 1);
            String matName = sec.getString("material", "STONE");
            Material mat = Material.matchMaterial(matName);
            if (mat == null || mat == Material.AIR) {
                errors.add("Shop '" + category.getId() + "' item '" + itemId + "' has invalid material '" + matName + "'");
                mat = Material.STONE;
            }

            ItemStack rewardStack = ItemSerializer.readItemFromSection(sec, plugin.getLogger());
            RewardType rewardType = RewardType.fromString(sec.getString("reward-type", "ITEM"));
            int stockLimit = sec.getInt("stock-limit", -1);
            int currentStock = sec.getInt("current-stock", stockLimit);

            ShopItem shopItem = new ShopItem(
                    itemId,
                    category.getId(),
                    slot,
                    page,
                    price,
                    amount,
                    mat,
                    sec.getString("display-name", ""),
                    sec.getStringList("lore"),
                    sec.getString("permission", ""),
                    rewardType,
                    rewardStack,
                    sec.getStringList("commands"),
                    stockLimit,
                    currentStock,
                    sec.getInt("player-limit", -1),
                    sec.getInt("cooldown-seconds", 0),
                    sec.getBoolean("require-confirmation", false)
            );
            items.put(itemId, shopItem);
        }
        return items;
    }

    public synchronized Collection<ShopCategory> getCategories() {
        return Collections.unmodifiableCollection(categories.values());
    }

    public synchronized ShopCategory getCategory(String id) {
        if (id == null) {
            return null;
        }
        return categories.get(id.toLowerCase(Locale.ROOT));
    }

    public synchronized Collection<ShopItem> getItems(String categoryId) {
        Map<String, ShopItem> map = categoryItems.get(categoryId.toLowerCase(Locale.ROOT));
        if (map == null) {
            return List.of();
        }
        return Collections.unmodifiableCollection(map.values());
    }

    public synchronized ShopItem getItem(String categoryId, String itemId) {
        Map<String, ShopItem> map = categoryItems.get(categoryId.toLowerCase(Locale.ROOT));
        if (map == null || itemId == null) {
            return null;
        }
        return map.get(itemId.toLowerCase(Locale.ROOT));
    }

    public synchronized ShopItem getItemAt(String categoryId, int page, int slot) {
        for (ShopItem item : getItems(categoryId)) {
            if (item.getPage() == page && item.getSlot() == slot) {
                return item;
            }
        }
        return null;
    }

    public synchronized int getTotalItemCount() {
        return categoryItems.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * Creates a new category file and its corresponding shop file atomically.
     */
    public synchronized ShopCategory createCategory(String id) throws IOException {
        String cleanId = id.toLowerCase(Locale.ROOT);
        if (!TextUtil.isValidId(cleanId)) {
            throw new IllegalArgumentException("Invalid category ID: " + id);
        }
        if (categories.containsKey(cleanId)) {
            throw new IllegalStateException("Category already exists: " + cleanId);
        }

        int freeSlot = findNextFreeCategorySlot();
        ShopCategory category = new ShopCategory(
                cleanId,
                true,
                "&#FFAA00&l" + cleanId,
                Material.CHEST,
                freeSlot,
                "",
                false,
                cleanId + ".yml",
                "&8✦ &#FFAA00" + cleanId + " &8(&7Page %page%&8) ✦",
                54,
                true,
                Material.GRAY_STAINED_GLASS_PANE,
                " ",
                List.of("&7Click to browse this category.")
        );

        saveCategoryToFile(category);
        File shopFile = new File(shopsDir, category.getShopFileName());
        if (!shopFile.exists()) {
            YamlConfiguration emptyShop = new YamlConfiguration();
            emptyShop.createSection("items");
            saveYamlAtomic(emptyShop, shopFile);
        }

        categories.put(cleanId, category);
        categoryItems.put(cleanId, new LinkedHashMap<>());
        return category;
    }

    public synchronized boolean removeCategory(String id) {
        String cleanId = id.toLowerCase(Locale.ROOT);
        ShopCategory removed = categories.remove(cleanId);
        if (removed == null) {
            return false;
        }
        categoryItems.remove(cleanId);
        File catFile = new File(categoriesDir, cleanId + ".yml");
        if (catFile.exists()) {
            catFile.delete();
        }
        return true;
    }

    public synchronized void saveCategoryToFile(ShopCategory category) throws IOException {
        File catFile = new File(categoriesDir, category.getId() + ".yml");
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("id", category.getId());
        yml.set("enabled", category.isEnabled());
        yml.set("display-name", category.getDisplayName());
        yml.set("material", category.getMaterial().name());
        yml.set("slot", category.getSlot());
        yml.set("permission", category.getPermission());
        yml.set("hide-if-no-permission", category.isHideIfNoPermission());
        yml.set("shop-file", category.getShopFileName());
        yml.set("gui-title", category.getGuiTitle());
        yml.set("gui-size", category.getGuiSize());
        yml.set("filler.enabled", category.isFillerEnabled());
        yml.set("filler.material", category.getFillerMaterial().name());
        yml.set("filler.name", category.getFillerName());
        yml.set("lore", category.getLore());
        saveYamlAtomic(yml, catFile);
    }

    /**
     * Adds an in-hand ItemStack to the specified category's shop file, preserving all ItemMeta & NBT/components.
     * If price <= 0, the item's price is saved as UNSET so it cannot be bought for free.
     */
    public synchronized ShopItem addHandItemToCategory(
            String categoryId,
            ItemStack heldItem,
             long priceOrUnset,
            String requestedId
    ) throws IOException {
        ShopCategory category = getCategory(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("Unknown category: " + categoryId);
        }
        if (heldItem == null || heldItem.getType() == Material.AIR || heldItem.getAmount() <= 0) {
            throw new IllegalArgumentException("Held item cannot be AIR or empty.");
        }

        Map<String, ShopItem> items = categoryItems.computeIfAbsent(category.getId(), k -> new LinkedHashMap<>());
        String finalId = (requestedId != null && !requestedId.isBlank())
                ? requestedId.toLowerCase(Locale.ROOT)
                : generateUniqueItemId(category.getId(), heldItem.getType());

        if (!TextUtil.isValidId(finalId)) {
            throw new IllegalArgumentException("Invalid item ID: " + finalId);
        }

        int[] pageAndSlot = findFirstFreePageAndSlot(category);
        ItemStack rewardCopy = heldItem.clone();

        String displayName = "";
        List<String> displayLore = new ArrayList<>();
        ItemMeta meta = rewardCopy.getItemMeta();
        if (meta != null) {
            Component customName = meta.displayName();
            if (customName != null) {
                displayName = LEGACY_AMP.serialize(customName);
            }
            List<Component> loreComponents = meta.lore();
            if (loreComponents != null) {
                for (Component line : loreComponents) {
                    displayLore.add(LEGACY_AMP.serialize(line));
                }
            }
        }

        long safePrice = priceOrUnset > 0L ? priceOrUnset : ShopItem.UNSET_PRICE;

        ShopItem shopItem = new ShopItem(
                finalId,
                category.getId(),
                pageAndSlot[1],
                pageAndSlot[0],
                safePrice,
                rewardCopy.getAmount(),
                rewardCopy.getType(),
                displayName,
                displayLore,
                "",
                RewardType.ITEM,
                rewardCopy,
                List.of(),
                -1,
                -1,
                -1,
                0,
                false
        );

        items.put(finalId, shopItem);
        saveCategoryShopFile(category.getId());
        return shopItem;
    }

    public synchronized boolean removeItem(String categoryId, String itemId) throws IOException {
        Map<String, ShopItem> items = categoryItems.get(categoryId.toLowerCase(Locale.ROOT));
        if (items == null) {
            return false;
        }
        ShopItem removed = items.remove(itemId.toLowerCase(Locale.ROOT));
        if (removed == null) {
            return false;
        }
        saveCategoryShopFile(categoryId);
        return true;
    }

    public synchronized void saveCategoryShopFile(String categoryId) throws IOException {
        ShopCategory category = getCategory(categoryId);
        if (category == null) {
            return;
        }
        Map<String, ShopItem> items = categoryItems.getOrDefault(category.getId(), Map.of());
        File shopFile = new File(shopsDir, category.getShopFileName());
        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection itemsSec = yml.createSection("items");

        for (ShopItem item : items.values()) {
            ConfigurationSection sec = itemsSec.createSection(item.getId());
            sec.set("slot", item.getSlot());
            sec.set("page", item.getPage());
            sec.set("price", item.isPriceValid() ? item.getPrice() : "UNSET");
            sec.set("amount", item.getAmount());
            sec.set("material", item.getDisplayMaterial().name());
            sec.set("display-name", item.getDisplayName());
            sec.set("lore", item.getDisplayLore());
            sec.set("permission", item.getPermission());
            sec.set("reward-type", item.getRewardType().name());
            sec.set("commands", item.getCommands());
            sec.set("stock-limit", item.getStockLimit());
            sec.set("current-stock", item.getCurrentStock());
            sec.set("player-limit", item.getPlayerLimit());
            sec.set("cooldown-seconds", item.getCooldownSeconds());
            sec.set("require-confirmation", item.isRequireConfirmation());

            if (item.hasExactRewardItem()) {
                ItemSerializer.writeItemToSection(item.getRawRewardItem(), sec, plugin.getLogger());
            }
        }
        saveYamlAtomic(yml, shopFile);
    }

    private String generateUniqueItemId(String categoryId, Material material) {
        String base = material.name().toLowerCase(Locale.ROOT);
        Map<String, ShopItem> existing = categoryItems.getOrDefault(categoryId, Map.of());
        if (!existing.containsKey(base)) {
            return base;
        }
        int counter = 2;
        while (existing.containsKey(base + "_" + counter)) {
            counter++;
        }
        return base + "_" + counter;
    }

    private int findNextFreeCategorySlot() {
        List<Integer> taken = categories.values().stream().map(ShopCategory::getSlot).toList();
        int[] preferred = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int slot : preferred) {
            if (!taken.contains(slot)) {
                return slot;
            }
        }
        for (int slot = 0; slot < 36; slot++) {
            if (!taken.contains(slot)) {
                return slot;
            }
        }
        return 0;
    }

    private int[] findFirstFreePageAndSlot(ShopCategory category) {
        int usableSlots = category.getGuiSize() - 9;
        for (int page = 1; page <= 50; page++) {
            for (int slot = 0; slot < usableSlots; slot++) {
                if (getItemAt(category.getId(), page, slot) == null) {
                    return new int[]{page, slot};
                }
            }
        }
        return new int[]{1, 0};
    }

    private void saveYamlAtomic(YamlConfiguration yml, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File temp = new File(parent, destination.getName() + ".tmp");
        yml.save(temp);
        try {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicEx) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
