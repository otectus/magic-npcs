# Magic NPCs — Completion Audit & Release-Readiness Report

> **Historical snapshot (0.1.1 → 0.3.0 review pass).** This report is not kept current with each
> release — see [`CHANGELOG.md`](CHANGELOG.md) for the authoritative, up-to-date record (current
> release: 0.5.0).

**Mod:** Magic NPCs (`magicnpcs`) · **MC:** 1.20.1 · **Loader:** Forge 47.4.16 ·
**Java:** 17 · **Version:** 0.1.1 → release-hardening pass

This document captures (1) the audit of the codebase as found, and (2) the work
done in this pass. It is the single source of truth for what the mod does, what was
fixed, and what remains.

---

## 1. Current state of the mod (as found)

Magic NPCs lets NPC mobs cast Iron's Spells 'n Spellbooks spells through a
**mod-agnostic, datapack-driven** core. The architecture was already clean and safe:

- **Soft dependencies done right.** Iron's and Recruits are `compileOnly`; both are
  `mandatory=false` in `mods.toml`. All Iron's references live in the
  `integration.irons` package (classloaded only behind `IronsCompat.isLoaded()`); all
  Recruits references live in `compat.recruits` / the mixin (behind
  `RecruitsCompat.isLoaded()` and a plugin-gated mixin). Verified: **no optional-mod
  import leaks** outside those guarded locations.
- **Casting path wired end-to-end.** A datapack loadout (`data/<ns>/spellcasters/*.json`)
  keyed by entity type opts a mob in; on spawn it gets Iron's `MAX_MANA`/`MANA_REGEN`
  attributes and a casting goal that calls the real
  `AbstractSpell.onCast(..., CastSource.MOB, ...)`. The mod owns the mana economy and
  cooldowns (Iron's does not run its player-side economy for `CastSource.MOB`).
- **One deep integration:** Villager Recruits (rank-scaled mana, diplomacy-aware
  targeting, optional Iron's `WizardAttackGoal` via mixin).

## 2. Intended design (inferred + confirmed)

A thin, centralized Iron's seam (`IronsBridge`) + a mod-agnostic adapter interface
(`NpcAdapter`) so each NPC mod contributes ownership/team/rank semantics **without**
the core importing it. Loadouts are datapack-overridable; balance is config-driven.
The `Plan.md` in-repo critique confirms the intent: lean on Iron's `MagicData` +
attributes, do not reinvent the resource layer, keep adapters thin and isolated.

## 3. Discovered bugs / incomplete systems

| # | Severity | Finding |
|---|----------|---------|
| B1 | Med | Goal could cast while the mob was **sleeping, dead/dying, removed/despawning, or AI-disabled** — `canUse()` only checked target validity. |
| B2 | Med | **No line-of-sight check** — NPCs could cast attack spells through walls. |
| B3 | Med | **No difficulty awareness** — casters acted identically on Peaceful (where hostiles shouldn't fight) and Hard. |
| B4 | Med | **Bystander friendly-fire only ran when an adapter "tracked allies"** — a plain skeleton (default adapter) could AoE a villager. |
| B5 | Low | No generic owner/team protection — companion/pet NPCs from un-compiled mods had no friendly-fire safety. |
| B6 | Low | `bootSanity` GameTest referenced a **missing `platform` structure** → the test errored after a successful boot. |
| B7 | Low | **No lang file**; config exposed no UI labels. |
| B8 | Info | 8 of 9 target NPC mods had **no code, config, or loadouts**. |

None were crashes; the mod was stable but narrow and under-hardened.

## 4. Compatibility status (after this pass)

| Mod | Mechanism | Status |
|-----|-----------|--------|
| **Iron's Spells 'n Spellbooks** | core dependency (compiled) | ✅ Full — casting, mana, cooldowns, attributes |
| **Villager Recruits** | compiled adapter + mixin | ✅ Full — rank mana, diplomacy targeting, **command-state respect (new)**, optional Iron's AI |
| **Human Companions** | generic `OwnableEntity` adapter | ✅ Friendly-fire safe (owner/siblings) + datapack loadouts |
| **Guard Villagers** | config-gated datapack loadout (shipped) | ⚙️ Loadout ships, inert until `compat.guardvillagers=true` |
| **MCA Reborn** | config-gated datapack loadout (example) | ⚙️ Example + bystander protection; whole-type caveat documented |
| **MineColonies** | config-gated datapack loadout (example) | ⚙️ Example (raiders) + bystander protection |
| **Easy NPC** | config-gated datapack loadout (example) | ⚙️ Example; entity ids vary by variant |
| **More Villagers / VillagersPlus** | bystander protection + docs | ⚙️ Protected as bystanders; profession-scoped casting/trades = future work |
| **Workers (Talhanation)** | inherits Recruits base | ⚙️ Covered by the Recruits adapter where it extends `AbstractRecruitEntity`; add loadouts per worker type |

✅ = compiled/tested · ⚙️ = data/config-driven, off by default, not runtime-tested here

**Environment constraint:** only Recruits (`libs/`) and Iron's (CurseMaven) are
available to compile against, and there is no Iron's runtime in dev. The 8 non-Recruits
mods therefore get config + datapack + generic-adapter support **by design** — no API
imports (which would crash without their jars) and no runtime test.

## 5. Implementation plan (executed)

A — harden the universal goal; B — adapter priority + `canCastNow`; C — generic
owner/team + bystander protection; D — config expansion (targeting/equipment/difficulty/
per-mod); E — spell-focus equipment gating; F — deepen Recruits; G — config-gated
breadth + example loadouts; H — lang/tag/structure/docs; I — validate. All items below.

---

## 6. What was fixed / completed in this pass

### Core hardening
- **`NpcSpellAttackGoal`** — `canUse()` now gates on `canCastInCurrentState()`:
  blocks dead/dying, removed, sleeping, `noAi`; blocks casting on **Peaceful** (config);
  defers a mod-specific busy state to `adapter.canCastNow(mob)`; optionally requires a
  held **spell focus**. `choose()` adds a **line-of-sight** gate for attack spells and
  widens the friendly-fire scan to also run when **bystander protection** is on (so
  even adapter-less casters spare townsfolk). *(fixes B1, B2, B3, B4)*
- **`IronsSpellcasterHandler`** — modest **difficulty scaling** of mana pools
  (Easy 0.85× / Hard 1.2×); **spawn-with-gear** (equip a random `spell_focuses` item by
  chance); **per-mod compat gate** (`isLoadoutEnabledFor`) so optional-mod loadouts are
  inert until enabled, with a debug log when skipped. *(B3, B8)*

### Adapter layer
- **`NpcAdapter`** — added `priority()` and `canCastNow(mob)` defaults.
- **`NpcAdapters`** — resolves the **highest-priority** applicable adapter (specific
  beats generic).
- **`OwnableTeamAdapter`** (new, vanilla-only) — owner/team friendly-fire protection;
  priority −100; registered unconditionally; gated by `targeting.protectOwners`. *(B5)*
- **`RecruitsAdapter`** — priority 100; `canCastNow` respects the recruit **command
  state** (no casting when ordered passive/flee).
- **`LineOfFire`** — optional generic **bystander** pass (villagers, iron golems,
  players, tamed pets). *(B4)*

### Config (`MagicNpcsConfig`)
- New `[targeting]` (`requireLineOfSight`, `protectBystanders`, `protectOwners`),
  `[equipment]` (`requireSpellFocus`, `spawnWithGearChance`), `[balance]` additions
  (`peacefulDisablesCasting`, `difficultyScaling`), and `[compat]` per-mod toggles
  (all **default off**). Added `isLoadoutEnabledFor` / `ownerModLoaded` helpers and
  `.translation()` keys on every option.

### Resources & docs
- `assets/magicnpcs/lang/en_us.json` (29 keys) *(B7)*; empty
  `data/magicnpcs/tags/items/spell_focuses.json`; shipped Guard Villagers loadout
  `data/magicnpcs/spellcasters/guard.json`; generated `data/magicnpcs/structures/platform.nbt`
  so `bootSanity` runs *(B6)*; `docs/loadouts/README.md` examples + limitations;
  README "Supported NPC mods" / expanded config / "Disabling risky integrations".
- **GameTest** — `bootSanity` now passes; the runtime-only scenarios are documented as
  manual production acceptance checks (no leftover TODO/stub markers).

## 7. Commands run & results

| Command | Result |
|---------|--------|
| `compileJava` | ✅ Success (only pre-existing-style `ResourceLocation`/`get()` deprecation warnings) |
| `build` (jar + reobf) | ✅ `BUILD SUCCESSFUL`; `magicnpcs-0.1.1.jar` produced |
| `runGameTestServer` (offline, no Iron's/Recruits) | ✅ Server boots; **All 1 required tests passed** (`bootSanity`). Soft-dep path confirmed: "Iron's not detected — spellcasting disabled", no crash; mixin gate skipped cleanly; config registered |
| JSON validation (all `data/`, `docs/`, lang) | ✅ All valid |
| grep TODO/FIXME/stub/placeholder in `src/main/java` | ✅ Clean |
| Import-isolation grep | ✅ No Iron's/Recruits imports outside `integration.irons` / `compat.recruits` / `mixin` |

> The wrapper script `gradlew` has CRLF line endings (shows as modified in git); builds
> were run via `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain …`
> to avoid touching the user's tracked file. No `runData`/datagen task is configured
> (loadouts are authored by hand), so there was no datagen step to run.

## 8. Known limitations

- The 8 non-Recruits NPC mods are **config/datapack-driven only** — no compiled API
  depth and **not runtime-tested** in this environment (their jars and an Iron's
  runtime are absent). Example loadout entity ids are documented and **must be verified**
  per mod version.
- **Profession-scoped casting** (More Villagers / VillagersPlus add professions to the
  vanilla villager *type*) and **magical trade/loot injection** are not implemented —
  loadouts key on entity type, which can't distinguish a profession.
- **MCA Reborn** villagers share entity types across roles, so a loadout affects all of
  them; enable `compat.mca` deliberately and pair with a tight `spellWhitelist`.
- Casting visuals rely on Iron's server-side `onCast` particle/sound spawning; no custom
  GeckoLib cast animation for non-GeckoLib NPCs (vanilla arm-swing only).

## 9. Remaining risks

- Iron's non-`api` internals could shift across 3.x; the seam is isolated to
  `IronsBridge` so breakage is contained to one file.
- Per-mod loadout entity ids for un-built mods may drift; they fail safe (an unknown
  type simply never matches) and are toggle-gated off by default.

## 10. Future recommendations

1. Add compiled adapters for Guard Villagers / MCA / MineColonies once their jars/maven
   coords are available (ownership, village reputation, colony permissions).
2. Profession-aware loadouts + optional magical trade injection for villager mods.
3. Datagen (`GatherDataEvent`) for loadouts + the `spell_focuses` tag to replace
   hand-authored JSON.
4. Flesh out the three runtime GameTest scenarios in a production instance with Iron's +
   Recruits present.

## 10b. Follow-up feature — per-entity magic schools (recruits & villagers)

Added after the hardening pass: each individual recruit/villager can be assigned an
Iron's **school**, with its spell pool built dynamically from that school.

- **`core/SchoolData`** — per-entity assignment stored in `getPersistentData()`
  (`magicnpcs.school`): a school id, the sticky `"none"` non-caster sentinel, or absent.
  Survives save/load; rolled once.
- **`integration/irons/SchoolSpellPool`** — builds a `SpellcasterLoadout` from
  `SpellRegistry.getSpellsForSchool(school)`, filtered by enabled + `INSTANT` +
  rarity/level caps + whitelist/blacklist, weighted (`UNIFORM`/`INVERSE_RARITY`),
  auto-classified ATTACK/SUPPORT. Feeds the unchanged `NpcSpellAttackGoal`.
- **`IronsSpellcasterHandler`** — when no JSON loadout exists, `trySchoolLoadout`
  assigns/resolves a school for eligible recruits (via `NpcAdapter.schoolAssignable` +
  rank) and villagers (`Villager` profession map), then injects the goal. `applySchool`/
  `clearSchool` rebuild on demand; regen now also covers school casters.
- **`command/SchoolCommand`** — `/magicnpcs school set|info|reroll|clear <targets> [school]`
  (perm-gated; registered only when Iron's present). References only our own seam, so
  the "Iron's imports stay in `integration.irons`" invariant still holds.
- **`item/SchoolTomeItem` + `registry/MagicNpcsItems`** — right-click to cycle a school
  (sneak to clear); model reuses the vanilla `enchanted_book` texture (no asset shipped).
  Registered unconditionally; effect Iron's-gated at use time.
- **Config `[schools]`** — `enableSchools`, `allowedSchools`, `maxRarity`,
  `maxSpellLevel`, `spellsPerSchool`, `weightingMode`, `attackMaxRange`, base mana,
  `supportSpellIds`, `schoolAwareFocus`, plus `[schools.recruits]`
  (`enabled`/`casterChance`/`assignmentMode`/`typeSchools`/`minRankToCast`),
  `[schools.villagers]` (`enabled`/`casterChance`/`professionSchools`/`unmappedGetRandom`),
  and command/item toggles. All with `.translation()` keys.
- **Design choice honored:** villagers get schools but only cast when they hold a target
  (raids / guard mods) — the goal's existing `mob.getTarget()` requirement enforces it;
  no new villager AI, vanilla passivity preserved.
- **Docs:** `docs/schools.md` + README "Magic schools" section.
- **Validation:** `clean build` ✅, offline `runGameTestServer` ✅ (item registers, boot
  passes, no crash without Iron's), all JSON valid, grep clean, import isolation intact.
- **Limitations:** runtime casting still untestable in dev (no Iron's runtime);
  ATTACK/SUPPORT split is heuristic (overridable via `supportSpellIds`); profession ids
  for villager mods must be added to `professionSchools` by the pack author.

## 11. Release readiness

**Release-ready for its compiled scope** (mod-agnostic core + Iron's + Recruits +
generic owner/team/bystander safety), with conservative, default-off, config-gated
hooks for the broader NPC-mod ecosystem. Build is green, the offline boot test passes,
resources validate, and there are no leftover stubs. Deep per-mod API integration for
the remaining 8 mods is deferred pending their development jars and a full runtime.

## 12. 0.3.0 review pass — fixes & completed deferred work

An independent re-review (skeptical of section 3's "all clean") plus completion of several
deferred items. Net: the core casting code held up — several first-pass "bugs" were false
positives (`LineOfFire` containment, `SchoolSpellPool` weighted sampling, the goal's
decision-timer/null-safety) and were confirmed correct by reading the code. The genuine
findings and their fixes:

- **Fixed — `clearSchool` was not sticky:** `/magicnpcs school clear` and the Tome's
  sneak-clear reset the NPC to *unrolled*, so it re-rolled into a caster on the next chunk
  reload. Now marks a sticky non-caster.
- **Fixed — school re-assign/clear ignored `WizardAttackGoal`:** with `recruits.useIronsAI`,
  re-assign/clear left a stale/duplicate Iron's goal. The removal predicate now matches
  `hasSpellGoal` (both goal types).
- **Fixed — empty school roll churn:** a fresh roll yielding no castable spells now marks a
  non-caster instead of re-rolling/re-failing each join.
- **Hardened — loadout parse:** numeric fields clamped; invalid `role` gives a clear message.
- **Removed cruft:** stray `Microsoft.Services.Store.winmd`.

Completed from §10 "Future recommendations":
- **#2 Profession-aware loadouts (explicit):** loadouts now take an optional `profession`
  field (multiple loadouts per type; profession-less fallback). *(Magical trade/loot
  injection — the other half of the original item — remains future work.)*
- **#3 Datagen:** `GatherDataEvent` generates the shipped loadouts + the `spell_focuses` tag
  (`runData` → `src/generated/resources`); the tag now defaults to Iron's `#school_focus`.
- **#4 Runtime GameTests:** the three casting scenarios are implemented (Iron's-gated; skip
  offline). They run — but are not yet *verified* — in a full runtime here.

Also implemented `schools.schoolAwareFocus` (previously a defined-but-unread config option):
it now accepts an Iron's per-school focus (`SchoolType.isFocus`) when `requireSpellFocus` is on.

**Still deferred (need external jars/assets):** compiled per-mod adapters (§10 #1), GeckoLib
cast animations, and magical trade/loot injection.
