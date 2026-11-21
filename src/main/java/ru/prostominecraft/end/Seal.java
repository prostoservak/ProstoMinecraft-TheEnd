package ru.prostominecraft.end;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

enum Seal {
    HEAT("Жара", NamedTextColor.GOLD, "Нормальная настойка · 4 стержня ифрита", "Слеза гаста · 3 магма-блока"),
    DEPTHS("Глубин", NamedTextColor.AQUA, "Сердце моря · 4 светящихся мешка", "4 блока сушёной ламинарии"),
    DARKNESS("Тьмы", NamedTextColor.DARK_AQUA, "Компас · 2 скалка · 4 аметиста", "2 светящиеся ягоды"),
    BREEZE("Вихря", NamedTextColor.WHITE, "Ключ испытаний · 4 стержня вихря", "4 медных блока"),
    LIFE("Жизни", NamedTextColor.GREEN, "Золотое яблоко · мёд · изумрудный блок", "Торт · золотая морковь · 4 блока мха"),
    THRESHOLD("Порога", NamedTextColor.DARK_PURPLE, "Эндер-сундук · 4 ока Эндера", "4 мембраны фантома");

    final String title;
    final NamedTextColor color;
    final List<String> ingredients;

    Seal(String title, NamedTextColor color, String first, String second) {
        this.title = title;
        this.color = color;
        this.ingredients = List.of(first, second);
    }

    NamespacedKey recipeKey(EndPlugin plugin) {
        return new NamespacedKey(plugin, "seal_" + name().toLowerCase());
    }

    ItemStack item(EndPlugin plugin) {
        ItemStack item = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("✦ Печать " + title + " ✦", color).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
            Component.text(ingredients.get(0), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text(ingredients.get(1), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(plugin.sealKey(), PersistentDataType.STRING, name());
        meta.setItemModel(new NamespacedKey("prostoservak", "seal_" + name().toLowerCase()));
        item.setItemMeta(meta);
        return item;
    }

    void registerRecipe(EndPlugin plugin) {
        ShapelessRecipe recipe = new ShapelessRecipe(recipeKey(plugin), item(plugin));
        switch (this) {
            case HEAT -> {
                recipe.addIngredient(4, Material.BLAZE_ROD);
                recipe.addIngredient(1, Material.POTION);
                recipe.addIngredient(3, Material.MAGMA_BLOCK);
                recipe.addIngredient(1, Material.GHAST_TEAR);
            }
            case DEPTHS -> {
                recipe.addIngredient(4, Material.GLOW_INK_SAC);
                recipe.addIngredient(1, Material.HEART_OF_THE_SEA);
                recipe.addIngredient(4, Material.DRIED_KELP_BLOCK);
            }
            case DARKNESS -> {
                recipe.addIngredient(4, Material.AMETHYST_SHARD);
                recipe.addIngredient(2, Material.SCULK);
                recipe.addIngredient(1, Material.COMPASS);
                recipe.addIngredient(2, Material.GLOW_BERRIES);
            }
            case BREEZE -> {
                recipe.addIngredient(4, Material.BREEZE_ROD);
                recipe.addIngredient(1, Material.TRIAL_KEY);
                recipe.addIngredient(4, Material.COPPER_BLOCK);
            }
            case LIFE -> {
                recipe.addIngredient(4, Material.MOSS_BLOCK);
                recipe.addIngredient(1, Material.HONEY_BOTTLE);
                recipe.addIngredient(1, Material.GOLDEN_APPLE);
                recipe.addIngredient(1, Material.EMERALD_BLOCK);
                recipe.addIngredient(1, Material.CAKE);
                recipe.addIngredient(1, Material.GOLDEN_CARROT);
            }
            case THRESHOLD -> {
                recipe.addIngredient(4, Material.ENDER_EYE);
                recipe.addIngredient(1, Material.ENDER_CHEST);
                recipe.addIngredient(4, Material.PHANTOM_MEMBRANE);
            }
        }
        Bukkit.addRecipe(recipe);
    }
}
