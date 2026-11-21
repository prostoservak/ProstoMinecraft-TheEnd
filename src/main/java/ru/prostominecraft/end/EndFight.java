package ru.prostominecraft.end;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Full 3-dragon End encounter for Folia 26.3:
 *  - First fight: Bastion -> Mirage -> Crown sequentially (20s intermission, 5m wipe grace)
 *  - Repeat fights: random dragon (no consecutive repeat), normal or empowered crystals
 * All dragon state runs on the dragon's EntityScheduler; player effects run on each player's
 * EntityScheduler; block/crystal/minion mutations run on RegionScheduler.
 */
final class EndFight implements Listener {
    private static final int PILLAR_COUNT = 10;
    private static final double PILLAR_RADIUS = 43.0;

    private final EndPlugin plugin;
    private final EndState state;
    private final EndItems items;

    private final NamespacedKey mirageCopyKey;
    private final NamespacedKey crownCoreKey;
    private final NamespacedKey managedCrystalKey;

    private final Map<UUID, Double> contribution = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerPosition> positions = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> transitioning = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> delivering = new ConcurrentHashMap<>();
    private final Set<UUID> countedCrystals = ConcurrentHashMap.newKeySet();
    private final Set<UUID> trackedDragons = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> blockExplosionUntil = new ConcurrentHashMap<>();

    // Per-dragon encounter runtime state
    private volatile int maxPlayersSeen = 1;
    private final AtomicInteger bastionCrystalsDestroyed = new AtomicInteger(0);

    // Mirage state
    private final Set<UUID> mirageCopies = ConcurrentHashMap.newKeySet();
    private final Set<Integer> mirageActivePillars = ConcurrentHashMap.newKeySet();
    private final Map<Integer, UUID> mirageCrystalByPillar = new ConcurrentHashMap<>();
    private volatile boolean mirageSplit65Done;
    private volatile boolean mirageSplit30Done;
    private volatile long mirageGroundedUntil;

    // Crown state
    private final Map<BlockKey, Integer> resonatorBlocks = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> crownCrystalByPillar = new ConcurrentHashMap<>();
    private final Map<Integer, Location> resonatorLocations = new ConcurrentHashMap<>();
    private final Set<UUID> crownCores = ConcurrentHashMap.newKeySet();
    private volatile boolean crownOverloadDone;
    private volatile boolean crownShielded;
    private volatile long crownStunnedUntil;
    private volatile UUID activeDragonId;

    // Temporary obsidian cage blocks placed for Bastion (cleaned between waves)
    private final Set<BlockKey> temporaryCageBlocks = ConcurrentHashMap.newKeySet();

    EndFight(EndPlugin plugin, EndState state, EndItems items) {
        this.plugin = plugin;
        this.state = state;
        this.items = items;
        this.mirageCopyKey = new NamespacedKey(plugin, "mirage_copy");
        this.crownCoreKey = new NamespacedKey(plugin, "crown_core");
        this.managedCrystalKey = new NamespacedKey(plugin, "managed_crystal");
        contribution.putAll(state.fightContribution());
        maxPlayersSeen = state.fightMaxPlayers();
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

    private List<Map.Entry<UUID, PlayerPosition>> activePlayers() {
        long now = System.currentTimeMillis();
        List<Map.Entry<UUID, PlayerPosition>> list = new ArrayList<>();
        for (var entry : positions.entrySet()) {
            PlayerPosition p = entry.getValue();
            if (now - p.timestamp < 5_000 && p.x * p.x + p.z * p.z <= 180 * 180) {
                list.add(entry);
            }
        }
        maxPlayersSeen = Math.max(maxPlayersSeen, list.size());
        return list;
    }

    @EventHandler
    public void onDragonAdded(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !state.opened() || state.corrupt()) return;
        if (dragon.getPersistentDataContainer().has(mirageCopyKey, PersistentDataType.BYTE)) return;
        if (event.getWorld().getEnvironment() != World.Environment.THE_END) return;
        attach(dragon);
    }

    void attachExisting() {
        if (!state.opened() || state.corrupt()) return;
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) continue;
            var battle = world.getEnderDragonBattle();
            EnderDragon dragon = battle == null ? null : battle.getEnderDragon();
            if (dragon != null) attach(dragon);
        }
    }

    void cleanup() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) continue;
            cleanupArenaArtifacts(world);
        }
    }

    private void attach(EnderDragon dragon) {
        if (!trackedDragons.add(dragon.getUniqueId())) return;
        activeDragonId = dragon.getUniqueId();
        dragon.getScheduler().run(plugin, task -> {
            boolean freshEncounter = false;
            if (state.firstFightComplete()) {
                try {
                    freshEncounter = !dragon.getUniqueId().equals(state.repeatDragonId());
                    state.beginRepeat(dragon.getUniqueId());
                    if (freshEncounter) {
                        contribution.clear();
                        maxPlayersSeen = 1;
                    }
                } catch (IOException error) {
                    dragon.setInvulnerable(true);
                    plugin.getLogger().severe("Cannot persist repeat dragon selection: " + error);
                    return;
                }
            }
            resetBossMechanicFlags();
            configure(dragon, state.firstFightComplete() && !freshEncounter);
            if (freshEncounter || !state.firstFightComplete()) {
                setupWaveArena(dragon.getWorld(), kind());
            }
        }, () -> trackedDragons.remove(dragon.getUniqueId()));

        final int[] seconds = {0};
        final long[] emptySince = {0};
        dragon.getScheduler().runAtFixedRate(plugin, task -> {
            if (!dragon.isValid() || dragon.isDead()) {
                trackedDragons.remove(dragon.getUniqueId());
                task.cancel();
                return;
            }
            List<Map.Entry<UUID, PlayerPosition>> near = activePlayers();
            int count = near.size();

            // 5-minute wipe grace: if arena is empty for 5 min, heal to 50% of current wave
            if (count == 0 && !transitioning.containsKey(dragon.getUniqueId())) {
                if (emptySince[0] == 0) emptySince[0] = System.currentTimeMillis();
                if (System.currentTimeMillis() - emptySince[0] >= 300_000L) {
                    var attr = dragon.getAttribute(Attribute.MAX_HEALTH);
                    if (attr != null) {
                        dragon.setHealth(Math.max(dragon.getHealth(), attr.getValue() * 0.5));
                    }
                    emptySince[0] = System.currentTimeMillis();
                }
            } else {
                emptySince[0] = 0;
            }

            // Dynamic HP scaling with nearby players
            if (count > 0 && !transitioning.containsKey(dragon.getUniqueId())) {
                double desired = FightPlan.maxHp(kind(), !state.firstFightComplete(), state.repeatEmpowered(), maxPlayersSeen);
                var health = dragon.getAttribute(Attribute.MAX_HEALTH);
                if (health != null && health.getBaseValue() < desired) {
                    double ratio = dragon.getHealth() / health.getValue();
                    health.setBaseValue(desired);
                    dragon.setHealth(Math.max(1.0, Math.min(desired, desired * ratio)));
                }
            }

            seconds[0]++;
            if (!transitioning.containsKey(dragon.getUniqueId())) {
                tickAmbientEffects(dragon, seconds[0]);
                if (count > 0) runAbilities(dragon, near, seconds[0]);
            }
            if (seconds[0] % 5 == 0) persistProgress();
        }, () -> {}, 20L, 20L);
    }

    private void resetBossMechanicFlags() {
        bastionCrystalsDestroyed.set(0);
        mirageSplit65Done = false;
        mirageSplit30Done = false;
        mirageGroundedUntil = 0;
        crownOverloadDone = false;
        crownShielded = false;
        crownStunnedUntil = 0;
    }

    private void persistProgress() {
        try { state.saveFightProgress(contribution, maxPlayersSeen); }
        catch (IOException error) { plugin.getLogger().severe("Cannot persist End fight progress: " + error); }
    }

    // =========================================================================
    // AMBIENT & PER-SECOND BOSS TICK
    // =========================================================================

    private void tickAmbientEffects(EnderDragon dragon, int seconds) {
        long now = System.currentTimeMillis();
        World world = dragon.getWorld();
        DragonKind current = kind();

        // Mirage real-dragon glowing purple trail when copies exist (visual distinguisher)
        if (current == DragonKind.MIRAGE && !mirageCopies.isEmpty()) {
            world.spawnParticle(Particle.DRAGON_BREATH, dragon.getLocation(), 24, 2.5, 1.5, 2.5, 0.04, 1.0f);
            world.spawnParticle(Particle.REVERSE_PORTAL, dragon.getLocation(), 20, 2.0, 1.5, 2.0, 0.08);
        }
        if (current == DragonKind.MIRAGE && mirageGroundedUntil > 0 && now < mirageGroundedUntil) {
            if (dragon.getPhase() != EnderDragon.Phase.SEARCH_FOR_BREATH_ATTACK_TARGET
                && dragon.getPhase() != EnderDragon.Phase.LAND_ON_PORTAL) {
                dragon.setPhase(EnderDragon.Phase.LAND_ON_PORTAL);
            }
        }

        // Crown resonator vertical particle beams (guide ground players to resonators!)
        if (current == DragonKind.CROWN && !resonatorLocations.isEmpty()) {
            for (var entry : resonatorLocations.entrySet()) {
                Location base = entry.getValue();
                Bukkit.getRegionScheduler().run(plugin, base, task -> {
                    for (int dy = 1; dy <= 28; dy += 3) {
                        world.spawnParticle(Particle.END_ROD, base.clone().add(0.5, dy, 0.5), 1, 0.08, 0.2, 0.08, 0);
                    }
                    world.spawnParticle(Particle.WITCH, base.clone().add(0.5, 1.2, 0.5), 6, 0.4, 0.4, 0.4, 0.02);
                });
            }
        }

        // Crown overload shield particles & stun visuals
        if (current == DragonKind.CROWN && crownShielded) {
            world.spawnParticle(Particle.END_ROD, dragon.getLocation(), 35, 3.2, 2.2, 3.2, 0.05);
            dragon.setPhase(EnderDragon.Phase.HOVER);
        } else if (current == DragonKind.CROWN && now < crownStunnedUntil) {
            world.spawnParticle(Particle.ELECTRIC_SPARK, dragon.getLocation(), 25, 2.5, 1.5, 2.5, 0.1);
            if (dragon.getPhase() != EnderDragon.Phase.LAND_ON_PORTAL) {
                dragon.setPhase(EnderDragon.Phase.LAND_ON_PORTAL);
            }
        }
    }

    // =========================================================================
    // 3 DRAGON ABILITY SUITES
    // =========================================================================

    private void runAbilities(EnderDragon dragon, List<Map.Entry<UUID, PlayerPosition>> near, int seconds) {
        double maxHp = dragon.getAttribute(Attribute.MAX_HEALTH) != null
            ? dragon.getAttribute(Attribute.MAX_HEALTH).getValue() : 200.0;
        double hpRatio = dragon.getHealth() / Math.max(1.0, maxHp);

        switch (kind()) {
            case BASTION -> runBastionAbilities(dragon, near, seconds, hpRatio);
            case MIRAGE -> runMirageAbilities(dragon, near, seconds, hpRatio);
            case CROWN -> runCrownAbilities(dragon, near, seconds, hpRatio);
        }
    }

    // ---------- 1. БАСТИОН КРАЯ ----------
    private void runBastionAbilities(EnderDragon dragon, List<Map.Entry<UUID, PlayerPosition>> near, int seconds, double hpRatio) {
        // 1) Сейсмический удар (каждые 20 сек) — перепрыгивается или блокируется щитом
        if (seconds % 20 == 0) {
            Location impact = dragon.getLocation().clone();
            World world = dragon.getWorld();
            broadcastToArena(near, Component.text("Бастион готовит сейсмический удар — прыгайте или поднимите щит!", NamedTextColor.RED));
            world.playSound(impact, Sound.ENTITY_ENDER_DRAGON_GROWL, 2.5f, 0.6f);
            world.spawnParticle(Particle.CLOUD, impact, 90, 5.0, 2.0, 5.0, 0.08);
            world.spawnParticle(Particle.EXPLOSION, impact, 8, 3.0, 1.0, 3.0, 0);
            for (var entry : near) sendActionBar(entry.getKey(), Component.text("УДАР ЧЕРЕЗ 2 СЕКУНДЫ", NamedTextColor.GOLD));
            dragon.getScheduler().runDelayed(plugin, task -> {
                if (!dragon.isValid() || dragon.isDead()) return;
                Location groundCenter = dragon.getLocation().clone();
                Bukkit.getRegionScheduler().run(plugin, groundCenter, rtask -> {
                    int y = world.getHighestBlockYAt(groundCenter.getBlockX(), groundCenter.getBlockZ());
                    groundCenter.setY(Math.max(58, y + 1));
                    world.playSound(groundCenter, Sound.ENTITY_GENERIC_EXPLODE, 3.5f, 0.55f);
                    world.playSound(groundCenter, Sound.ENTITY_WARDEN_SONIC_BOOM, 3.0f, 0.55f);
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, groundCenter, 3, 1.5, 0.3, 1.5, 0);
                    world.spawnParticle(Particle.SONIC_BOOM, groundCenter.clone().add(0, 1.0, 0), 5, 2.0, 0.3, 2.0, 0);
                    // Expanding ground particle ring
                    for (int step = 1; step <= 4; step++) {
                        final double rad = step * 4.0;
                        Bukkit.getRegionScheduler().runDelayed(plugin, groundCenter, ringTask -> {
                            for (int a = 0; a < 36; a++) {
                                double ang = (2 * Math.PI * a) / 36.0;
                                Location p = groundCenter.clone().add(Math.cos(ang) * rad, 0.3, Math.sin(ang) * rad);
                                Bukkit.getRegionScheduler().run(plugin, p, visual -> {
                                    world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, p, 5, 0.2, 0.3, 0.2, 0.02);
                                    world.spawnParticle(Particle.CRIT, p, 4, 0.2, 0.2, 0.2, 0.05);
                                    world.spawnParticle(Particle.EXPLOSION, p, 1, 0, 0, 0, 0);
                                });
                            }
                        }, step * 2L);
                    }
                });
                for (var entry : activePlayers()) {
                    PlayerPosition p = entry.getValue();
                    double dx = p.x - groundCenter.getX();
                    double dz = p.z - groundCenter.getZ();
                    if (dx * dx + dz * dz <= 16 * 16) {
                        hitSeismicShockwave(entry.getKey(), groundCenter);
                    }
                }
            }, () -> {}, 40L);
        }

        // 2) Бомбардировка обломками (каждые 14 сек, со смещением)
        if (seconds % 14 == 7) {
            List<Map.Entry<UUID, PlayerPosition>> shuffled = new ArrayList<>(near);
            java.util.Collections.shuffle(shuffled, ThreadLocalRandom.current());
            int count = Math.min(3, shuffled.size());
            World world = dragon.getWorld();
            for (int i = 0; i < count; i++) {
                PlayerPosition tp = shuffled.get(i).getValue();
                Location strike = new Location(world, tp.x, tp.y, tp.z);
                // Draw 1.8s target circle on the ground before debris impact
                for (int pulse = 0; pulse < 3; pulse++) {
                    Bukkit.getRegionScheduler().runDelayed(plugin, strike, rtask -> {
                        int y = world.getHighestBlockYAt(strike.getBlockX(), strike.getBlockZ());
                        Location ring = new Location(world, strike.getX(), Math.max(55, y + 0.3), strike.getZ());
                        for (int a = 0; a < 18; a++) {
                            double ang = (2 * Math.PI * a) / 18.0;
                            world.spawnParticle(Particle.FLAME, ring.clone().add(Math.cos(ang) * 3.0, 0.1, Math.sin(ang) * 3.0), 1, 0, 0, 0, 0);
                        }
                    }, 1L + pulse * 10L);
                }
                Bukkit.getRegionScheduler().runDelayed(plugin, strike, rtask -> {
                    int y = world.getHighestBlockYAt(strike.getBlockX(), strike.getBlockZ());
                    Location hit = new Location(world, strike.getX(), Math.max(55, y + 1.0), strike.getZ());
                    world.playSound(hit, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.5f, 0.7f);
                    world.spawnParticle(Particle.EXPLOSION, hit, 3, 0.8, 0.5, 0.8, 0);
                    world.spawnParticle(Particle.LARGE_SMOKE, hit, 18, 1.5, 0.6, 1.5, 0.05);
                    for (var e : activePlayers()) {
                        double dx = e.getValue().x - hit.getX();
                        double dz = e.getValue().z - hit.getZ();
                        if (dx * dx + dz * dz <= 3.5 * 3.5) {
                            damagePlayer(e.getKey(), 7.0, 0.4, "Бомбардировка обломками", NamedTextColor.RED);
                        }
                    }
                }, 36L);
            }
        }

        // 3) Шквал крыльев (< 40% HP, каждые 12 сек) — сбрасывает засидевшихся на башнях
        if (hpRatio < 0.40 && seconds % 12 == 4) {
            boolean knockedAny = false;
            for (var entry : near) {
                PlayerPosition p = entry.getValue();
                if (p.y >= 78.0) {
                    knockedAny = true;
                    UUID id = entry.getKey();
                    Player player = Bukkit.getPlayer(id);
                    if (player != null) {
                        player.getScheduler().run(plugin, task -> {
                            if (player.getWorld().getEnvironment() != World.Environment.THE_END || player.isDead()) return;
                            Vector away = player.getLocation().toVector().setY(0);
                            if (away.lengthSquared() < 0.1) away = new Vector(1, 0, 0);
                            away.normalize().multiply(1.35).setY(0.55);
                            player.setVelocity(away);
                            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.4f, 0.75f);
                            player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 20, 0.7, 0.5, 0.7, 0.12);
                            ((Audience) player).sendActionBar(Component.text("Шквал крыльев Бастиона сбрасывает Вас с башни!", NamedTextColor.RED));
                        }, () -> {});
                    }
                }
            }
            if (knockedAny) {
                dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 2.5f, 0.6f);
            }
        }
    }

    private void hitSeismicShockwave(UUID id, Location center) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        player.getScheduler().run(plugin, task -> {
            if (player.getWorld().getEnvironment() != World.Environment.THE_END || player.isDead()) return;
            // Jumpable shockwave: airborne players > 0.55 blocks above ground dodge cleanly!
            Location loc = player.getLocation();
            boolean airborne = !player.isOnGround()
                && loc.clone().subtract(0, 0.6, 0).getBlock().isPassable();
            if (airborne) {
                ((Audience) player).sendActionBar(Component.text("Вы перепрыгнули сейсмическую волну!", NamedTextColor.GREEN));
                return;
            }
            double dmg = player.isBlocking() ? 8.0 * 0.3 : 8.0;
            player.damage(dmg);
            Vector push = loc.toVector().subtract(center.toVector()).setY(0);
            if (push.lengthSquared() < 0.01) push = new Vector(1, 0, 0);
            player.setVelocity(push.normalize().multiply(0.8).setY(0.42));
            ((Audience) player).sendActionBar(Component.text("Сейсмический удар Бастиона!", NamedTextColor.RED));
        }, () -> {});
    }

    // ---------- 2. МИРАЖ КРАЯ ----------
    private void runMirageAbilities(EnderDragon dragon, List<Map.Entry<UUID, PlayerPosition>> near, int seconds, double hpRatio) {
        // 1) Двойники-миражи на 65% и 30% HP
        if (!mirageSplit65Done && hpRatio <= 0.65) {
            mirageSplit65Done = true;
            spawnMirageCopies(dragon, near);
        } else if (!mirageSplit30Done && hpRatio <= 0.30) {
            mirageSplit30Done = true;
            spawnMirageCopies(dragon, near);
        }

        // 2) Шаг Эндера (каждые 18 сек) — блинк за спину игрока для пикирования
        if (seconds % 18 == 0) {
            PlayerPosition target = near.get(ThreadLocalRandom.current().nextInt(near.size())).getValue();
            World world = dragon.getWorld();
            world.spawnParticle(Particle.PORTAL, dragon.getLocation(), 80, 3.0, 2.0, 3.0, 0.25);
            world.playSound(dragon.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 2.5f, 0.7f);
            broadcastToArena(near, Component.text("Мираж Края растворяется в пустоте...", NamedTextColor.DARK_PURPLE));
            dragon.getScheduler().runDelayed(plugin, task -> {
                if (!dragon.isValid() || dragon.isDead() || transitioning.containsKey(dragon.getUniqueId())) return;
                double ang = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                Location dest = new Location(world,
                    target.x + Math.cos(ang) * 14,
                    Math.max(target.y + 15, 82),
                    target.z + Math.sin(ang) * 14);
                dragon.teleportAsync(dest).whenComplete((ok, err) -> dragon.getScheduler().run(plugin, t -> {
                    if (!dragon.isValid()) return;
                    world.spawnParticle(Particle.REVERSE_PORTAL, dragon.getLocation(), 70, 2.5, 2.0, 2.5, 0.2);
                    world.playSound(dragon.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 2.5f, 0.9f);
                    dragon.setPhase(EnderDragon.Phase.CHARGE_PLAYER);
                }, () -> {}));
            }, () -> {}, 30L);
        }

        // 3) Рокировка (если игроков >= 2, каждые 28 сек)
        if (near.size() >= 2 && seconds % 28 == 10) {
            List<Map.Entry<UUID, PlayerPosition>> copy = new ArrayList<>(near);
            java.util.Collections.shuffle(copy, ThreadLocalRandom.current());
            UUID aId = copy.get(0).getKey();
            UUID bId = copy.get(1).getKey();
            Player pA = Bukkit.getPlayer(aId);
            Player pB = Bukkit.getPlayer(bId);
            if (pA != null && pB != null) {
                sendActionBar(aId, Component.text("Мираж связывает Вас с " + pB.getName() + " — рокировка через 3 сек!", NamedTextColor.LIGHT_PURPLE));
                sendActionBar(bId, Component.text("Мираж связывает Вас с " + pA.getName() + " — рокировка через 3 сек!", NamedTextColor.LIGHT_PURPLE));
                // Visual particle tether between positions
                PlayerPosition posA = copy.get(0).getValue();
                PlayerPosition posB = copy.get(1).getValue();
                World world = dragon.getWorld();
                for (int step = 0; step < 3; step++) {
                    dragon.getScheduler().runDelayed(plugin, t -> drawParticleLine(world,
                        new Location(world, posA.x, posA.y + 1.2, posA.z),
                        new Location(world, posB.x, posB.y + 1.2, posB.z),
                        Particle.WITCH, 18), () -> {}, 1L + step * 18L);
                }
                dragon.getScheduler().runDelayed(plugin, t -> {
                    Player curA = Bukkit.getPlayer(aId);
                    Player curB = Bukkit.getPlayer(bId);
                    PlayerPosition latestA = positions.get(aId);
                    PlayerPosition latestB = positions.get(bId);
                    if (curA == null || curB == null || latestA == null || latestB == null) return;
                    Location locA = new Location(world, latestA.x, latestA.y, latestA.z);
                    Location locB = new Location(world, latestB.x, latestB.y, latestB.z);
                    curA.teleportAsync(locB).whenComplete((ok, e) -> curA.getScheduler().run(plugin, tt -> {
                        curA.playSound(curA.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.1f);
                        curA.spawnParticle(Particle.PORTAL, curA.getLocation(), 35, 0.5, 1.0, 0.5, 0.1);
                    }, () -> {}));
                    curB.teleportAsync(locA).whenComplete((ok, e) -> curB.getScheduler().run(plugin, tt -> {
                        curB.playSound(curB.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.1f);
                        curB.spawnParticle(Particle.PORTAL, curB.getLocation(), 35, 0.5, 1.0, 0.5, 0.1);
                    }, () -> {}));
                }, () -> {}, 60L);
            }
        }

        // 4) Хорус-стяжка (каждые 22 сек)
        if (seconds % 22 == 14) {
            PlayerPosition tp = near.get(ThreadLocalRandom.current().nextInt(near.size())).getValue();
            World world = dragon.getWorld();
            Location vortex = new Location(world, tp.x, tp.y + 0.5, tp.z);
            broadcastToArena(near, Component.text("Мираж открыл Хорус-стяжку! Выбегайте из вихря!", NamedTextColor.DARK_PURPLE));
            for (int tick = 0; tick < 10; tick++) {
                dragon.getScheduler().runDelayed(plugin, t -> {
                    Bukkit.getRegionScheduler().run(plugin, vortex, rt -> {
                        world.spawnParticle(Particle.REVERSE_PORTAL, vortex, 28, 1.6, 0.6, 1.6, 0.15);
                    });
                    for (var e : activePlayers()) {
                        double dx = vortex.getX() - e.getValue().x;
                        double dz = vortex.getZ() - e.getValue().z;
                        double distSq = dx * dx + dz * dz;
                        if (distSq <= 100.0 && distSq > 1.0) {
                            Player pl = Bukkit.getPlayer(e.getKey());
                            if (pl != null) {
                                Vector pull = new Vector(dx, 0.08, dz).normalize().multiply(0.32);
                                pl.getScheduler().run(plugin, pt -> {
                                    if (!pl.isDead() && pl.getWorld().getEnvironment() == World.Environment.THE_END) {
                                        pl.setVelocity(pl.getVelocity().add(pull));
                                    }
                                }, () -> {});
                            }
                        }
                    }
                }, () -> {}, 1L + tick * 5L);
            }
            // Detonate after 2.5s
            dragon.getScheduler().runDelayed(plugin, t -> {
                Bukkit.getRegionScheduler().run(plugin, vortex, rt -> {
                    world.playSound(vortex, Sound.ENTITY_GENERIC_EXPLODE, 1.8f, 1.3f);
                    world.spawnParticle(Particle.DRAGON_BREATH, vortex, 55, 2.2, 1.0, 2.2, 0.12, 1.0f);
                });
                for (var e : activePlayers()) {
                    double dx = e.getValue().x - vortex.getX();
                    double dz = e.getValue().z - vortex.getZ();
                    if (dx * dx + dz * dz <= 4.8 * 4.8) {
                        damagePlayer(e.getKey(), 7.0, 0.3, "Взрыв Хорус-стяжки", NamedTextColor.DARK_PURPLE);
                    }
                }
            }, () -> {}, 50L);
        }
        // Echo rupture: three visible false targets, then one real detonation.
        if (seconds % 26 == 8) {
            PlayerPosition target = near.get(ThreadLocalRandom.current().nextInt(near.size())).getValue();
            World world = dragon.getWorld();
            Location center = new Location(world, target.x, target.y + 0.4, target.z);
            broadcastToArena(near, Component.text("Мираж раскалывает пространство! Покиньте фиолетовый круг!", NamedTextColor.LIGHT_PURPLE));
            for (var entry : near) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null) player.getScheduler().run(plugin, t -> {
                    if (player.getWorld().getEnvironment() != World.Environment.THE_END) return;
                    PotionEffect old = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
                    if (old == null || old.getDuration() < 240)
                        player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 240, 0, true, false, false));
                    player.playSound(player.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, 0.55f, 1.4f);
                }, () -> {});
            }
            for (int pulse = 0; pulse < 4; pulse++) {
                final int charge = pulse;
                dragon.getScheduler().runDelayed(plugin, t -> Bukkit.getRegionScheduler().run(plugin, center, rt -> {
                    world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 2.0f, 0.7f + charge * 0.15f);
                    for (int i = 0; i < 40; i++) {
                        double angle = i * Math.PI / 20.0;
                        Location edge = center.clone().add(Math.cos(angle) * 5.0, 0.2, Math.sin(angle) * 5.0);
                        Bukkit.getRegionScheduler().run(plugin, edge, visual ->
                            world.spawnParticle(Particle.REVERSE_PORTAL, edge, 3, 0.1, 0.3, 0.1, 0.03));
                    }
                    world.spawnParticle(Particle.DRAGON_BREATH, center, 25, 2.0, 0.4, 2.0, 0.04, 1.0f);
                }), () -> {}, 1L + pulse * 10L);
            }
            dragon.getScheduler().runDelayed(plugin, t -> {
                Bukkit.getRegionScheduler().run(plugin, center, rt -> {
                    world.playSound(center, Sound.ENTITY_ENDER_DRAGON_SHOOT, 3.0f, 0.65f);
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 2, 1.0, 0.2, 1.0, 0);
                    world.spawnParticle(Particle.DRAGON_BREATH, center, 120, 3.0, 1.0, 3.0, 0.12, 1.0f);
                });
                for (var e : activePlayers()) {
                    double dx = e.getValue().x - center.getX(), dz = e.getValue().z - center.getZ();
                    if (dx * dx + dz * dz <= 25.0) damagePlayer(e.getKey(), 6.0, 0.4, "Разлом Миража", NamedTextColor.LIGHT_PURPLE);
                }
            }, () -> {}, 50L);
        }
    }

    private void spawnMirageCopies(EnderDragon dragon, List<Map.Entry<UUID, PlayerPosition>> near) {
        clearMirageCopies(dragon.getWorld());
        broadcastToArena(near, Component.text("Мираж Края создал двойников! Настоящий оставляет яркий фиолетовый шлейф!", NamedTextColor.LIGHT_PURPLE));
        World world = dragon.getWorld();
        Location base = dragon.getLocation();
        for (int i = 0; i < 2; i++) {
            double ang = (i == 0 ? 1 : -1) * Math.PI * 0.6;
            Location spawnLoc = base.clone().add(Math.cos(ang) * 18, 4, Math.sin(ang) * 18);
            Bukkit.getRegionScheduler().run(plugin, spawnLoc, rtask -> {
                EnderDragon copy = world.spawn(spawnLoc, EnderDragon.class, spawned ->
                    spawned.getPersistentDataContainer().set(mirageCopyKey, PersistentDataType.BYTE, (byte) 1));
                copy.customName(Component.text(DragonKind.MIRAGE.title, DragonKind.MIRAGE.color));
                copy.setCustomNameVisible(true);
                var hp = copy.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) {
                    hp.setBaseValue(40.0);
                    copy.setHealth(40.0);
                }
                mirageCopies.add(copy.getUniqueId());
                copy.setPhase(EnderDragon.Phase.CIRCLING);
                // Dull smoke trail distinguishes copies from the real dragon's bright purple aura
                copy.getScheduler().runAtFixedRate(plugin, ct -> {
                    if (!copy.isValid() || copy.isDead() || !mirageCopies.contains(copy.getUniqueId())) {
                        mirageCopies.remove(copy.getUniqueId());
                        ct.cancel();
                        return;
                    }
                    copy.getWorld().spawnParticle(Particle.LARGE_SMOKE, copy.getLocation(), 12, 1.4, 0.7, 1.4, 0.02);
                }, () -> mirageCopies.remove(copy.getUniqueId()), 10L, 10L);
            });
        }
    }

    private void clearMirageCopies(World world) {
        for (UUID id : new ArrayList<>(mirageCopies)) {
            mirageCopies.remove(id);
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.getScheduler().run(plugin, t -> {
                    if (e.isValid()) {
                        e.getWorld().spawnParticle(Particle.LARGE_SMOKE, e.getLocation(), 25, 1.2, 0.8, 1.2, 0.05);
                        e.remove();
                    }
                }, () -> {});
            }
        }
    }

    // ---------- 3. ВЕНЕЦ КРАЯ ----------
    private void runCrownAbilities(EnderDragon dragon, List<Map.Entry<UUID, PlayerPosition>> near, int seconds, double hpRatio) {
        // 1) Перегрузка ядра на 50% HP (щит 12 сек + 3 Кристальных Средоточия вокруг портала)
        if (!crownOverloadDone && hpRatio <= 0.50) {
            crownOverloadDone = true;
            startCrownOverload(dragon, near);
            return;
        }
        if (crownShielded) return;

        // 2) Призматическая цепь (каждые 18 сек) — +25% урона за каждый прыжок в радиусе 6 блоков
        if (seconds % 18 == 0) {
            List<Map.Entry<UUID, PlayerPosition>> shuffled = new ArrayList<>(near);
            java.util.Collections.shuffle(shuffled, ThreadLocalRandom.current());
            int seeds = Math.min(near.size() >= 4 ? 2 : 1, shuffled.size());
            List<UUID> marked = new ArrayList<>();
            for (int i = 0; i < seeds; i++) {
                UUID id = shuffled.get(i).getKey();
                marked.add(id);
                sendActionBar(id, Component.text("Призматический заряд на Вас — отойдите от союзников на 6 блоков!", NamedTextColor.AQUA));
            }
            World world = dragon.getWorld();
            for (int pulse = 0; pulse < 3; pulse++) {
                dragon.getScheduler().runDelayed(plugin, t -> {
                    for (UUID id : marked) {
                        Player pl = Bukkit.getPlayer(id);
                        if (pl != null) {
                            pl.getScheduler().run(plugin, pt -> {
                                pl.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, pl.getLocation().add(0, 1.2, 0), 18, 0.5, 0.7, 0.5, 0.08);
                                pl.playSound(pl.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.6f);
                            }, () -> {});
                        }
                    }
                }, () -> {}, Math.max(1L, pulse * 18L));
            }
            dragon.getScheduler().runDelayed(plugin, t -> {
                List<Map.Entry<UUID, PlayerPosition>> current = activePlayers();
                Set<UUID> hit = new HashSet<>();
                for (UUID seedId : marked) {
                    PlayerPosition seedPos = positions.get(seedId);
                    if (seedPos == null) continue;
                    // BFS chain with +25% damage per hop
                    List<UUID> frontier = List.of(seedId);
                    hit.add(seedId);
                    double hopDamage = 6.0;
                    while (!frontier.isEmpty()) {
                        List<UUID> nextFrontier = new ArrayList<>();
                        for (UUID curId : frontier) {
                            damagePlayer(curId, hopDamage, 0.35, "Призматическая цепь", NamedTextColor.AQUA);
                            PlayerPosition curPos = positions.get(curId);
                            if (curPos == null) continue;
                            for (var cand : current) {
                                if (hit.contains(cand.getKey())) continue;
                                double dx = cand.getValue().x - curPos.x;
                                double dz = cand.getValue().z - curPos.z;
                                if (dx * dx + dz * dz <= 36.0) {
                                    hit.add(cand.getKey());
                                    nextFrontier.add(cand.getKey());
                                    drawParticleLine(world,
                                        new Location(world, curPos.x, curPos.y + 1.2, curPos.z),
                                        new Location(world, cand.getValue().x, cand.getValue().y + 1.2, cand.getValue().z),
                                        Particle.ELECTRIC_SPARK, 12);
                                }
                            }
                        }
                        hopDamage *= 1.25;
                        frontier = nextFrontier;
                    }
                }
            }, () -> {}, 60L);
        }

        // 3) Луч маяка (каждые 24 сек) — вращающийся луч над ареной, блокируется башнями
        if (seconds % 24 == 12) {
            World world = dragon.getWorld();
            broadcastToArena(near, Component.text("Венец Края запускает Луч маяка! Бегите по кругу или прячьтесь за башнями!", NamedTextColor.AQUA));
            double startAngle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
            Location origin = new Location(world, 0.5, 68.0, 0.5);
            for (int step = 0; step < 12; step++) {
                final double angle = startAngle + step * (Math.PI / 6.0);
                dragon.getScheduler().runDelayed(plugin, t -> {
                    if (!dragon.isValid() || dragon.isDead() || crownShielded) return;
                    Location target = new Location(world, Math.cos(angle) * 52.0, 68.0, Math.sin(angle) * 52.0);
                    drawBeaconRay(world, origin, target);
                    for (var entry : activePlayers()) {
                        PlayerPosition p = entry.getValue();
                        // Distance from player to beam segment in XZ
                        double beamCos = Math.cos(angle);
                        double beamSin = Math.sin(angle);
                        double proj = p.x * beamCos + p.z * beamSin;
                        if (proj < 2.0 || proj > 54.0) continue;
                        double perp = Math.abs(-p.x * beamSin + p.z * beamCos);
                        if (perp <= 3.0) {
                            hitBeaconBeam(entry.getKey(), origin);
                        }
                    }
                }, () -> {}, 1L + step * 10L);
            }
        }
        // Crownfall: a marked player must leave the impact circle before the crystal burst.
        if (seconds % 30 == 20) {
            PlayerPosition target = near.get(ThreadLocalRandom.current().nextInt(near.size())).getValue();
            World world = dragon.getWorld();
            Location center = new Location(world, target.x, target.y + 0.4, target.z);
            broadcastToArena(near, Component.text("Венец призывает падение кристалла! Уходите от голубого круга!", NamedTextColor.AQUA));
            for (int pulse = 0; pulse < 5; pulse++) {
                final int charge = pulse;
                dragon.getScheduler().runDelayed(plugin, t -> Bukkit.getRegionScheduler().run(plugin, center, rt -> {
                    world.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 2.0f, 0.6f + charge * 0.2f);
                    for (int i = 0; i < 48; i++) {
                        double angle = i * Math.PI / 24.0;
                        Location edge = center.clone().add(Math.cos(angle) * 6.0, 0.2, Math.sin(angle) * 6.0);
                        Bukkit.getRegionScheduler().run(plugin, edge, visual ->
                            world.spawnParticle(Particle.END_ROD, edge, 3, 0.1, 0.2, 0.1, 0.01));
                    }
                }), () -> {}, 1L + pulse * 10L);
            }
            dragon.getScheduler().runDelayed(plugin, t -> {
                Bukkit.getRegionScheduler().run(plugin, center, rt -> {
                    world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 3.0f, 1.35f);
                    world.spawnParticle(Particle.SONIC_BOOM, center.clone().add(0, 1, 0), 10, 2.5, 0.4, 2.5, 0);
                    world.spawnParticle(Particle.ELECTRIC_SPARK, center, 110, 3.5, 2.0, 3.5, 0.15);
                });
                for (var e : activePlayers()) {
                    double dx = e.getValue().x - center.getX(), dz = e.getValue().z - center.getZ();
                    if (dx * dx + dz * dz <= 36.0) damagePlayer(e.getKey(), 9.0, 0.3, "Падение кристалла", NamedTextColor.AQUA);
                }
            }, () -> {}, 60L);
        }
    }

    private void hitBeaconBeam(UUID id, Location beamOrigin) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        player.getScheduler().run(plugin, t -> {
            if (player.getWorld().getEnvironment() != World.Environment.THE_END || player.isDead()) return;
            Location eye = player.getEyeLocation();
            Vector toOrigin = beamOrigin.toVector().subtract(eye.toVector());
            double dist = toOrigin.length();
            if (dist > 1.0) {
                RayTraceResult cover = player.getWorld().rayTraceBlocks(eye, toOrigin.normalize(), Math.min(dist, 64.0), FluidCollisionMode.NEVER, true);
                if (cover != null && cover.getHitBlock() != null) {
                    // Protected behind obsidian pillar/wall!
                    return;
                }
            }
            player.damage(player.isBlocking() ? 2.0 : 4.0);
            player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, eye, 12, 0.3, 0.4, 0.3, 0.05);
            ((Audience) player).sendActionBar(Component.text("Луч маяка! Укройтесь за башней!", NamedTextColor.AQUA));
        }, () -> {});
    }

    private void startCrownOverload(EnderDragon dragon, List<Map.Entry<UUID, PlayerPosition>> near) {
        crownShielded = true;
        clearCrownCores();
        World world = dragon.getWorld();
        broadcastToArena(near, Component.text("ПЕРЕГРУЗКА ЯДРА! Разбейте 3 Кристальных Средоточия у портала за 12 секунд!", NamedTextColor.GOLD));
        Location hover = new Location(world, 0.5, 82.0, 0.5);
        dragon.teleportAsync(hover);
        dragon.setPhase(EnderDragon.Phase.HOVER);

        for (int i = 0; i < 3; i++) {
            double ang = (2 * Math.PI * i) / 3.0;
            Location loc = new Location(world, Math.cos(ang) * 10.0 + 0.5, 66.0, Math.sin(ang) * 10.0 + 0.5);
            Bukkit.getRegionScheduler().run(plugin, loc, rtask -> {
                int y = world.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
                Location spawn = new Location(world, loc.getX(), Math.max(58, y + 1), loc.getZ());
                Shulker core = (Shulker) world.spawnEntity(spawn, EntityType.SHULKER);
                core.setAI(false);
                core.setGlowing(true);
                core.setPeek(0.6f);
                core.customName(Component.text("✦ Кристальное Средоточие ✦", NamedTextColor.AQUA));
                core.setCustomNameVisible(true);
                core.getPersistentDataContainer().set(crownCoreKey, PersistentDataType.BYTE, (byte) 1);
                var hp = core.getAttribute(Attribute.MAX_HEALTH);
                if (hp != null) {
                    hp.setBaseValue(25.0);
                    core.setHealth(25.0);
                }
                crownCores.add(core.getUniqueId());
                world.spawnParticle(Particle.END_ROD, spawn.clone().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.08);
            });
        }

        // 12-second resolution timer
        dragon.getScheduler().runDelayed(plugin, task -> {
            if (!crownShielded) return; // Already interrupted!
            crownShielded = false;
            boolean failed = !crownCores.isEmpty();
            clearCrownCores();
            if (!dragon.isValid() || dragon.isDead()) return;
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
            if (failed) {
                broadcastToArena(activePlayers(), Component.text("Средоточия не разрушены — ударная волна Перегрузки!", NamedTextColor.RED));
                Location center = new Location(world, 0.5, 67.0, 0.5);
                Bukkit.getRegionScheduler().run(plugin, center, rt -> {
                    world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 3.0f, 0.6f);
                    world.spawnParticle(Particle.SONIC_BOOM, center, 8, 4.0, 2.0, 4.0, 0);
                });
                for (var entry : activePlayers()) {
                    hitOverloadWave(entry.getKey(), center);
                }
            }
        }, () -> {}, 240L);
    }

    private void hitOverloadWave(UUID id, Location center) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        player.getScheduler().run(plugin, t -> {
            if (player.getWorld().getEnvironment() != World.Environment.THE_END || player.isDead()) return;
            Location eye = player.getEyeLocation();
            Vector toCenter = center.toVector().subtract(eye.toVector());
            double dist = toCenter.length();
            if (dist > 2.0) {
                RayTraceResult cover = player.getWorld().rayTraceBlocks(eye, toCenter.normalize(), Math.min(dist, 90.0), FluidCollisionMode.NEVER, true);
                if (cover != null && cover.getHitBlock() != null && cover.getHitBlock().getType() == Material.OBSIDIAN) {
                    ((Audience) player).sendActionBar(Component.text("Обсидиановая башня защитила Вас от волны!", NamedTextColor.GREEN));
                    return;
                }
            }
            double dmg = player.isBlocking() ? 14.0 * 0.3 : 14.0;
            player.damage(dmg);
            ((Audience) player).sendActionBar(Component.text("Ударная волна Перегрузки!", NamedTextColor.RED));
        }, () -> {});
    }

    private void clearCrownCores() {
        for (UUID id : new ArrayList<>(crownCores)) {
            crownCores.remove(id);
            Entity e = Bukkit.getEntity(id);
            if (e != null) {
                e.getScheduler().run(plugin, t -> {
                    if (e.isValid()) e.remove();
                }, () -> {});
            }
        }
    }

    // =========================================================================
    // WAVE CRYSTAL & RESONATOR ARENA SETUP (REGION-SAFE)
    // =========================================================================

    private void setupWaveArena(World world, DragonKind kind) {
        cleanupArenaArtifacts(world);
        countedCrystals.clear();
        bastionCrystalsDestroyed.set(0);

        for (int i = 0; i < PILLAR_COUNT; i++) {
            final int pillarIdx = i;
            Location pillar = pillarApproxLocation(world, pillarIdx);
            Bukkit.getRegionScheduler().run(plugin, pillar, task -> {
                Location top = findPillarTop(world, pillar.getBlockX(), pillar.getBlockZ());
                if (top == null) return;
                // Remove old crystals near this pillar top
                for (Entity e : world.getNearbyEntities(top, 4.5, 6.0, 4.5)) {
                    if (e instanceof EnderCrystal crystal) crystal.remove();
                }

                if (kind == DragonKind.BASTION) {
                    // 8 crystals (pillars 0..7), 3 of them (0, 3, 6) in obsidian/iron cages
                    if (pillarIdx < 8) {
                        spawnPillarCrystal(world, top, false);
                        if (pillarIdx == 0 || pillarIdx == 3 || pillarIdx == 6) {
                            buildCrystalCage(world, top);
                        }
                    }
                } else if (kind == DragonKind.MIRAGE) {
                    // 4 migrating crystals (pillars 0, 2, 5, 7)
                    if (pillarIdx == 0 || pillarIdx == 2 || pillarIdx == 5 || pillarIdx == 7) {
                        EnderCrystal c = spawnPillarCrystal(world, top, false);
                        mirageActivePillars.add(pillarIdx);
                        mirageCrystalByPillar.put(pillarIdx, c.getUniqueId());
                    }
                } else if (kind == DragonKind.CROWN) {
                    // 4 invulnerable Resonant crystals (pillars 0, 2, 5, 7) + Crying Obsidian Resonator at base
                    if (pillarIdx == 0 || pillarIdx == 2 || pillarIdx == 5 || pillarIdx == 7) {
                        EnderCrystal c = spawnPillarCrystal(world, top, true);
                        crownCrystalByPillar.put(pillarIdx, c.getUniqueId());
                        placeResonatorAtPillarBase(world, pillarIdx, top.getBlockX(), top.getBlockZ());
                    }
                }
            });
        }
    }

    private EnderCrystal spawnPillarCrystal(World world, Location top, boolean invulnerable) {
        Location crystalLoc = top.clone().add(0.5, 1.0, 0.5);
        EnderCrystal crystal = (EnderCrystal) world.spawnEntity(crystalLoc, EntityType.END_CRYSTAL);
        crystal.setShowingBottom(true);
        crystal.setInvulnerable(invulnerable);
        crystal.getPersistentDataContainer().set(managedCrystalKey, PersistentDataType.BYTE, (byte) 1);
        return crystal;
    }

    private void buildCrystalCage(World world, Location top) {
        int cx = top.getBlockX();
        int cy = top.getBlockY() + 1;
        int cz = top.getBlockZ();
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean border = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                    boolean roof = dy == 3 && Math.abs(dx) <= 2 && Math.abs(dz) <= 2;
                    if (!border && !roof) continue;
                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (b.getType() != Material.AIR) continue;
                    boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                    b.setType(corner || roof ? Material.OBSIDIAN : Material.IRON_BARS, false);
                    temporaryCageBlocks.add(new BlockKey(world.getName(), b.getX(), b.getY(), b.getZ()));
                }
            }
        }
    }

    private void placeResonatorAtPillarBase(World world, int pillarIdx, int px, int pz) {
        // Place glowing Crying Obsidian resonator just outside the obsidian pillar facing island center (0, 0)
        Vector inward = new Vector(-px, 0, -pz).normalize().multiply(4.2);
        int rx = px + (int) Math.round(inward.getX());
        int rz = pz + (int) Math.round(inward.getZ());
        int surfaceY = world.getHighestBlockYAt(rx, rz);
        int ry = Math.max(58, surfaceY + 1);
        Block block = world.getBlockAt(rx, ry, rz);
        block.setType(Material.CRYING_OBSIDIAN, false);
        BlockKey key = new BlockKey(world.getName(), rx, ry, rz);
        resonatorBlocks.put(key, pillarIdx);
        resonatorLocations.put(pillarIdx, new Location(world, rx, ry, rz));
    }

    private void migrateMirageCrystals(World world, int destroyedPillar) {
        mirageActivePillars.remove(destroyedPillar);
        mirageCrystalByPillar.remove(destroyedPillar);
        if (mirageActivePillars.isEmpty()) return;

        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < PILLAR_COUNT; i++) {
            if (!mirageActivePillars.contains(i) && i != destroyedPillar) free.add(i);
        }
        java.util.Collections.shuffle(free, ThreadLocalRandom.current());

        List<Integer> oldPillars = new ArrayList<>(mirageActivePillars);
        mirageActivePillars.clear();
        for (int idx = 0; idx < oldPillars.size() && idx < free.size(); idx++) {
            int oldPillar = oldPillars.get(idx);
            int newPillar = free.get(idx);
            UUID oldCrystalId = mirageCrystalByPillar.remove(oldPillar);
            if (oldCrystalId != null) {
                Entity oldEntity = Bukkit.getEntity(oldCrystalId);
                if (oldEntity != null) {
                    oldEntity.getScheduler().run(plugin, t -> {
                        if (oldEntity.isValid()) {
                            oldEntity.getWorld().spawnParticle(Particle.PORTAL, oldEntity.getLocation(), 40, 0.8, 0.8, 0.8, 0.2);
                            oldEntity.remove();
                        }
                    }, () -> {});
                }
            }
            Location destApprox = pillarApproxLocation(world, newPillar);
            mirageActivePillars.add(newPillar);
            Bukkit.getRegionScheduler().run(plugin, destApprox, task -> {
                Location top = findPillarTop(world, destApprox.getBlockX(), destApprox.getBlockZ());
                if (top == null) return;
                EnderCrystal spawned = spawnPillarCrystal(world, top, false);
                mirageCrystalByPillar.put(newPillar, spawned.getUniqueId());
                world.spawnParticle(Particle.REVERSE_PORTAL, spawned.getLocation(), 45, 0.8, 1.0, 0.8, 0.2);
                world.playSound(spawned.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 1.1f);
            });
        }
        broadcastToArena(activePlayers(), Component.text("Оставшиеся кристаллы Миража переместились на другие башни!", NamedTextColor.DARK_PURPLE));
    }

    private void cleanupArenaArtifacts(World world) {
        clearMirageCopies(world);
        clearCrownCores();
        mirageActivePillars.clear();
        mirageCrystalByPillar.clear();
        crownCrystalByPillar.clear();
        resonatorLocations.clear();

        for (BlockKey key : new ArrayList<>(resonatorBlocks.keySet())) {
            resonatorBlocks.remove(key);
            if (!world.getName().equals(key.world)) continue;
            Location loc = new Location(world, key.x, key.y, key.z);
            Bukkit.getRegionScheduler().run(plugin, loc, t -> {
                Block b = world.getBlockAt(key.x, key.y, key.z);
                if (b.getType() == Material.CRYING_OBSIDIAN) b.setType(Material.AIR, false);
            });
        }
        for (BlockKey key : new ArrayList<>(temporaryCageBlocks)) {
            temporaryCageBlocks.remove(key);
            if (!world.getName().equals(key.world)) continue;
            Location loc = new Location(world, key.x, key.y, key.z);
            Bukkit.getRegionScheduler().run(plugin, loc, t -> {
                Block b = world.getBlockAt(key.x, key.y, key.z);
                if (b.getType() == Material.OBSIDIAN || b.getType() == Material.IRON_BARS) {
                    b.setType(Material.AIR, false);
                }
            });
        }
    }

    private Location pillarApproxLocation(World world, int index) {
        double angle = 2.0 * Math.PI * index / PILLAR_COUNT;
        int x = (int) Math.round(PILLAR_RADIUS * Math.cos(angle));
        int z = (int) Math.round(PILLAR_RADIUS * Math.sin(angle));
        return new Location(world, x, 80, z);
    }

    private Location findPillarTop(World world, int approxX, int approxZ) {
        // Scan 5x5 around expected coordinates to locate the pillar's bedrock cap or top obsidian
        int bestY = -1;
        int bestX = approxX;
        int bestZ = approxZ;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int x = approxX + dx;
                int z = approxZ + dz;
                for (int y = 115; y >= 72; y--) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (m == Material.BEDROCK) {
                        return new Location(world, x, y, z);
                    }
                    if (m == Material.OBSIDIAN && y > bestY) {
                        bestY = y;
                        bestX = x;
                        bestZ = z;
                        break;
                    }
                }
            }
        }
        return bestY > 0 ? new Location(world, bestX, bestY, bestZ) : null;
    }

    // =========================================================================
    // EVENTS: CRYSTALS, RESONATORS, MIRAGE COPIES, CROWN CORES, DRAGON DAMAGE
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResonatorBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getWorld().getEnvironment() != World.Environment.THE_END) return;
        BlockKey key = new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        Integer pillarIdx = resonatorBlocks.remove(key);
        if (pillarIdx == null) return;
        resonatorLocations.remove(pillarIdx);
        event.setDropItems(false);
        contribution.merge(event.getPlayer().getUniqueId(), 35.0, Double::sum);
        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.5f, 1.2f);
        block.getWorld().spawnParticle(Particle.END_ROD, block.getLocation().add(0.5, 0.5, 0.5), 30, 0.6, 0.6, 0.6, 0.08);
        UUID crystalId = crownCrystalByPillar.remove(pillarIdx);
        if (crystalId != null) {
            Entity e = Bukkit.getEntity(crystalId);
            if (e instanceof EnderCrystal crystal) {
                crystal.getScheduler().run(plugin, t -> {
                    if (crystal.isValid()) {
                        crystal.setInvulnerable(false);
                        crystal.getWorld().spawnParticle(Particle.EXPLOSION, crystal.getLocation(), 2, 0.3, 0.3, 0.3, 0);
                    }
                }, () -> {});
            }
        }
        broadcastToArena(activePlayers(), Component.text(event.getPlayer().getName() + " разрушил Резонатор! Кристалл на башне уязвим!", NamedTextColor.AQUA));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMirageCopyHit(EntityDamageByEntityEvent event) {
        EnderDragon copy = resolveDragon(event.getEntity());
        if (copy == null) return;
        if (!copy.getPersistentDataContainer().has(mirageCopyKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        mirageCopies.remove(copy.getUniqueId());
        copy.getWorld().spawnParticle(Particle.LARGE_SMOKE, copy.getLocation(), 40, 1.5, 1.0, 1.5, 0.08);
        copy.getWorld().playSound(copy.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.7f);
        copy.remove();
        Player hitter = attacker(event.getDamager());
        if (hitter != null) {
            hitter.getScheduler().run(plugin, t -> {
                if (hitter.isDead() || hitter.getWorld().getEnvironment() != World.Environment.THE_END) return;
                Location base = hitter.getLocation();
                double ang = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                double dist = ThreadLocalRandom.current().nextDouble(8.0, 12.0);
                int tx = (int) Math.round(base.getX() + Math.cos(ang) * dist);
                int tz = (int) Math.round(base.getZ() + Math.sin(ang) * dist);
                Location dest = new Location(base.getWorld(), tx + 0.5, base.getY(), tz + 0.5, base.getYaw(), base.getPitch());
                if (Bukkit.isOwnedByCurrentRegion(base.getWorld(), tx >> 4, tz >> 4)) {
                    int y = base.getWorld().getHighestBlockYAt(tx, tz);
                    if (y > base.getWorld().getMinHeight() + 2) dest.setY(y + 1);
                }
                hitter.teleportAsync(dest).whenComplete((ok, err) -> hitter.getScheduler().run(plugin, tt -> {
                    hitter.playSound(hitter.getLocation(), Sound.ITEM_CHORUS_FRUIT_TELEPORT, 1.0f, 1.0f);
                    ((Audience) hitter).sendActionBar(Component.text("Это был мираж! Пространство сместило Вас!", NamedTextColor.DARK_PURPLE));
                }, () -> {}));
            }, () -> {});
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrownCoreDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Shulker core)) return;
        if (!core.getPersistentDataContainer().has(crownCoreKey, PersistentDataType.BYTE)) return;
        event.getDrops().clear();
        if (!crownCores.remove(core.getUniqueId())) return;
        Player killer = core.getKiller();
        if (killer != null) contribution.merge(killer.getUniqueId(), 20.0, Double::sum);
        core.getWorld().playSound(core.getLocation(), Sound.BLOCK_GLASS_BREAK, 1.5f, 0.8f);
        core.getWorld().spawnParticle(Particle.END_ROD, core.getLocation().add(0, 0.5, 0), 30, 0.5, 0.5, 0.5, 0.1);

        if (crownShielded && crownCores.isEmpty()) {
            // Overload interrupted -> stun dragon for 6 seconds with x1.5 incoming damage!
            crownShielded = false;
            crownStunnedUntil = System.currentTimeMillis() + 6_000L;
            broadcastToArena(activePlayers(), Component.text("Перегрузка сорвана! Венец Края оглушён на 6 сек и получает ×1.5 урона!", NamedTextColor.GOLD));
            UUID id = activeDragonId;
            if (id != null) {
                Entity e = Bukkit.getEntity(id);
                if (e instanceof EnderDragon dragon) {
                    dragon.getScheduler().run(plugin, t -> {
                        if (dragon.isValid() && !dragon.isDead()) {
                            dragon.setPhase(EnderDragon.Phase.LAND_ON_PORTAL);
                            dragon.getWorld().playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_HURT, 2.5f, 0.6f);
                        }
                    }, () -> {});
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalPlaced(EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal) || !state.firstFightComplete() || state.corrupt()) return;
        if (event.getBlock().getWorld().getEnvironment() != World.Environment.THE_END || event.getBlock().getType() != Material.BEDROCK) return;
        Location point = event.getBlock().getLocation();
        if (point.getX() * point.getX() + point.getZ() * point.getZ() > 64) return;
        ItemStack held = event.getHand() == EquipmentSlot.OFF_HAND
            ? event.getPlayer().getInventory().getItemInOffHand() : event.getPlayer().getInventory().getItemInMainHand();
        try { state.recordPortalCrystal(crystal.getUniqueId(), items.is(held, "empowered_end_crystal")); }
        catch (IOException error) { plugin.getLogger().severe("Cannot persist End crystal placement: " + error); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonDamage(EntityDamageEvent event) {
        EnderDragon dragon = resolveDragon(event.getEntity());
        if (dragon == null || !state.opened() || state.corrupt()) return;
        if (dragon.getPersistentDataContainer().has(mirageCopyKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            return;
        }
        if (transitioning.containsKey(dragon.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        // Crown Overload shield blocks all damage while 3 Cores are alive
        if (kind() == DragonKind.CROWN && crownShielded) {
            event.setCancelled(true);
            return;
        }
        // Bed / Respawn Anchor explosion anti-abuse cap
        if (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            long now = System.currentTimeMillis();
            if (now < blockExplosionUntil.getOrDefault(dragon.getUniqueId(), 0L)) {
                event.setCancelled(true);
                return;
            }
            blockExplosionUntil.put(dragon.getUniqueId(), now + 10_000L);
            event.setDamage(Math.min(12.0, event.getDamage()));
        }
        // Bastion obsidian armor: -40% projectile damage (-5% per broken crystal), bypassed by headshots & melee
        if (kind() == DragonKind.BASTION && event instanceof EntityDamageByEntityEvent byEntity
            && byEntity.getDamager() instanceof Projectile proj) {
            boolean headshot = proj.getLocation().distanceSquared(dragon.getEyeLocation()) <= 12.25;
            if (!headshot) {
                double resist = Math.max(0.0, 0.40 - 0.05 * bastionCrystalsDestroyed.get());
                event.setDamage(event.getDamage() * (1.0 - resist));
            }
        }
        // Crown stunned vulnerability: x1.5 damage for 6 seconds after interrupting Overload
        if (kind() == DragonKind.CROWN && System.currentTimeMillis() < crownStunnedUntil) {
            event.setDamage(event.getDamage() * 1.5);
        }
        // Mirage: hitting the real dragon while copies exist shatters copies and grounds dragon for 5s!
        if (kind() == DragonKind.MIRAGE && !mirageCopies.isEmpty()) {
            clearMirageCopies(dragon.getWorld());
            mirageGroundedUntil = System.currentTimeMillis() + 5_000L;
            dragon.setPhase(EnderDragon.Phase.LAND_ON_PORTAL);
            broadcastToArena(activePlayers(), Component.text("Иллюзия развеяна! Мираж Края спустился к земле на 5 секунд!", NamedTextColor.LIGHT_PURPLE));
        }

        double damage = Math.min(dragon.getHealth(), event.getFinalDamage());
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Player attacker = attacker(byEntity.getDamager());
            if (attacker != null && damage > 0) contribution.merge(attacker.getUniqueId(), damage, Double::sum);
        }

        // First-fight Wave 1 -> Wave 2 -> Wave 3 transition without prematurely triggering vanilla DragonBattle win
        if (state.firstFightComplete() || damage < dragon.getHealth() - 1.0) return;
        if (state.wave() >= 2) return;
        event.setCancelled(true);
        try {
            state.saveFightProgress(contribution, maxPlayersSeen);
            state.advanceWave();
        } catch (IOException error) {
            plugin.getLogger().severe("Cannot persist End wave transition: " + error);
            return;
        }
        transitioning.put(dragon.getUniqueId(), true);
        World world = dragon.getWorld();
        cleanupArenaArtifacts(world);
        dragon.setHealth(1.0);
        dragon.setInvulnerable(true);
        dragon.setInvisible(true);
        dragon.setPhase(EnderDragon.Phase.HOVER);
        world.playSound(dragon.getLocation(), Sound.ENTITY_ENDER_DRAGON_DEATH, 2.5f, 1.0f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, dragon.getLocation(), 4, 2.0, 2.0, 2.0, 0);
        dragon.teleportAsync(new Location(world, 0.5, 155.0, 0.5));
        int nextWaveNum = state.wave() + 1;
        plugin.announce(Component.text("Волна пройдена! Передышка 20 секунд до волны " + nextWaveNum + "/3 (" + kind().title + ").", NamedTextColor.LIGHT_PURPLE));

        // 10s countdown warning
        dragon.getScheduler().runDelayed(plugin, t -> {
            if (dragon.isValid()) {
                broadcastToArena(activePlayers(), Component.text("До появления " + kind().title + " осталось 10 секунд...", kind().color));
            }
        }, () -> {}, 200L);

        // Spawn next wave after 20 seconds (400 ticks)
        dragon.getScheduler().runDelayed(plugin, task -> {
            transitioning.remove(dragon.getUniqueId());
            if (!dragon.isValid() || dragon.isDead()) return;
            resetBossMechanicFlags();
            setupWaveArena(world, kind());
            configure(dragon, false);
            dragon.teleportAsync(new Location(world, 0.5, 105.0, 0.5));
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
            Location strike = new Location(world, 0.5, 68.0, 0.5);
            Bukkit.getRegionScheduler().run(plugin, strike, rt -> world.strikeLightningEffect(strike));
            plugin.announce(Component.text("Волна " + nextWaveNum + "/3: Из пустоты появился " + kind().title + "!", kind().color));
        }, () -> transitioning.remove(dragon.getUniqueId()), 400L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal) || !state.opened() || state.corrupt()) return;
        if (crystal.getWorld().getEnvironment() != World.Environment.THE_END) return;
        if (crystal.isInvulnerable()) return;
        onCrystalShattered(attacker(event.getDamager()), crystal);
    }

    void onCrystalShattered(Player player, EnderCrystal crystal) {
        if (!state.opened() || state.corrupt()) return;
        if (crystal.getWorld().getEnvironment() != World.Environment.THE_END) return;
        // If player broke a pre-placed portal crystal before summon, forget it in EndState
        if (state.firstFightComplete() && state.repeatKind() == null) {
            try { state.forgetPortalCrystal(crystal.getUniqueId()); }
            catch (IOException ignored) {}
            return;
        }
        if (!countedCrystals.add(crystal.getUniqueId())) return;
        if (player != null) contribution.merge(player.getUniqueId(), 35.0, Double::sum);
        if (kind() == DragonKind.BASTION) {
            bastionCrystalsDestroyed.incrementAndGet();
        } else if (kind() == DragonKind.MIRAGE) {
            Integer pillar = null;
            for (var e : mirageCrystalByPillar.entrySet()) {
                if (e.getValue().equals(crystal.getUniqueId())) { pillar = e.getKey(); break; }
            }
            if (pillar != null) {
                migrateMirageCrystals(crystal.getWorld(), pillar);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon) || !state.opened() || state.corrupt()) return;
        if (dragon.getPersistentDataContainer().has(mirageCopyKey, PersistentDataType.BYTE)) {
            event.getDrops().clear();
            return;
        }
        if (!state.firstFightComplete() && state.wave() < 2) return;
        cleanupArenaArtifacts(dragon.getWorld());

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
            if (first && i < 3) {
                each.add(switch (i) {
                    case 0 -> "void_needle:1";
                    case 1 -> "dragon_claw:1";
                    default -> "sky_loop:1";
                });
            }
            if (!each.isEmpty()) rewards.put(id, each);
        }

        // Repeat fight artifact roll (15% normal / 30% empowered, weighted by contribution)
        UUID luckyWinner = null;
        String luckyArtifact = null;
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
            luckyWinner = winner;
            luckyArtifact = artifact;
        }

        try { state.queueRewards(rewards, first); }
        catch (IOException error) {
            plugin.getLogger().severe("Cannot persist End fight rewards: " + error);
            event.setCancelled(true);
            dragon.setHealth(1);
            return;
        }

        plugin.announce(Component.text("✦ " + kind().title + " повержен! Топ вклада:", NamedTextColor.GOLD));
        for (int i = 0; i < Math.min(5, ranked.size()); i++) {
            int place = i + 1;
            UUID id = ranked.get(i).getKey();
            double score = ranked.get(i).getValue();
            int pct = total > 0 ? (int) Math.round(score * 100.0 / total) : 0;
            Player player = Bukkit.getPlayer(id);
            String name = player == null ? id.toString().substring(0, 8) : player.getName();
            plugin.announce(Component.text(place + ". " + name + " — " + Math.round(score) + " очков (" + pct + "%)", NamedTextColor.YELLOW));
        }
        if (luckyWinner != null && luckyArtifact != null) {
            Player wp = Bukkit.getPlayer(luckyWinner);
            String wname = wp == null ? luckyWinner.toString().substring(0, 8) : wp.getName();
            String artName = switch (luckyArtifact) {
                case "void_needle" -> "Игла бездны";
                case "dragon_claw" -> "Коготь дракона";
                default -> "Небесная петля";
            };
            plugin.announce(Component.text("✦ Особая добыча: " + wname + " получил артефакт «" + artName + "»!", NamedTextColor.LIGHT_PURPLE));
        }
        for (UUID id : rewards.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.getScheduler().run(plugin, task -> deliver(player), () -> {});
        }
        if (first) {
            plugin.announce(Component.text("✦ Испытание Трёх завершено! Внешние острова открыты, Просачивание Края прекратилось!", NamedTextColor.AQUA));
        }
        contribution.clear();
        maxPlayersSeen = 1;
        resetBossMechanicFlags();
        countedCrystals.clear();
        activeDragonId = null;
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private DragonKind kind() {
        return state.firstFightComplete() && state.repeatKind() != null ? state.repeatKind() : DragonKind.values()[state.wave()];
    }

    private void configure(EnderDragon dragon, boolean restore) {
        if (!dragon.isValid()) return;
        DragonKind kind = kind();
        String barTitle = !state.firstFightComplete()
            ? kind.title + " · Волна " + (state.wave() + 1) + "/3"
            : state.repeatEmpowered() ? "✦ Усиленный " + kind.title : kind.title;
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
            battle.getBossBar().setTitle(barTitle);
            battle.getBossBar().setColor(switch (kind) {
                case BASTION -> BarColor.RED;
                case MIRAGE -> BarColor.PURPLE;
                case CROWN -> BarColor.WHITE;
            });
        }
    }

    private EnderDragon resolveDragon(Entity entity) {
        if (entity instanceof EnderDragon dragon) return dragon;
        if (entity instanceof ComplexEntityPart part && part.getParent() instanceof EnderDragon dragon) return dragon;
        return null;
    }

    private Player attacker(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    private void damagePlayer(UUID id, double damage, double shieldMultiplier, String reason, NamedTextColor color) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        player.getScheduler().run(plugin, task -> {
            if (player.getWorld().getEnvironment() != World.Environment.THE_END || player.isDead()) return;
            player.damage(player.isBlocking() ? damage * shieldMultiplier : damage);
            ((Audience) player).sendActionBar(Component.text(reason, color));
        }, () -> {});
    }

    private void sendActionBar(UUID id, Component text) {
        Player player = Bukkit.getPlayer(id);
        if (player == null) return;
        player.getScheduler().run(plugin, task -> {
            if (!player.isDead()) ((Audience) player).sendActionBar(text);
        }, () -> {});
    }

    private void broadcastToArena(List<Map.Entry<UUID, PlayerPosition>> near, Component text) {
        for (var entry : near) {
            sendActionBar(entry.getKey(), text);
        }
    }

    private void drawParticleLine(World world, Location a, Location b, Particle particle, int points) {
        Vector delta = b.toVector().subtract(a.toVector());
        for (int i = 0; i <= points; i++) {
            double frac = i / (double) Math.max(1, points);
            Location p = a.clone().add(delta.clone().multiply(frac));
            Bukkit.getRegionScheduler().run(plugin, p, t -> world.spawnParticle(particle, p, 1, 0.05, 0.05, 0.05, 0));
        }
    }

    private void drawBeaconRay(World world, Location origin, Location target) {
        Vector delta = target.toVector().subtract(origin.toVector());
        for (int i = 0; i <= 52; i++) {
            final int pointIndex = i;
            Location base = origin.clone().add(delta.clone().multiply(i / 52.0));
            Bukkit.getRegionScheduler().run(plugin, base, task -> {
                for (int y = 0; y <= 12; y += 2) {
                    Location point = base.clone().add(0, y, 0);
                    world.spawnParticle(Particle.END_ROD, point, 5, 0.25, 0.3, 0.25, 0.01);
                    if (y % 4 == 0) world.spawnParticle(Particle.ELECTRIC_SPARK, point, 2, 0.1, 0.2, 0.1, 0.01);
                }
                if (pointIndex % 8 == 0) world.playSound(base, Sound.BLOCK_BEACON_AMBIENT, 1.4f, 1.7f);
            });
        }
    }

    private record PlayerPosition(double x, double y, double z, long timestamp) {}
    private record BlockKey(String world, int x, int y, int z) {}
}
