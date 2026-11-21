package ru.prostominecraft.end;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
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
import org.bukkit.event.player.PlayerMoveEvent;
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
    private BossBar reminderBar;
    private long reminderUntil;
    private long nextReminder;
    private final java.util.Map<UUID, Long> boundaryWarnings = new java.util.concurrent.ConcurrentHashMap<>();
    private EndFight fight;
    private EndCrafting crafting;
    private EndItems items;
    private EndArtifacts artifacts;
    private EndSeepage seepage;
    private boolean borderBonusPending;
    private long lastBorderAttempt;
    private long lastAnnouncedMinute = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        sealKey = new NamespacedKey(this, "end_seal");
        state = new EndState(getDataFolder().toPath().resolve("state.yml"));
        state.load();
        if (state.corrupt()) getLogger().severe("End state.yml is unreadable; End opening remains locked.");
        for (Seal seal : Seal.values()) seal.registerRecipe(this);
        items = new EndItems(this);
        artifacts = new EndArtifacts(this, items);
        fight = new EndFight(this, state, items);
        artifacts.bindFight(fight);
        crafting = new EndCrafting(this, items);
        seepage = new EndSeepage(this, state);
        crafting.registerRecipes();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(fight, this);
        Bukkit.getPluginManager().registerEvents(crafting, this);
        Bukkit.getPluginManager().registerEvents(artifacts, this);
        Bukkit.getPluginManager().registerEvents(seepage, this);
        Bukkit.getPluginManager().registerEvents(new LegacyModelSync(this, items), this);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> tickOpening(), 20L, 20L);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> fight.attachExisting(), 40L);
        forPlayers(this::trackPlayer);
        getLogger().info("End items loaded; seals=" + sealsEnabled() + ", portal opening=" + openingEnabled());
    }

    @Override
    public void onDisable() {
        if (fight != null) fight.cleanup();
    }

    NamespacedKey sealKey() { return sealKey; }

    private boolean openingEnabled() { return !state.corrupt() && getConfig().getBoolean("end-opening.enabled", false); }
    private boolean sealsEnabled() { return !state.corrupt() && getConfig().getBoolean("seals.enabled", true); }

    private static final List<String> GIVE_ITEMS = List.of(
        "void_needle", "void_needle_upgraded",
        "dragon_claw", "dragon_claw_upgraded",
        "sky_loop", "sky_loop_upgraded",
        "dragon_scale", "dragon_chitin", "dragon_blood",
        "chorus_brew", "empowered_end_crystal", "end_wings", "yggdrasil_fruit_empowered",
        "seal_heat", "seal_depths", "seal_darkness", "seal_breeze", "seal_life", "seal_threshold"
    );

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && (args[0].equalsIgnoreCase("remind") || args[0].equalsIgnoreCase("reminder"))) {
            if (!sender.hasPermission("prostominecraft.end.admin") && !sender.isOp()) {
                ((Audience) sender).sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
                return true;
            }
            String custom = args.length > 1
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim()
                : null;
            Bukkit.getGlobalRegionScheduler().execute(this, () -> sendReminder(custom));
            ((Audience) sender).sendMessage(Component.text("Напоминание о Крае отправлено игрокам.", NamedTextColor.LIGHT_PURPLE));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("prostominecraft.end.admin") && !sender.isOp()) {
                ((Audience) sender).sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
                return true;
            }
            if (!(sender instanceof Player player)) {
                ((Audience) sender).sendMessage(Component.text("Команда /end give доступна только игроку в игре.", NamedTextColor.YELLOW));
                return true;
            }
            if (args.length < 2) {
                ((Audience) player).sendMessage(Component.text("Использование: /end give <предмет> [кол-во]", NamedTextColor.YELLOW));
                return true;
            }
            String id = args[1].toLowerCase(java.util.Locale.ROOT);
            int amount = 1;
            if (args.length >= 3) {
                try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); }
                catch (NumberFormatException ignored) {}
            }
            ItemStack given = buildGiveItem(id, amount);
            if (given == null) {
                ((Audience) player).sendMessage(Component.text("Неизвестный предмет: " + id, NamedTextColor.RED));
                return true;
            }
            player.getScheduler().run(this, task -> {
                var leftover = player.getInventory().addItem(given);
                for (ItemStack drop : leftover.values()) player.getWorld().dropItemNaturally(player.getLocation(), drop);
                ((Audience) player).sendMessage(Component.text("Выдано: " + id + " ×" + given.getAmount(), NamedTextColor.LIGHT_PURPLE));
            }, () -> {});
            return true;
        }
        if (!(sender instanceof Player player)) {
            ((Audience) sender).sendMessage(Component.text("Использование из консоли: /end remind [текст]", NamedTextColor.YELLOW));
            return true;
        }
        if (args.length != 0) {
            String usage = (player.hasPermission("prostominecraft.end.admin") || player.isOp())
                ? "Использование: /end | /end remind [текст] | /end give <предмет> [кол-во]"
                : "Использование: /end";
            ((Audience) player).sendMessage(Component.text(usage, NamedTextColor.RED));
            return true;
        }
        openMenu(player);
        return true;
    }

    private ItemStack buildGiveItem(String id, int amount) {
        if (id.startsWith("seal_")) {
            try {
                Seal seal = Seal.valueOf(id.substring(5).toUpperCase(java.util.Locale.ROOT));
                ItemStack stack = seal.item(this);
                stack.setAmount(amount);
                return stack;
            } catch (IllegalArgumentException ignored) { return null; }
        }
        if (id.equals("end_wings")) return items.wings(new ItemStack(Material.ELYTRA));
        if (id.endsWith("_upgraded")) {
            String base = id.substring(0, id.length() - "_upgraded".length());
            if (base.equals("void_needle") || base.equals("dragon_claw") || base.equals("sky_loop")) {
                return items.upgrade(items.make(base, 1));
            }
            return null;
        }
        if (GIVE_ITEMS.contains(id)) {
            int capped = (id.equals("void_needle") || id.equals("dragon_claw") || id.equals("sky_loop")) ? 1 : amount;
            return items.make(id, capped);
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("prostominecraft.end.admin") && !sender.isOp()) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
            List<String> out = new java.util.ArrayList<>();
            if ("remind".startsWith(prefix)) out.add("remind");
            if ("give".startsWith(prefix)) out.add("give");
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase(java.util.Locale.ROOT);
            List<String> out = new java.util.ArrayList<>();
            for (String item : GIVE_ITEMS) if (item.startsWith(prefix)) out.add(item);
            return out;
        }
        return List.of();
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
                Component.text(sealsEnabled() ? "Нажмите, если Печать в инвентаре" : "Сбор Печатей пока готовится", NamedTextColor.YELLOW)
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
        if (!sealsEnabled()) {
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
            int countdown = openingEnabled() ? Math.max(1, getConfig().getInt("end-opening.countdown-seconds", 300)) : 0;
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
        if (state.count() == Seal.values().length) announce(Component.text(openingEnabled()
            ? "Все Печати собраны! Начался обратный отсчёт до открытия Энда."
            : "Все Печати собраны. Край пока остаётся запечатанным.", NamedTextColor.LIGHT_PURPLE));
    }

    private void restore(Player player, ItemStack item, int slot) {
        ItemStack current = player.getInventory().getItem(slot);
        if (current == null || current.getType() == Material.AIR) player.getInventory().setItem(slot, item);
        else current.setAmount(current.getAmount() + 1);
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Seal seal = recipeSeal(event.getRecipe());
        if (seal == null) return;
        if (!sealsEnabled() || seal == Seal.HEAT && !hasNormalTincture(event.getInventory().getMatrix()))
            event.getInventory().setResult(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        Seal seal = recipeSeal(event.getRecipe());
        if (seal != null && (!sealsEnabled() || seal == Seal.HEAT && !hasNormalTincture(event.getInventory().getMatrix())))
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
        if (!event.isCancelled() && event.getTo() != null && beyondFirstFightBoundary(event.getTo())) {
            event.setCancelled(true);
            ((Audience) event.getPlayer()).sendMessage(Component.text("За пределами острова пока действует барьер Края.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || !beyondFirstFightBoundary(event.getTo())) return;
        event.setTo(event.getFrom());
        long now = System.currentTimeMillis();
        long previous = boundaryWarnings.getOrDefault(event.getPlayer().getUniqueId(), 0L);
        if (now - previous >= 3000) {
            boundaryWarnings.put(event.getPlayer().getUniqueId(), now);
            ((Audience) event.getPlayer()).sendMessage(Component.text("Барьер Края удерживает Вас у центрального острова.", NamedTextColor.LIGHT_PURPLE));
        }
    }

    private boolean beyondFirstFightBoundary(org.bukkit.Location point) {
        if (!state.opened() || state.firstFightComplete() || point.getWorld().getEnvironment() != World.Environment.THE_END) return false;
        double radius = getConfig().getDouble("first-fight.outer-island-barrier-radius", 300);
        return point.getX() * point.getX() + point.getZ() * point.getZ() > radius * radius;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNaturalRegen(EntityRegainHealthEvent event) {
        if (!getConfig().getBoolean("end-debuff.enabled", true) || event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED) return;
        if (event.getEntity() instanceof Player player && player.getWorld().getEnvironment() == World.Environment.THE_END)
            event.setAmount(event.getAmount() * (1 - (1 - getConfig().getDouble("end-debuff.satiated-regen-multiplier", 0.5))
                * (1 - Math.min(1, items.armorReduction(player) + crafting.brewReduction(player)))));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExhaustion(EntityExhaustionEvent event) {
        if (!getConfig().getBoolean("end-debuff.enabled", true)) return;
        if (event.getEntity() instanceof Player player && player.getWorld().getEnvironment() == World.Environment.THE_END)
            event.setExhaustion((float) (event.getExhaustion() * (1 + (getConfig().getDouble("end-debuff.exhaustion-multiplier", 1.5) - 1)
                * (1 - Math.min(1, items.armorReduction(player) + crafting.brewReduction(player))))));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (openingBar != null) event.getPlayer().showBossBar(openingBar);
        if (reminderBar != null) event.getPlayer().showBossBar(reminderBar);
        trackPlayer(event.getPlayer());
        event.getPlayer().getScheduler().runDelayed(this, task -> fight.deliver(event.getPlayer()), () -> {}, 20L);
    }

    private void trackPlayer(Player player) {
        player.getScheduler().run(this, task -> discoverEndRecipes(player), () -> {});
        player.getScheduler().runAtFixedRate(this, task -> fight.updatePosition(player), () -> {}, 1L, 20L);
        player.getScheduler().runAtFixedRate(this, task -> artifacts.tick(player), () -> {}, 5L, 5L);
        player.getScheduler().runAtFixedRate(this, task -> seepage.tick(player), () -> {}, 20L, 20L);
    }

    private void discoverEndRecipes(Player player) {
        java.util.List<NamespacedKey> keys = new java.util.ArrayList<>();
        for (Seal seal : Seal.values()) keys.add(seal.recipeKey(this));
        keys.addAll(crafting.recipeKeys());
        player.discoverRecipes(keys);
    }

    private void tickOpening() {
        tickReminder();
        tickBorderBonus();
        if (!openingEnabled() || state.opened()) return;
        long now = System.currentTimeMillis();
        try {
            state.startOpeningIfComplete(now, Math.max(1, getConfig().getInt("end-opening.countdown-seconds", 300)));
            if (state.openingAt() == 0) return;
            if (state.openIfDue(now)) {
                if (openingBar != null) forPlayers(player -> player.hideBossBar(openingBar));
                openingBar = null;
                announce(Component.text("Печати Края разрушены. Энд открыт!", NamedTextColor.LIGHT_PURPLE));
                fight.attachExisting();
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

    private void tickBorderBonus() {
        if (!state.firstFightComplete() || state.borderExpanded() || borderBonusPending) return;
        long now = System.currentTimeMillis();
        if (now - lastBorderAttempt < 60_000) return;
        lastBorderAttempt = now;
        var borderPlugin = Bukkit.getPluginManager().getPlugin("ProstoMinecraft-Worldborder");
        if (borderPlugin == null || !borderPlugin.isEnabled()) {
            getLogger().warning("Worldborder plugin unavailable; End victory bonus pending.");
            return;
        }
        borderBonusPending = true;
        try {
            Object response = borderPlugin.getClass().getMethod("expandForEndVictory", double.class)
                .invoke(borderPlugin, getConfig().getDouble("first-fight.overworld-border-bonus", 256.0));
            if (!(response instanceof java.util.concurrent.CompletableFuture<?> future)) throw new IllegalStateException("Worldborder API did not return a future");
            future.whenComplete((success, error) -> Bukkit.getGlobalRegionScheduler().execute(this, () -> {
                borderBonusPending = false;
                if (error != null || !Boolean.TRUE.equals(success)) {
                    getLogger().severe("End border bonus failed: " + (error == null ? "Worldborder limit" : error));
                    return;
                }
                try { state.markBorderExpanded(); }
                catch (IOException io) { getLogger().severe("Could not record End border bonus: " + io); }
            }));
        } catch (ReflectiveOperationException | IllegalStateException error) {
            borderBonusPending = false;
            getLogger().severe("Worldborder plugin lacks End bonus API: " + error);
        }
    }

    private void tickReminder() {
        long now = System.currentTimeMillis();
        if (reminderBar != null && now >= reminderUntil) {
            BossBar old = reminderBar;
            reminderBar = null;
            forPlayers(player -> player.hideBossBar(old));
        }
        if (!sealsEnabled() || state.firstFightComplete() || state.openingAt() != 0 && !state.opened()) return;
        int intervalMin = Math.max(1, getConfig().getInt("end-opening.reminder.interval-minutes", 15));
        if (nextReminder == 0) nextReminder = now + intervalMin * 60_000L;
        if (now < nextReminder) return;
        sendReminder(null);
    }

    private void sendReminder(String customMessage) {
        long now = System.currentTimeMillis();
        if (reminderBar != null) {
            BossBar old = reminderBar;
            reminderBar = null;
            forPlayers(player -> player.hideBossBar(old));
        }
        int intervalMin = Math.max(1, getConfig().getInt("end-opening.reminder.interval-minutes", 15));
        nextReminder = now + intervalMin * 60_000L;
        String message = (customMessage != null && !customMessage.isEmpty())
            ? customMessage
            : (state.opened()
                ? "Край открыт. Исследуйте центральный остров."
                : "Край запечатан: " + state.count() + "/6 печатей.");
        reminderBar = BossBar.bossBar(Component.text(message, NamedTextColor.LIGHT_PURPLE), 1, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        reminderUntil = now + Math.max(1, getConfig().getInt("end-opening.reminder.bossbar-duration-seconds", 10)) * 1000L;
        forPlayers(player -> player.showBossBar(reminderBar));
        if (getConfig().getBoolean("end-opening.reminder.send-chat-message", true)) {
            announce(Component.text(message + " · Открыть /end", NamedTextColor.LIGHT_PURPLE)
                .clickEvent(ClickEvent.runCommand("/end")));
        }
    }

    void announce(Component message) { forPlayers(player -> ((Audience) player).sendMessage(message)); }

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
