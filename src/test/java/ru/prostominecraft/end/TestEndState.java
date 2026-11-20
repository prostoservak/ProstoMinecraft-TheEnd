package ru.prostominecraft.end;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

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
        Files.writeString(path, "opened: [broken");
        EndState corrupt = new EndState(path);
        corrupt.load();
        if (!corrupt.corrupt() || corrupt.opened() || corrupt.count() != 0) throw new AssertionError("corrupt state must fail closed");
        System.out.println("End state persistence cases passed");
    }
}
