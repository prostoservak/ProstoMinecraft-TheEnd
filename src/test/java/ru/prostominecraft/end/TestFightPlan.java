package ru.prostominecraft.end;

public final class TestFightPlan {
    public static void main(String[] args) {
        if (FightPlan.maxHp(DragonKind.BASTION, true, false, 1) != 260) throw new AssertionError("solo Bastion");
        if (FightPlan.maxHp(DragonKind.CROWN, true, false, 3) != 680) throw new AssertionError("three-player Crown");
        if (FightPlan.maxHp(DragonKind.MIRAGE, false, true, 2) != 1020) throw new AssertionError("empowered repeat");
        if (FightPlan.scalePool(true, false, 2) != 60 || FightPlan.scalePool(true, false, 4) != 80) throw new AssertionError("first pool");
        if (FightPlan.scalePool(false, false, 3) != 26 || FightPlan.scalePool(false, true, 3) != 52) throw new AssertionError("repeat pool");
        if (FightPlan.scalesFor(60, 2, 100, true) != 0) throw new AssertionError("threshold");
        if (FightPlan.scalesFor(60, 3, 100, true) != 2) throw new AssertionError("contribution");
        System.out.println("Fight plan cases passed");
    }
}
