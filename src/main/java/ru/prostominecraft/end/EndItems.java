package ru.prostominecraft.end;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

final class EndItems {
    private static final String NAMESPACE = "prostoservak";
    private final NamespacedKey kindKey;
    private final NamespacedKey chargesKey;
    private final NamespacedKey maxChargesKey;
    private final NamespacedKey chitinArmorKey;

    EndItems(EndPlugin plugin) {
        kindKey = new NamespacedKey(plugin, "item_kind");
        chargesKey = new NamespacedKey(plugin, "end_charges");
        maxChargesKey = new NamespacedKey(plugin, "end_max_charges");
        chitinArmorKey = new NamespacedKey(plugin, "chitin_stitched");
    }

    ItemStack make(String kind, int amount) {
        Material material = switch (kind) {
            case "dragon_scale" -> Material.PHANTOM_MEMBRANE;
            case "dragon_chitin" -> Material.NETHERITE_SCRAP;
            case "dragon_blood" -> Material.DRAGON_BREATH;
            case "chorus_brew" -> Material.POTION;
            case "empowered_end_crystal" -> Material.END_CRYSTAL;
            case "void_needle" -> Material.SPYGLASS;
            case "dragon_claw" -> Material.BREEZE_ROD;
            case "sky_loop" -> Material.FEATHER;
            case "end_wings" -> Material.ELYTRA;
            default -> throw new IllegalArgumentException(kind);
        };
        ItemStack stack = new ItemStack(material, amount);
        ItemMeta meta = stack.getItemMeta();
        String name = switch (kind) {
            case "dragon_scale" -> "Чешуя дракона";
            case "dragon_chitin" -> "Драконий хитин";
            case "dragon_blood" -> "Кровь дракона";
            case "chorus_brew" -> "Отвар Хоруса";
            case "empowered_end_crystal" -> "Усиленный кристалл Энда";
            case "void_needle" -> "Игла бездны";
            case "dragon_claw" -> "Коготь дракона";
            case "sky_loop" -> "Небесная петля";
            case "end_wings" -> "Крылья Края";
            default -> kind;
        };
        NamedTextColor color = kind.startsWith("dragon_") ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.AQUA;
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.setItemModel(new NamespacedKey(NAMESPACE, kind));
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, kind);
        int charges = switch (kind) {
            case "void_needle" -> 20;
            case "dragon_claw" -> 24;
            case "sky_loop" -> 8;
            default -> 0;
        };
        if (charges > 0) {
            meta.getPersistentDataContainer().set(chargesKey, PersistentDataType.INTEGER, charges);
            meta.getPersistentDataContainer().set(maxChargesKey, PersistentDataType.INTEGER, charges);
            meta.lore(List.of(Component.text("Заряды: " + charges + "/" + charges, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    boolean is(ItemStack stack, String kind) {
        return stack != null && stack.hasItemMeta() && kind.equals(stack.getItemMeta().getPersistentDataContainer().get(kindKey, PersistentDataType.STRING));
    }

    ItemStack stitch(ItemStack armor) {
        ItemStack result = armor.clone();
        ItemMeta meta = result.getItemMeta();
        meta.getPersistentDataContainer().set(chitinArmorKey, PersistentDataType.BYTE, (byte) 1);
        List<Component> lore = new java.util.ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.add(Component.text("Защита от пустоты: -20%", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        result.setItemMeta(meta);
        return result;
    }

    boolean stitched(ItemStack armor) {
        return armor != null && armor.hasItemMeta()
            && armor.getItemMeta().getPersistentDataContainer().has(chitinArmorKey, PersistentDataType.BYTE);
    }

    double armorReduction(org.bukkit.entity.Player player) {
        int pieces = 0;
        for (ItemStack armor : player.getInventory().getArmorContents()) if (stitched(armor) || is(armor, "end_wings")) pieces++;
        return Math.min(0.8, pieces * 0.2);
    }
}
