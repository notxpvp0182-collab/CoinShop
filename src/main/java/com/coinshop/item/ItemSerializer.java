package com.coinshop.item;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Version-aware ItemStack serializer for Paper 1.21.x & 26.x.
 *
 * Uses TWO complementary persistence representations inside the shop YAML:
 * 1. `reward-item-bytes`: Paper's native `ItemStack#serializeAsBytes()` Base64-encoded (lossless NBT/DataComponent round-trip
 *    covering enchantments, custom name, lore, custom model data, attributes, skull textures, trims, potion effects, damage, etc.).
 * 2. `reward-item-yaml`: Bukkit ConfigurationSerializable `ItemStack` section so admins can also inspect or edit YAML directly.
 */
public final class ItemSerializer {

    private ItemSerializer() {
    }

    public static void writeItemToSection(ItemStack item, ConfigurationSection section, Logger logger) {
        if (item == null || section == null) {
            return;
        }
        ItemStack copy = item.clone();
        section.set("reward-item-yaml", copy);
        try {
            byte[] bytes = copy.serializeAsBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);
            section.set("reward-item-bytes", base64);
        } catch (Throwable t) {
            // Fallback gracefully if running in a unit test environment without Paper's CraftMagicNumbers initialized
            if (logger != null) {
                logger.log(Level.FINE, "serializeAsBytes unavailable in current runtime; using Bukkit YAML serialization.", t);
            }
        }
    }

    public static ItemStack readItemFromSection(ConfigurationSection section, Logger logger) {
        if (section == null) {
            return null;
        }
        String base64 = section.getString("reward-item-bytes");
        if (base64 != null && !base64.isBlank()) {
            try {
                byte[] bytes = Base64.getDecoder().decode(base64);
                return ItemStack.deserializeBytes(bytes);
            } catch (Throwable t) {
                if (logger != null) {
                    logger.log(Level.WARNING, "Failed to deserialize reward-item-bytes; falling back to reward-item-yaml.", t);
                }
            }
        }
        if (section.contains("reward-item-yaml")) {
            return section.getItemStack("reward-item-yaml");
        }
        return null;
    }
}
