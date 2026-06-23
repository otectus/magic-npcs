# Changelog

All notable changes to Magic NPCs are documented here. Versions follow
`MAJOR.MINOR.PATCH`; this is a pre-1.0 line.

## [0.4.0] — cast pacing & aimed casting

Per-spell pacing controls and a real aiming wind-up for the built-in casting goal. Each
knob has a global default in config and an optional per-spell override in the loadout
JSON. Builds green; shipped loadouts regenerate unchanged via `runData`, and the offline
`bootSanity` GameTest still passes.

### Added
- **Casting wind-up + continuous aim** (`targeting.castWindupTicks`, default 6; per-spell
  `windup`). Before an attack spell fires, the caster faces and **tracks** its target for
  the wind-up, re-checking line of sight/range each tick, and only casts if the target is
  still valid — fixing the old "look once, fire the same tick" behaviour that flung
  off-axis shots wide. `0` restores instant casting.
- **Per-spell cast chance** (`balance.castChance`, default 1.0; per-spell `cast_chance`):
  a [0..1] probability that a caster actually casts on each decision, so casters can
  "hesitate" instead of firing the instant a spell is eligible.
- **Per-spell cooldown override** (per-spell `cooldown` explicit ticks, or
  `cooldown_multiplier`; precedence: explicit > multiplier > global `cooldownMultiplier`)
  and a configurable floor `balance.minCooldownTicks` (default 20, formerly hard-coded).
- **GameTest** `castChanceZeroNeverCasts`: with `castChance` forced to 0 a skeleton never
  spends mana over a 100-tick window (skips offline like the other runtime tests). The
  existing `skeletonCastsMagicMissile` now also exercises the default wind-up path.

### Changed
- `NpcSpellAttackGoal` now runs across a short wind-up window (adds `tick()`/`stop()` and
  a re-validating `canContinueToUse()`) instead of casting instantly on activation.
- The 20-tick cooldown floor moved from a hard-coded constant to `balance.minCooldownTicks`.

### Notes
- Fully backward compatible: loadout JSON fields are optional and inherit the matching
  global config default when omitted; existing packs and shipped loadouts are unchanged.
- Iron's Spells 'n Spellbooks and Villager Recruits remain compile-only soft dependencies.

## [0.3.0] — review fixes, completed deferred systems & datapack docs

An independent review pass: correctness fixes, four previously-deferred systems
implemented, and a full datapack authoring guide. Builds green; `runData` and the offline
`bootSanity` GameTest pass. Runtime casting is unchanged from the proven 0.1.1 path.

### Added
- **Data generation** (`./gradlew runData`): the shipped loadouts and the
  `magicnpcs:spell_focuses` tag are now generated from Java (single source of truth) into
  `src/generated/resources`. The focus tag includes Iron's `#irons_spellbooks:school_focus`
  by default, so `requireSpellFocus` works out-of-the-box when Iron's is installed.
- **Profession-scoped loadouts:** a loadout may declare an optional `profession` to apply to
  only villagers of that profession (multiple loadouts per entity type; a profession-less one
  is the fallback). See `docs/loadouts/`.
- **School-aware spell focus** (`schools.schoolAwareFocus`, previously inert): with
  `requireSpellFocus` on, a school caster may satisfy it by holding a focus for its own Iron's
  school (the per-school `irons_spellbooks:<school>_focus` tag); spawn-with-gear prefers a
  school-appropriate focus.
- **Runtime casting GameTests** (`skeletonCastsMagicMissile`, `recruitCasts`,
  `recruitCastsWithIronsAi`): assert a real cast (mana spent) in a full Iron's runtime; they
  skip cleanly offline so `bootSanity` stays green.
- **Datapack authoring guide** in the README (complete pack skeleton with `pack_format` 15, an
  annotated multi-spell example, modded-mob and profession examples, the spell-focus tag, and
  explicit-loadout-vs-magic-school guidance) plus a datapack example in the CurseForge description.

### Fixed
- **`/magicnpcs school clear` and the Tome's sneak-clear are now sticky:** they mark the NPC a
  non-caster instead of resetting it, so it no longer re-rolls into a caster on the next chunk
  reload.
- **School re-assign/clear now removes the Iron's-AI goal too:** with `recruits.useIronsAI=true`,
  re-assigning or clearing a school via the command/Tome no longer leaves a stale/duplicate
  `WizardAttackGoal`.
- A villager whose rolled school yields no castable spells is now marked a non-caster instead of
  re-rolling (and re-failing) every join.
- Loadout numeric fields are clamped on load, and an invalid `role` reports a clear error.

### Changed
- Loadouts load as a list per entity type (to support `profession`); behaviour for existing
  profession-less loadouts is unchanged.

### Notes
- `recruits.useIronsAI` (off by default) delegates targeting/fleeing to Iron's, so the built-in
  line-of-sight, friendly-fire, Peaceful, and spell-focus gates do not apply in that mode (the
  cast still uses Magic NPCs' mana economy).
- Iron's Spells 'n Spellbooks and Villager Recruits remain compile-only soft dependencies.

## [0.2.0] — NPC compatibility, hardening & magic schools

A broad release-readiness pass plus a new per-NPC magic-school system. Builds green
and boots cleanly with or without Iron's/Recruits (offline GameTest `bootSanity`
passes); runtime casting is unchanged from the proven 0.1.1 path.

### Added
- **Magic schools for recruits & villagers.** Each individual recruit/villager can be
  assigned an Iron's school (fire, ice, lightning, holy, ender, blood, evocation,
  nature, eldritch). The spell pool is built **dynamically** from that school's enabled
  spells (filtered by rarity/level caps, INSTANT-only, and the allow/deny lists), so
  add-on spells are picked up automatically. Assignment is stored per-entity in
  persistent data and rolled once. See `docs/schools.md`.
  - Automatic on spawn — recruits by chance/rank/mode (`RANDOM`/`BY_TYPE`/`BY_RANK`),
    villagers by a profession→school map. Villagers only actually cast when they have a
    target (raids / guard mods), preserving vanilla passivity.
  - **`/magicnpcs school set|info|reroll|clear <targets> [school]`** command (perm-gated).
  - **School Tome** item — right-click an NPC to cycle its school, sneak to clear.
- **Generic owner/team friendly-fire protection** (`targeting.protectOwners`): a
  vanilla-only adapter (scoreboard teams + `OwnableEntity`) protects companion/pet/
  follower NPCs (e.g. Human Companions) and their owner with no hard dependency.
- **Generic bystander protection** (`targeting.protectBystanders`): attack spells avoid
  catching villagers, iron golems, players, and tamed pets in their line of fire.
- **Per-mod compat toggles** (`[compat]`, default off) gating datapack loadouts for
  Guard Villagers, MCA Reborn, MineColonies, Easy NPC, Human Companions, More Villagers,
  and VillagersPlus. A ready Guard Villagers loadout ships (inert until enabled); copy-
  paste examples for the rest are in `docs/loadouts/`.
- **Equipment options** (`[equipment]`): `requireSpellFocus` (NPC must hold an item in
  the `magicnpcs:spell_focuses` tag) and `spawnWithGearChance`.
- **Lang file** (`en_us.json`) with config labels + item text; item model reuses the
  vanilla enchanted-book texture (no third-party asset shipped).

### Changed
- **Casting hardening.** NPCs no longer cast while sleeping, dead/dying, removed, or
  AI-disabled; attack spells now require **line of sight** (`requireLineOfSight`); casting
  is suppressed on **Peaceful** (`peacefulDisablesCasting`); mana pools scale with world
  **difficulty** (`difficultyScaling`).
- **Recruits** now respect their command system — a recruit ordered to a passive/flee
  state will not cast.
- `bootSanity` GameTest now ships its `platform` structure and passes offline.

### Notes
- Iron's Spells 'n Spellbooks and Villager Recruits remain **compile-only** soft
  dependencies — neither is bundled. The 8 non-Recruits NPC mods are supported via
  config + datapack + generic vanilla-interface adapters (no API imports), so the mod
  loads safely whether or not they are installed.

## [0.1.1] — production-validated

No functional code changes from 0.1.0. Validated the runtime in a real Forge **47.4.16**
dedicated server (production/SRG space) with Iron's `1.20.1-3.15.x`, GeckoLib 4.8.3,
Curios 5.14.1+1.20.1, PlayerAnimator 1.0.2-rc1+1.20, and Recruits 1.15.0 installed:

- **Boots cleanly** — Iron's own mixins apply (the SRG-vs-named failure seen in the
  ForgeGradle *dev* runtime is dev-only), and our `AbstractRecruitEntity` mixin loads
  without error.
- **Casting executes:** a tagged skeleton (universal path) and a Villager Recruit
  (adapter path) both cast Magic Missile via `onCast(CastSource.MOB)` with correct mana
  deduction (`mana 100 → 90`, `80 → 70`).
- **Iron's-AI Mixin path works:** with `recruits.useIronsAI=true`, recruits cast via
  Iron's `WizardAttackGoal` (driven through the mixin's `initiateCastSpell`) — no crash.
- Build wiring: `forge_version=47.4.16`; a `-PdevRuntime` runtime-companion block (off by
  default; ships nothing). Default `./gradlew build` stays green + third-party-free.

## [0.1.0] — initial development build

First feature-complete build (Minecraft 1.20.1, Forge). Compiles and packages
cleanly; the full in-game casting pass is pending a dev runtime with Iron's and its
companions (see `docs/dev-runtime.md`).

### Added
- **Mod-agnostic casting core.** Datapack loadouts
  (`data/<ns>/spellcasters/*.json`) make any entity type a spellcaster; an injected
  AI goal selects spells by role/range/mana/cooldown and casts via Iron's
  `AbstractSpell.onCast(..., CastSource.MOB, ...)`. Mana pool + regen + per-spell
  cooldowns are managed by the mod (Iron's runs its economy only for players).
- **SUPPORT spells** self-cast when the caster drops below a health threshold.
- **Villager Recruits adapter** (soft-dep): rank-scaled mana (`getXpLevel()`),
  diplomacy-aware targeting via Recruits' `shouldAttack()`, and a **line-of-fire
  ally check**. Curated loadouts for `recruit`, `bowman`, `crossbowman`, `captain`.
- **Opt-in Iron's mob AI for recruits** (`recruits.useIronsAI`, default off): a
  duck-interface Mixin makes recruits `IMagicEntity` so Iron's `WizardAttackGoal`
  can drive them; the cast itself still routes through the proven path.
- **Server config** (`magicnpcs-server.toml`): global toggle, mana/cooldown/regen
  multipliers, decision interval, support threshold, friendly-fire toggle, spell
  allow/deny lists, and the Recruits options.
- **GameTest harness** (`runGameTestServer`) with an offline boot-sanity test.
- Docs: `README`, `docs/dev-runtime.md`, and ADR `docs/decisions/0001-irons-mob-casting.md`.

### Notes
- Iron's Spells 'n Spellbooks and Villager Recruits are **compile-only** soft
  dependencies — neither is bundled or redistributed.
- API verified by decompiling the target Iron's (3.15.x) and Recruits (1.15.0) jars.
