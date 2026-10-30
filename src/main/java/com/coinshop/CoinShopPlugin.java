package com.coinshop;

import com.coinshop.command.CoinShopCommand;
import com.coinshop.config.ConfigManager;
import com.coinshop.config.MessageManager;
import com.coinshop.economy.CoinEconomyService;
import com.coinshop.editor.EditorSessionManager;
import com.coinshop.gui.CoinShopInventoryHolder;
import com.coinshop.gui.GuiListener;
import com.coinshop.gui.GuiManager;
import com.coinshop.logging.TransactionLogger;
import com.coinshop.placeholder.CoinShopPlaceholderExpansion;
import com.coinshop.shop.PurchaseService;
import com.coinshop.shop.ShopManager;
import com.coinshop.storage.SqliteStorageProvider;
import com.coinshop.storage.StorageProvider;
import com.coinshop.storage.YamlStorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Main lifecycle entry point for CoinShop.
 * Compatible with Paper 1.21.x and 26.x on Java 21 and Java 25.
 */
public final class CoinShopPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private StorageProvider storageProvider;
    private CoinEconomyService economyService;
    private TransactionLogger transactionLogger;
    private ShopManager shopManager;
    private PurchaseService purchaseService;
    private GuiManager guiManager;
    private EditorSessionManager editorSessionManager;

    @Override
    public void onEnable() {
        // 1. Load configuration and messages
        this.configManager = new ConfigManager(this);
        this.configManager.loadInitial();

        this.messageManager = new MessageManager(this);
        this.messageManager.load();

        // 2. Initialize persistent storage (SQLite default, YAML fallback)
        try {
            if ("YAML".equalsIgnoreCase(configManager.getStorageType())) {
                File yamlFile = new File(getDataFolder(), configManager.getYamlFileName());
                this.storageProvider = new YamlStorageProvider(yamlFile, getLogger());
            } else {
                File dbFile = new File(getDataFolder(), configManager.getSqliteFileName());
                this.storageProvider = new SqliteStorageProvider(dbFile, getLogger());
            }
            this.storageProvider.initialize();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize primary storage; falling back to YAML storage.", e);
            try {
                File yamlFile = new File(getDataFolder(), "balances_fallback.yml");
                this.storageProvider = new YamlStorageProvider(yamlFile, getLogger());
                this.storageProvider.initialize();
            } catch (Exception fatal) {
                getLogger().log(Level.SEVERE, "Fatal error initializing storage. Disabling CoinShop.", fatal);
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
        }

        // 3. Initialize Coin economy service and load player balances
        this.economyService = new CoinEconomyService(
                storageProvider,
                getLogger(),
                configManager.getStartingBalance(),
                configManager.getMaxBalance(),
                configManager.createCurrencyFormatter()
        );
        try {
            this.economyService.loadInitialCache();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not preload all balances at startup.", e);
        }

        // 4. Initialize audit transaction logger
        this.transactionLogger = new TransactionLogger(
                getDataFolder(),
                getLogger(),
                configManager.isLogTransactionsToFile(),
                configManager.isLogAdminActionsToConsole()
        );

        // 5. Initialize categories and shops
        this.shopManager = new ShopManager(this);
        this.shopManager.saveDefaultResourcesIfMissing();
        List<String> startupErrors = new ArrayList<>();
        this.shopManager.loadAndValidate(startupErrors, false);
        if (!startupErrors.isEmpty()) {
            getLogger().warning("Detected " + startupErrors.size() + " configuration warning(s) on startup:");
            for (String err : startupErrors) {
                getLogger().warning(" - " + err);
            }
        }

        // 6. Initialize purchase service, GUIs, and editor sessions
        this.purchaseService = new PurchaseService(
                configManager,
                messageManager,
                shopManager,
                economyService,
                storageProvider,
                transactionLogger,
                getLogger()
        );
        this.guiManager = new GuiManager(
                configManager,
                messageManager,
                shopManager,
                economyService,
                storageProvider,
                purchaseService
        );
        this.editorSessionManager = new EditorSessionManager(
                this,
                shopManager,
                guiManager,
                messageManager
        );

        // 7. Register event listeners
        Bukkit.getPluginManager().registerEvents(editorSessionManager, this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(
                configManager,
                messageManager,
                shopManager,
                guiManager,
                purchaseService,
                editorSessionManager
        ), this);

        // 8. Register commands
        PluginCommand cmd = getCommand("coinshop");
        if (cmd != null) {
            CoinShopCommand executor = new CoinShopCommand(
                    this,
                    messageManager,
                    economyService,
                    shopManager,
                    guiManager,
                    transactionLogger
            );
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        // 9. Optional integrations (PlaceholderAPI & EssentialsX separation check)
        registerOptionalIntegrations();

        getLogger().info("CoinShop v" + getPluginMeta().getVersion() + " enabled successfully ("
                + shopManager.getCategories().size() + " categories, "
                + shopManager.getTotalItemCount() + " items).");
    }

    @Override
    public void onDisable() {
        // Close any open CoinShop GUIs so players cannot hold stale menus across reloads/shutdown
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof CoinShopInventoryHolder) {
                player.closeInventory();
            }
        }

        if (editorSessionManager != null) {
            editorSessionManager.clearAll();
        }
        if (economyService != null) {
            economyService.shutdown();
        }
        if (transactionLogger != null) {
            transactionLogger.shutdown();
        }
        getLogger().info("CoinShop disabled cleanly.");
    }

    /**
     * Validates config.yml, categories/*.yml, and shops/*.yml BEFORE replacing active settings.
     * If any validation error is found, active configuration and shops are preserved untouched.
     */
    public synchronized List<String> reloadEverythingSafely() {
        List<String> errors = new ArrayList<>();
        boolean configOk = configManager.reloadAndValidate(errors);
        boolean shopsOk = shopManager.loadAndValidate(errors, true);

        if (!configOk || !shopsOk || !errors.isEmpty()) {
            return errors;
        }

        messageManager.load();
        economyService.updateSettings(
                configManager.getStartingBalance(),
                configManager.getMaxBalance(),
                configManager.createCurrencyFormatter()
        );
        transactionLogger.updateSettings(
                configManager.isLogTransactionsToFile(),
                configManager.isLogAdminActionsToConsole()
        );
        return List.of();
    }

    private void registerOptionalIntegrations() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new CoinShopPlaceholderExpansion(getPluginMeta().getVersion(), economyService).register();
                getLogger().info("Hooked into PlaceholderAPI (%coinshop_balance%, %coinshop_balance_formatted%, %coinshop_balance_raw%).");
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Failed to register PlaceholderAPI expansion", t);
            }
        } else {
            getLogger().info("PlaceholderAPI not found; running without external placeholders.");
        }

        if (Bukkit.getPluginManager().getPlugin("Essentials") != null) {
            getLogger().info("EssentialsX detected. CoinShop currency remains strictly separate from EssentialsX money.");
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public CoinEconomyService getEconomyService() {
        return economyService;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }
}
