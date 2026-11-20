package ru.prostominecraft.end;

import java.util.List;
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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;

final class EndCrafting implements Listener {
    private final EndPlugin plugin;
    private final EndItems items;
    private final NamespacedKey brewUntil;
    private static final NamespacedKey ORIGINAL_FRUIT = new NamespacedKey("prostominecraft-customitems", "yggdrasil_fruit");

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
        ShapedRecipe wings = new ShapedRecipe(new NamespacedKey(plugin, "end_wings"), items.make("end_wings", 1));
        wings.shape("PBP", "CEC", "RDR");
        wings.setIngredient('P', Material.PHANTOM_MEMBRANE);
        wings.setIngredient('B', Material.DRAGON_BREATH);
        wings.setIngredient('C', Material.NETHERITE_SCRAP);
        wings.setIngredient('E', Material.ELYTRA);
        wings.setIngredient('R', Material.BREEZE_ROD);
        wings.setIngredient('D', Material.DRAGON_BREATH);
        Bukkit.addRecipe(wings);
        ShapelessRecipe fruit = new ShapelessRecipe(new NamespacedKey(plugin, "yggdrasil_fruit_empowered"), items.make("yggdrasil_fruit_empowered", 1));
        fruit.addIngredient(Material.APPLE);
        fruit.addIngredient(Material.DRAGON_BREATH);
        fruit.addIngredient(Material.DRAGON_BREATH);
        Bukkit.addRecipe(fruit);
        upgradeRecipe("void_needle", Material.SPYGLASS, "EBD", "RNR", "EDI", 'E', Material.ECHO_SHARD, 'R', Material.BLAZE_ROD, 'D', Material.DRAGON_BREATH, 'I', Material.NETHERITE_INGOT);
        upgradeRecipe("dragon_claw", Material.BREEZE_ROD, "RBD", "PCP", "RDI", 'R', Material.BREEZE_ROD, 'P', Material.PHANTOM_MEMBRANE, 'D', Material.DRAGON_BREATH, 'I', Material.NETHERITE_INGOT);
        upgradeRecipe("sky_loop", Material.FEATHER, "DBD", "PSP", "HFI", 'D', Material.DRAGON_BREATH, 'P', Material.PHANTOM_MEMBRANE, 'H', Material.HEART_OF_THE_SEA, 'F', Material.POPPED_CHORUS_FRUIT, 'I', Material.NETHERITE_INGOT);
        for (String kind : List.of("void_needle", "dragon_claw", "sky_loop")) {
            Material base = items.make(kind, 1).getType();
            for (String ingredient : List.of("dragon_chitin", "dragon_blood")) {
                ShapelessRecipe repair = new ShapelessRecipe(new NamespacedKey(plugin, "repair_" + kind + "_" + ingredient), items.make(kind, 1));
                repair.addIngredient(base);
                repair.addIngredient(items.make(ingredient, 1).getType());
                Bukkit.addRecipe(repair);
            }
        }
    }

    private void upgradeRecipe(String kind, Material base, String top, String middle, String bottom,
                               Object... ingredients) {
        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(plugin, "upgrade_" + kind), items.upgrade(items.make(kind, 1)));
        recipe.shape(top, middle, bottom);
        recipe.setIngredient(kind.equals("void_needle") ? 'N' : kind.equals("dragon_claw") ? 'C' : 'S', base);
        recipe.setIngredient('B', Material.DRAGON_BREATH);
        for (int i = 0; i < ingredients.length; i += 2) recipe.setIngredient((char) ingredients[i], (Material) ingredients[i + 1]);
        Bukkit.addRecipe(recipe);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepare(PrepareItemCraftEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        String type = recipeType(event.getRecipe());
        if (type != null && type.startsWith("upgrade_")) {
            event.getInventory().setResult(valid(event.getRecipe(), matrix) ? items.upgrade(matrix[4]) : null);
            return;
        }
        if ("end_wings".equals(type)) {
            event.getInventory().setResult(valid(event.getRecipe(), matrix) ? items.wings(matrix[4]) : null);
            return;
        }
        if (type != null && type.startsWith("repair_")) {
            ItemStack original = artifactIn(matrix);
            event.getInventory().setResult(valid(event.getRecipe(), matrix)
                ? items.repair(original, type.endsWith("_dragon_blood")) : null);
            return;
        }
        if (!valid(event.getRecipe(), matrix)) event.getInventory().setResult(null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!valid(event.getRecipe(), event.getInventory().getMatrix())) event.setCancelled(true);
    }

    private boolean valid(Recipe recipe, ItemStack[] matrix) {
        String type = recipeType(recipe);
        if (type == null) return true;
        if (type.equals("dragon_chitin")) return count(matrix, "dragon_scale") == 4;
        if (type.equals("dragon_blood")) return count(matrix, "dragon_chitin") == 4;
        if (type.equals("empowered_end_crystal")) return count(matrix, "dragon_chitin") == 2;
        if (type.equals("end_wings")) return matrix.length >= 9 && matrix[4] != null && matrix[4].getType() == Material.ELYTRA
            && !items.is(matrix[4], "end_wings") && items.is(matrix[1], "dragon_blood")
            && count(matrix, "dragon_chitin") == 2;
        if (type.equals("yggdrasil_fruit_empowered")) {
            boolean original = false;
            for (ItemStack stack : matrix) if (stack != null && stack.hasItemMeta()
                && stack.getItemMeta().getPersistentDataContainer().has(ORIGINAL_FRUIT, PersistentDataType.BYTE)) original = true;
            return original && count(matrix, "dragon_blood") == 1;
        }
        if (type.startsWith("upgrade_")) {
            String kind = type.substring("upgrade_".length());
            return matrix.length >= 9 && items.is(matrix[4], kind) && !items.upgraded(matrix[4])
                && items.is(matrix[1], "dragon_blood");
        }
        if (type.startsWith("repair_")) {
            String kind = type.substring("repair_".length()).replace("_dragon_chitin", "").replace("_dragon_blood", "");
            String ingredient = type.endsWith("_dragon_blood") ? "dragon_blood" : "dragon_chitin";
            ItemStack original = artifactIn(matrix);
            return original != null && items.is(original, kind) && count(matrix, ingredient) == 1
                && items.charges(original) < maxCharges(original);
        }
        if (type.equals("chorus_brew")) {
            for (ItemStack stack : matrix) {
                if (stack != null && stack.getType() == Material.POTION) {
                    if (!(stack.getItemMeta() instanceof PotionMeta meta) || meta.getBasePotionType() != PotionType.WATER) return false;
                }
            }
        }
        return true;
    }

    private String recipeType(Recipe recipe) {
        return recipe instanceof org.bukkit.Keyed keyed && keyed.getKey().getNamespace().equals(plugin.getName().toLowerCase())
            ? keyed.getKey().getKey() : null;
    }

    private ItemStack artifactIn(ItemStack[] matrix) {
        for (ItemStack stack : matrix) if (items.is(stack, "void_needle") || items.is(stack, "dragon_claw") || items.is(stack, "sky_loop")) return stack;
        return null;
    }

    private int maxCharges(ItemStack stack) {
        return stack.getItemMeta().getPersistentDataContainer().getOrDefault(
            new NamespacedKey(plugin, "end_max_charges"), PersistentDataType.INTEGER, 0);
    }

    private int count(ItemStack[] matrix, String kind) {
        int n = 0;
        for (ItemStack stack : matrix) if (items.is(stack, kind)) n++;
        return n;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (items.is(event.getItem(), "yggdrasil_fruit_empowered")) {
            AttributeInstance health = event.getPlayer().getAttribute(Attribute.MAX_HEALTH);
            if (health == null || health.getBaseValue() >= 28) {
                event.setCancelled(true);
                ((Audience) event.getPlayer()).sendMessage(Component.text("Душа достигла предела: 28 HP.", NamedTextColor.RED));
            } else {
                health.setBaseValue(Math.min(28, health.getBaseValue() + 1));
                ((Audience) event.getPlayer()).sendMessage(Component.text("Плод Иггдрасиля укрепил душу. +1 к максимальному HP.", NamedTextColor.GREEN));
            }
            return;
        }
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
