package ru.prostominecraft.end;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

final class EndCrafting implements Listener {
    private final EndPlugin plugin;
    private final EndItems items;
    private final NamespacedKey brewUntil;
    private final List<NamespacedKey> registeredKeys = new ArrayList<>();
    private static final NamespacedKey ORIGINAL_FRUIT = new NamespacedKey("prostominecraft-customitems", "yggdrasil_fruit");

    EndCrafting(EndPlugin plugin, EndItems items) {
        this.plugin = plugin;
        this.items = items;
        brewUntil = new NamespacedKey(plugin, "chorus_brew_until");
    }

    List<NamespacedKey> recipeKeys() {
        return List.copyOf(registeredKeys);
    }

    private void addRecipe(ShapelessRecipe recipe) {
        Bukkit.addRecipe(recipe);
        registeredKeys.add(recipe.getKey());
    }

    void registerRecipes() {
        ShapelessRecipe chitin = new ShapelessRecipe(new NamespacedKey(plugin, "dragon_chitin"), items.make("dragon_chitin", 1));
        chitin.addIngredient(4, Material.PHANTOM_MEMBRANE);
        addRecipe(chitin);

        ShapelessRecipe blood = new ShapelessRecipe(new NamespacedKey(plugin, "dragon_blood"), items.make("dragon_blood", 1));
        blood.addIngredient(4, Material.NETHERITE_SCRAP);
        addRecipe(blood);

        ShapelessRecipe brew = new ShapelessRecipe(new NamespacedKey(plugin, "chorus_brew"), items.make("chorus_brew", 1));
        brew.addIngredient(Material.POTION);
        brew.addIngredient(Material.POPPED_CHORUS_FRUIT);
        brew.addIngredient(Material.GOLDEN_CARROT);
        brew.addIngredient(Material.BLAZE_POWDER);
        addRecipe(brew);

        ShapelessRecipe crystal = new ShapelessRecipe(new NamespacedKey(plugin, "empowered_end_crystal"), items.make("empowered_end_crystal", 1));
        crystal.addIngredient(1, Material.END_CRYSTAL);
        crystal.addIngredient(2, Material.NETHERITE_SCRAP);
        addRecipe(crystal);

        ShapelessRecipe wings = new ShapelessRecipe(new NamespacedKey(plugin, "end_wings"), items.make("end_wings", 1));
        wings.addIngredient(1, Material.ELYTRA);
        wings.addIngredient(2, Material.DRAGON_BREATH); // 1x dragon_blood + 1x vanilla dragon breath
        wings.addIngredient(2, Material.NETHERITE_SCRAP); // 2x dragon_chitin
        wings.addIngredient(2, Material.PHANTOM_MEMBRANE);
        wings.addIngredient(2, Material.BREEZE_ROD);
        addRecipe(wings);

        ShapelessRecipe fruit = new ShapelessRecipe(new NamespacedKey(plugin, "yggdrasil_fruit_empowered"), items.make("yggdrasil_fruit_empowered", 1));
        fruit.addIngredient(Material.APPLE);
        fruit.addIngredient(2, Material.DRAGON_BREATH); // 1x dragon_blood + 1x vanilla dragon breath
        addRecipe(fruit);

        // Artifact upgrades (all 9-slot shapeless, verified strictly in valid())
        ShapelessRecipe upgNeedle = new ShapelessRecipe(new NamespacedKey(plugin, "upgrade_void_needle"), items.upgrade(items.make("void_needle", 1)));
        upgNeedle.addIngredient(1, Material.SPYGLASS);
        upgNeedle.addIngredient(3, Material.DRAGON_BREATH); // 1x dragon_blood + 2x vanilla
        upgNeedle.addIngredient(2, Material.ECHO_SHARD);
        upgNeedle.addIngredient(2, Material.BLAZE_ROD);
        upgNeedle.addIngredient(1, Material.NETHERITE_INGOT);
        addRecipe(upgNeedle);

        ShapelessRecipe upgClaw = new ShapelessRecipe(new NamespacedKey(plugin, "upgrade_dragon_claw"), items.upgrade(items.make("dragon_claw", 1)));
        upgClaw.addIngredient(3, Material.BREEZE_ROD); // 1x dragon_claw + 2x vanilla breeze rods
        upgClaw.addIngredient(3, Material.DRAGON_BREATH); // 1x dragon_blood + 2x vanilla
        upgClaw.addIngredient(2, Material.PHANTOM_MEMBRANE);
        upgClaw.addIngredient(1, Material.NETHERITE_INGOT);
        addRecipe(upgClaw);

        ShapelessRecipe upgLoop = new ShapelessRecipe(new NamespacedKey(plugin, "upgrade_sky_loop"), items.upgrade(items.make("sky_loop", 1)));
        upgLoop.addIngredient(1, Material.FEATHER);
        upgLoop.addIngredient(3, Material.DRAGON_BREATH); // 1x dragon_blood + 2x vanilla
        upgLoop.addIngredient(2, Material.PHANTOM_MEMBRANE);
        upgLoop.addIngredient(1, Material.HEART_OF_THE_SEA);
        upgLoop.addIngredient(1, Material.POPPED_CHORUS_FRUIT);
        upgLoop.addIngredient(1, Material.NETHERITE_INGOT);
        addRecipe(upgLoop);

        for (String kind : List.of("void_needle", "dragon_claw", "sky_loop")) {
            Material base = items.make(kind, 1).getType();
            for (String ingredient : List.of("dragon_chitin", "dragon_blood")) {
                ShapelessRecipe repair = new ShapelessRecipe(new NamespacedKey(plugin, "repair_" + kind + "_" + ingredient), items.make(kind, 1));
                repair.addIngredient(base);
                repair.addIngredient(items.make(ingredient, 1).getType());
                addRecipe(repair);
            }
        }
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        String type = recipeType(event.getRecipe());
        if (type == null) return;
        if (type.startsWith("upgrade_")) {
            String kind = type.substring("upgrade_".length());
            ItemStack base = findKind(matrix, kind);
            event.getInventory().setResult(base != null && valid(event.getRecipe(), matrix) ? items.upgrade(base) : null);
            return;
        }
        if ("end_wings".equals(type)) {
            ItemStack elytra = findVanillaElytra(matrix);
            event.getInventory().setResult(elytra != null && valid(event.getRecipe(), matrix) ? items.wings(elytra) : null);
            return;
        }
        if (type.startsWith("repair_")) {
            ItemStack original = artifactIn(matrix);
            event.getInventory().setResult(original != null && valid(event.getRecipe(), matrix)
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
        if (type.equals("empowered_end_crystal")) {
            return count(matrix, "dragon_chitin") == 2 && count(matrix, "empowered_end_crystal") == 0;
        }
        if (type.equals("end_wings")) {
            return findVanillaElytra(matrix) != null
                && count(matrix, "dragon_blood") == 1
                && count(matrix, "dragon_chitin") == 2
                && count(matrix, "dragon_scale") == 0
                && count(matrix, "dragon_claw") == 0;
        }
        if (type.equals("yggdrasil_fruit_empowered")) {
            return hasOriginalYggdrasil(matrix) && count(matrix, "dragon_blood") == 1;
        }
        if (type.startsWith("upgrade_")) {
            String kind = type.substring("upgrade_".length());
            ItemStack base = findKind(matrix, kind);
            return base != null && !items.upgraded(base)
                && count(matrix, kind) == 1
                && count(matrix, "dragon_blood") == 1
                && count(matrix, "dragon_scale") == 0;
        }
        if (type.startsWith("repair_")) {
            String kind = type.substring("repair_".length()).replace("_dragon_chitin", "").replace("_dragon_blood", "");
            String ingredient = type.endsWith("_dragon_blood") ? "dragon_blood" : "dragon_chitin";
            ItemStack original = artifactIn(matrix);
            return original != null && items.is(original, kind) && count(matrix, ingredient) == 1
                && items.charges(original) < items.maxCharges(original);
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

    private boolean hasOriginalYggdrasil(ItemStack[] matrix) {
        for (ItemStack stack : matrix) {
            if (stack == null || stack.getType() != Material.APPLE || !stack.hasItemMeta()) continue;
            if (items.is(stack, "yggdrasil_fruit_empowered")) continue;
            var meta = stack.getItemMeta();
            if (meta.getPersistentDataContainer().has(ORIGINAL_FRUIT, PersistentDataType.BYTE)) return true;
            if (plainDisplay(meta.displayName()).contains("иггдрасил")) return true;
        }
        return false;
    }

    private String plainDisplay(Component component) {
        if (component == null) return "";
        StringBuilder out = new StringBuilder();
        appendPlain(component, out);
        return out.toString().toLowerCase(Locale.ROOT);
    }

    private void appendPlain(Component component, StringBuilder out) {
        if (component instanceof TextComponent text) out.append(text.content());
        for (Component child : component.children()) appendPlain(child, out);
    }

    private String recipeType(Recipe recipe) {
        return recipe instanceof org.bukkit.Keyed keyed && keyed.getKey().getNamespace().equals(plugin.getName().toLowerCase())
            ? keyed.getKey().getKey() : null;
    }

    private ItemStack findKind(ItemStack[] matrix, String kind) {
        for (ItemStack stack : matrix) if (items.is(stack, kind)) return stack;
        return null;
    }

    private ItemStack findVanillaElytra(ItemStack[] matrix) {
        for (ItemStack stack : matrix) {
            if (stack != null && stack.getType() == Material.ELYTRA && !items.is(stack, "end_wings")) return stack;
        }
        return null;
    }

    private ItemStack artifactIn(ItemStack[] matrix) {
        for (ItemStack stack : matrix) {
            if (items.is(stack, "void_needle") || items.is(stack, "dragon_claw") || items.is(stack, "sky_loop")) return stack;
        }
        return null;
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

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack armor = event.getInventory().getInputEquipment();
        ItemStack mineral = event.getInventory().getInputMineral();
        if (event.getInventory().getInputTemplate() != null || !items.is(mineral, "dragon_chitin") || !isArmor(armor) || items.stitched(armor)) return;
        event.setResult(items.stitch(armor));
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmith(SmithItemEvent event) {
        ItemStack armor = event.getInventory().getInputEquipment();
        ItemStack mineral = event.getInventory().getInputMineral();
        if (event.getInventory().getInputTemplate() == null && items.is(mineral, "dragon_chitin") && isArmor(armor) && !items.stitched(armor)) {
            event.getInventory().setResult(items.stitch(armor));
        }
    }

    private boolean isArmor(ItemStack stack) {
        if (stack == null) return false;
        String name = stack.getType().name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }
}
