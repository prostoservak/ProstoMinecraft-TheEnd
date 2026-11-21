package ru.prostominecraft.end;

import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Retrofits minecraft:item_model (prostoservak:*) onto custom items created by
 * ProstoMinecraft-CustomItems and ProstoMinecraft-Nether so both newly crafted
 * and already-stored items render with the server resource pack.
 */
final class LegacyModelSync implements Listener {
    private static final String MODEL_NS = "prostoservak";
    private static final NamespacedKey MODEL_TALISMAN = new NamespacedKey(MODEL_NS, "gladiator_talisman");
    private static final NamespacedKey MODEL_YGGDRASIL = new NamespacedKey(MODEL_NS, "yggdrasil_fruit");
    private static final NamespacedKey MODEL_TINCTURE_WEAK = new NamespacedKey(MODEL_NS, "fire_tincture_weak");
    private static final NamespacedKey MODEL_TINCTURE_NORMAL = new NamespacedKey(MODEL_NS, "fire_tincture_normal");
    private static final NamespacedKey MODEL_TINCTURE_STRONG = new NamespacedKey(MODEL_NS, "fire_tincture_strong");

    private static final NamespacedKey ORIGINAL_FRUIT = new NamespacedKey("prostominecraft-customitems", "yggdrasil_fruit");
    private static final NamespacedKey TINCTURE_MARK = new NamespacedKey("prostominecraft-nether", "nether_tincture");
    private static final NamespacedKey TINCTURE_SECONDS = new NamespacedKey("prostominecraft-nether", "tincture_seconds");

    private final EndPlugin plugin;
    private final EndItems items;

    LegacyModelSync(EndPlugin plugin, EndItems items) {
        this.plugin = plugin;
        this.items = items;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.getScheduler().run(plugin, task -> syncInventory(player.getInventory()), () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        syncInventory(event.getInventory());
        syncInventory(event.getPlayer().getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        ItemStack stack = event.getItem().getItemStack();
        if (syncItem(stack)) {
            event.getItem().setItemStack(stack);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        ItemStack next = event.getPlayer().getInventory().getItem(event.getNewSlot());
        if (syncItem(next)) {
            event.getPlayer().getInventory().setItem(event.getNewSlot(), next);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        if (syncItem(current)) {
            event.setCurrentItem(current);
        }
        syncItem(event.getCursor());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (syncItem(result)) {
            event.getInventory().setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            player.getScheduler().runDelayed(plugin, task -> syncInventory(player.getInventory()), () -> {}, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        for (int i = 0; i < event.getResults().size(); i++) {
            syncItem(event.getResults().get(i));
        }
    }

    private void syncInventory(Inventory inventory) {
        if (inventory == null) return;
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (syncItem(stack)) {
                inventory.setItem(i, stack);
            }
        }
    }

    boolean syncItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return false;
        Material type = stack.getType();
        if (type != Material.TOTEM_OF_UNDYING && type != Material.APPLE
            && type != Material.GOLDEN_APPLE && type != Material.ENCHANTED_GOLDEN_APPLE
            && type != Material.POTION) return false;
        if (items.kind(stack) != null) return false; // EndPlugin items already manage their own item_model

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;

        // Revert previously attached item_model on weak/normal fire tinctures so ProstoMinecraft-Nether's
        // ExactChoice/isSimilar crafting recipes for normal and strong tinctures keep matching!
        if (type == Material.POTION && meta.hasItemModel()) {
            NamespacedKey current = meta.getItemModel();
            if (MODEL_TINCTURE_WEAK.equals(current) || MODEL_TINCTURE_NORMAL.equals(current)) {
                meta.setItemModel(null);
                stack.setItemMeta(meta);
                return true;
            }
            return false;
        }
        if (meta.hasItemModel()) return false;

        NamespacedKey model = resolveModel(type, meta);
        if (model == null) return false;

        meta.setItemModel(model);
        stack.setItemMeta(meta);
        return true;
    }

    private NamespacedKey resolveModel(Material type, ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (type == Material.APPLE || type == Material.GOLDEN_APPLE || type == Material.ENCHANTED_GOLDEN_APPLE) {
            if (pdc.has(ORIGINAL_FRUIT, PersistentDataType.BYTE)
                || hasKeyMatching(pdc, "prostominecraft-customitems", "yggdrasil")
                || nameContains(meta, "иггдрасил")) {
                return MODEL_YGGDRASIL;
            }
            return null;
        }
        if (type == Material.TOTEM_OF_UNDYING) {
            if (hasKeyMatching(pdc, "prostominecraft-customitems", "gladiator")
                || hasKeyMatching(pdc, "prostominecraft-customitems", "talisman")
                || hasNamespace(pdc, "prostominecraft-customitems")
                || nameContains(meta, "гладиатор")) {
                return MODEL_TALISMAN;
            }
            return null;
        }
        if (type == Material.POTION) {
            if (!pdc.has(TINCTURE_MARK, PersistentDataType.BYTE)
                && !hasNamespace(pdc, "prostominecraft-nether")) {
                return null;
            }
            // Only apply item_model to the final Strong tier (weak & normal are used as ingredients in ProstoMinecraft-Nether)
            Integer seconds = pdc.get(TINCTURE_SECONDS, PersistentDataType.INTEGER);
            if (seconds != null) {
                return seconds > 600 ? MODEL_TINCTURE_STRONG : null;
            }
            if (nameContains(meta, "сильн")) return MODEL_TINCTURE_STRONG;
        }
        return null;
    }

    private boolean hasNamespace(PersistentDataContainer pdc, String namespace) {
        for (NamespacedKey key : pdc.getKeys()) {
            if (key.getNamespace().equalsIgnoreCase(namespace)) return true;
        }
        return false;
    }

    private boolean hasKeyMatching(PersistentDataContainer pdc, String namespace, String fragment) {
        for (NamespacedKey key : pdc.getKeys()) {
            if (key.getNamespace().equalsIgnoreCase(namespace)
                && key.getKey().toLowerCase(Locale.ROOT).contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private boolean nameContains(ItemMeta meta, String needleLower) {
        Component display = meta.displayName();
        if (display == null) return false;
        StringBuilder out = new StringBuilder();
        appendPlain(display, out);
        return out.toString().toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private void appendPlain(Component component, StringBuilder out) {
        if (component instanceof TextComponent text) {
            out.append(text.content());
        }
        for (Component child : component.children()) {
            appendPlain(child, out);
        }
    }
}
