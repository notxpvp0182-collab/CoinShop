package com.coinshop.gui;

import com.coinshop.category.ShopCategory;
import com.coinshop.config.ConfigManager;
import com.coinshop.config.MessageManager;
import com.coinshop.editor.EditorSessionManager;
import com.coinshop.shop.PurchaseService;
import com.coinshop.shop.RewardType;
import com.coinshop.shop.ShopItem;
import com.coinshop.shop.ShopManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Handles all clicks and drags inside CoinShop inventories.
 *
 * Exploit Protection:
 * - Cancels ALL clicks, shift-clicks, number-key hotbar swaps, offhand swaps, double-click collect-to-cursor,
 *   and inventory drags when the top inventory is a CoinShopInventoryHolder.
 * - Verifies editor permission on every single click inside an editor menu.
 */
public final class GuiListener implements Listener {

    private final ConfigManager configManager;
    private final MessageManager messageManager;
    private final ShopManager shopManager;
    private final GuiManager guiManager;
    private final PurchaseService purchaseService;
    private final EditorSessionManager editorSessionManager;

    public GuiListener(
            ConfigManager configManager,
            MessageManager messageManager,
            ShopManager shopManager,
            GuiManager guiManager,
            PurchaseService purchaseService,
            EditorSessionManager editorSessionManager
    ) {
        this.configManager = configManager;
        this.messageManager = messageManager;
        this.shopManager = shopManager;
        this.guiManager = guiManager;
        this.purchaseService = purchaseService;
        this.editorSessionManager = editorSessionManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof CoinShopInventoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof CoinShopInventoryHolder holder)) {
            return;
        }

        // Unconditionally cancel every inventory manipulation when a CoinShop GUI is open
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Block collect-to-cursor, hotbar swap, offhand swap, drop
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getClick() == ClickType.NUMBER_KEY
                || event.getClick() == ClickType.SWAP_OFFHAND
                || event.getClick() == ClickType.DOUBLE_CLICK
                || event.getClick() == ClickType.DROP
                || event.getClick() == ClickType.CONTROL_DROP) {
            return;
        }

        // Enforce editor permission for editor menus
        if (holder.isEditorMenu() && !player.hasPermission("coinshop.editor") && !player.hasPermission("coinshop.admin")) {
            player.closeInventory();
            messageManager.send(player, "errors.no-permission", Map.of("%permission%", "coinshop.editor"));
            return;
        }

        Inventory clickedInv = event.getClickedInventory();
        if (clickedInv == null) {
            return;
        }

        // Allow clicking an item in the bottom player inventory ONLY inside EDITOR_SHOP_SLOTS to quickly add that item
        if (!clickedInv.equals(top)) {
            if (holder.getMenuType() == CoinShopInventoryHolder.MenuType.EDITOR_SHOP_SLOTS) {
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem != null && clickedItem.getType() != Material.AIR) {
                    addQuickItemFromEditor(player, holder.getCategoryId(), clickedItem, holder.getPage());
                }
            }
            return;
        }

        int slot = event.getRawSlot();
        switch (holder.getMenuType()) {
            case MAIN_CATEGORIES -> handleMainMenuClick(player, slot);
            case CATEGORY_SHOP -> handleCategoryShopClick(player, holder, slot);
            case CONFIRM_PURCHASE -> handleConfirmClick(player, holder, slot);
            case EDITOR_CATEGORIES -> handleEditorCategoriesClick(player, event.getClick(), slot);
            case EDITOR_SHOP_SLOTS -> handleEditorShopSlotsClick(player, holder, event.getClick(), slot);
            case EDITOR_ITEM_PROPERTIES -> handleEditorItemPropertiesClick(player, holder, event.getClick(), slot);
        }
    }

    private void handleMainMenuClick(Player player, int slot) {
        if (configManager.isCloseButtonEnabled() && slot == configManager.getCloseButtonSlot()) {
            player.closeInventory();
            return;
        }
        for (ShopCategory category : shopManager.getCategories()) {
            if (category.isEnabled() && category.getSlot() == slot) {
                guiManager.openCategoryShop(player, category.getId(), 1);
                return;
            }
        }
    }

    private void handleCategoryShopClick(Player player, CoinShopInventoryHolder holder, int slot) {
        ShopCategory category = shopManager.getCategory(holder.getCategoryId());
        if (category == null) {
            player.closeInventory();
            return;
        }

        int size = category.getGuiSize();
        int prevSlot = configManager.getPrevPageSlot() < size ? configManager.getPrevPageSlot() : size - 9;
        int backSlot = configManager.getBackSlot() < size ? configManager.getBackSlot() : size - 5;
        int nextSlot = configManager.getNextPageSlot() < size ? configManager.getNextPageSlot() : size - 1;

        if (slot == backSlot) {
            guiManager.openMainMenu(player);
            return;
        }
        if (slot == prevSlot && holder.getPage() > 1) {
            guiManager.openCategoryShop(player, category.getId(), holder.getPage() - 1);
            return;
        }
        if (slot == nextSlot) {
            boolean hasNext = shopManager.getItems(category.getId()).stream().anyMatch(i -> i.getPage() > holder.getPage());
            if (hasNext) {
                guiManager.openCategoryShop(player, category.getId(), holder.getPage() + 1);
            }
            return;
        }

        ShopItem item = shopManager.getItemAt(category.getId(), holder.getPage(), slot);
        if (item == null) {
            return;
        }

        if (!item.isPriceValid()) {
            messageManager.send(player, "shop.price-unset");
            return;
        }

        long confirmThreshold = configManager.getConfirmThreshold();
        if (item.isRequireConfirmation() || (confirmThreshold > 0 && item.getPrice() >= confirmThreshold)) {
            guiManager.openConfirmationGui(player, category, item, holder.getPage());
        } else {
            if (purchaseService.attemptPurchase(player, category.getId(), item.getId())) {
                guiManager.openCategoryShop(player, category.getId(), holder.getPage());
            }
        }
    }

    private void handleConfirmClick(Player player, CoinShopInventoryHolder holder, int slot) {
        if (slot == 11) {
            purchaseService.attemptPurchase(player, holder.getCategoryId(), holder.getItemId());
            guiManager.openCategoryShop(player, holder.getCategoryId(), holder.getPage());
        } else if (slot == 15) {
            guiManager.openCategoryShop(player, holder.getCategoryId(), holder.getPage());
        }
    }

    private void handleEditorCategoriesClick(Player player, ClickType click, int slot) {
        if (slot == 53) {
            player.closeInventory();
            return;
        }
        if (slot == 49) {
            editorSessionManager.startPrompt(
                    player,
                    EditorSessionManager.EditField.CREATE_CATEGORY,
                    null,
                    null,
                    "Enter the new category ID (lowercase letters, numbers, hyphens):"
            );
            return;
        }

        for (ShopCategory category : shopManager.getCategories()) {
            if (category.getSlot() == slot) {
                if (click == ClickType.SHIFT_RIGHT) {
                    shopManager.removeCategory(category.getId());
                    messageManager.send(player, "admin.category-removed", Map.of("%category%", category.getId()));
                    guiManager.openEditorCategories(player);
                } else if (click == ClickType.RIGHT) {
                    editorSessionManager.startPrompt(
                            player,
                            EditorSessionManager.EditField.RENAME_CATEGORY,
                            category.getId(),
                            null,
                            "Enter the new display name for category '" + category.getId() + "':"
                    );
                } else {
                    guiManager.openEditorShopSlots(player, category.getId(), 1);
                }
                return;
            }
        }
    }

    private void handleEditorShopSlotsClick(Player player, CoinShopInventoryHolder holder, ClickType click, int slot) {
        String catId = holder.getCategoryId();
        int page = holder.getPage();

        if (slot == 49) {
            guiManager.openEditorCategories(player);
            return;
        }
        if (slot == 45 && page > 1) {
            guiManager.openEditorShopSlots(player, catId, page - 1);
            return;
        }
        if (slot == 53) {
            guiManager.openEditorShopSlots(player, catId, page + 1);
            return;
        }
        if (slot == 48) {
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            if (mainHand.getType() == Material.AIR) {
                messageManager.send(player, "errors.empty-hand");
                return;
            }
            addQuickItemFromEditor(player, catId, mainHand, page);
            return;
        }

        ShopItem item = shopManager.getItemAt(catId, page, slot);
        if (item == null) {
            return;
        }

        if (click == ClickType.SHIFT_RIGHT) {
            try {
                shopManager.removeItem(catId, item.getId());
                messageManager.send(player, "admin.item-removed", Map.of(
                        "%item_id%", item.getId(),
                        "%category%", catId
                ));
                guiManager.openEditorShopSlots(player, catId, page);
            } catch (IOException e) {
                messageManager.send(player, "errors.storage-error");
            }
        } else {
            guiManager.openEditorItemProperties(player, catId, item.getId());
        }
    }

    private void handleEditorItemPropertiesClick(Player player, CoinShopInventoryHolder holder, ClickType click, int slot) {
        String catId = holder.getCategoryId();
        String itemId = holder.getItemId();
        ShopItem item = shopManager.getItem(catId, itemId);
        if (item == null) {
            guiManager.openEditorShopSlots(player, catId, holder.getPage());
            return;
        }

        switch (slot) {
            case 10 -> editorSessionManager.startPrompt(
                    player,
                    EditorSessionManager.EditField.ITEM_PRICE,
                    catId,
                    itemId,
                    "Enter the new positive Coin price for '" + itemId + "':"
            );
            case 11 -> editorSessionManager.startPrompt(
                    player,
                    EditorSessionManager.EditField.ITEM_DISPLAY_NAME,
                    catId,
                    itemId,
                    "Enter the new display name (or 'none' to use default item name):"
            );
            case 12 -> editorSessionManager.startPrompt(
                    player,
                    EditorSessionManager.EditField.ITEM_AMOUNT,
                    catId,
                    itemId,
                    "Enter the stack amount (1-64):"
            );
            case 13 -> editorSessionManager.startPrompt(
                    player,
                    EditorSessionManager.EditField.ITEM_SLOT,
                    catId,
                    itemId,
                    "Enter the slot index (0-44):"
            );
            case 14 -> editorSessionManager.startPrompt(
                    player,
                    EditorSessionManager.EditField.ITEM_PERMISSION,
                    catId,
                    itemId,
                    "Enter required permission node (or 'none' to clear):"
            );
            case 15 -> {
                RewardType next = switch (item.getRewardType()) {
                    case ITEM -> RewardType.COMMAND;
                    case COMMAND -> RewardType.BOTH;
                    case BOTH -> RewardType.ITEM;
                };
                item.setRewardType(next);
                try {
                    shopManager.saveCategoryShopFile(catId);
                } catch (IOException ignored) {
                }
                guiManager.openEditorItemProperties(player, catId, itemId);
            }
            case 16 -> {
                if (click == ClickType.RIGHT) {
                    item.setCommands(List.of());
                    try {
                        shopManager.saveCategoryShopFile(catId);
                    } catch (IOException ignored) {
                    }
                    guiManager.openEditorItemProperties(player, catId, itemId);
                } else {
                    editorSessionManager.startPrompt(
                            player,
                            EditorSessionManager.EditField.ITEM_ADD_COMMAND,
                            catId,
                            itemId,
                            "Enter console reward command (use %player% placeholder):"
                    );
                }
            }
            case 31 -> {
                try {
                    shopManager.saveCategoryShopFile(catId);
                    messageManager.send(player, "admin.editor-saved", Map.of("%category%", catId));
                } catch (IOException e) {
                    messageManager.send(player, "errors.storage-error");
                }
                guiManager.openEditorShopSlots(player, catId, item.getPage());
            }
        }
    }

    private void addQuickItemFromEditor(Player player, String categoryId, ItemStack stack, int currentPage) {
        try {
            ShopItem added = shopManager.addHandItemToCategory(categoryId, stack, ShopItem.UNSET_PRICE, null);
            ShopCategory category = shopManager.getCategory(categoryId);
            messageManager.send(player, "admin.hand-item-added", Map.of(
                    "%category%", categoryId,
                    "%item_id%", added.getId(),
                    "%shop_file%", category != null ? category.getShopFileName() : (categoryId + ".yml"),
                    "%slot%", Integer.toString(added.getSlot()),
                    "%price_status%", "UNSET (Unavailable until priced)"
            ));
            guiManager.openEditorItemProperties(player, categoryId, added.getId());
        } catch (Exception e) {
            messageManager.send(player, "errors.storage-error");
        }
    }
}
