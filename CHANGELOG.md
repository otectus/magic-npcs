# Changelog

All notable changes to Magic NPCs are documented here. Versions follow
`MAJOR.MINOR.PATCH`; this is a pre-1.0 line.

## [0.5.0] — predictable overrides, broken-spell fixes, aimed casting, spell discovery

Predictability and pack-author control for Iron's casters. The bundled skeleton example no longer
ships as active jar data (so modpack datapacks own skeleton behaviour cleanly), datapacks can now
**explicitly override** a shipped loadout, and the long/target-locked spells that silently did
nothing for mobs — `root`, `devour`, `wisp`, `stomp` — now cast correctly. Plus the earlier 0.5.0
work: projectile spells fire **at** the target, commands to list spells and inspect/validate
loadouts, weighted starting gear, and documented cooldowns. Every new field is optional and backward
compatible, and the offline `bootSanity` GameTest still passes with neither Iron's nor Recruits.

### Added
- **`/magicnpcs spells [filter]`** — list the valid Iron's spell registry ids with school, rarity,
  default cooldown, cast type, and a mob-friendly hint (filter by substring). Read-only; available
  whenever Iron's is present. Backed by the new generated reference
  [`docs/irons_spell_ids.md`](docs/irons_spell_ids.md).
- **`/magicnpcs loadout entity <targets>` / `loadout id <entity_type>`** — show the resolved
  spellcaster loadout(s) for a mob or entity type: source datapack, pool/replace status, and per-spell
  level, weight, range, role, safety radius, resolved cooldown, compat category, and any reason a
  spell would be skipped (unknown/disabled id, needs a target, out of range). Op-only, read-only.
- **`/magicnpcs validate`** — scan all loaded `spellcasters/*.json` and report duplicate/pooled keys
  (with the exact "add `replace: true` to override" guidance), unknown/disabled spell ids, spells that
  need target data, and suspicious ranges (e.g. a forward ground-AoE like `stomp` with a long
  `max_range`). Built for OpenLoader/modpack authors.
- **`replace` loadout flag** — a root-level `"replace": true` makes a loadout override (clear) all
  other non-replace loadouts that share its effective key (`entity_type` + optional `profession`) at
  load time, instead of pooling with them. The explicit "my datapack wins" escape hatch on top of
  0.4.0's pooling.
- **Weighted starting equipment** — an optional per-loadout `equipment` block grants gear on spawn:
  `mainhand`/`offhand` weighted item lists (bare `"id"` or `{ "item", "weight" }`), plus `chance`
  (default 1.0) and `only_if_empty` (default true). Omit the block to keep the existing global
  `equipment.spawnWithGearChance` behaviour. Useful with `requireSpellFocus` to arm casters with a
  Pyrium Staff or other focus.
- **Mob-friendly spell guide** ([`docs/mob-friendly-spells.md`](docs/mob-friendly-spells.md)):
  curated projectile / self-support / melee / AoE / not-recommended categories, with example
  projectile- and melee-mob loadouts.
- **Cooldown documentation** in the README: explicit `cooldown` (ticks) vs `cooldown_multiplier`
  (scales the Iron's default — bigger = slower), the `minCooldownTicks` floor, 20 ticks = 1 s, and a
  worked Phantom `echoing_strikes` 5-second example.

### Fixed
- **Target-locked spells now work for mobs (`root`, `devour`, `wisp`).** These read a
  `TargetEntityCastData` target during `onCast`; Magic NPCs never set one, so they silently did
  nothing. The casting bridge now attaches the mob's target before the pre-cast check and cast.
- **Long (channelled) spells now complete (`root`, `wisp`, `stomp`).** These are `CastType.LONG`, but
  the mob path only ever fired a single immediate `onCast`. The goal now channels for the spell's
  Iron's cast time and then runs `onCast` + `onServerCastComplete`, so the spell resolves correctly.
- **Stomp fires forward.** As a `CastType.LONG` forward ground-AoE, `stomp` now channels and lands its
  AoE in front of the caster (which faces the target throughout the wind-up); `/magicnpcs validate`
  flags an unsuitably long `max_range`, and the docs recommend `max_range ≤ 5`, `safety_radius ~4`.
- **Unsupported spells are skipped, not mis-fired.** A spell a mob can't be given the data for
  (multi-target / player-only) is dropped from the loadout with a clear log line instead of casting
  into the void.
- **Aimed casting / stale projectile direction.** The casting goal relied on `LookControl`, whose
  rotation is applied *after* the goal runs — so on the instant (`windup = 0`) path a projectile
  spell fired in the caster's previous facing. The goal now snaps the caster's yaw/pitch (head **and**
  body) onto the target immediately before `onCast`, so spells launch on-aim regardless of wind-up.
- **Silent unknown spell ids.** A loadout referencing an unresolved spell id used to fail invisibly.
  It now logs a clear warning naming the file, entity, field, and bad id, and a bare id (no namespace)
  is auto-retried under `irons_spellbooks:` (so `devour` resolves to `irons_spellbooks:devour`).

### Changed
- **The bundled `minecraft:skeleton` loadout is no longer shipped as active jar data.** An active
  vanilla-mob default silently changed skeleton behaviour and pooled with (and override-fought) a
  modpack's own skeleton datapack. It now lives as a copy-paste example at
  [`docs/loadouts/examples/skeleton.json`](docs/loadouts/examples/skeleton.json). The shipped
  loadouts that remain (`recruit`, `bowman`, `crossbowman`, `captain`, `guard`) all target optional
  NPC mods, so they stay inert unless that mod is installed.
- **Override semantics are explicit and logged.** Multiple datapacks targeting one entity type still
  pool by default (0.4.0 behaviour), but a load-time warning now names the contributing sources, and
  the new `replace` flag gives deterministic override. `/magicnpcs validate` reports both.
- `NpcSpellAttackGoal.resolveCooldown` now delegates to a pure, unit-tested `CooldownResolver`
  (no behaviour change): explicit ticks > per-spell multiplier > global multiplier, always floored.

### Tests
- New JUnit tests: `LoadoutResolveTest` (pooling vs `replace` override, profession scoping),
  `SpellcasterLoadoutProviderTest` (the jar ships no active vanilla-mob loadout), `CooldownResolverTest`,
  and `WeightedItemPickTest`. New runtime GameTests: `devourCastsWithTargetData`,
  `longCastCompletesAfterCastTime` (root), `stompAoeFiresForward`, plus the earlier `windupAimsAtTarget`,
  `focusGateRequiresHeldFocus`, `bareSpellIdAutoNamespaces`, and `perSpellCastChanceZeroNeverCasts`.
  The two config-mutating GameTests run in isolated batches so they don't perturb the casting tests.

### Notes
- Fully backward compatible **for datapacks** — every loadout field is optional. **Migration for
  0.4.0 users:** Magic NPCs 0.4.0 shipped an active `minecraft:skeleton` example loadout; it has been
  removed from active jar data so modpack datapacks control skeleton behaviour cleanly. If you relied
  on vanilla skeletons casting, copy [`docs/loadouts/examples/skeleton.json`](docs/loadouts/examples/skeleton.json)
  into your own datapack. To make your datapack override a pooled/shipped loadout instead of stacking
  with it, add `"replace": true`. Use `/magicnpcs loadout` and `/magicnpcs validate` to see exactly
  what each mob resolves to.
- If you were confused by cooldowns on an older build: raising `cooldown_multiplier` *increases*
  cooldown; use a value below 1.0 to speed a spell up, or set an explicit `"cooldown"` in ticks.
- Spell ids and item ids are version-specific to your installed Iron's build; `/magicnpcs spells` and
  `/give` are the authoritative sources.

## [0.4.0] — reactive casting, telegraphs, contextual & pooled loadouts

Casters that read the moment and fit the pack: per-spell reactive conditions, a visible
cast "tell" during the wind-up, and loadouts that can be gated by world context or pooled
into per-NPC variants — on top of the cast-pacing/aimed-casting work below. Every addition
is opt-in and backward compatible; existing datapacks and configs are unchanged, shipped
loadouts regenerate byte-for-byte via `runData`, and the offline `bootSanity` GameTest
still passes with neither Iron's nor Recruits installed.

### Added
- **Reactive cast conditions** (per-spell `condition` block; master `reactive.enabled`). A
  loadout spell may gate itself on the situation: `self_hp_below` (panic shields/heals),
  `target_hp_below` (executes), `enemies_within`/`enemies_radius` (favour an AoE when
  swarmed), and `when_recently_hurt`/`recent_damage_window` (blink/retaliate when struck).
  For SUPPORT spells a condition replaces the default "cast when hurt" gate. A satisfied
  condition can also bias selection weight (`reactive.matchedConditionWeightBonus`, default
  1.0 = off) so the right tool is favoured. All vanilla logic — no extra dependency.
- **Cast telegraphs & school identity** (`[feedback]`). When a caster begins its attack
  wind-up it now plays a brief "tell" — server-spawned vanilla particles tinted by the
  spell's Iron's **school colour**, plus a charge sound — scaled by a danger tier (rarity +
  AoE size). Toggles: `feedback.telegraphs`, `feedback.schoolParticles`,
  `feedback.telegraphGlow`, `feedback.telegraphVolume`, `feedback.minDangerTier`.
  Dedicated-server-safe (broadcast to tracking clients); no-op when `castWindupTicks` is 0.
- **Contextual loadouts** (loadout-level `conditions` block). Gate a loadout by world
  context — `dimensions`, `biomes` (ids or `#tags`), `difficulties`, `time` (day/night),
  `min_y`/`max_y`, `require_raid`, `require_storm`, `moon_phases` — evaluated when the mob
  spawns/loads. Lets e.g. nether mobs cast fire, surface mobs cast only at night, or a
  variant appear only during a raid. Vanilla-only predicates.
- **Loadout pools / variety** (loadout-level `pool_weight`). Several loadouts may now target
  the same entity type (and profession); each NPC sticky-picks one by weight (persisted, so
  it does not re-roll on reload) — a skeleton can roll a fire-mage *or* an ice-mage variant.
  Profession-specific loadouts still win over generic ones; within the winning bucket the
  matching, condition-passing variants form the pool.

### Added (cast pacing & aimed casting)
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
- **Multiple loadouts for one entity type now form a pick-one pool** instead of "last file
  wins". If a pack previously relied on a duplicate silently overriding another, give the
  losing file a context `conditions` block or accept that both are now pooled.

### Fixed
- Added the missing config labels for `castChance`, `minCooldownTicks`, and `castWindupTicks`
  so every option now has an `en_us.json` translation key.
- `onRegisterCommands` no longer reads a config value before the server config is loaded
  (guarded with `ForgeConfigSpec.isLoaded()`) — a latent crash that surfaced once the dev
  runtime could load Iron's and reach command registration.

### Dev tooling
- The `-PdevRuntime` gametest/client now actually **runs Iron's + Recruits** in the named
  ForgeGradle workspace (previously impossible): Mixin is given ForgeGradle's SRG→named
  mapping (`mixin.env.refMapRemappingFile`) so Iron's production refmap resolves. The runtime
  stack loads from local `libs/` jars (Iron's 3.16.1 + `irons_lib`, GeckoLib, PlayerAnimator,
  Recruits). `runGameTestServer -PdevRuntime` runs the casting GameTests for real — the
  universal skeleton path passes (real `onCast`, mana spent). See `docs/dev-runtime.md`.

### Notes
- Fully backward compatible: loadout JSON fields are optional and inherit the matching
  global config default when omitted; existing packs and shipped loadouts are unchanged.
- Reactive `condition`s and loadout `conditions` use vanilla world/entity APIs only, so they
  evaluate without Iron's; telegraph particle colours/sounds come from Iron's schools (read
  in the `IronsBridge` seam) and degrade to a neutral tell when a school has none.
- The telegraph look, school-coloured particles, and live reactive/contextual behaviour are
  not runtime-verified in the build environment (no Iron's runtime); verify them in an
  instance with Iron's installed. Builds green and offline `bootSanity` passes.
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
