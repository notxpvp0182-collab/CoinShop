package com.coinshop.editor;

import com.coinshop.category.ShopCategory;
import com.coinshop.config.MessageManager;
import com.coinshop.gui.GuiManager;
import com.coinshop.shop.ShopItem;
import com.coinshop.shop.ShopManager;
import com.coinshop.util.TextUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages active chat-prompt editing sessions for admins, keyed by player UUID.
 * Automatically cleans up sessions on player disconnect and plugin shutdown.
 */
public final class EditorSessionManager implements Listener {

    public enum EditField {
        CREATE_CATEGORY,
        RENAME_CATEGORY,
        ITEM_PRICE,
        ITEM_DISPLAY_NAME,
        ITEM_AMOUNT,
        ITEM_SLOT,
        ITEM_PERMISSION,
        ITEM_ADD_COMMAND
    }

    public record PromptSession(
            EditField field,
            String categoryId,
            String itemId
    ) {
    }

    private final JavaPlugin plugin;
    private final ShopManager shopManager;
    private final GuiManager guiManager;
    private final MessageManager messageManager;
    private final ConcurrentHashMap<UUID, PromptSession> activePrompts = new ConcurrentHashMap<>();

    public EditorSessionManager(
            JavaPlugin plugin,
            ShopManager shopManager,
            GuiManager guiManager,
            MessageManager messageManager
    ) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.guiManager = guiManager;
        this.messageManager = messageManager;
    }

    public void startPrompt(Player player, EditField field, String categoryId, String itemId, String instruction) {
        player.closeInventory();
        activePrompts.put(player.getUniqueId(), new PromptSession(field, categoryId, itemId));
        player.sendMessage(TextUtil.colorize(messageManager.getPrefix() + "&#FFAA00" + instruction + " &7(Type &ccancel &7to abort)"));
    }

    public void clearSession(UUID uuid) {
        activePrompts.remove(uuid);
    }

    public void clearAll() {
        activePrompts.clear();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        activePrompts.remove(uuid);
        guiManager.getPurchaseService().cleanupPlayer(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PromptSession session = activePrompts.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        Bukkit.getScheduler().runTask(plugin, () -> handleInputSync(player, session, input));
    }

    private void handleInputSync(Player player, PromptSession session, String input) {
        if (!player.hasPermission("coinshop.editor") && !player.hasPermission("coinshop.admin")) {
            messageManager.send(player, "errors.no-permission", Map.of("%permission%", "coinshop.editor"));
            return;
        }

        if ("cancel".equalsIgnoreCase(input)) {
            messageManager.send(player, "admin.editor-prompt-cancelled");
            reopenAppropriateMenu(player, session);
            return;
        }

        try {
            switch (session.field()) {
                case CREATE_CATEGORY -> {
                    String id = input.toLowerCase();
                    if (!TextUtil.isValidId(id)) {
                        messageManager.send(player, "errors.invalid-id", Map.of("%id%", input));
                    } else if (shopManager.getCategory(id) != null) {
                        messageManager.send(player, "errors.category-already-exists", Map.of("%category%", id));
                    } else {
                        shopManager.createCategory(id);
                        messageManager.send(player, "admin.category-created", Map.of("%category%", id));
                    }
                    guiManager.openEditorCategories(player);
                }
                case RENAME_CATEGORY -> {
                    ShopCategory cat = shopManager.getCategory(session.categoryId());
                    if (cat != null) {
                        cat.setDisplayName(input);
                        shopManager.saveCategoryToFile(cat);
                        messageManager.send(player, "admin.editor-saved", Map.of("%category%", cat.getId()));
                    }
                    guiManager.openEditorCategories(player);
                }
                case ITEM_PRICE -> {
                    ShopItem item = shopManager.getItem(session.categoryId(), session.itemId());
                    if (item != null) {
                        try {
                            long price = Long.parseLong(input);
                            item.setPrice(price);
                            shopManager.saveCategoryShopFile(session.categoryId());
                            messageManager.send(player, "admin.editor-saved", Map.of("%category%", session.categoryId()));
                        } catch (NumberFormatException ex) {
                            messageManager.send(player, "errors.invalid-amount", Map.of("%amount%", input));
                        }
                    }
                    guiManager.openEditorItemProperties(player, session.categoryId(), session.itemId());
                }
                case ITEM_DISPLAY_NAME -> {
                    ShopItem item = shopManager.getItem(session.categoryId(), session.itemId());
                    if (item != null) {
                        item.setDisplayName("none".equalsIgnoreCase(input) ? "" : input);
                        shopManager.saveCategoryShopFile(session.categoryId());
                        messageManager.send(player, "admin.editor-saved", Map.of("%category%", session.categoryId()));
                    }
                    guiManager.openEditorItemProperties(player, session.categoryId(), session.itemId());
                }
                case ITEM_AMOUNT -> {
                    ShopItem item = shopManager.getItem(session.categoryId(), session.itemId());
                    if (item != null) {
                        try {
                            int amt = Integer.parseInt(input);
                            if (amt < 1 || amt > 64) {
                                messageManager.send(player, "errors.invalid-amount", Map.of("%amount%", input));
                            } else {
                                item.setAmount(amt);
                                shopManager.saveCategoryShopFile(session.categoryId());
                                messageManager.send(player, "admin.editor-saved", Map.of("%category%", session.categoryId()));
                            }
                        } catch (NumberFormatException ex) {
                            messageManager.send(player, "errors.invalid-amount", Map.of("%amount%", input));
                        }
                    }
                    guiManager.openEditorItemProperties(player, session.categoryId(), session.itemId());
                }
                case ITEM_SLOT -> {
                    ShopItem item = shopManager.getItem(session.categoryId(), session.itemId());
                    if (item != null) {
                        try {
                            int slot = Integer.parseInt(input);
                            if (slot < 0 || slot >= 45) {
                                messageManager.send(player, "errors.invalid-amount", Map.of("%amount%", input));
                            } else {
                                item.setSlot(slot);
                                shopManager.saveCategoryShopFile(session.categoryId());
                                messageManager.send(player, "admin.editor-saved", Map.of("%category%", session.categoryId()));
                            }
                        } catch (NumberFormatException ex) {
                            messageManager.send(player, "errors.invalid-amount", Map.of("%amount%", input));
                        }
                    }
                    guiManager.openEditorItemProperties(player, session.categoryId(), session.itemId());
                }
                case ITEM_PERMISSION -> {
                    ShopItem item = shopManager.getItem(session.categoryId(), session.itemId());
                    if (item != null) {
                        item.setPermission("none".equalsIgnoreCase(input) ? "" : input);
                        shopManager.saveCategoryShopFile(session.categoryId());
                        messageManager.send(player, "admin.editor-saved", Map.of("%category%", session.categoryId()));
                    }
                    guiManager.openEditorItemProperties(player, session.categoryId(), session.itemId());
                }
                case ITEM_ADD_COMMAND -> {
                    ShopItem item = shopManager.getItem(session.categoryId(), session.itemId());
                    if (item != null) {
                        List<String> cmds = new ArrayList<>(item.getCommands());
                        cmds.add(input.startsWith("/") ? input.substring(1) : input);
                        item.setCommands(cmds);
                        shopManager.saveCategoryShopFile(session.categoryId());
                        messageManager.send(player, "admin.editor-saved", Map.of("%category%", session.categoryId()));
                    }
                    guiManager.openEditorItemProperties(player, session.categoryId(), session.itemId());
                }
            }
        } catch (IOException e) {
            messageManager.send(player, "errors.storage-error");
        }
    }

    private void reopenAppropriateMenu(Player player, PromptSession session) {
        if (session.itemId() != null) {
            guiManager.openEditorItemProperties(player, session.categoryId(), session.itemId());
        } else if (session.categoryId() != null) {
            guiManager.openEditorShopSlots(player, session.categoryId(), 1);
        } else {
            guiManager.openEditorCategories(player);
        }
    }
}
