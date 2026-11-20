package ru.prostominecraft.end;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Map;
import java.util.List;

public final class TestEndState {
    public static void main(String[] args) throws Exception {
        Path path = Files.createTempDirectory("pm-end-test-").resolve("state.yml");
        EndState state = new EndState(path);
        state.load();
        long now = 1_000_000L;
        for (Seal seal : Seal.values()) {
            if (!state.submit(seal, UUID.randomUUID(), "Tester", now, 300)) throw new AssertionError("submit " + seal);
        }
        if (state.count() != 6 || state.openingAt() != now + 300_000L) throw new AssertionError("countdown");
        EndState restored = new EndState(path);
        restored.load();
        if (restored.count() != 6 || restored.opened()) throw new AssertionError("restore");
        if (restored.openIfDue(now + 299_000L)) throw new AssertionError("early opening");
        if (!restored.openIfDue(now + 300_000L)) throw new AssertionError("late opening");
        EndState reopened = new EndState(path);
        reopened.load();
        if (!reopened.opened() || reopened.submit(Seal.HEAT, UUID.randomUUID(), "Other", now, 300)) throw new AssertionError("permanence");
        reopened.advanceWave();
        reopened.advanceWave();
        UUID winner = UUID.randomUUID();
        reopened.queueRewards(Map.of(winner, List.of("dragon_scale:60", "void_needle:1")), true);
        EndState rewarded = new EndState(path);
        rewarded.load();
        if (!rewarded.firstFightComplete() || rewarded.wave() != 2 || rewarded.pendingRewards(winner).size() != 2)
            throw new AssertionError("fight and offline rewards must survive reload");
        rewarded.acknowledgeReward(winner, "dragon_scale:60");
        EndState acknowledged = new EndState(path);
        acknowledged.load();
        if (!acknowledged.pendingRewards(winner).equals(List.of("void_needle:1"))) throw new AssertionError("reward acknowledgement");
        UUID dragon = UUID.randomUUID();
        for (int i = 0; i < 4; i++) acknowledged.recordPortalCrystal(UUID.randomUUID(), true);
        DragonKind firstRepeat = acknowledged.beginRepeat(dragon);
        EndState resumed = new EndState(path);
        resumed.load();
        if (resumed.repeatKind() != firstRepeat || !resumed.repeatEmpowered() || resumed.beginRepeat(dragon) != firstRepeat)
            throw new AssertionError("repeat fight must survive reload");
        resumed.queueRewards(Map.of(), false);
        if (resumed.beginRepeat(UUID.randomUUID()) == firstRepeat) throw new AssertionError("repeat should avoid preceding kind");
        resumed.markBorderExpanded();
        EndState borderRestored = new EndState(path);
        borderRestored.load();
        if (!borderRestored.borderExpanded()) throw new AssertionError("one-time world-border bonus state");
        Files.writeString(path, "opened: [broken");
        EndState corrupt = new EndState(path);
        corrupt.load();
        if (!corrupt.corrupt() || corrupt.opened() || corrupt.count() != 0) throw new AssertionError("corrupt state must fail closed");
        System.out.println("End state persistence cases passed");
    }
}
