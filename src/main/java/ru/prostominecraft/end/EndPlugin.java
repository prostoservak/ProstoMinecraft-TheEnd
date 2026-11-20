package ru.prostominecraft.end;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class EndPlugin extends JavaPlugin implements Listener {
    private static final int[] SLOTS = {10, 11, 12, 14, 15, 16};
    private static final NamespacedKey TINCTURE_MARK = new NamespacedKey("prostominecraft-nether", "nether_tincture");
    private static final NamespacedKey TINCTURE_SECONDS = new NamespacedKey("prostominecraft-nether", "tincture_seconds");
    private NamespacedKey sealKey;
    private EndState state;
    private BossBar openingBar;
    private long lastAnnouncedMinute = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        sealKey = new NamespacedKey(this, "end_seal");
        state = new EndState(getDataFolder().toPath().resolve("state.yml"));
        state.load();
        if (state.corrupt()) getLogger().severe("End state.yml is unreadable; End opening remains locked.");
        for (Seal seal : Seal.values()) seal.registerRecipe(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> tickOpening(), 20L, 20L);
        getLogger().info("End foundation loaded; seal submission enabled=" + openingEnabled());
    }

    NamespacedKey sealKey() { return sealKey; }

    private boolean openingEnabled() { return !state.corrupt() && getConfig().getBoolean("end-opening.enabled", false); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            getLogger().info("Откройте меню в игре: /end");
            return true;
        }
        if (args.length != 0) {
            ((Audience) player).sendMessage(Component.text("Использование: /end", NamedTextColor.RED));
            return true;
        }
        openMenu(player);
        return true;
    }

    private void openMenu(Player player) {
        SealMenu holder = new SealMenu();
        Inventory inventory = Bukkit.createInventory(holder, 27, "Печати Края · " + state.count() + "/6");
        holder.inventory = inventory;
        for (int i = 0; i < Seal.values().length; i++) {
            Seal seal = Seal.values()[i];
            ItemStack item = seal.item(this);
            ItemMeta meta = item.getItemMeta();
            String contributor = state.contributor(seal);
            List<Component> lore = contributor == null ? List.of(
                Component.text(seal.ingredients.get(0), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(seal.ingredients.get(1), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(openingEnabled() ? "Нажмите, если Печать в инвентаре" : "Сбор Печатей пока готовится", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false)
            ) : List.of(Component.text("Сдал: " + contributor, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
            inventory.setItem(SLOTS[i], item);
        }
        player.openInventory(inventory);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof SealMenu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= 27) return;
        for (int i = 0; i < SLOTS.length; i++) {
            if (event.getRawSlot() == SLOTS[i]) {
                submit(player, Seal.values()[i]);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof SealMenu) event.setCancelled(true);
    }

    private void submit(Player player, Seal seal) {
        if (!openingEnabled()) {
            ((Audience) player).sendMessage(Component.text("Сбор Печатей пока готовится.", NamedTextColor.YELLOW));
            return;
        }
        if (state.opened() || state.contributor(seal) != null) {
            ((Audience) player).sendMessage(Component.text("Эта Печать уже сдана.", NamedTextColor.YELLOW));
            return;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        int found = -1;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || !item.hasItemMeta()) continue;
            String mark = item.getItemMeta().getPersistentDataContainer().get(sealKey, PersistentDataType.STRING);
            if (seal.name().equals(mark)) { found = i; break; }
        }
        if (found < 0) {
            ((Audience) player).sendMessage(Component.text("У Вас нет Печати " + seal.title + ".", NamedTextColor.RED));
            return;
        }
        ItemStack item = contents[found];
        if (item.getAmount() == 1) player.getInventory().setItem(found, null);
        else item.setAmount(item.getAmount() - 1);
        try {
            int countdown = Math.max(1, getConfig().getInt("end-opening.countdown-seconds", 300));
            if (!state.submit(seal, player.getUniqueId(), player.getName(), System.currentTimeMillis(), countdown)) {
                restore(player, item, found);
                ((Audience) player).sendMessage(Component.text("Эта Печать уже сдана.", NamedTextColor.YELLOW));
                return;
            }
        } catch (IOException error) {
            restore(player, item, found);
            getLogger().severe("Could not save End seal progress: " + error);
            ((Audience) player).sendMessage(Component.text("Не удалось сохранить Печать. Попробуйте позже.", NamedTextColor.RED));
            return;
        }
        announce(Component.text(player.getName() + " сдал Печать " + seal.title + " · " + state.count() + "/6 печатей сломано", seal.color));
        player.closeInventory();
        if (state.count() == Seal.values().length) announce(Component.text("Все Печати собраны! Начался обратный отсчёт до открытия Энда.", NamedTextColor.LIGHT_PURPLE));
    }

    private void restore(Player player, ItemStack item, int slot) {
        ItemStack current = player.getInventory().getItem(slot);
        if (current == null || current.getType() == Material.AIR) player.getInventory().setItem(slot, item);
        else current.setAmount(current.getAmount() + 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Seal seal = recipeSeal(event.getRecipe());
        if (seal == null) return;
        if (!openingEnabled() || seal == Seal.HEAT && !hasNormalTincture(event.getInventory().getMatrix()))
            event.getInventory().setResult(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        Seal seal = recipeSeal(event.getRecipe());
        if (seal != null && (!openingEnabled() || seal == Seal.HEAT && !hasNormalTincture(event.getInventory().getMatrix())))
            event.setCancelled(true);
    }

    private Seal recipeSeal(org.bukkit.inventory.Recipe recipe) {
        if (!(recipe instanceof Keyed keyed) || !keyed.getKey().getNamespace().equals(getName().toLowerCase())) return null;
        String key = keyed.getKey().getKey();
        if (!key.startsWith("seal_")) return null;
        try { return Seal.valueOf(key.substring(5).toUpperCase()); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private boolean hasNormalTincture(ItemStack[] matrix) {
        for (ItemStack item : matrix) {
            if (item == null || item.getType() != Material.POTION || !item.hasItemMeta()) continue;
            var pdc = item.getItemMeta().getPersistentDataContainer();
            Integer seconds = pdc.get(TINCTURE_SECONDS, PersistentDataType.INTEGER);
            if (pdc.has(TINCTURE_MARK, PersistentDataType.BYTE) && seconds != null && seconds == 600) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null && event.getTo().getWorld().getEnvironment() == World.Environment.THE_END
            && (!openingEnabled() || !state.opened())) {
            event.setCancelled(true);
            ((Audience) event.getPlayer()).sendMessage(Component.text("Энд запечатан. Откройте /end.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNaturalRegen(EntityRegainHealthEvent event) {
        if (!getConfig().getBoolean("end-debuff.enabled", true) || event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED) return;
        if (event.getEntity() instanceof Player player && player.getWorld().getEnvironment() == World.Environment.THE_END)
            event.setAmount(event.getAmount() * getConfig().getDouble("end-debuff.satiated-regen-multiplier", 0.5));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExhaustion(EntityExhaustionEvent event) {
        if (!getConfig().getBoolean("end-debuff.enabled", true)) return;
        if (event.getEntity() instanceof Player player && player.getWorld().getEnvironment() == World.Environment.THE_END)
            event.setExhaustion((float) (event.getExhaustion() * getConfig().getDouble("end-debuff.exhaustion-multiplier", 1.5)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (openingBar != null) event.getPlayer().showBossBar(openingBar);
    }

    private void tickOpening() {
        if (!openingEnabled() || state.opened() || state.openingAt() == 0) return;
        long now = System.currentTimeMillis();
        try {
            if (state.openIfDue(now)) {
                if (openingBar != null) forPlayers(player -> player.hideBossBar(openingBar));
                openingBar = null;
                announce(Component.text("Печати Края разрушены. Энд открыт!", NamedTextColor.LIGHT_PURPLE));
                return;
            }
        } catch (IOException error) {
            getLogger().severe("Could not persist End opening: " + error);
            return;
        }
        long seconds = Math.max(1, (state.openingAt() - now + 999) / 1000);
        int total = Math.max(1, getConfig().getInt("end-opening.countdown-seconds", 300));
        if (openingBar == null) {
            openingBar = BossBar.bossBar(Component.text("Энд откроется через " + seconds + " сек", NamedTextColor.LIGHT_PURPLE),
                Math.min(1.0f, seconds / (float) total), BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
            forPlayers(player -> player.showBossBar(openingBar));
        } else {
            openingBar.name(Component.text("Энд откроется через " + seconds + " сек", NamedTextColor.LIGHT_PURPLE));
            openingBar.progress(Math.min(1.0f, seconds / (float) total));
        }
        if (seconds == 180 || seconds == 60 || seconds == 10) {
            long marker = seconds;
            if (lastAnnouncedMinute != marker) {
                lastAnnouncedMinute = marker;
                announce(Component.text("Энд откроется через " + seconds + " секунд.", NamedTextColor.LIGHT_PURPLE));
            }
        }
    }

    private void announce(Component message) { forPlayers(player -> ((Audience) player).sendMessage(message)); }

    private void forPlayers(java.util.function.Consumer<Player> action) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(this, task -> action.accept(player), () -> {});
        }
    }

    private static final class SealMenu implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}
