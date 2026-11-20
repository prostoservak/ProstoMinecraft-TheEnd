package ru.prostominecraft.end;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class EndArtifacts implements Listener {
    private final EndPlugin plugin;
    private final EndItems items;
    private final NamespacedKey rescueUntil;
    private final NamespacedKey falling;
    private final NamespacedKey noFallUntil;
    private final Map<UUID, Long> clawCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> needleCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rocketCooldown = new ConcurrentHashMap<>();

    EndArtifacts(EndPlugin plugin, EndItems items) {
        this.plugin = plugin;
        this.items = items;
        rescueUntil = new NamespacedKey(plugin, "void_rescue_cooldown_until");
        falling = new NamespacedKey(plugin, "void_rescue_falling");
        noFallUntil = new NamespacedKey(plugin, "claw_no_fall_until");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getItem() == null
            || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (items.is(held, "dragon_claw")) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            if (now < clawCooldown.getOrDefault(player.getUniqueId(), 0L)) return;
            if (!items.useCharge(held)) { depleted(player); return; }
            player.getInventory().setItemInMainHand(held);
            clawCooldown.put(player.getUniqueId(), now + 10_000);
            player.getPersistentDataContainer().set(noFallUntil, PersistentDataType.LONG, now + 4000);
            Vector direction = player.getLocation().getDirection().normalize().multiply(1.85);
            player.setVelocity(direction.add(new Vector(0, 0.38, 0)));
            ((Audience) player).sendActionBar(Component.text("Коготь дракона", NamedTextColor.LIGHT_PURPLE));
        } else if (items.is(held, "void_needle")) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            if (now < needleCooldown.getOrDefault(player.getUniqueId(), 0L)) return;
            Entity target = player.getTargetEntity(35, false);
            if (!(target instanceof LivingEntity living) || target == player) return;
            if (!items.useCharge(held)) { depleted(player); return; }
            player.getInventory().setItemInMainHand(held);
            needleCooldown.put(player.getUniqueId(), now + 8000);
            living.getScheduler().run(plugin, task -> {
                if (!living.isDead() && living.isValid()) {
                    living.damage(8);
                    living.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 200, 0));
                }
            }, () -> {});
            ((Audience) player).sendActionBar(Component.text("Игла бездны", NamedTextColor.DARK_PURPLE));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
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
        ((Audience) player).sendActionBar(Component.text("Артефакт истощён", NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.FLY_INTO_WALL
            && items.is(player.getInventory().getChestplate(), "end_wings")) event.setDamage(event.getDamage() * 0.5);
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
        if (items.is(event.getItem(), "sky_loop")) { event.setCancelled(true); return; }
        if (items.is(event.getItem(), "void_needle") || items.is(event.getItem(), "dragon_claw"))
            items.mend(event.getItem(), event.getRepairAmount());
    }

    private boolean artifact(ItemStack stack) {
        return items.is(stack, "void_needle") || items.is(stack, "dragon_claw") || items.is(stack, "sky_loop");
    }

    void tick(Player player) {
        if (player.getPersistentDataContainer().has(falling, PersistentDataType.BYTE)
            && (player.isOnGround() || player.isInWater())) {
            player.getPersistentDataContainer().remove(falling);
            player.removePotionEffect(PotionEffectType.SLOW_FALLING);
        }
        if (player.isDead()) return;
        World world = player.getWorld();
        double threshold = world.getEnvironment() == World.Environment.THE_END ? -12 : world.getMinHeight() - 12;
        if (player.getLocation().getY() >= threshold) return;
        long now = System.currentTimeMillis();
        long until = player.getPersistentDataContainer().getOrDefault(rescueUntil, PersistentDataType.LONG, 0L);
        if (now < until) return;
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean offhand = false;
        if (!items.is(hand, "sky_loop")) {
            hand = player.getInventory().getItemInOffHand();
            offhand = true;
        }
        if (!items.is(hand, "sky_loop")) return;
        if (items.charges(hand) < 1) {
            ((Audience) player).sendActionBar(Component.text("Небесная петля истощена", NamedTextColor.RED));
            player.getPersistentDataContainer().set(rescueUntil, PersistentDataType.LONG, now + 3000);
            return;
        }
        items.useCharge(hand);
        if (offhand) player.getInventory().setItemInOffHand(hand);
        else player.getInventory().setItemInMainHand(hand);
        player.getPersistentDataContainer().set(rescueUntil, PersistentDataType.LONG, now + 3000);
        player.getPersistentDataContainer().set(falling, PersistentDataType.BYTE, (byte) 1);
        Location from = player.getLocation();
        Location safe = from.clone();
        safe.setY(100);
        player.teleportAsync(safe).whenComplete((success, error) -> player.getScheduler().run(plugin, task -> {
            if (error != null || !Boolean.TRUE.equals(success)) {
                player.getPersistentDataContainer().remove(falling);
                return;
            }
            player.setVelocity(new Vector(0, 0, 0));
            player.setFallDistance(0);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 180, 0, false, true));
            ((Audience) player).sendActionBar(Component.text("Небесная петля спасла Вас от пустоты", NamedTextColor.AQUA));
        }, () -> {}));
    }
}
