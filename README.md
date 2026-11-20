# ProstoMinecraft End

Folia 26.3 plugin under construction. The current development build has the six seal recipes, a shared `/end` menu, durable submissions, a five-minute opening countdown, an entry gate, reminders, the initial island boundary, and the base End regeneration/exhaustion effects. It also contains an **untested-in-game** first-fight wave transition, persistent offline reward queue, scale/chitin/blood and chorus-brew recipes. The fight lacks its three distinct abilities, repeat selection, crystal rules, artifact abilities, equipment upgrades, and pack delivery. None of these incomplete mechanics should be advertised as live.

The supplied 26.3 resource pack is stored in `resourcepack/`. Its JSON and PNG files have been validated. Development items now carry `item_model` identifiers; pack hosting and fallback behavior remain unresolved. Do not distribute the development JAR before pack delivery is ready.

`end-opening.enabled` defaults to `false`, so the unfinished encounter cannot open the End. Before release, complete the encounter and switch `allow-end` in Folia's `bukkit.yml` to `true`; the plugin then controls admission. Do not deploy this development JAR to the live server yet.

Run `./build.ps1` on the project workstation. Output: `build/ProstoMinecraft-End.jar`.
