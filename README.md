# ProstoMinecraft End

Folia 26.3 plugin under construction. The development build contains six seal recipes, a shared `/end` menu, durable submissions, a five-minute opening countdown, an entry gate, reminders, the initial island boundary, seepage, and End debuffs with chitin/chorus-brew reduction. The first-fight wave transition, repeat selection, empowered-crystal tracking, contributions, offline reward queue, resource recipes, artifact charges/basic actions, End wings, and empowered fruit compile and have limited state tests. The three boss mechanics are still simplified; crystal patterns, Mirage copies, Crown resonators/overload, and several artifact details are unfinished. These mechanics are **untested in game** and must not be advertised as live.

The supplied 26.3 resource pack is stored in `resourcepack/`. Its JSON and PNG files have been validated. Development items now carry `item_model` identifiers; pack hosting and fallback behavior remain unresolved. Do not distribute the development JAR before pack delivery is ready.

`end-opening.enabled` defaults to `false`, so the unfinished encounter cannot open the End. Before release, complete the encounter and switch `allow-end` in Folia's `bukkit.yml` to `true`; the plugin then controls admission. Do not deploy this development JAR to the live server yet.

Run `./build.ps1` on the project workstation. Output: `build/ProstoMinecraft-End.jar`.
