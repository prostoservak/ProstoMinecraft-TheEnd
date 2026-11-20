package ru.prostominecraft.end;

final class FightPlan {
    private FightPlan() {}

    static double maxHp(DragonKind kind, boolean firstFight, boolean empowered, int players) {
        int base = firstFight ? kind.firstBaseHp : empowered ? 680 : 420;
        return base * (1.0 + 0.5 * Math.max(0, players - 1));
    }

    static int scalePool(boolean firstFight, boolean empowered, int players) {
        int n = Math.max(1, players);
        if (firstFight) return Math.max(60, 20 * n);
        int normal = 20 + 6 * Math.max(0, n - 2);
        return empowered ? normal * 2 : normal;
    }

    static int scalesFor(int pool, double contribution, double total, boolean firstFight) {
        if (total <= 0 || contribution <= 0) return 0;
        double share = contribution / total;
        if (share < (firstFight ? 0.03 : 0.05)) return 0;
        return Math.max(1, (int) Math.round(pool * share));
    }
}
