package ru.prostominecraft.end;

import net.kyori.adventure.text.format.NamedTextColor;

enum DragonKind {
    BASTION("Бастион Края", NamedTextColor.RED, 260),
    MIRAGE("Мираж Края", NamedTextColor.DARK_PURPLE, 300),
    CROWN("Венец Края", NamedTextColor.AQUA, 340);

    final String title;
    final NamedTextColor color;
    final int firstBaseHp;

    DragonKind(String title, NamedTextColor color, int firstBaseHp) {
        this.title = title;
        this.color = color;
        this.firstBaseHp = firstBaseHp;
    }

    DragonKind next() { return values()[(ordinal() + 1) % values().length]; }
}
