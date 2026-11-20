package ru.prostominecraft.end;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.entity.Projectile;

/** All dragon access runs in the dragon's region. Player access is scheduled on each player's entity scheduler. */
final class EndFight implements Listener {
    private final EndPlugin plugin;
    private final EndState state;
    private final EndItems items;
    private final Map<UUID, Double> contribution = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerPosition> positions = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> transitioning = new ConcurrentHashMap<>();
    private int maxPlayersSeen = 1;
    private int bastionCrystalsDestroyed;
    private final Map<UUID, Boolean> delivering = new ConcurrentHashMap<>();
    private final Set<UUID> countedCrystals = ConcurrentHashMap.newKeySet();

    EndFight(EndPlugin plugin, EndState state, EndItems items) {
        this.plugin = plugin;
        this.state = state;
        this.items = items;
    }

    void updatePosition(Player player) {
        if (player.getWorld().getEnvironment() != World.Environment.THE_END || player.isDead()) {
            positions.remove(player.getUniqueId());
            return;
        }
        Location point = player.getLocation();
        positions.put(player.getUniqueId(), new PlayerPosition(point.getX(), point.getY(), point.getZ(), System.currentTimeMillis()));
    }

    void deliver(Player player) {
        UUID id = player.getUniqueId();
        if (delivering.putIfAbsent(id, true) != null) return;
        try {
            for (String reward : state.pendingRewards(id)) {
                String[] parts = reward.split(":", 2);
                if (parts.length != 2) continue;
                int amount;
                try { amount = Integer.parseInt(parts[1]); }
                catch (NumberFormatException ignored) { continue; }
                if (amount < 1) continue;
                player.getInventory().addItem(items.make(parts[0], amount)).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                try { state.acknowledgeReward(id, reward); }
                catch (IOException error) {
                    plugin.getLogger().severe("Cannot acknowledge End reward for " + id + ": " + error);
                    break;
                }
            }
        } finally { delivering.remove(id); }
    }

    private int nearbyPlayers() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (PlayerPosition p : positions.values()) {
            if (now - p.timestamp < 5_000 && p.x * p.x + p.z * p.z <= 180 * 180) count++;
        }
        maxPlayersSeen = Math.max(maxPlayersSeen, count);
        return count;
    }

    @EventHandler
    public void onDragonAdded(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !state.opened() || state.corrupt()) return;
        if (event.getWorld().getEnvironment() != World.Environment.THE_END) return;
        dragon.getScheduler().run(plugin, task -> {
            if (state.firstFightComplete()) {
                try { state.beginRepeat(dragon.getUniqueId()); }
                catch (IOException error) {
                    dragon.setInvulnerable(true);
                    plugin.getLogger().severe("Cannot persist repeat dragon selection: " + error);
                    return;
                }
            }
            configure(dragon, true);
        }, () -> {});
        final int[] seconds = {0};
        final long[] emptySince = {0};
        dragon.getScheduler().runAtFixedRate(plugin, task -> {
            if (!dragon.isValid() || dragon.isDead()) { task.cancel(); return; }
            int count = nearbyPlayers();
            if (count == 0 && !transitioning.containsKey(dragon.getUniqueId())) {
                if (emptySince[0] == 0) emptySince[0] = System.currentTimeMillis();
                if (System.currentTimeMillis() - emptySince[0] >= 300_000) {
                    dragon.setHealth(Math.max(dragon.getHealth(), dragon.getAttribute(Attribute.MAX_HEALTH).getValue() * 0.5));
                    emptySince[0] = System.currentTimeMillis();
                }
            } else emptySince[0] = 0;
            if (count > 0 && !transitioning.containsKey(dragon.getUniqueId())) {
                double desired = FightPlan.maxHp(kind(), !state.firstFightComplete(), state.repeatEmpowered(), maxPlayersSeen);
                var health = dragon.getAttribute(Attribute.MAX_HEALTH);
                if (health != null && health.getBaseValue() < desired) {
                    double ratio = dragon.getHealth() / health.getValue();
                    health.setBaseValue(desired);
                    dragon.setHealth(Math.max(1, Math.min(desired, desired * ratio)));
                }
            }
            if (count > 0 && !transitioning.containsKey(dragon.getUniqueId())) runAbility(dragon, ++seconds[0]);
        }, () -> {}, 20L, 20L);
    }

    private void runAbility(EnderDragon dragon, int seconds) {
        if (kind() == DragonKind.BASTION && seconds % 20 == 0) {
            Location at = dragon.getLocation().clone();
            plugin.announce(Component.text("Бастион поднимает крылья. Укройтесь или приготовьте щит!", NamedTextColor.RED));
            dragon.getScheduler().runDelayed(plugin, task -> attackNear(dragon, at, 13, 7), () -> {}, 40L);
        } else if (kind() == DragonKind.MIRAGE && seconds % 18 == 0) {
            PlayerPosition target = positions.values().stream().filter(p -> System.currentTimeMillis() - p.timestamp < 5000)
                .findAny().orElse(null);
            if (target != null) {
                plugin.announce(Component.text("Мираж растворяется в пустоте...", NamedTextColor.DARK_PURPLE));
                dragon.getScheduler().runDelayed(plugin, task -> {
                    Location destination = new Location(dragon.getWorld(), target.x + 4, Math.max(target.y + 15, 80), target.z + 4);
                    dragon.teleportAsync(destination);
                }, () -> {}, 30L);
            }
        } else if (kind() == DragonKind.CROWN && seconds % 18 == 0) {
            List<Map.Entry<UUID, PlayerPosition>> near = positions.entrySet().stream()
                .filter(entry -> System.currentTimeMillis() - entry.getValue().timestamp < 5000).toList();
            if (near.isEmpty()) return;
            Map.Entry<UUID, PlayerPosition> target = near.get(ThreadLocalRandom.current().nextInt(near.size()));
            plugin.announce(Component.text("Венец собирает призматический заряд. Разойдитесь!", NamedTextColor.AQUA));
            dragon.getScheduler().runDelayed(plugin, task -> {
                for (var entry : near) {
                    double dx = entry.getValue().x - target.getValue().x;
                    double dz = entry.getValue().z - target.getValue().z;
                    if (entry.getKey().equals(target.getKey()) || dx * dx + dz * dz <= 36)
                        damagePlayer(entry.getKey(), 6, "Призматическая цепь");
                }
            }, () -> {}, 60L);
        }
    }

    private void attackNear(EnderDragon dragon, Location center, double radius, double damage) {
        for (var entry : positions.entrySet()) {
            PlayerPosition p = entry.getValue();
            if (System.currentTimeMillis() - p.timestamp > 5000) continue;
            double dx = p.x - center.getX();
            double dz = p.z - center.getZ();
            if (dx * dx + dz * dz <= radius * radius) damagePlayer(entry.getKey(), damage, "Сейсмический удар");
        }
    }

    private void damagePlayer(UUID id, double damage, String reason) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        player.getScheduler().run(plugin, task -> {
            if (player.getWorld().getEnvironment() != World.Environment.THE_END || player.isDead()) return;
            player.damage(player.isBlocking() ? damage * 0.3 : damage);
            ((Audience) player).sendMessage(Component.text(reason, NamedTextColor.LIGHT_PURPLE));
        }, () -> {});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalPlaced(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal) || !state.firstFightComplete() || state.corrupt()) return;
        if (event.getBlock().getWorld().getEnvironment() != World.Environment.THE_END || event.getBlock().getType() != Material.BEDROCK) return;
        Location point = event.getBlock().getLocation();
        if (point.getX() * point.getX() + point.getZ() * point.getZ() > 64) return;
        org.bukkit.inventory.ItemStack held = event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND
            ? event.getPlayer().getInventory().getItemInOffHand() : event.getPlayer().getInventory().getItemInMainHand();
        try { state.recordPortalCrystal(crystal.getUniqueId(), items.is(held, "empowered_end_crystal")); }
        catch (IOException error) { plugin.getLogger().severe("Cannot persist End crystal placement: " + error); }
    }

    private DragonKind kind() {
        return state.firstFightComplete() && state.repeatKind() != null ? state.repeatKind() : DragonKind.values()[state.wave()];
    }

    private void configure(EnderDragon dragon, boolean restore) {
        if (!dragon.isValid()) return;
        DragonKind kind = kind();
        dragon.customName(Component.text(kind.title, kind.color));
        dragon.setCustomNameVisible(true);
        dragon.setInvulnerable(false);
        dragon.setInvisible(false);
        var attribute = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (attribute != null) {
            double max = FightPlan.maxHp(kind, !state.firstFightComplete(), state.repeatEmpowered(), maxPlayersSeen);
            attribute.setBaseValue(max);
            dragon.setHealth(restore ? Math.min(dragon.getHealth(), max) : max);
        }
        var battle = dragon.getDragonBattle();
        if (battle != null && battle.getBossBar() != null) {
            battle.getBossBar().setColor(switch (kind) {
                case BASTION -> org.bukkit.boss.BarColor.RED;
                case MIRAGE -> org.bukkit.boss.BarColor.PURPLE;
                case CROWN -> org.bukkit.boss.BarColor.WHITE;
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !state.opened() || state.corrupt()) return;
        if (transitioning.containsKey(dragon.getUniqueId())) { event.setCancelled(true); return; }
        if (kind() == DragonKind.BASTION && event instanceof EntityDamageByEntityEvent byEntity
            && byEntity.getDamager() instanceof Projectile) {
            double resist = Math.max(0, 0.40 - 0.05 * bastionCrystalsDestroyed);
            event.setDamage(event.getDamage() * (1 - resist));
        }
        double damage = Math.min(dragon.getHealth(), event.getFinalDamage());
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = attacker(byEntity.getDamager());
            if (attacker != null && damage > 0) contribution.merge(attacker.getUniqueId(), damage, Double::sum);
        }
        if (state.firstFightComplete() || damage < dragon.getHealth() - 1) return;
        if (state.wave() >= 2) return;
        event.setCancelled(true);
        try { state.advanceWave(); }
        catch (IOException error) {
            plugin.getLogger().severe("Cannot persist End wave transition: " + error);
            return;
        }
        transitioning.put(dragon.getUniqueId(), true);
        dragon.setHealth(1);
        dragon.setInvulnerable(true);
        dragon.setInvisible(true);
        dragon.setPhase(EnderDragon.Phase.HOVER);
        plugin.announce(Component.text("Дракон исчез во вспышке. Передышка: 20 секунд.", NamedTextColor.LIGHT_PURPLE));
        dragon.getScheduler().runDelayed(plugin, task -> {
            transitioning.remove(dragon.getUniqueId());
            configure(dragon, false);
            plugin.announce(Component.text("Из пустоты появился " + kind().title + "!", kind().color));
        }, () -> {}, 400L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal) || !state.opened() || state.corrupt()) return;
        if (crystal.getWorld().getEnvironment() != World.Environment.THE_END
            || state.firstFightComplete() && state.repeatKind() == null
            || !countedCrystals.add(crystal.getUniqueId())) return;
        Player player = attacker(event.getDamager());
        if (player != null) contribution.merge(player.getUniqueId(), 35.0, Double::sum);
        if (kind() == DragonKind.BASTION) bastionCrystalsDestroyed++;
    }

    private Player attacker(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !state.opened() || state.corrupt()) return;
        if (!state.firstFightComplete() && state.wave() < 2) return;
        boolean first = !state.firstFightComplete();
        int players = maxPlayersSeen;
        List<Map.Entry<UUID, Double>> ranked = new ArrayList<>(contribution.entrySet());
        ranked.sort(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()));
        double total = ranked.stream().mapToDouble(Map.Entry::getValue).sum();
        boolean empowered = state.repeatEmpowered();
        int pool = FightPlan.scalePool(first, empowered, players);
        Map<UUID, List<String>> rewards = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            UUID id = ranked.get(i).getKey();
            int scales = FightPlan.scalesFor(pool, ranked.get(i).getValue(), total, first);
            List<String> each = new ArrayList<>();
            if (scales > 0) each.add("dragon_scale:" + scales);
            if (first && i < 3) each.add(switch (i) {
                case 0 -> "void_needle:1";
                case 1 -> "dragon_claw:1";
                default -> "sky_loop:1";
            });
            if (!each.isEmpty()) rewards.put(id, each);
        }
        if (!first && total > 0 && ThreadLocalRandom.current().nextDouble() < (empowered ? 0.30 : 0.15)) {
            double pick = ThreadLocalRandom.current().nextDouble(total);
            UUID winner = ranked.get(0).getKey();
            for (var entry : ranked) {
                pick -= entry.getValue();
                if (pick <= 0) { winner = entry.getKey(); break; }
            }
            String[] artifacts = {"void_needle", "dragon_claw", "sky_loop"};
            String artifact = artifacts[ThreadLocalRandom.current().nextInt(artifacts.length)];
            rewards.computeIfAbsent(winner, ignored -> new ArrayList<>()).add(artifact + ":1");
        }
        try { state.queueRewards(rewards, first); }
        catch (IOException error) {
            plugin.getLogger().severe("Cannot persist End fight rewards: " + error);
            event.setCancelled(true);
            dragon.setHealth(1);
            return;
        }
        plugin.announce(Component.text("Дракон повержен. Вклад участников:", NamedTextColor.GOLD));
        for (int i = 0; i < Math.min(5, ranked.size()); i++) {
            int place = i + 1;
            UUID id = ranked.get(i).getKey();
            double score = ranked.get(i).getValue();
            Player player = Bukkit.getPlayer(id);
            String name = player == null ? id.toString().substring(0, 8) : player.getName();
            plugin.announce(Component.text(place + ". " + name + " — " + Math.round(score) + " очков", NamedTextColor.YELLOW));
        }
        for (UUID id : rewards.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.getScheduler().run(plugin, task -> deliver(player), () -> {});
        }
        if (first) {
            plugin.announce(Component.text("Испытание Края завершено! Внешние острова открыты.", NamedTextColor.AQUA));
        }
        contribution.clear();
        maxPlayersSeen = 1;
        bastionCrystalsDestroyed = 0;
        countedCrystals.clear();
    }

    private record PlayerPosition(double x, double y, double z, long timestamp) {}
}
