package ru.prostominecraft.end;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

final class EndCrafting implements Listener {
    private final EndPlugin plugin;
    private final EndItems items;
    private final NamespacedKey brewUntil;

    EndCrafting(EndPlugin plugin, EndItems items) {
        this.plugin = plugin;
        this.items = items;
        brewUntil = new NamespacedKey(plugin, "chorus_brew_until");
    }

    void registerRecipes() {
        ShapedRecipe chitin = new ShapedRecipe(new NamespacedKey(plugin, "dragon_chitin"), items.make("dragon_chitin", 1));
        chitin.shape("SS", "SS");
        chitin.setIngredient('S', Material.PHANTOM_MEMBRANE);
        Bukkit.addRecipe(chitin);
        ShapedRecipe blood = new ShapedRecipe(new NamespacedKey(plugin, "dragon_blood"), items.make("dragon_blood", 1));
        blood.shape("CC", "CC");
        blood.setIngredient('C', Material.NETHERITE_SCRAP);
        Bukkit.addRecipe(blood);
        ShapelessRecipe brew = new ShapelessRecipe(new NamespacedKey(plugin, "chorus_brew"), items.make("chorus_brew", 1));
        brew.addIngredient(Material.POTION);
        brew.addIngredient(Material.POPPED_CHORUS_FRUIT);
        brew.addIngredient(Material.GOLDEN_CARROT);
        brew.addIngredient(Material.BLAZE_POWDER);
        Bukkit.addRecipe(brew);
        ShapedRecipe crystal = new ShapedRecipe(new NamespacedKey(plugin, "empowered_end_crystal"), items.make("empowered_end_crystal", 1));
        crystal.shape("C", "E", "C");
        crystal.setIngredient('C', Material.NETHERITE_SCRAP);
        crystal.setIngredient('E', Material.END_CRYSTAL);
        Bukkit.addRecipe(crystal);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepare(PrepareItemCraftEvent event) {
        if (!valid(event.getRecipe(), event.getInventory().getMatrix())) event.getInventory().setResult(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!valid(event.getRecipe(), event.getInventory().getMatrix())) event.setCancelled(true);
    }

    private boolean valid(Recipe recipe, ItemStack[] matrix) {
        if (!(recipe instanceof org.bukkit.Keyed keyed) || !keyed.getKey().getNamespace().equals(plugin.getName().toLowerCase())) return true;
        String type = keyed.getKey().getKey();
        if (type.equals("dragon_chitin")) return count(matrix, "dragon_scale") == 4;
        if (type.equals("dragon_blood")) return count(matrix, "dragon_chitin") == 4;
        if (type.equals("empowered_end_crystal")) return count(matrix, "dragon_chitin") == 2;
        if (type.equals("chorus_brew")) {
            for (ItemStack stack : matrix) {
                if (stack != null && stack.getType() == Material.POTION) {
                    if (!(stack.getItemMeta() instanceof PotionMeta meta) || meta.getBasePotionType() != PotionType.WATER) return false;
                }
            }
        }
        return true;
    }

    private int count(ItemStack[] matrix, String kind) {
        int n = 0;
        for (ItemStack stack : matrix) if (items.is(stack, kind)) n++;
        return n;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!items.is(event.getItem(), "chorus_brew")) return;
        long now = System.currentTimeMillis();
        long previous = event.getPlayer().getPersistentDataContainer().getOrDefault(brewUntil, PersistentDataType.LONG, 0L);
        long duration = Math.max(1, plugin.getConfig().getInt("end-debuff.chorus-brew.duration-seconds", 600)) * 1000L;
        long until = Math.max(now, previous) + duration;
        event.getPlayer().getPersistentDataContainer().set(brewUntil, PersistentDataType.LONG, until);
        ((Audience) event.getPlayer()).sendMessage(Component.text("Защита от холода Края усилена. Осталось " + ((until - now) / 1000) + " сек.", NamedTextColor.AQUA));
    }

    double brewReduction(org.bukkit.entity.Player player) {
        Long until = player.getPersistentDataContainer().get(brewUntil, PersistentDataType.LONG);
        return until != null && until > System.currentTimeMillis()
            ? plugin.getConfig().getDouble("end-debuff.chorus-brew.reduction", 0.5) : 0.0;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack armor = event.getInventory().getInputEquipment();
        ItemStack mineral = event.getInventory().getInputMineral();
        if (event.getInventory().getInputTemplate() != null || !items.is(mineral, "dragon_chitin") || !isArmor(armor) || items.stitched(armor)) return;
        event.setResult(items.stitch(armor));
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmith(SmithItemEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result == null || !items.stitched(result)) return;
        if (!items.is(event.getInventory().getInputMineral(), "dragon_chitin")
            || !isArmor(event.getInventory().getInputEquipment())
            || event.getInventory().getInputTemplate() != null) event.setCancelled(true);
    }

    private boolean isArmor(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        String material = stack.getType().name();
        return material.endsWith("_HELMET") || material.endsWith("_CHESTPLATE")
            || material.endsWith("_LEGGINGS") || material.endsWith("_BOOTS");
    }
}
