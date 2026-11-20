package ru.prostominecraft.end;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;

final class EndState {
    private final Path file;
    private final Map<Seal, Submission> submitted = new EnumMap<>(Seal.class);
    private boolean opened;
    private long openingAt;
    private boolean corrupt;

    EndState(Path file) {
        this.file = file;
    }

    synchronized void load() {
        YamlConfiguration yaml = new YamlConfiguration();
        submitted.clear();
        opened = false;
        openingAt = 0;
        corrupt = false;
        if (Files.exists(file)) {
            try { yaml.load(file.toFile()); }
            catch (IOException | InvalidConfigurationException error) {
                corrupt = true;
                return;
            }
        }
        for (Seal seal : Seal.values()) {
            String raw = yaml.getString("submitted." + seal.name() + ".id");
            if (raw != null) {
                try { submitted.put(seal, new Submission(UUID.fromString(raw), yaml.getString("submitted." + seal.name() + ".name", "Игрок"))); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        opened = yaml.getBoolean("opened", false);
        openingAt = Math.max(0L, yaml.getLong("opening-at", 0L));
    }

    synchronized boolean submit(Seal seal, UUID player, String name, long now, int countdownSeconds) throws IOException {
        if (opened || submitted.containsKey(seal)) return false;
        submitted.put(seal, new Submission(player, name));
        long oldOpeningAt = openingAt;
        if (submitted.size() == Seal.values().length) openingAt = now + countdownSeconds * 1000L;
        try { save(); }
        catch (IOException error) {
            submitted.remove(seal);
            openingAt = oldOpeningAt;
            throw error;
        }
        return true;
    }

    synchronized boolean openIfDue(long now) throws IOException {
        if (opened || openingAt == 0 || now < openingAt) return false;
        opened = true;
        try { save(); }
        catch (IOException error) { opened = false; throw error; }
        return true;
    }

    synchronized int count() { return submitted.size(); }
    synchronized String contributor(Seal seal) {
        Submission submission = submitted.get(seal);
        return submission == null ? null : submission.name();
    }
    synchronized boolean opened() { return opened; }
    synchronized long openingAt() { return openingAt; }
    synchronized boolean corrupt() { return corrupt; }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("opened", opened);
        yaml.set("opening-at", openingAt);
        for (var entry : submitted.entrySet()) {
            String path = "submitted." + entry.getKey().name();
            yaml.set(path + ".id", entry.getValue().id().toString());
            yaml.set(path + ".name", entry.getValue().name());
        }
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "end-state-", ".tmp");
        try {
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private record Submission(UUID id, String name) { }
}
