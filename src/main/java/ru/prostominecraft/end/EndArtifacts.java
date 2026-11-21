package ru.prostominecraft.end;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

final class EndArtifacts implements Listener {
    private final EndPlugin plugin;
    private final EndItems items;
    private EndFight fight;
    private final NamespacedKey rescueUntil;
    private final NamespacedKey falling;
    private final NamespacedKey noFallUntil;
    private final Map<UUID, Long> clawCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, BurstState> clawBurst = new ConcurrentHashMap<>();
    private final Map<UUID, Long> needleCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rocketCooldown = new ConcurrentHashMap<>();

    EndArtifacts(EndPlugin plugin, EndItems items) {
        this.plugin = plugin;
        this.items = items;
        rescueUntil = new NamespacedKey(plugin, "void_rescue_cooldown_until");
        falling = new NamespacedKey(plugin, "void_rescue_falling");
        noFallUntil = new NamespacedKey(plugin, "claw_no_fall_until");
    }

    void bindFight(EndFight fight) {
        this.fight = fight;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getItem() == null
            || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (items.is(held, "dragon_claw")) {
            event.setCancelled(true);
            useDragonClaw(player, held);
        } else if (items.is(held, "void_needle")) {
            event.setCancelled(true);
            useVoidNeedle(player, held);
        }
    }

    private void useDragonClaw(Player player, ItemStack held) {
        boolean upgraded = items.upgraded(held);
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();

        if (upgraded) {
            long cdMs = 6_500L;
            BurstState state = clawBurst.computeIfAbsent(id, ignored -> new BurstState(2, now));
            state.refill(now, 2, cdMs);
            if (state.charges < 1) {
                long leftSec = Math.max(1, (state.nextRefillAt - now + 999) / 1000);
                ((Audience) player).sendActionBar(Component.text("Коготь дракона: перезарядка " + leftSec + "с", NamedTextColor.YELLOW));
                return;
            }
            if (!items.useCharge(held)) {
                depleted(player);
                return;
            }
            state.consume(now, cdMs);
            player.getInventory().setItemInMainHand(held);
            player.getPersistentDataContainer().set(noFallUntil, PersistentDataType.LONG, now + 6_000L);
            Location origin = player.getLocation().clone();
            Vector direction = origin.getDirection().normalize().multiply(2.20);
            player.setVelocity(direction.add(new Vector(0, 0.42, 0)));
            player.getWorld().playSound(origin, Sound.ENTITY_BREEZE_JUMP, 1.1f, 0.85f);
            player.getWorld().spawnParticle(Particle.SWEEP_ATTACK, origin.clone().add(0, 1.0, 0), 4, 0.9, 0.4, 0.9, 0);
            player.getWorld().spawnParticle(Particle.DRAGON_BREATH, origin.clone().add(0, 0.8, 0), 22, 0.8, 0.4, 0.8, 0.05);
            // Launch AoE wind slash (5 HP + knockback in 3.5 blocks)
            for (Entity nearby : player.getNearbyEntities(3.5, 3.0, 3.5)) {
                if (nearby == player || !(nearby instanceof LivingEntity living) || living.isDead()) continue;
                Vector knock = living.getLocation().toVector().subtract(origin.toVector());
                if (knock.lengthSquared() < 0.01) knock = new Vector(0, 0.4, 0);
                Vector push = knock.normalize().multiply(0.95).setY(0.38);
                living.getScheduler().run(plugin, task -> {
                    if (living.isValid() && !living.isDead()) {
                        living.damage(5.0, player);
                        living.setVelocity(living.getVelocity().add(push));
                    }
                }, () -> {});
            }
            ((Audience) player).sendActionBar(Component.text("Усиленный коготь дракона · рывков: " + state.charges + "/2", NamedTextColor.GOLD));
        } else {
            long until = clawCooldown.getOrDefault(id, 0L);
            if (now < until) {
                long leftSec = Math.max(1, (until - now + 999) / 1000);
                ((Audience) player).sendActionBar(Component.text("Коготь дракона: перезарядка " + leftSec + "с", NamedTextColor.YELLOW));
                return;
            }
            if (!items.useCharge(held)) {
                depleted(player);
                return;
            }
            player.getInventory().setItemInMainHand(held);
            clawCooldown.put(id, now + 10_000L);
            player.getPersistentDataContainer().set(noFallUntil, PersistentDataType.LONG, now + 4_000L);
            Location origin = player.getLocation();
            Vector direction = origin.getDirection().normalize().multiply(1.85);
            player.setVelocity(direction.add(new Vector(0, 0.38, 0)));
            player.getWorld().playSound(origin, Sound.ENTITY_BREEZE_JUMP, 1.0f, 1.0f);
            player.getWorld().spawnParticle(Particle.CLOUD, origin.clone().add(0, 0.5, 0), 16, 0.4, 0.2, 0.4, 0.08);
            ((Audience) player).sendActionBar(Component.text("Коготь дракона", NamedTextColor.LIGHT_PURPLE));
        }
    }

    private void useVoidNeedle(Player player, ItemStack held) {
        boolean upgraded = items.upgraded(held);
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        long until = needleCooldown.getOrDefault(id, 0L);
        if (now < until) {
            long leftSec = Math.max(1, (until - now + 999) / 1000);
            ((Audience) player).sendActionBar(Component.text("Игла бездны: перезарядка " + leftSec + "с", NamedTextColor.YELLOW));
            return;
        }
        if (!items.useCharge(held)) {
            depleted(player);
            return;
        }
        player.getInventory().setItemInMainHand(held);
        needleCooldown.put(id, now + (upgraded ? 5_500L : 8_000L));

        double maxRange = upgraded ? 50.0 : 35.0;
        double baseDamage = upgraded ? 11.0 : 8.0;
        int maxTargets = upgraded ? 3 : 1;
        int glowingTicks = upgraded ? 240 : 200;

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();

        // Check solid block stop distance
        RayTraceResult blockHit = world.rayTraceBlocks(eye, dir, maxRange, FluidCollisionMode.NEVER, true);
        double beamLength = blockHit != null ? eye.toVector().distance(blockHit.getHitPosition()) : maxRange;

        // Collect all entities along the beam (within current region / nearby bounding box)
        List<Entity> candidates = player.getNearbyEntities(beamLength + 2, beamLength + 2, beamLength + 2);
        Set<UUID> hitLiving = new HashSet<>();
        Set<UUID> hitCrystals = new HashSet<>();
        List<LivingEntity> orderedTargets = new ArrayList<>();
        List<EnderCrystal> orderedCrystals = new ArrayList<>();

        for (double d = 1.0; d <= beamLength; d += 0.65) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            if (Bukkit.isOwnedByCurrentRegion(world, point.getBlockX() >> 4, point.getBlockZ() >> 4)) {
                world.spawnParticle(upgraded ? Particle.END_ROD : Particle.DRAGON_BREATH, point, 1, 0.04, 0.04, 0.04, 0);
                if (upgraded && ((int) (d * 2)) % 3 == 0) {
                    world.spawnParticle(Particle.DRAGON_BREATH, point, 1, 0.05, 0.05, 0.05, 0.01);
                }
            }
            for (Entity entity : candidates) {
                if (entity == player || !entity.isValid()) continue;
                BoundingBox box = entity.getBoundingBox().expand(0.35);
                if (!box.contains(point.toVector())) continue;
                if (entity instanceof EnderCrystal crystal) {
                    if (hitCrystals.add(crystal.getUniqueId())) orderedCrystals.add(crystal);
                } else {
                    LivingEntity living = unwrapLiving(entity);
                    if (living != null && living != player && !living.isDead() && hitLiving.add(living.getUniqueId())) {
                        orderedTargets.add(living);
                    }
                }
            }
            if (orderedTargets.size() >= maxTargets && orderedCrystals.isEmpty()) {
                // Stop beam visually once max living targets pierced
                if (orderedTargets.size() >= maxTargets) break;
            }
        }

        world.playSound(eye, Sound.ENTITY_GUARDIAN_ATTACK, 1.0f, upgraded ? 1.45f : 1.25f);
        world.playSound(eye, Sound.ENTITY_ENDER_EYE_DEATH, 0.8f, 1.3f);

        // Instantly shatter any End Crystals on the beam (credits EndFight via onCrystalShattered!)
        for (EnderCrystal crystal : orderedCrystals) {
            crystal.getScheduler().run(plugin, task -> {
                if (crystal.isValid() && !crystal.isInvulnerable()) {
                    Location at = crystal.getLocation();
                    if (fight != null) fight.onCrystalShattered(player, crystal);
                    crystal.remove();
                    at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0);
                    at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 1.0f);
                }
            }, () -> {});
        }

        // Damage up to maxTargets living entities
        int count = Math.min(maxTargets, orderedTargets.size());
        for (int i = 0; i < count; i++) {
            LivingEntity living = orderedTargets.get(i);
            living.getScheduler().run(plugin, task -> {
                if (!living.isValid() || living.isDead()) return;
                double dmg = baseDamage;
                if (upgraded && living.hasPotionEffect(PotionEffectType.GLOWING)) {
                    dmg *= 1.35;
                }
                // Break PvP shield on hit
                if (living instanceof Player targetPlayer && targetPlayer.isBlocking()) {
                    targetPlayer.setCooldown(Material.SHIELD, 100);
                    targetPlayer.getWorld().playSound(targetPlayer.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 1.0f);
                }
                living.damage(dmg, player);
                living.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowingTicks, 0));
                living.getWorld().spawnParticle(Particle.WITCH, living.getLocation().add(0, 1.0, 0), 14, 0.35, 0.5, 0.35, 0.05);
            }, () -> {});
        }

        String label = upgraded ? "Усиленная игла бездны" : "Игла бездны";
        if (count > 0 || !orderedCrystals.isEmpty()) {
            ((Audience) player).sendActionBar(Component.text(label + " · попаданий: " + (count + orderedCrystals.size()),
                upgraded ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.DARK_PURPLE));
        } else {
            ((Audience) player).sendActionBar(Component.text(label, NamedTextColor.DARK_PURPLE));
        }
    }

    private LivingEntity unwrapLiving(Entity entity) {
        if (entity instanceof LivingEntity living) return living;
        if (entity instanceof ComplexEntityPart part && part.getParent() instanceof LivingEntity living) return living;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRocket(PlayerInteractEvent event) {
        if (event.getItem() == null || event.getItem().getType() != Material.FIREWORK_ROCKET
            || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!player.isGliding()) return;
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || chest.getType() != Material.ELYTRA || items.is(chest, "end_wings")) return;
        long now = System.currentTimeMillis();
        long until = rocketCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now < until) {
            event.setCancelled(true);
            ((Audience) player).sendActionBar(Component.text("Фейерверк через " + ((until - now + 999) / 1000) + " сек.", NamedTextColor.YELLOW));
        } else rocketCooldown.put(player.getUniqueId(), now + 4000);
    }

    private void depleted(Player player) {
        ((Audience) player).sendActionBar(Component.text("Артефакт истощён · почините Драконьим хитином", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.FLY_INTO_WALL
            && items.is(player.getInventory().getChestplate(), "end_wings")) {
            event.setDamage(event.getDamage() * 0.5);
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        long until = player.getPersistentDataContainer().getOrDefault(noFallUntil, PersistentDataType.LONG, 0L);
        if (System.currentTimeMillis() < until) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getFirstItem();
        ItemStack book = event.getInventory().getSecondItem();
        if (!artifact(first) || book == null || book.getType() != Material.ENCHANTED_BOOK
            || !(book.getItemMeta() instanceof EnchantmentStorageMeta stored)) return;
        if (items.is(first, "sky_loop") && stored.hasStoredEnchant(Enchantment.MENDING)) {
            event.setResult(null);
            return;
        }
        ItemStack result = first.clone();
        boolean changed = false;
        for (var enchant : stored.getStoredEnchants().entrySet()) {
            if (enchant.getKey() != Enchantment.UNBREAKING && enchant.getKey() != Enchantment.MENDING) continue;
            if (enchant.getKey() == Enchantment.MENDING && items.is(first, "sky_loop")) continue;
            int level = Math.min(enchant.getKey().getMaxLevel(), Math.max(enchant.getValue(), result.getEnchantmentLevel(enchant.getKey())));
            result.addUnsafeEnchantment(enchant.getKey(), level);
            changed = true;
        }
        if (changed) {
            event.setResult(result);
            event.getView().setRepairCost(Math.max(1, event.getView().getRepairCost()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMend(PlayerItemMendEvent event) {
        if (items.is(event.getItem(), "sky_loop")) {
            event.setCancelled(true);
            return;
        }
        if (items.is(event.getItem(), "void_needle") || items.is(event.getItem(), "dragon_claw")) {
            int current = items.charges(event.getItem());
            int max = items.maxCharges(event.getItem());
            if (current >= max) {
                event.setCancelled(true);
                return;
            }
            items.mend(event.getItem(), Math.max(1, event.getRepairAmount() / 2));
            // Keep vanilla damage component strictly synchronized with PDC charges
            event.setRepairAmount(0);
        }
    }

    private boolean artifact(ItemStack stack) {
        return items.is(stack, "void_needle") || items.is(stack, "dragon_claw") || items.is(stack, "sky_loop");
    }

    void tick(Player player) {
        if (player.getPersistentDataContainer().has(falling, PersistentDataType.BYTE)) {
            if (player.isOnGround() || player.isInWater() || player.isInsideVehicle() || player.isDead()) {
                player.getPersistentDataContainer().remove(falling);
                player.removePotionEffect(PotionEffectType.SLOW_FALLING);
            }
        }
        if (player.isDead()) return;
        World world = player.getWorld();
        double threshold = world.getEnvironment() == World.Environment.THE_END ? -12 : world.getMinHeight() - 12;
        if (player.getLocation().getY() >= threshold) return;
        long now = System.currentTimeMillis();
        long until = player.getPersistentDataContainer().getOrDefault(rescueUntil, PersistentDataType.LONG, 0L);
        if (now < until) return;

        SlotRef found = findSkyLoop(player);
        if (found == null) return;
        ItemStack loop = found.item;
        if (items.charges(loop) < 1) {
            ((Audience) player).sendActionBar(Component.text("Небесная петля истощена · почините Драконьим хитином", NamedTextColor.RED));
            player.getPersistentDataContainer().set(rescueUntil, PersistentDataType.LONG, now + 3000);
            return;
        }
        boolean upgraded = items.upgraded(loop);
        items.useCharge(loop);
        found.writeBack(player, loop);
        player.getPersistentDataContainer().set(rescueUntil, PersistentDataType.LONG, now + 3000);
        player.getPersistentDataContainer().set(falling, PersistentDataType.BYTE, (byte) 1);

        Location from = player.getLocation();
        double targetX = from.getX();
        double targetZ = from.getZ();
        int baseY = upgraded ? 125 : 100;

        // Upgraded Sky Loop: shift toward nearest solid island surface within 24 blocks (Folia region-safe)
        if (upgraded) {
            int[] land = findNearestLandInRegion(world, from.getBlockX(), from.getBlockZ(), 24);
            if (land != null) {
                targetX = land[0] + 0.5;
                targetZ = land[1] + 0.5;
            }
        }

        int safeY = findSafeAirY(world, (int) Math.floor(targetX), baseY, (int) Math.floor(targetZ));
        Location safe = new Location(world, targetX, safeY, targetZ, from.getYaw(), from.getPitch());

        player.teleportAsync(safe).whenComplete((success, error) -> player.getScheduler().run(plugin, task -> {
            if (error != null || !Boolean.TRUE.equals(success)) {
                player.getPersistentDataContainer().remove(falling);
                return;
            }
            player.setVelocity(new Vector(0, 0, 0));
            player.setFallDistance(0);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 180, 0, false, true));
            if (upgraded) {
                player.setNoDamageTicks(80);
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 80, 4, false, true));
                var maxHp = player.getAttribute(Attribute.MAX_HEALTH);
                if (maxHp != null) player.setHealth(Math.min(maxHp.getValue(), player.getHealth() + 4.0));
            }
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.7f, 1.35f);
            player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0), 30, 0.6, 0.8, 0.6, 0.08);
            ((Audience) player).sendActionBar(Component.text(
                upgraded ? "Усиленная небесная петля вытащила Вас из пустоты" : "Небесная петля спасла Вас от пустоты",
                upgraded ? NamedTextColor.BLUE : NamedTextColor.AQUA));
        }, () -> {}));
    }

    private int findSafeAirY(World world, int bx, int minY, int bz) {
        if (!Bukkit.isOwnedByCurrentRegion(world, bx >> 4, bz >> 4)) return minY;
        int maxY = Math.min(world.getMaxHeight() - 3, minY + 40);
        for (int y = minY; y <= maxY; y++) {
            if (world.getBlockAt(bx, y, bz).isPassable() && world.getBlockAt(bx, y + 1, bz).isPassable()) {
                return y;
            }
        }
        return minY;
    }

    private int[] findNearestLandInRegion(World world, int ox, int oz, int radius) {
        int bestDistSq = Integer.MAX_VALUE;
        int[] best = null;
        for (int dx = -radius; dx <= radius; dx += 3) {
            for (int dz = -radius; dz <= radius; dz += 3) {
                int distSq = dx * dx + dz * dz;
                if (distSq > radius * radius || distSq >= bestDistSq) continue;
                int x = ox + dx;
                int z = oz + dz;
                if (!Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4)) continue;
                int y = world.getHighestBlockYAt(x, z);
                if (y > world.getMinHeight() + 1 && world.getBlockAt(x, y, z).getType().isSolid()) {
                    bestDistSq = distSq;
                    best = new int[] { x, z };
                }
            }
        }
        return best;
    }

    private SlotRef findSkyLoop(Player player) {
        SlotRef depletedFallback = null;
        ItemStack main = player.getInventory().getItemInMainHand();
        if (items.is(main, "sky_loop")) {
            if (items.charges(main) > 0) return new SlotRef(-1, main);
            depletedFallback = new SlotRef(-1, main);
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (items.is(off, "sky_loop")) {
            if (items.charges(off) > 0) return new SlotRef(-2, off);
            if (depletedFallback == null) depletedFallback = new SlotRef(-2, off);
        }
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            ItemStack st = storage[i];
            if (!items.is(st, "sky_loop")) continue;
            if (items.charges(st) > 0) return new SlotRef(i, st);
            if (depletedFallback == null) depletedFallback = new SlotRef(i, st);
        }
        return depletedFallback;
    }

    private record SlotRef(int slot, ItemStack item) {
        void writeBack(Player player, ItemStack updated) {
            if (slot == -1) player.getInventory().setItemInMainHand(updated);
            else if (slot == -2) player.getInventory().setItemInOffHand(updated);
            else player.getInventory().setItem(slot, updated);
        }
    }

    private static final class BurstState {
        int charges;
        long nextRefillAt;

        BurstState(int charges, long now) {
            this.charges = charges;
            this.nextRefillAt = now;
        }

        void refill(long now, int max, long cdMs) {
            while (charges < max && now >= nextRefillAt) {
                charges++;
                nextRefillAt += cdMs;
            }
            if (charges >= max) nextRefillAt = now;
        }

        void consume(long now, long cdMs) {
            if (charges == 2) nextRefillAt = now + cdMs;
            charges = Math.max(0, charges - 1);
        }
    }
}
