package ru.prostominecraft.end;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

final class EndSeepage implements Listener {
    private final EndPlugin plugin;
    private final EndState state;
    private final NamespacedKey riftMark;
    private final Map<UUID, Long> nextRift = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextRoar = new ConcurrentHashMap<>();

    EndSeepage(EndPlugin plugin, EndState state) {
        this.plugin = plugin;
        this.state = state;
        riftMark = new NamespacedKey(plugin, "rift_enderman");
    }

    private boolean active() {
        return state.opened() && !state.firstFightComplete() && !state.corrupt()
            && plugin.getConfig().getBoolean("end-opening.seepage-before-first-kill.enabled", true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNaturalEnderman(CreatureSpawnEvent event) {
        if (!active() || event.getEntityType() != EntityType.ENDERMAN
            || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
            || event.getLocation().getWorld().getEnvironment() != World.Environment.NORMAL) return;
        long time = event.getLocation().getWorld().getTime();
        if (time < 13000 || time > 23000) return;
        int extras = ThreadLocalRandom.current().nextBoolean() ? 2 : 1;
        Location at = event.getLocation();
        Bukkit.getRegionScheduler().run(plugin, at, task -> {
            for (int i = 0; i < extras; i++) at.getWorld().spawnEntity(at, EntityType.ENDERMAN);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBed(PlayerBedEnterEvent event) {
        if (!active() || event.getPlayer().getWorld().getEnvironment() != World.Environment.NORMAL) return;
        if (ThreadLocalRandom.current().nextDouble() >= plugin.getConfig().getDouble("end-opening.seepage-before-first-kill.bed-interrupt-chance", 0.30)) return;
        event.setUseBed(Event.Result.DENY);
        event.setCancelled(true);
        ((Audience) event.getPlayer()).sendMessage(Component.text("Владыки Края не спят...", NamedTextColor.DARK_PURPLE));
        event.getPlayer().spawnParticle(Particle.PORTAL, event.getPlayer().getLocation(), 40);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRiftEndermanDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Enderman enderman)
            || !enderman.getPersistentDataContainer().has(riftMark, PersistentDataType.BYTE)) return;
        event.getDrops().add(new ItemStack(Material.ENDER_PEARL, 2));
    }

    void tick(Player player) {
        if (!active() || player.getWorld().getEnvironment() != World.Environment.NORMAL) return;
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (now >= nextRoar.computeIfAbsent(id, ignored -> now + 12 * 60_000L)) {
            nextRoar.put(id, now + 12 * 60_000L);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.35f, 0.65f);
            player.spawnParticle(Particle.REVERSE_PORTAL, player.getLocation(), 18);
        }
        if (now < nextRift.computeIfAbsent(id, ignored -> now + 90_000L)) return;
        nextRift.put(id, now + 90_000L + ThreadLocalRandom.current().nextLong(60_000L));
        if (player.getWorld().getTime() < 13000 || player.getWorld().getTime() > 23000) return;
        Location origin = player.getLocation();
        PotionEffect oldVision = player.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (oldVision == null || oldVision.getDuration() < 240)
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 240, 0, true, false, false));
        player.playSound(origin, Sound.BLOCK_END_PORTAL_SPAWN, 0.5f, 1.5f);
        player.spawnParticle(Particle.REVERSE_PORTAL, origin.clone().add(0, 1, 0), 50, 1.2, 0.8, 1.2, 0.12);
        Location at = origin.clone().add(ThreadLocalRandom.current().nextInt(-8, 9), 0, ThreadLocalRandom.current().nextInt(-8, 9));
        Bukkit.getRegionScheduler().run(plugin, at, task -> {
            int y = at.getWorld().getHighestBlockYAt(at.getBlockX(), at.getBlockZ());
            if (y <= at.getWorld().getMinHeight() || y >= at.getWorld().getMaxHeight() - 3) return;
            at.setY(y + 1);
            at.getWorld().spawnParticle(Particle.PORTAL, at, 70);
            int count = ThreadLocalRandom.current().nextInt(2, 4);
            for (int i = 0; i < count; i++) {
                Enderman enderman = (Enderman) at.getWorld().spawnEntity(at, EntityType.ENDERMAN);
                enderman.getPersistentDataContainer().set(riftMark, PersistentDataType.BYTE, (byte) 1);
            }
        });
    }
}
