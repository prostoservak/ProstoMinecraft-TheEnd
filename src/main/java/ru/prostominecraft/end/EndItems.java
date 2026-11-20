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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.meta.Damageable;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.concurrent.ThreadLocalRandom;

final class EndItems {
    private static final String NAMESPACE = "prostoservak";
    private final NamespacedKey kindKey;
    private final NamespacedKey chargesKey;
    private final NamespacedKey maxChargesKey;
    private final NamespacedKey chitinArmorKey;
    private final NamespacedKey upgradedKey;

    EndItems(EndPlugin plugin) {
        kindKey = new NamespacedKey(plugin, "item_kind");
        chargesKey = new NamespacedKey(plugin, "end_charges");
        maxChargesKey = new NamespacedKey(plugin, "end_max_charges");
        chitinArmorKey = new NamespacedKey(plugin, "chitin_stitched");
        upgradedKey = new NamespacedKey(plugin, "end_upgraded");
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
            case "yggdrasil_fruit_empowered" -> Material.APPLE;
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
            case "yggdrasil_fruit_empowered" -> "Усиленный плод Иггдрасиля";
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
        if (charges > 0) updateChargeDisplay(stack, charges, charges, kind);
        return stack;
    }

    int charges(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return 0;
        return stack.getItemMeta().getPersistentDataContainer().getOrDefault(chargesKey, PersistentDataType.INTEGER, 0);
    }

    boolean useCharge(ItemStack stack) {
        int current = charges(stack);
        if (current < 1) return false;
        int level = stack.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (level > 0 && ThreadLocalRandom.current().nextInt(level + 1) != 0) return true;
        ItemMeta meta = stack.getItemMeta();
        int max = meta.getPersistentDataContainer().getOrDefault(maxChargesKey, PersistentDataType.INTEGER, current);
        String kind = meta.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        meta.getPersistentDataContainer().set(chargesKey, PersistentDataType.INTEGER, current - 1);
        meta.lore(List.of(Component.text("Заряды: " + (current - 1) + "/" + max, NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false)));
        stack.setItemMeta(meta);
        updateChargeDisplay(stack, current - 1, max, kind);
        return true;
    }

    ItemStack upgrade(ItemStack original) {
        String kind = original.getItemMeta().getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        int max = switch (kind) {
            case "void_needle" -> 32;
            case "dragon_claw" -> 36;
            case "sky_loop" -> 14;
            default -> throw new IllegalArgumentException("Not an artifact: " + kind);
        };
        ItemStack result = original.clone();
        ItemMeta meta = result.getItemMeta();
        meta.getPersistentDataContainer().set(upgradedKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(maxChargesKey, PersistentDataType.INTEGER, max);
        meta.getPersistentDataContainer().set(chargesKey, PersistentDataType.INTEGER, max);
        meta.lore(List.of(Component.text("Заряды: " + max + "/" + max, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        result.setItemMeta(meta);
        updateChargeDisplay(result, max, max, kind);
        return result;
    }

    ItemStack repair(ItemStack original, boolean blood) {
        String kind = original.getItemMeta().getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        int current = charges(original);
        int max = original.getItemMeta().getPersistentDataContainer().getOrDefault(maxChargesKey, PersistentDataType.INTEGER, current);
        int amount = switch (kind) {
            case "void_needle" -> 5;
            case "dragon_claw" -> 6;
            case "sky_loop" -> 2;
            default -> throw new IllegalArgumentException("Not an artifact: " + kind);
        };
        int newCharge = blood ? max : Math.min(max, current + amount);
        ItemStack result = original.clone();
        ItemMeta meta = result.getItemMeta();
        meta.getPersistentDataContainer().set(chargesKey, PersistentDataType.INTEGER, newCharge);
        meta.lore(List.of(Component.text("Заряды: " + newCharge + "/" + max, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        result.setItemMeta(meta);
        updateChargeDisplay(result, newCharge, max, kind);
        return result;
    }

    ItemStack wings(ItemStack elytra) {
        ItemStack result = elytra.clone();
        ItemMeta meta = result.getItemMeta();
        meta.displayName(Component.text("Крылья Края", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.ITALIC, false));
        meta.setItemModel(new NamespacedKey(NAMESPACE, "end_wings"));
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "end_wings");
        meta.setFireResistant(true);
        if (meta instanceof Damageable damageable) damageable.setMaxDamage(650);
        var equippable = meta.getEquippable();
        equippable.setModel(new NamespacedKey(NAMESPACE, "end_wings"));
        meta.setEquippable(equippable);
        meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(new NamespacedKey(NAMESPACE, "end_wings_armor"), 4,
            AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.CHEST));
        meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(new NamespacedKey(NAMESPACE, "end_wings_toughness"), 1,
            AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.CHEST));
        meta.lore(List.of(Component.text("Защита от пустоты: -20%", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false)));
        result.setItemMeta(meta);
        return result;
    }

    boolean upgraded(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
            && stack.getItemMeta().getPersistentDataContainer().has(upgradedKey, PersistentDataType.BYTE);
    }

    void mend(ItemStack stack, int repaired) {
        int current = charges(stack);
        int max = stack.getItemMeta().getPersistentDataContainer().getOrDefault(maxChargesKey, PersistentDataType.INTEGER, current);
        int result = Math.min(max, current + Math.max(0, repaired));
        ItemMeta meta = stack.getItemMeta();
        String kind = meta.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        meta.getPersistentDataContainer().set(chargesKey, PersistentDataType.INTEGER, result);
        meta.lore(List.of(Component.text("Заряды: " + result + "/" + max, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.setItemModel(new NamespacedKey(NAMESPACE, result == 0 ? kind + "_depleted" : upgraded(stack) ? kind + "_upgraded" : kind));
        stack.setItemMeta(meta);
    }

    private void updateChargeDisplay(ItemStack stack, int current, int max, String kind) {
        stack.setData(DataComponentTypes.MAX_DAMAGE, max + 1);
        stack.setData(DataComponentTypes.DAMAGE, max - current);
        ItemMeta meta = stack.getItemMeta();
        String model = current == 0 ? kind + "_depleted" : upgraded(stack) ? kind + "_upgraded" : kind;
        meta.setItemModel(new NamespacedKey(NAMESPACE, model));
        stack.setItemMeta(meta);
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
