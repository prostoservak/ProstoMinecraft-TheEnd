package ru.prostominecraft.end;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;

final class EndState {
    private final Path file;
    private final Map<Seal, Submission> submitted = new EnumMap<>(Seal.class);
    private boolean opened;
    private long openingAt;
    private boolean corrupt;
    private boolean firstFightComplete;
    private int wave;
    private final Map<UUID, List<String>> pendingRewards = new HashMap<>();
    private UUID repeatDragonId;
    private DragonKind repeatKind;
    private DragonKind lastRepeatKind;
    private boolean repeatEmpowered;
    private final Map<UUID, Boolean> portalCrystals = new HashMap<>();
    private boolean borderExpanded;
    private final Map<UUID, Double> fightContribution = new HashMap<>();
    private int fightMaxPlayers = 1;

    EndState(Path file) {
        this.file = file;
    }

    synchronized void load() {
        YamlConfiguration yaml = new YamlConfiguration();
        submitted.clear();
        opened = false;
        openingAt = 0;
        corrupt = false;
        firstFightComplete = false;
        wave = 0;
        pendingRewards.clear();
        repeatDragonId = null;
        repeatKind = null;
        lastRepeatKind = null;
        repeatEmpowered = false;
        portalCrystals.clear();
        borderExpanded = false;
        fightContribution.clear();
        fightMaxPlayers = 1;
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
        firstFightComplete = yaml.getBoolean("first-fight-complete", false);
        wave = Math.max(0, Math.min(2, yaml.getInt("wave", 0)));
        borderExpanded = yaml.getBoolean("border-expanded", false);
        fightMaxPlayers = Math.max(1, yaml.getInt("fight.max-players", 1));
        if (yaml.isConfigurationSection("fight.contribution")) {
            for (String rawId : yaml.getConfigurationSection("fight.contribution").getKeys(false)) {
                try {
                    double value = yaml.getDouble("fight.contribution." + rawId);
                    if (Double.isFinite(value) && value > 0) fightContribution.put(UUID.fromString(rawId), value);
                } catch (IllegalArgumentException ignored) { corrupt = true; }
            }
        }
        if (yaml.isConfigurationSection("pending-rewards")) {
            for (String rawId : yaml.getConfigurationSection("pending-rewards").getKeys(false)) {
                try { pendingRewards.put(UUID.fromString(rawId), new ArrayList<>(yaml.getStringList("pending-rewards." + rawId))); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        try {
            String id = yaml.getString("repeat.dragon-id");
            repeatDragonId = id == null ? null : UUID.fromString(id);
            String kind = yaml.getString("repeat.kind");
            repeatKind = kind == null ? null : DragonKind.valueOf(kind);
            String last = yaml.getString("repeat.last-kind");
            lastRepeatKind = last == null ? null : DragonKind.valueOf(last);
            repeatEmpowered = yaml.getBoolean("repeat.empowered", false);
        } catch (IllegalArgumentException error) {
            corrupt = true;
        }
        if (yaml.isConfigurationSection("portal-crystals")) {
            for (String rawId : yaml.getConfigurationSection("portal-crystals").getKeys(false)) {
                try { portalCrystals.put(UUID.fromString(rawId), yaml.getBoolean("portal-crystals." + rawId)); }
                catch (IllegalArgumentException ignored) { corrupt = true; }
            }
        }
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
    synchronized boolean firstFightComplete() { return firstFightComplete; }
    synchronized int wave() { return wave; }

    synchronized void advanceWave() throws IOException {
        if (firstFightComplete || wave >= 2) throw new IllegalStateException("No further first-fight wave");
        int previous = wave;
        wave++;
        try { save(); }
        catch (IOException error) { wave = previous; throw error; }
    }

    synchronized void finishFirstFight() throws IOException {
        if (firstFightComplete) return;
        firstFightComplete = true;
        try { save(); }
        catch (IOException error) { firstFightComplete = false; throw error; }
    }

    synchronized void queueRewards(Map<UUID, List<String>> rewards, boolean firstFight) throws IOException {
        Map<UUID, List<String>> previous = new HashMap<>();
        for (var entry : pendingRewards.entrySet()) previous.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        boolean oldComplete = firstFightComplete;
        UUID oldDragonId = repeatDragonId;
        DragonKind oldKind = repeatKind;
        DragonKind oldLastKind = lastRepeatKind;
        boolean oldEmpowered = repeatEmpowered;
        Map<UUID, Double> oldContribution = new HashMap<>(fightContribution);
        int oldPlayers = fightMaxPlayers;
        for (var entry : rewards.entrySet()) pendingRewards.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
        if (firstFight) firstFightComplete = true;
        else {
            lastRepeatKind = repeatKind;
            repeatDragonId = null;
            repeatKind = null;
            repeatEmpowered = false;
        }
        fightContribution.clear();
        fightMaxPlayers = 1;
        try { save(); }
        catch (IOException error) {
            pendingRewards.clear(); pendingRewards.putAll(previous);
            firstFightComplete = oldComplete;
            repeatDragonId = oldDragonId;
            repeatKind = oldKind;
            lastRepeatKind = oldLastKind;
            repeatEmpowered = oldEmpowered;
            fightContribution.putAll(oldContribution);
            fightMaxPlayers = oldPlayers;
            throw error;
        }
    }

    synchronized void recordPortalCrystal(UUID id, boolean empowered) throws IOException {
        Boolean old = portalCrystals.put(id, empowered);
        try { save(); }
        catch (IOException error) {
            if (old == null) portalCrystals.remove(id); else portalCrystals.put(id, old);
            throw error;
        }
    }

    synchronized void forgetPortalCrystal(UUID id) throws IOException {
        Boolean old = portalCrystals.remove(id);
        if (old == null) return;
        try { save(); }
        catch (IOException error) { portalCrystals.put(id, old); throw error; }
    }

    synchronized DragonKind beginRepeat(UUID dragonId) throws IOException {
        if (!firstFightComplete) throw new IllegalStateException("First fight is not complete");
        if (dragonId.equals(repeatDragonId) && repeatKind != null) return repeatKind;
        UUID oldId = repeatDragonId;
        DragonKind oldKind = repeatKind;
        boolean oldEmpowered = repeatEmpowered;
        Map<UUID, Boolean> oldCrystals = new HashMap<>(portalCrystals);
        Map<UUID, Double> oldContribution = new HashMap<>(fightContribution);
        int oldPlayers = fightMaxPlayers;
        DragonKind[] options = DragonKind.values();
        DragonKind next;
        do { next = options[ThreadLocalRandom.current().nextInt(options.length)]; }
        while (next == lastRepeatKind && options.length > 1);
        repeatDragonId = dragonId;
        repeatKind = next;
        repeatEmpowered = portalCrystals.size() == 4 && portalCrystals.values().stream().allMatch(Boolean::booleanValue);
        portalCrystals.clear();
        fightContribution.clear();
        fightMaxPlayers = 1;
        try { save(); }
        catch (IOException error) {
            repeatDragonId = oldId;
            repeatKind = oldKind;
            repeatEmpowered = oldEmpowered;
            portalCrystals.putAll(oldCrystals);
            fightContribution.putAll(oldContribution);
            fightMaxPlayers = oldPlayers;
            throw error;
        }
        return next;
    }

    synchronized DragonKind repeatKind() { return repeatKind; }
    synchronized UUID repeatDragonId() { return repeatDragonId; }
    synchronized boolean repeatEmpowered() { return repeatEmpowered; }
    synchronized Map<UUID, Double> fightContribution() { return Map.copyOf(fightContribution); }
    synchronized int fightMaxPlayers() { return fightMaxPlayers; }

    synchronized void saveFightProgress(Map<UUID, Double> contribution, int maxPlayers) throws IOException {
        if (fightContribution.equals(contribution) && fightMaxPlayers == maxPlayers) return;
        Map<UUID, Double> old = new HashMap<>(fightContribution);
        int oldMax = fightMaxPlayers;
        fightContribution.clear();
        fightContribution.putAll(contribution);
        fightMaxPlayers = Math.max(1, maxPlayers);
        try { save(); }
        catch (IOException error) {
            fightContribution.clear(); fightContribution.putAll(old);
            fightMaxPlayers = oldMax;
            throw error;
        }
    }
    synchronized boolean borderExpanded() { return borderExpanded; }

    synchronized void markBorderExpanded() throws IOException {
        if (borderExpanded) return;
        borderExpanded = true;
        try { save(); }
        catch (IOException error) { borderExpanded = false; throw error; }
    }

    synchronized List<String> pendingRewards(UUID id) {
        return List.copyOf(pendingRewards.getOrDefault(id, List.of()));
    }

    synchronized void acknowledgeReward(UUID id, String reward) throws IOException {
        List<String> entries = pendingRewards.get(id);
        if (entries == null || !entries.remove(reward)) return;
        if (entries.isEmpty()) pendingRewards.remove(id);
        try { save(); }
        catch (IOException error) {
            pendingRewards.computeIfAbsent(id, ignored -> new ArrayList<>()).add(reward);
            throw error;
        }
    }

    private void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("opened", opened);
        yaml.set("opening-at", openingAt);
        yaml.set("first-fight-complete", firstFightComplete);
        yaml.set("wave", wave);
        yaml.set("border-expanded", borderExpanded);
        yaml.set("fight.max-players", fightMaxPlayers);
        for (var entry : fightContribution.entrySet()) yaml.set("fight.contribution." + entry.getKey(), entry.getValue());
        yaml.set("repeat.dragon-id", repeatDragonId == null ? null : repeatDragonId.toString());
        yaml.set("repeat.kind", repeatKind == null ? null : repeatKind.name());
        yaml.set("repeat.last-kind", lastRepeatKind == null ? null : lastRepeatKind.name());
        yaml.set("repeat.empowered", repeatEmpowered);
        for (var entry : portalCrystals.entrySet()) yaml.set("portal-crystals." + entry.getKey(), entry.getValue());
        for (var entry : pendingRewards.entrySet()) yaml.set("pending-rewards." + entry.getKey(), entry.getValue());
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
