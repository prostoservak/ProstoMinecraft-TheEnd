package ru.prostominecraft.end;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
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
        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(plugin, "seal_" + name().toLowerCase()), item(plugin));
        switch (this) {
            case HEAT -> {
                recipe.shape("BBM", "BTB", "MGM");
                recipe.setIngredient('B', Material.BLAZE_ROD);
                recipe.setIngredient('T', new RecipeChoice.MaterialChoice(Material.POTION));
                recipe.setIngredient('M', Material.MAGMA_BLOCK);
                recipe.setIngredient('G', Material.GHAST_TEAR);
            }
            case DEPTHS -> {
                recipe.shape("KKD", "KHD", "KDD");
                recipe.setIngredient('K', Material.GLOW_INK_SAC);
                recipe.setIngredient('H', Material.HEART_OF_THE_SEA);
                recipe.setIngredient('D', Material.DRIED_KELP_BLOCK);
            }
            case DARKNESS -> {
                recipe.shape("AAS", "ACA", "BBS");
                recipe.setIngredient('A', Material.AMETHYST_SHARD);
                recipe.setIngredient('S', Material.SCULK);
                recipe.setIngredient('C', Material.COMPASS);
                recipe.setIngredient('B', Material.GLOW_BERRIES);
            }
            case BREEZE -> {
                recipe.shape("RRC", "RKC", "RCC");
                recipe.setIngredient('R', Material.BREEZE_ROD);
                recipe.setIngredient('K', Material.TRIAL_KEY);
                recipe.setIngredient('C', Material.COPPER_BLOCK);
            }
            case LIFE -> {
                recipe.shape("MHM", "AEK", "MCM");
                recipe.setIngredient('M', Material.MOSS_BLOCK);
                recipe.setIngredient('H', Material.HONEY_BOTTLE);
                recipe.setIngredient('A', Material.GOLDEN_APPLE);
                recipe.setIngredient('E', Material.EMERALD_BLOCK);
                recipe.setIngredient('K', Material.CAKE);
                recipe.setIngredient('C', Material.GOLDEN_CARROT);
            }
            case THRESHOLD -> {
                recipe.shape("OOP", "OCP", "OPP");
                recipe.setIngredient('O', Material.ENDER_EYE);
                recipe.setIngredient('C', Material.ENDER_CHEST);
                recipe.setIngredient('P', Material.PHANTOM_MEMBRANE);
            }
        }
        Bukkit.addRecipe(recipe);
    }
}
