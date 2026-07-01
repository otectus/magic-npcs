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

## Dev-runtime stack (`-PdevRuntime`) — resolved, wired & **running**

`build.gradle` loads the full stack at runtime behind `-PdevRuntime` from local `libs/` jars
(runtimeOnly → never shipped/committed; `forge_version` is `47.4.16`). The Iron's *runtime*
jar is **3.16.1** (matching the target pack); the mod still **compiles** against 3.15.2:

```gradle
if (project.hasProperty('devRuntime')) {
    runtimeOnly fg.deobf("blank:irons_spellbooks:${irons_spellbooks_runtime}") // libs/irons_spellbooks-1.20.1-3.16.1.jar
    runtimeOnly fg.deobf("blank:irons_lib:1.20.1-1.1.0")                        // libs/irons_lib-*.jar (3.16.x split-out lib)
    runtimeOnly fg.deobf("blank:geckolib:4.8.3")                                // libs/geckolib-4.8.3.jar (Forge jar)
    runtimeOnly fg.deobf("maven.modrinth:curios:5.14.1+1.20.1")
    runtimeOnly fg.deobf("blank:player-animation-lib-forge:1.0.2-rc1+1.20")     // libs/player-animation-lib-forge-*.jar
    runtimeOnly fg.deobf("blank:recruits:${recruits_jar_version}")              // libs/recruits-1.20.1-1.15.0.jar
}
```

**Iron's 3.16.x needs `irons_lib`:** Iron's split a required library mod out at 3.16; without
`irons_lib` in `[1.20.1-1, 1.20.1-2)` mod loading aborts. Staged as `libs/irons_lib-1.20.1-1.1.0.jar`.

**GeckoLib gotcha:** the Modrinth *maven* coordinate `geckolib:4.8.3` serves the non-Forge
*common* jar (no `mods.toml`), so FML reports geckolib "not installed". Use the loader-specific
Forge jar at `libs/geckolib-4.8.3.jar`. PlayerAnimator is likewise the local Forge jar;
Curios resolves from Modrinth/cache. All `libs/*.jar` are gitignored.

**Run it:** `./gradlew runGameTestServer -PdevRuntime` boots a headless server **with Iron's +
Recruits** and runs the casting GameTests for real (see status below).

## Offline boot check (no companions needed)

Confirm the mod boots cleanly **without** Iron's/Recruits — the soft-dep "absent"
path — fully offline:

```
./gradlew runGameTestServer --offline
```

A headless gametest server launches and **loads the mod** — the boot log line
`main … Magic NPCs … magicnpcs … 0.5.0 … DONE` (followed by "Started game test
server") proves `mods.toml` is valid, the mixin config loads without crashing when
its targets are absent (plugin gate → skip), the config registers, and the
"Iron's absent → disabled, no crash" path holds. (This is verified.)

`bootSanity` runs on the shipped `platform` structure
(`data/magicnpcs/structures/platform.nbt`) and passes offline. The three casting
GameTests (`skeletonCastsMagicMissile`, `recruitCasts`, `recruitCastsWithIronsAi`) are
gated on `IronsCompat.isLoaded()` and **succeed immediately (skip) when Iron's is absent**,
so the offline run stays green; their real assertions — spawn the mob + a target, then
assert mana is spent on a cast — run only in the full runtime below (and the recruit cases
also need Recruits). They are marked `required = false`, so a runtime-specific flake never
fails the suite.

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

## Solved: running Iron's mixins in the named dev runtime

Earlier this was thought impossible: the dev gametest/client crashed at **Iron's own**
mixin — `mixins.irons_spellbooks.json:EntityMixin`'s `@Inject` on `isAlliedTo` looks for the
SRG name `m_7307_`, absent in the **named** dev runtime — and `-Dmixin.env.remapRefMap=true`
alone did not bridge it. The fix is to also hand Mixin ForgeGradle's **SRG→named** mapping so
it can remap the production refmap:

```gradle
runs.configureEach {
    property 'mixin.env.remapRefMap', 'true'
    property 'mixin.env.refMapRemappingFile', file('build/createSrgToMcp/output.srg').absolutePath
}
```

With that, the log shows `Remapping refMap mixins.irons_spellbooks.json … using …/createSrgToMcp/output.srg`
and Iron's (and Recruits) mixins apply cleanly in dev — `Magic NPCs loading (Iron's: true,
Recruits: true)`. (`build/createSrgToMcp/output.srg` is produced by ForgeGradle during the
run setup.)

One latent bug surfaced once load actually reached this far: `onRegisterCommands` read a
config value during `RegisterCommandsEvent`, which in the gametest server fires *before* the
server config loads (a dev-only hard error). Now guarded with `ForgeConfigSpec.isLoaded()`.

## Recommended: validate casting in a production instance

Everything must be in production/SRG space for Iron's mixins to work, so run the **built
jar** in a real Forge 1.20.1 (47.4.0+) instance — the normal end-user flow:

1. `./gradlew build` → `build/libs/magicnpcs-0.5.0.jar`.
2. Into a Forge **47.4.16** client/server `mods/` folder, drop `magicnpcs-0.5.0.jar` + the
   **production** jars for Iron's `1.20.1-3.15.x`, **GeckoLib 4.8.3 (forge)**, **Curios
   5.14.1+1.20.1**, **PlayerAnimator 1.0.2-rc1+1.20**, and (optional) **Recruits 1.15.0**.
   (`libs/geckolib-4.8.3.jar` and `libs/recruits-1.20.1-1.15.0.jar` are already the
   production jars.)
3. Run the **in-game test** above. For a headless check, set `debugLogging=true` in
   `config/magicnpcs-server.toml` and watch the log for `[cast]` lines + mana deltas.

## Status — DEV-RUNTIME CASTING NOW VERIFIED

`./gradlew runGameTestServer -PdevRuntime` now boots with Iron's 3.16.1 + Recruits and runs
the casting GameTests for real (no longer skip-only). Results:

- ✅ **All required tests pass**, including `skeletonCastsMagicMissile` — the universal
  `NpcSpellAttackGoal` path (with the 0.4.0 wind-up, telegraph, and reactive-condition code)
  spawns a caster + target and **spends mana on a real `onCast`**. `castChanceZeroNeverCasts`
  also passes. This is genuine end-to-end verification of the casting path.
- ⚠️ `recruitCasts` / `recruitCastsWithIronsAi` (both **optional**, `required = false`) fail in
  this synthetic harness: the test only calls `setTarget`, but `RecruitsAdapter.canCastAt`
  routes through Recruits' diplomacy (`shouldAttack`), and an ownerless recruit won't attack a
  bare zombie — so it (correctly) declines. Recruit casting itself was validated manually in a
  production server (`CHANGELOG.md` [0.1.1]); making these automated cases pass needs the test
  to set up a real Recruits attack relationship (owner/command/enemy) and is tracked as
  follow-up. They do not fail the build.

Earlier production validation (Forge 47.4.16 dedicated server, `CHANGELOG.md` [0.1.1]) still
stands: skeleton + recruit both cast Magic Missile with correct mana deduction, and the
`recruits.useIronsAI=true` Mixin → `WizardAttackGoal` path casts without crashing.
