# ProstoMinecraft End

Folia 26.3 plugin under construction. This first stage implements the six seal recipes, a shared `/end` menu, durable submissions, a five-minute opening countdown, an entry gate, and the base End regeneration/exhaustion effects. It does **not** implement the three-dragon encounter, rewards, seepage, chitin, chorus brew, or resource-pack delivery yet.

The supplied 26.3 resource pack is stored in `resourcepack/`. Its JSON and PNG files have been validated. Custom item models are not attached to items until pack delivery and fallback behavior are settled.

`end-opening.enabled` defaults to `false`, so the unfinished encounter cannot open the End. Before release, complete the encounter and switch `allow-end` in Folia's `bukkit.yml` to `true`; the plugin then controls admission. Do not deploy this development JAR to the live server yet.

Run `./build.ps1` on the project workstation. Output: `build/ProstoMinecraft-End.jar`.
