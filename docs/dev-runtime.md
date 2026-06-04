# Dev runtime — verifying spellcasting in-game

Iron's Spellbooks is declared **`compileOnly`** (we compile against it but never bundle or ship it — it's All-Rights-Reserved, and this mirrors the proven `ars-n-spells` setup). The consequence: **`./gradlew runClient` launches *without* Iron's by default**, so `IronsCompat.isLoaded()` is `false` and spellcasting is disabled. That is the correct "Iron's absent" smoke test — but to watch a skeleton actually cast, the dev runtime needs Iron's **and its hard dependencies** on the classpath.

## Required runtime companions

Read from Iron's `mods.toml` for the version we target (file `7402504`, `1.20.1-3.15.x`):

| Mod | Required range | Cached locally? |
|---|---|---|
| **Forge** | `[47.4.0,)` | yes (`47.4.16`) — **bump `forge_version` from 47.2.0 for runtime testing** |
| **GeckoLib** | `[1.20.1:4.8,)` | yes (`geckolib-388172:5460309`) — verify ≥ 4.8 |
| **Curios** | `[5.4.7+1.20.1,)` | **no — fetch from CurseForge/CurseMaven** |
| **PlayerAnimator** | `[1.0.2-rc1+1.20,)` | **no — fetch from CurseForge/CurseMaven** |
| ~~Caelus~~ | not required | n/a — commented out in Iron's `mods.toml` for 3.15.x |

> Because Curios and PlayerAnimator are not in the local Gradle cache, the in-game test **cannot be run fully offline** — it requires a one-time network fetch of those two jars.

## How to enable (temporary, do NOT commit for release)

1. In `gradle.properties`, set `forge_version=47.4.16` (Iron's needs 47.4.0+ at runtime).
2. In `build.gradle` `dependencies`, add — alongside the existing `compileOnly` — runtime copies so FML loads them in dev:
   ```gradle
   runtimeOnly fg.deobf("curse.maven:${irons_spellbooks_project}:${irons_spellbooks_file}")
   runtimeOnly fg.deobf("curse.maven:geckolib-388172:5460309")
   runtimeOnly fg.deobf("curse.maven:curios-309927:<fileId>")        // pick a 5.4.7+1.20.1 build
   runtimeOnly fg.deobf("curse.maven:playeranimator-658587:<fileId>") // pick a 1.0.2-rc1+ build
   // Recruits (from libs/, via the flatDir repo) — needed for the recruit adapter + Mixin:
   runtimeOnly fg.deobf("blank:recruits:${recruits_jar_version}")
   ```
   (Look up current Curios/PlayerAnimator file IDs on CurseForge; slugs above are the usual `<name>-<projectId>` form — confirm them.)
3. `./gradlew runClient`.

## Offline boot check (no companions needed)

Confirm the mod boots cleanly **without** Iron's/Recruits — the soft-dep "absent"
path — fully offline:

```
./gradlew runGameTestServer --offline
```

A headless gametest server launches and **loads the mod** — the boot log line
`main … Magic NPCs … magicnpcs … 0.1.0 … DONE` (followed by "Started game test
server") proves `mods.toml` is valid, the mixin config loads without crashing when
its targets are absent (plugin gate → skip), the config registers, and the
"Iron's absent → disabled, no crash" path holds. (This is verified.)

The `@GameTest` bodies themselves need a structure template (`platform`), which is
authored in-game via a structure block and exported to
`data/magicnpcs/structures/`; until one is added, the run reports a missing
structure *after* the successful boot. The boot is the offline-verifiable part; the
structure-backed casting assertions belong to the full-runtime pass below.

## In-game test (full casting — needs the runtime above)

1. `/summon minecraft:skeleton ~ ~ ~`; approach so it targets you → it casts **Magic
   Missile** (projectile + Iron's sound/particles, **once** per cast) alongside its bow.
2. `/attribute @e[type=skeleton,limit=1] irons_spellbooks:max_mana get` → `100`; mana
   drops per cast and refills; casting pauses while on cooldown or out of mana.
3. **Recruits:** hire `recruit`/`bowman`/`crossbowman`/`captain`, set them on a hostile
   → they cast at the hostile, never at you or each other; stand an ally in the line of
   fire → that cast is skipped; level a recruit up → its mana pool grows.
4. Set `recruits.useIronsAI=true` in `magicnpcs-server.toml` → recruits drive Iron's
   `WizardAttackGoal` (vary spell by distance, flee at low HP); `false` → built-in goal.
5. Run with the `runtimeOnly` lines removed → no crash; log reads
   "Iron's Spellbooks not detected — … disabled."

## Status

Phases 0–6 are implemented; the project **compiles and builds cleanly** (`./gradlew
build` incl. reobf) and the offline gametest boots the mod. The in-game casting matrix
above is pending a dev runtime with the two un-cached companions (Curios +
PlayerAnimator), which needs a one-time network fetch.
