# ProstoMinecraft End

Folia 26.3 plugin implementing the full End rework for ProstoServak:

- **6 End Seals & `/end` menu:** shared submission progress, 5-minute opening countdown, 15-minute reminders (10s BossBar + chat), entry gate, and *End Seepage* anomaly in the Overworld until the first three-dragon clear.
- **End Debuff & Protection:** permanent *Void Cold & Exhaustion* (`-50%` satiated regen, `×1.5` hunger drain), mitigated by chitin-stitched armor (`-20%` per piece, up to `-80%`) and *Chorus Brew*.
- **Three-Dragon Boss Encounter (`Бастион Края → Мираж Края → Венец Края`):**
  - First clear runs all 3 waves sequentially with 20s intermissions, 5-minute wipe grace, pillar crystal setups, 300-block island barrier, Top-3 guaranteed artifacts, proportional Dragon Scale pool, and Overworld WorldBorder bonus (`+256`).
  - Repeat summons spawn a random non-repeating dragon (normal or Empowered via 4 Empowered End Crystals) with proportional scales and weighted artifact drop chance (`15%` / `30%`).
- **Artifacts, Scale Progression & End Wings:** `Чешуя дракона → Драконий хитин → Кровь дракона`, non-breakable charged artifacts (*Игла бездны*, *Коготь дракона*, *Небесная петля*) with one-step upgrades, anvil enchanting (`Unbreaking` on all 3, `Mending` on Needle/Claw only), *Крылья Края* (`+4` armor, `+1` toughness, fire-resistant, no firework CD, `-20%` End debuff), and *Усиленный плод Иггдрасиля* (`24 → 28 HP`).

## Server Setup (Folia 26.3)

1. Drop `ProstoMinecraft-End.jar` into `plugins/`.
2. Ensure `allow-end: true` in `bukkit.yml` (the plugin controls admission until the 6 seals are broken).
3. Add the hosted resource pack to `server.properties`:
   ```properties
   resource-pack=https://appela.ru/resourcepacks/prostoservak-pack.zip
   resource-pack-sha1=c040d17b2dc0b2b7914c65174d1bbedc59db7b63
   require-resource-pack=false
   ```

## Build & Test

Run `./test.ps1` (or `./build.ps1`) on the project workstation. Output: `build/ProstoMinecraft-End.jar`.
