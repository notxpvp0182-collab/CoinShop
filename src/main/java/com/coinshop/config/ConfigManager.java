package com.coinshop.config;

import com.coinshop.economy.CurrencyFormatter;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/**
 * Manages and validates main config.yml settings, keeping the last known-good snapshot on reload failures.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;
    private FileConfiguration config;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadInitial() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    /**
     * Validates a fresh disk copy of config.yml before replacing the active configuration.
     */
    public boolean reloadAndValidate(List<String> errors) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            plugin.saveDefaultConfig();
        }
        YamlConfiguration candidate = YamlConfiguration.loadConfiguration(file);
        int mainSize = candidate.getInt("main-menu.size", 45);
        if (mainSize < 9 || mainSize > 54 || mainSize % 9 != 0) {
            errors.add("config.yml: main-menu.size must be a multiple of 9 between 9 and 54 (found " + mainSize + ")");
        }
        String fillerMat = candidate.getString("main-menu.filler.material", "BLACK_STAINED_GLASS_PANE");
        if (Material.matchMaterial(fillerMat) == null) {
            errors.add("config.yml: invalid main-menu.filler.material '" + fillerMat + "'");
        }
        long maxBal = candidate.getLong("currency.max-balance", 9000000000000000L);
        if (maxBal <= 0L) {
            errors.add("config.yml: currency.max-balance must be > 0");
        }
        if (!errors.isEmpty()) {
            return false;
        }
        this.config = candidate;
        return true;
    }

    public CurrencyFormatter createCurrencyFormatter() {
        return new CurrencyFormatter(
                config.getString("currency.singular-name", "Coin"),
                config.getString("currency.plural-name", "Coins"),
                config.getString("currency.symbol", "⛃"),
                config.getBoolean("currency.format.use-thousand-separators", true),
                config.getString("currency.format.suffixes.thousand", "K"),
                config.getString("currency.format.suffixes.million", "M"),
                config.getString("currency.format.suffixes.billion", "B"),
                config.getString("currency.format.suffixes.trillion", "T")
        );
    }

    public String getStorageType() {
        return config.getString("storage.type", "SQLITE").toUpperCase();
    }

    public String getSqliteFileName() {
        return config.getString("storage.sqlite-file", "database.db");
    }

    public String getYamlFileName() {
        return config.getString("storage.yaml-file", "balances.yml");
    }

    public long getStartingBalance() {
        return Math.max(0L, config.getLong("currency.starting-balance", 0L));
    }

    public long getMaxBalance() {
        return Math.max(1L, config.getLong("currency.max-balance", 9000000000000000L));
    }

    public boolean isUseSmallCaps() {
        return config.getBoolean("style.use-small-caps", true);
    }

    public String getMainMenuTitle() {
        return config.getString("main-menu.title", "&8✦ &#FFAA00ᴄᴏɪɴ ꜱʜᴏᴘ &8✦");
    }

    public int getMainMenuSize() {
        int size = config.getInt("main-menu.size", 45);
        return (size >= 9 && size <= 54 && size % 9 == 0) ? size : 45;
    }

    public boolean isMainFillerEnabled() {
        return config.getBoolean("main-menu.filler.enabled", true);
    }

    public Material getMainFillerMaterial() {
        Material m = Material.matchMaterial(config.getString("main-menu.filler.material", "BLACK_STAINED_GLASS_PANE"));
        return m != null ? m : Material.BLACK_STAINED_GLASS_PANE;
    }

    public String getMainFillerName() {
        return config.getString("main-menu.filler.name", " ");
    }

    public boolean isBalanceItemEnabled() {
        return config.getBoolean("main-menu.balance-item.enabled", true);
    }

    public int getBalanceItemSlot() {
        return config.getInt("main-menu.balance-item.slot", 40);
    }

    public Material getBalanceItemMaterial() {
        Material m = Material.matchMaterial(config.getString("main-menu.balance-item.material", "SUNFLOWER"));
        return m != null ? m : Material.SUNFLOWER;
    }

    public String getBalanceItemName() {
        return config.getString("main-menu.balance-item.name", "&#FFAA00&lʏᴏᴜʀ ʙᴀʟᴀɴᴄᴇ");
    }

    public List<String> getBalanceItemLore() {
        return config.getStringList("main-menu.balance-item.lore");
    }

    public boolean isCloseButtonEnabled() {
        return config.getBoolean("main-menu.close-button.enabled", true);
    }

    public int getCloseButtonSlot() {
        return config.getInt("main-menu.close-button.slot", 44);
    }

    public Material getCloseButtonMaterial() {
        Material m = Material.matchMaterial(config.getString("main-menu.close-button.material", "BARRIER"));
        return m != null ? m : Material.BARRIER;
    }

    public String getCloseButtonName() {
        return config.getString("main-menu.close-button.name", "&#FF5555&lᴄʟᴏꜱᴇ");
    }

    public List<String> getCloseButtonLore() {
        return config.getStringList("main-menu.close-button.lore");
    }

    public long getConfirmThreshold() {
        return config.getLong("shop-defaults.confirm-threshold", 1000L);
    }

    public String getConfirmGuiTitle() {
        return config.getString("shop-defaults.confirm-gui-title", "&8✦ &#FFAA00ᴄᴏɴꜰɪʀᴍ ᴘᴜʀᴄʜᴀꜱᴇ &8✦");
    }

    public long getPurchaseCooldownMs() {
        return Math.max(0L, config.getLong("shop-defaults.purchase-cooldown-ms", 350L));
    }

    public int getPrevPageSlot() {
        return config.getInt("shop-defaults.navigation.previous-page-slot", 45);
    }

    public Material getPrevPageMaterial() {
        Material m = Material.matchMaterial(config.getString("shop-defaults.navigation.previous-page-material", "ARROW"));
        return m != null ? m : Material.ARROW;
    }

    public String getPrevPageName() {
        return config.getString("shop-defaults.navigation.previous-page-name", "&#FFAA00« ᴘʀᴇᴠɪᴏᴜꜱ ᴘᴀɢᴇ");
    }

    public int getBackSlot() {
        return config.getInt("shop-defaults.navigation.back-slot", 49);
    }

    public Material getBackMaterial() {
        Material m = Material.matchMaterial(config.getString("shop-defaults.navigation.back-material", "DARK_OAK_DOOR"));
        return m != null ? m : Material.DARK_OAK_DOOR;
    }

    public String getBackName() {
        return config.getString("shop-defaults.navigation.back-name", "&#FF5555« ʙᴀᴄᴋ ᴛᴏ ᴄᴀᴛᴇɢᴏʀɪᴇꜱ");
    }

    public int getNextPageSlot() {
        return config.getInt("shop-defaults.navigation.next-page-slot", 53);
    }

    public Material getNextPageMaterial() {
        Material m = Material.matchMaterial(config.getString("shop-defaults.navigation.next-page-material", "ARROW"));
        return m != null ? m : Material.ARROW;
    }

    public String getNextPageName() {
        return config.getString("shop-defaults.navigation.next-page-name", "&#FFAA00ɴᴇxᴛ ᴘᴀɢᴇ »");
    }

    public List<String> getShopLoreFormat() {
        return config.getStringList("shop-defaults.lore-format");
    }

    public String getPrompt(String key) {
        return config.getString("shop-defaults.prompts." + key, "");
    }

    public String getSoundName(String key) {
        return config.getString("sounds." + key, "");
    }

    public boolean isLogTransactionsToFile() {
        return config.getBoolean("logging.log-transactions-to-file", true);
    }

    public boolean isLogAdminActionsToConsole() {
        return config.getBoolean("logging.log-admin-actions-to-console", true);
    }
}
