# Changelog

All notable changes to Magic NPCs are documented here. Versions follow
`MAJOR.MINOR.PATCH`; this is a pre-1.0 line.

## [0.6.3] — casters that know where to stand

Follow-up to 0.6.2. Villager Recruits have cast spells since 0.4.0 and still do; what they could not
do was *behave* like casters. Decompiling the Recruits jar makes the reason concrete:
`AbstractRecruitEntity.registerGoals()` gives **every** recruit a `RecruitMeleeAttackGoal`, and only
the ranged types get a ranged goal on top. A plain `recruits:recruit` handed a magic-missile loadout
has a melee goal, no ranged goal, and a `getMeleeStartRange()` of 32 — so it closes to sword range and
casts on the way in. It is infantry that occasionally throws a spell. (A captain is not in that group:
`CaptainEntity` extends `BowmanEntity`, so it already has ranged AI and a melee-start range of 5.)

### Added

- **Casters reposition.** A mob whose own attack AI is suppressed now backs off when a target is
  inside its minimum range and closes when the target is beyond its maximum, holding the band where
  its own spells are eligible. The band comes from the loadout's own `min_range`/`max_range`, so
  there is one place a pack states where a mob fights and no way for two settings to disagree.
- **It obeys the orders its owner gave it.** Movement goes through a new `movementPolicy` on the
  adapter seam, so a mod's command system decides how much latitude the NPC has. The Recruits adapter
  maps that onto `getShouldHoldPos` / `getShouldFollow` / `getShouldMovePos` and the formation flags:
  a recruit holding a position gets a short leash, one following its owner a longer one, and one in a
  formation or marching to a position is pinned and does not move at all. Policies compose the same
  way every other adapter rule does — the most restrictive answer wins, so registering an adapter can
  only ever make an NPC less free.
- **A caster holds still while channelling.** The cast session re-aims every tick; walking at the
  same time throws that aim away, and a channelled spell that reads the caster's look angle would
  spray.
- **Rank raises spell level.** A progression NPC's rank (a Recruit's XP level) previously scaled only
  its mana pool. The loadout's `level` is now a floor that rank raises toward the spell's maximum,
  bounded by `balance.rankLevelMaxBonus`. Every read of a spell's level — the telegraph, the
  affordability check, the cast, the diagnostic table — goes through one resolver, because checking
  affordability at one level and casting at another makes the caster silently never cast.
- `general.suppressibleAttackGoals` gains the four Recruits attack goals by default, so
  `"native_attack": "suppress"` works on a recruit without having to discover that list first.
  Suppression has been reversible since 0.6.2, so listing them costs nothing when unused.
- `/magicnpcs why` reports the movement policy and its reason code, and shows `lvl1->3` in the
  per-spell table when rank has raised a level.

### Changed

- **Nothing, for existing worlds.** The movement goal engages only where a loadout already sets
  `"native_attack": "suppress"`, and the shipped recruit loadouts keep the default `coexist` — they
  remain battlemages that fight with their weapon and cast alongside it. `native_attack: "suppress"`
  is now documented as the one-line way to build a pure caster.

### Removed

- `recruits.useIronsAI`, `recruits.ironsAiSpeed` and `recruits.ironsAiIntervalTicks`. The first has
  had no effect since 0.6.2; the other two had **no readers at all** and did nothing in any version
  that shipped them. Delete them from `magicnpcs-server.toml` or let Forge drop them.
- The last traces of the Iron's-AI path: the startup warning, the `/magicnpcs config` row, its
  GameTest, three lang keys, and an empty `mixin/` package left behind by the 0.6.2 deletions.

### Why not Iron's own `WizardAttackGoal`

It was considered and rejected on three counts, each from the Iron's 1.20.1-3.16.3 bytecode:

1. Its constructor does `this.mob = (PathfinderMob) spellCastingMob` — a hard cast, so the
   `IMagicEntity` must *be* the mob and a Mixin is mandatory.
2. It declares `MOVE`, `LOOK` and `TARGET`, and knows nothing about hold-position or formations — it
   would override the player's orders, and claiming those flags reintroduces the goal-starvation bug
   ADR 0002 exists to prevent.
3. Its API is four spell lists and a quality float. `min_range`, `max_range`, `safety_radius`,
   `cast_chance`, `cooldown`, `weight` and `condition` cannot be expressed through it, so the loadout
   contract would be lost by construction — which was the original defect.

Recruits' own `RecruitRangedBowAttackGoal` was the model instead: it repositions while consulting the
command system, and declares `LOOK` rather than `MOVE` — flags govern goal scheduling, not access to
the navigation API. See
[ADR 0009](docs/decisions/0009-caster-movement-and-rank-scaling.md).

## [0.6.2] — the datapack that was listed, validated clean, and did nothing

A player added a spellcaster datapack, ran `/reload`, saw it listed, ran `/magicnpcs validate` and was
told there were no issues — and their skeletons still did not cast. Most of the other commands copied
from the project page produced red syntax errors. That report was reproducible and was not one mistake;
it was the intersection of several defects, and a full audit of the 0.6.1 build turned up more of the
same class. This release addresses that audit.

### The reported failure

- **`/reload` could not turn an existing mob into a caster.** The reload handler walked loaded entities
  with `if (!hasSpellGoal(mob)) continue;` — a precondition that skips exactly the mobs a newly added
  datapack is meant to reach. A skeleton that existed *before* the datapack was added had no Magic NPCs
  goal, so every reload passed straight over it and the only way to get a caster was to spawn a fresh
  one. A reload now reconciles **every** loaded mob, in bounded batches across server ticks, and reports
  how many casters it installed, removed, or failed to reconcile.
- **`/magicnpcs validate` could not see the file most likely to be broken.** It read the map of
  successfully parsed, post-override loadouts. A file that failed to parse had already been logged and
  discarded; a file in the wrong folder was never discovered at all. Bundled optional loadouts kept that
  map non-empty, so "no issues found" was a true statement about a set the user's file had never
  entered. Validation now reports **every discovered file** with its status — active, shadowed,
  suppressed or rejected — the exact JSON pointer of each problem, and a suggestion where one can be
  inferred. It also states plainly what it cannot see: a file outside `data/<namespace>/spellcasters/`
  never reaches the mod at all, and validation says so instead of implying the pack is fine.
- **Most advertised commands were not commands.** `/magicnpcs loadout` and `/magicnpcs school` are
  headings that need a subcommand, and `/magicnpcs config` was advertised but not registered in the
  0.6.1 binary at all — which is exactly why `/magicnpcs validate`, the one complete line on the page,
  was the one that worked. Every intermediate node now prints its executable forms with copy-paste
  selector examples, `/magicnpcs config` is back, and the command index lists only complete commands.

### Spell execution

- **Iron's cast lifecycle is now actually run (`MobCastSession`).** The old bridge checked pre-cast
  conditions, called `onCast` directly, and deducted mana. It never called `MagicData.initiateCast`,
  `onServerPreCast` or `onServerCastTick`, and had no notion of a continuous cast — so every spell whose
  effect lives in a cast tick did nothing visible while still charging mana and starting a cooldown:
  starfall's comets, blaze storm's fireballs, ray of siphoning's channel, telekinesis' pull. Casting now
  follows the same order Iron's own casting mobs use, including cancellation, so an interrupted channel
  tears down what its pre-cast created instead of leaking it.
- **Mana and cooldown have one documented transaction point.** Both are charged once, when Iron's
  accepts the cast — the same point a player pays. A spell that is refused before then costs nothing.
- **Spell support is a reviewed manifest, not a guess.** 0.6.1 mapped four spell paths explicitly and
  defaulted *everything else* to "aimed projectile, supported", so the validator and the runtime both
  claimed support for essentially the whole Iron's registry, player-only spells included. Every
  `AbstractSpell` in the Iron's 1.20.1-3.16.3 jar was disassembled and classified on what it actually
  does; the result ships as `SpellManifest`. Anything outside it — an add-on spell, or a newer Iron's
  than this build was checked against — is **UNVERIFIED** and is not cast unless you opt in with
  `spells.allowUnverifiedSpells`. `/magicnpcs spells` and `/magicnpcs validate` now distinguish
  *supported*, *unsupported* and *unverified*, which are three different answers.
- **The declared Iron's range now matches what was tested.** `mods.toml` accepted everything below
  4.0.0; it now ends at 3.17.0, where the verification ends.

### State safety

- **A reload no longer heals your casters.** `applyLoadout` called `initMana` every time and kept
  cooldowns as fields on the goal it was about to replace, so editing a datapack mid-fight refilled
  every caster's mana, cleared every cooldown and reset the decision cadence. Cooldowns, the decision
  deadline and the mana-initialised flag now live in per-entity managed state; mana is filled once, on
  first activation, and a reload that changes nothing does nothing.
- **`native_attack: "suppress"` is reversible.** It used to remove the mob's own attack goals outright,
  with nothing recording how to rebuild them — so a mob that had ever been a suppressing caster could
  never swing its sword again, whatever you changed afterwards. Suppression is now a lease: the original
  goal object is wrapped, held inert at its own priority, and handed back intact when the lease ends.
- **The master switch takes effect immediately.** `general.enableSpellcasting` was read when injecting a
  goal and in the mana tick, but an already-installed goal never consulted it again. It is now checked
  on every decision, *and* a config reload reconciles the world.
- **Equipment tracks the loadout it was granted for.** The permanent "already equipped" latch meant a
  changed loadout could never grant its new gear. The mark is now the loadout's identity, so a genuine
  change applies once — and only once.
- **Reload counts are truthful.** `tryInject` returned `true` even when application had bailed out for a
  mob with no mana attributes, so a reload could report rebuilding casters it had not built. Every
  reconcile returns a typed outcome and reason code, and the counts come from those.

### Restored from 0.6.0

These were present in the 0.6.0 binary, absent from 0.6.1, still advertised, and never announced as
removals.

- **`/magicnpcs config`**, extended with the catalog generation, reconciliation state, the built-in
  loadout toggles, and the manifest's coverage. It also prints the real config paths: Forge's server
  config is per-world under `<world>/serverconfig/`, not `config/`.
- **`caster_chance`** on a loadout — the probability an individual NPC is a caster at all. Rolled once
  per NPC and persisted, so a reload can never flip an NPC into or out of being a caster.
- **`require_held_item` / `required_items` / `required_hand`** per spell, so one spell can need a staff
  while others do not. Separate from the global `equipment.requireSpellFocus`, which gates all casting.
- **`[builtinLoadouts]`** — per-loadout switches for the loadouts Magic NPCs itself ships, so you can
  keep an NPC mod and drop our spells for it without writing a datapack.
- **Raid ally protection.** A raider will not catch another raider from the same raid in its line of
  fire (`targeting.protectRaidAllies`).
- **The sitting-companion gate.** A tamed companion ordered to sit does not cast. It is now its own
  policy rather than a branch inside owner protection, so switching owner protection off no longer
  switches it off too (`targeting.sittingPetsMayCast` opts back in).

### Adapters, schools and safety

- **Adapters compose instead of competing.** The single highest-priority applicable adapter used to win
  and the rest were discarded — so a recruit that was also a pet, or on a team, or in a raid, lost that
  protection *because* a more specific adapter existed. State blockers now combine with AND, ally
  relationships take the most protective answer, and only mana/rank scaling comes from one selected
  provider.
- **A manual school assignment no longer silently falls back to a datapack.** If a hand-assigned school
  yields nothing today — schools disabled, the school gone from Iron's, the pool emptied by a config cap
  — 0.6.1 quietly installed the datapack loadout instead, contradicting the command's own promise that a
  manual assignment overrides any loadout. The three states are now explicit (`AUTO`, `MANUAL_SCHOOL`,
  `MANUAL_DISABLED`) and a manual school installs that school or nothing, with the reason visible.
- **`/magicnpcs school auto <targets>`** returns an NPC to automatic assignment. There was previously no
  way to undo a manual assignment at all.
- **School pools classify by targeting, not by name.** A spell that needs a hostile target can never be
  a self-cast, whatever it is called; the name-keyword heuristic is now the last resort rather than the
  whole rule.
- **Friendly-fire geometry follows the spell.** Everything was measured against a straight caster→target
  corridor. A forward ground AoE lands in front of the *caster* and a target-area spell detonates around
  the *target*; both are now measured where they actually go, and `/magicnpcs why` names the shape and
  the entity that blocked the shot.

### Loadout schema

- **Unknown keys are reported.** `max_manna`, `spell_id` or `castchange` used to be read by nobody and
  mentioned to nobody. They are now a warning by default (an error under `general.strictLoadoutSchema`)
  with the key that was probably meant.
- **A restriction that empties itself is an error.** Invalid values in `dimensions`, `difficulties`,
  `biomes`, `moon_phases` or `required_items` were dropped silently, and an empty restriction list means
  "allow anywhere" — so a typo *widened* the condition instead of narrowing it.
- **Cross-field checks**: inverted `min_range`/`max_range` and `min_y`/`max_y`, moon phases outside 0–7,
  fractions outside 0–1 (with a hint when the value looks like a percentage), and cooldowns large enough
  to be seconds someone forgot to convert.
- **Registry checks**: `entity_type`, `profession` and item ids are resolved, so an unregistered id is
  named instead of producing a loadout nothing can ever match.
- **The bare `{ "enabled": false }` stub works again.** The documented way to switch off a shipped
  loadout is a stub at the same data path; 0.6.1's parser demanded `entity_type` before it read
  `enabled` and rejected exactly that file. The key is now inherited from the loadout being shadowed,
  and the inference is recorded so you can see it happened.

### Recruits

- **`recruits.useIronsAI` is disabled and warns if set.** It handed Iron's `WizardAttackGoal` a bare
  list of spells and discarded every per-entry setting in the loadout — level, weight, ranges, safety
  radius, cast chance, cooldown, wind-up, reactive conditions — while the mixin behind it reported
  `isCasting() == false` and no-oped the lifecycle methods that goal depends on. One config toggle
  therefore changed what a datapack *meant*. The path and its mixin are removed rather than left as a
  trap; recruits use the built-in casting goal, which honours the whole loadout.

### Diagnostics

- `/magicnpcs loadout entity` shows **desired vs installed**: what the mob would resolve to now, what
  its goal is actually running, and `STALE` when those differ.
- `/magicnpcs why` reports the last reconcile and its reason code, catalog staleness, the live cast
  session, the composed adapter list, and stable bracketed codes (`[COOLDOWN]`, `[NO_TARGET]`,
  `[FRIENDLY_FIRE]`, …) so support and tests can refer to a blocker without matching prose.
- `/magicnpcs validate resource <id>` and `/magicnpcs validate id <entity_type>` focus on one file or
  one entity type.
- `/magicnpcs reconcile [targets]` re-evaluates managed state on demand.
- Startup logs the mod version, git commit, build time and detected dependency versions; the jar
  manifest carries the same.

### Tests

- Required GameTests for the audit's acceptance criteria: an already-loaded mob becomes a caster on
  reconciliation, a reload preserves mana and cooldowns, the master switch blocks an installed goal,
  `native_attack` suppression is reversible, a sitting pet does not cast, and an unsupported spell
  spends nothing.
- Unit coverage for the schema contract, resource statuses, restriction-widening, the `enabled:false`
  stub, content hashing, and adapter composition. 112 unit tests, up from 77.
- `./gradlew check` fails when `gradle.properties`, the changelog heading and `mods.toml` disagree on
  the version.

### Known limitations

- Spells needing Iron's mob-specific preparation (`teleport`, `frost_step`, `blood_step`,
  `burning_dash`, `ray_of_siphoning`) are marked unsupported rather than half-implemented: Iron's
  prepares them through `IMagicEntity` hooks a foreign mob cannot provide.
- Loadout context `conditions` remain a **snapshot**, evaluated when a mob is reconciled rather than
  continuously. Crossing a biome boundary does not re-pick a loadout until the next reconcile; the docs
  now say so rather than implying otherwise.

## [0.6.1] — the School Tome actually works, and villagers actually cast

A player asked how to make a villager a spellcaster, then reported that the School Tome did nothing on
their recruit, that there was no way to craft it, and no way to tell which NPC had which school.
Chasing those three turned up a cluster of features that did not work at all. Every root cause below
was confirmed against the Forge 47.4.16 patch set and the Recruits 1.15.2 / Iron's 3.15.2 jars.

### Fixed

- **The School Tome was unreachable on villagers and recruits — its two documented targets.** Forge's
  `Player#interactOn` calls `entity.interact(...)` *before* an item's `interactLivingEntity`, and
  returns early if it consumed the click. Recruits returns SUCCESS for its owner both when sneaking
  (opens the recruit's inventory — exactly what was reported) and when not (cycles follow state), and a
  vanilla villager opens its trade screen. The Tome's logic now runs from a
  `PlayerInteractEvent.EntityInteract` handler, which fires before either.
- **~20 Iron's spells did nothing when NPC-cast, while still charging mana and setting a cooldown.**
  `checkPreCastConditions` is where many Iron's spells *build* their cast data — `HasteSpell` raycasts
  for a target there, spawns a `TargetedAreaEntity` and installs a `TargetedTargetAreaCastData`, and
  `onCast` skips its whole effect without it — but the bridge only called it for the four target-locked
  spells. Every spell now gets its pre-cast step, and a `false` return skips the cast instead of
  spending mana on nothing. Affected `haste`, `blessing_of_life`, `healing_circle`, `sunbeam`,
  `chain_lightning`, `slow`, `wololo`, `arrow_volley`, `blight`, `earthquake` and more — two of which
  ship in the default `schools.supportSpellIds`.
- **Friendly-fire protection was inert at its default radius.** The line-of-fire scan compared ally
  *feet* positions against a segment drawn between two *eye* positions, so an ally standing squarely in
  the line measured ~1.6 blocks away — outside the default `safety_radius` of 1.5. It now measures
  against the whole body, so `friendlyFireCheck`, `protectBystanders`, `protectOwners` and the Recruits
  ally logic do what they say. Spectator and creative players no longer block a cast.
- **A villager with an unmapped profession was permanently barred from ever casting.** The spawn roll
  sticky-marked it a non-caster whenever no school mapped to its profession — 8 of the 15 vanilla
  professions by default — so adding that profession to `professionSchools` later could not rescue any
  existing villager. Only a *decided* outcome (a lost caster-chance roll) is sticky now. The same
  applied to a pool emptied by `maxRarity` / `maxSpellLevel` / `allowedCastTypes` / the spell
  allow-deny list, which are config states, not verdicts.
- **A manually set school reverted on the next chunk reload, and "clear" did not stick.** Goals are not
  persisted, so re-injection resolved the explicit loadout first and threw the player's choice away —
  which is why the Tome and `/magicnpcs school` appeared to do nothing lasting on recruits. A school
  set by hand is now a per-NPC override that outranks any loadout and survives reloads.
- **`/reload` never rebuilt casters already in the world.** The goal captured its loadout by reference,
  so an author's edit only applied to mobs that joined afterwards — despite the docs telling them to
  `/reload`. Live casters are now rebuilt on datapack sync.
- **A datapack weight could crash the server.** Weighted spell/loadout/equipment picks summed weights
  into an `int`; a large weight, or a moderate one scaled by `matchedConditionWeightBonus` (up to
  100×), wrapped negative and made `RandomSource.nextInt` throw out of `Goal#canUse()` into the level
  tick. Totals now saturate instead of wrapping.
- **Removing a casting goal could deadlock a mob's AI.** `applySchool` / `clearSchool` used
  `GoalSelector#removeAllGoals`, which never calls `stop()`, so a running goal kept its MOVE/LOOK/TARGET
  locks forever and left its telegraph glow behind. Both now use `removeGoal`.
- **A caster that idled just before a fight could not act for up to 5 seconds** (2 minutes at the config
  maximum): the out-of-combat cadence was committed before a target appeared and could only ever move
  later. Acquiring a target now pulls the next decision back to the combat cadence.
- **`recruits.enabled = false` removed friendly-fire protection but left casting on**, so recruits
  would blast their own owner. The toggle now suppresses recruit *casting* and rank scaling; the
  diplomacy and ally logic is never removed.
- **A recruit ordered Passive could not heal itself**, which is when it needs to most. The command
  state now gates attacking, not self-cast support.
- **A telegraph glow could be permanent.** `setGlowingTag` writes saved NBT, but only the goal's
  `stop()` cleared it — so a mob that unloaded, died, or was on a server stopped mid-wind-up came back
  outlined forever. Ownership is now recorded in NBT and stranded glows are cleared on join.
- **The Tome's cycle could trap itself on an empty school forever**, because a failed assignment leaves
  the stored school unchanged and the next click recomputed the same one.
- **A blacklisted spell written as a bare id** passed the goal's filter but was refused at cast time,
  and since a refused cast sets no cooldown the mob replayed its wind-up indefinitely. Filtering now
  happens on the resolved id.
- **`windup: 1` behaved as `0`**, and every LONG channel finished a tick early: a goal started during a
  `GoalSelector` tick is also ticked during it, so a configured N produced N-1 ticks.
- **`SpellcasterIllager$SpellcasterCastingSpellGoal`** — shipped in the default suppressible-goal list —
  could never match, because `getSimpleName()` returns only the inner name for a *named* nested class.
  So `native_attack: "suppress"` never removed an evoker's casting goal.
- Current mana is clamped when max mana is rescaled down (Iron's only clamps for players), so lowering
  `manaMultiplier` or dropping difficulty no longer strands a mob above its new maximum.
- The selection scan is no longer re-run every other tick when every spell is blocked.
- `/magicnpcs school info` reports which source is actually driving the mob, not just the stored
  school — the two could disagree.
- `/magicnpcs spells` is op-gated, matching the README's "all op-only"; `school info` reports "no mobs
  selected" like its siblings; `SchoolReroll` failure lists keep the documented order-tried;
  `/magicnpcs spells` no longer NPEs on an addon spell with no registry id.
- Reading school data no longer forces an empty `ForgeData` compound onto every mob in the world (the
  other half of backlog B1).

### Added

- **A craftable School Tome.** It had no recipe and no loot source at all. Two recipes ship under
  mutually exclusive conditions, so exactly one always loads: arcane essence + blank runes when Iron's
  Spellbooks is installed, amethyst + lapis when it is not.
- **Right-click an NPC with the Tome to inspect it** — the only way a survival player can see an NPC's
  school, since `/magicnpcs school info` needs op and the school particles only show during a wind-up.
  It reports which source is actually driving the mob. Sneak-right-click cycles; cycling past the last
  school clears, so "stop casting" stays reachable from the item.
- **`schools.villagers.selfDefense`** (default **off**) — lets a school villager retaliate when
  attacked, so its ATTACK spells are reachable at all.
- Defensive self-buffs added to the default `schools.supportSpellIds` (`oakskin`, `evasion`,
  `invisibility`, `spider_aspect`, `ice_block`, `frost_step`, `shield`, `fang_ward`, and the missing
  healing spells), and a villager is no longer given a school that yields no support spell. Under the
  stock lists four of the six mapped professions — farmer, weaponsmith, toolsmith, fletcher — landed on
  an attack-only school, producing a caster that regenerated mana forever and could never cast.

### Changed

- **Docs: villagers do not cast during raids.** Seven places claimed raids give a villager a target.
  They do not — a vanilla villager has an empty `GoalSelector` and nothing ever calls `setTarget` on
  it; raid behaviour for villagers is hiding. Offensive villager casting needs a guard/NPC mod or the
  new `selfDefense` toggle.
- **Docs: three config keys that do not exist.** `schools.command.permissionLevel`, `schools.item` and
  `schools.command.enabled` were documented; the real paths are `schools.control.commandPermissionLevel`,
  `schools.control.itemEnabled` and `schools.control.commandEnabled`.
- `irons_spellbooks:cure_wounds` dropped from the default support list — no such spell exists in Iron's.
- The telegraph sound is categorised by what the caster is, so a player-owned recruit's tell no longer
  plays under "Hostile Creatures".
- `mods.toml` no longer describes the mod as "tag-driven"; it is datapack-driven.

### Migration

- `windup` values now mean what they say. Existing packs get one extra tick of wind-up per attack
  spell — imperceptible, but re-tune if you had compensated for the old off-by-one.
- If you relied on `recruits.enabled = false` to stop the Recruits adapter while keeping recruits
  casting, they now do not cast at all. That combination previously ran them with no owner protection.
- Spells that were silently doing nothing now either work or are skipped without spending mana, so
  loadouts using them will visibly change behaviour.

## [0.6.0] — casting reaches the mobs it should, and the mod explains itself

The release that answers "X isn't happening and I can't tell why". Mobs with their own ranged AI
(the vanilla witch, and modded equivalents) now cast at all; skeletons shoot **and** cast instead of
casting instead of shooting; support NPCs heal without waiting to be attacked; a datapack loadout
beats the mod's own without needing a flag; magic-school rerolls say what they tried and why; and
`/magicnpcs why` answers the question directly for any live mob. Every root cause was confirmed
against 1.20.1 / Iron's 3.15.2 bytecode before a line was written — see
[`docs/findings/0.6.0-investigation.md`](docs/findings/0.6.0-investigation.md).

**Read the Migration section.** Cooldowns now count real game ticks, which makes existing packs'
casters roughly twice as fast until re-tuned.

### Added
- **`/magicnpcs why <targets>`** — for a live mob, in the order the casting goal itself checks and
  stopping at the first blocker: whether a casting goal was injected (and if not, why not); the full
  goal environment as `priority | class | flags | running`, with any goal that starves the casting
  goal named explicitly; the state gates; target, distance and line of sight; mana and regen; and a
  per-spell table giving cooldown remaining, mana cost, range, condition, and *which entity* is
  blocking a friendly-fire check. Op-only and genuinely read-only.
- **`/magicnpcs school pool [school]`** — per-school survivor counts and, for a named school, the
  exact filter that dropped each spell (`maxRarity`, `maxSpellLevel`, `allowedCastTypes`, the
  blacklist, or Iron's own per-spell config). The direct answer to "is there something I'm missing in
  the configs?"
- **Reload summary in the log** — one line per allowed school (`fire: 7 of 19 spells castable`), a
  warning for any school that can never be assigned, a warning when a compat toggle is off while the
  owning mod *is* installed, and a line naming which loadout won an override contest and which pack it
  came from.
- **Out-of-combat support casting** — a wounded caster with no hostile target now uses its SUPPORT
  spells. Self-cast only, on the separate `balance.supportOutOfCombatIntervalTicks` cadence
  (default 100 ticks), and only while below `balance.supportHealthThreshold`. ATTACK spells are never
  selected without a target. Turn the whole thing off with `balance.supportOutOfCombat = false`.
- **`"enabled": false` on a loadout** — the datapack off switch. A disabled loadout removes itself;
  combined with `"replace": true` it suppresses every loadout for that `entity_type` + `profession`,
  so one small JSON file stops a mob type casting with no jar edits. A disabled loadout may omit
  `spells` entirely.
- **`general.disabledEntityTypes`** — the same escape hatch with no datapack at all: list entity type
  ids that must never cast.
- **`"native_attack"` on a loadout** — how the casting goal coexists with the mob's own attack AI:
  `"coexist"` (default), `"suppress"` (remove the mob's native attack goals — a "pure caster"
  conversion, and every removal is logged), or `"yield"` (only cast while no native attack goal is
  running).
- **`"goal_priority"` on a loadout** and **`general.castingGoalPriority`** — tune the `GoalSelector`
  priority the casting goal is injected at, per entity type or globally.
- **`schools.allowedCastTypes`** (default `["INSTANT", "LONG"]`) — which Iron's cast types a generated
  school pool may draw from.
- **A tested `minecraft:witch` example loadout** at
  [`docs/loadouts/examples/witch.json`](docs/loadouts/examples/witch.json).
- **`config/magicnpcs-common.toml`** — a second config file, in `config/` rather than per world, for
  installation-level settings (see Migration).
- Four new decision records: [0002 casting-goal injection](docs/decisions/0002-casting-goal-injection.md),
  [0003 loadout source tiering](docs/decisions/0003-loadout-source-tiering.md),
  [0004 config split](docs/decisions/0004-config-split.md),
  [0005 out-of-combat support & tick cadence](docs/decisions/0005-out-of-combat-support-and-tick-cadence.md).

### Fixed
- **Mobs with built-in ranged AI never cast (witch, and modded equivalents).** The casting goal was
  injected at priority 2 declaring `Goal.Flag.LOOK`. `WrappedGoal.canBeReplacedBy` requires a
  *strictly* lower priority number, and `minecraft:witch` registers its `RangedAttackGoal` at priority
  2 with `MOVE, LOOK` — so the casting goal could never start while the witch had a target. The goal
  now declares **no** control flags by default and runs alongside native AI. It never needed the flag:
  it snaps its own rotation at cast time because `LookControl` applies too late. Restore the old
  behaviour with `general.castingGoalUsesLookFlag = true`.
- **Skeletons cast instead of shooting.** Same root cause, opposite outcome: the skeleton's bow goal
  sits at priority 4, so a LOOK-claiming casting goal at priority 2 preempted it on every decision. A
  bow skeleton now shoots *and* casts.
- **Support spells only fired after entering combat.** `canUse()` returned false whenever
  `getTarget()` was null, before any role check — one line that defeated the entire SUPPORT design.
- **`/magicnpcs school reroll` reported "0 NPCs" with no explanation.** It picked exactly one random
  school and gave up silently if that school had no castable spells — so the same command succeeded or
  failed run to run ("schools come and go from the random pool"). It now walks the whole shuffled
  allowed pool, excludes the mob's current school, and on total failure reports every school it tried
  and why each failed. The School Tome reports the same reasons.
- **Whole magic schools could never be assigned.** The school pool excluded every `LONG`-cast spell,
  even though 0.5.0 taught the casting goal to channel long casts correctly. Schools that skew
  long-cast were silently empty. `LONG` is now included by default via `schools.allowedCastTypes`;
  `CONTINUOUS` remains excluded (nothing drives a channel loop for a mob).
- **A school caster whose school later became empty regenerated mana forever.** If the caps were
  tightened after assignment, the mob got no casting goal but the tick handler still treated it as a
  caster. It is now marked a non-caster (recoverable with `school set`/`reroll`).
- **Villagers were permanently barred from magic schools.** `EntityJoinLevelEvent` fires while a
  villager's profession is still `minecraft:none`; that profession isn't in the profession→school map,
  and the failed roll was sticky — so under default config every naturally spawned villager was
  disqualified forever. Professionless villagers are now left alone and re-checked once they take a
  job. The same re-check makes `"profession"`-scoped loadouts apply to naturally spawned villagers.
- **A hostile caster in multiplayer silently never cast.** Every `Player` counted as a protected
  bystander, so a caster fighting player A refused to fire while player B stood near the firing line.
  Players are no longer bystanders by default (`targeting.protectBystanderPlayers`, default off), and
  the caster's own target is never treated as a bystander.
- **`enemies_within` counted cows.** The nearby-enemy count filtered on the adapter's `canCastAt`,
  which the default adapter answers `true` for everything — so an AoE condition fired in a pasture. It
  now uses a real hostility test (current target, anything targeting the caster, or an opposing
  monster/non-monster faction member that actually fights).
- **Friendly-fire protection never engaged for mobs tamed or teamed after spawn.** The adapter was
  resolved once in the goal's constructor; it is now refreshed periodically.
- **Pets blasted their litter-mates while their owner was offline.** The sibling check was nested
  inside `getOwner() != null`, and `getOwner()` returns null for an offline player. It compares owner
  UUIDs directly now.
- **Starting equipment was re-granted on every chunk reload.** Goals aren't persisted, so the loadout
  was re-applied on each load — with `only_if_empty: false` that replaced the mob's held item every
  time, and the global `spawnWithGearChance` path re-rolled until it eventually won. The roll is now
  recorded per NPC.
- **An interrupted wind-up replayed its telegraph on repeat.** A cast attempt aborted by the target
  ducking behind a block set no cooldown at all, so it restarted almost immediately.
- **Toggling `telegraphGlow` off mid-wind-up left a mob permanently glowing**, and clearing could strip
  a glow another mod had set. The goal now tracks whether it applied the glow itself.
- **The Iron's-AI path (`recruits.useIronsAI`) ignored the spell blacklist** and could be handed spells
  a mob can never cast; it also passed a null target, so every target-locked spell (`root`, `devour`,
  `wisp`) was silently rejected. It now applies the same filters as the built-in goal, passes the
  mob's target through, and falls back to the built-in goal if nothing survives filtering.
- **`"time": "day"` could never pass in the Nether or the End** (fixed-time dimensions report
  `isDay() == false` forever). The term is now skipped where there is no day/night cycle.
- **`recent_damage_window` above 100 silently behaved as 100** (vanilla clears `lastHurtByMob` then).
  It is clamped at parse time with a warning.
- **`/magicnpcs loadout entity` mutated the world it was inspecting** — it persisted a pool pick and
  drew from the mob's RNG. It is read-only now, and its "1 of N pooled" count reflects this mob's
  actual candidate set rather than every loadout declared for the type.

### Changed
- **Cooldowns and the decision interval now count real game ticks.** See Migration.
- **A datapack loadout beats a mod-jar loadout automatically.** Load-time precedence is now
  **source tier → `replace` → pool** (ADR 0003). The shipped `guardvillagers:guard` loadout no longer
  pools with a pack author's own guard JSON — theirs simply wins, with no `"replace": true` needed.
  `replace` keeps its exact 0.5.0 meaning for datapack-vs-datapack conflicts. The winning source and
  its pack are printed by `/magicnpcs loadout`.
- **The per-mob tick handler no longer resolves loadouts.** It ran full resolution — including a biome
  lookup and a raid query — twenty times a second for every mob in the world, and read (thereby
  creating) persistent NBT on each one. It now early-outs on the regen cadence first and asks the mob's
  own goal list, touching no NBT for non-casters.
- **Reading a mob's school no longer writes to it.** The accessor used to `put` an empty
  `magicnpcs{}` compound on every read, which — because Forge persists `ForgeData` once touched — wrote
  a compound to every mob on the server, permanently, on disk.
- **The friendly-fire corridor is scanned once per decision**, at the widest `safety_radius` among the
  surviving candidates, instead of once per spell with an unfiltered entity query.
- `/magicnpcs school set` and the School Tome now report a reason when an assignment fails.

### Migration
- **Cooldowns are ~2× faster until you re-tune.** `Mob#serverAiStep` only reaches a goal's `canUse()`
  on alternating ticks, and the cooldown/decision counters lived there — so every configured tick value
  behaved as roughly double. They are now absolute `tickCount` deadlines, accurate to ±1 tick. If your
  pack was balanced against the old behaviour, set `balance.cooldownMultiplier = 2.0` to reproduce it,
  or halve your explicit per-spell `"cooldown"` values' effective rate by doubling them.
- **`[compat]` and `general.debugLogging` moved to `config/magicnpcs-common.toml`.** That file applies
  to every world; the old per-world `magicnpcs-server.toml` copies are **still read for this release**
  (a toggle is on if either file enables it), so nothing resets on update. A one-time warning names any
  key still in the old location. The server-side copies are removed in 0.7.0.
- **Modpack authors:** `config/magicnpcs-common.toml` needs no `defaultconfigs/` copy. For the per-world
  `magicnpcs-server.toml`, put your file in `defaultconfigs/magicnpcs-server.toml` to seed every **new**
  world; an **existing** world needs it at `saves/<world>/serverconfig/magicnpcs-server.toml`. See the
  "Modpack authors" section of the README.
- **Datapacks that deliberately pooled with a shipped mod loadout will now win outright** instead of
  splitting per NPC. If you actually wanted the pooling, move your loadout into a resource pack the mod
  does not ship, or use `pool_weight` between your own variants.
- **`targeting.protectBystanders` no longer covers players** by default. Re-enable with
  `targeting.protectBystanderPlayers = true` if your pack wants casters to hold fire around any player.
- **`/magicnpcs school set` now honours `schools.allowedSchools`.** Naming a school outside that list
  used to work; it is now refused with a message saying why, so the config key means what it says. Add
  the school to `allowedSchools` if you want to assign it.
- No datapack field was removed or renamed; every 0.5.0 loadout parses unchanged.

### Tests
- New JUnit: `GoalContentionTest` (the vanilla flag/priority rule, with the witch and skeleton cases as
  fixtures), `SchoolRerollTest` (whole-pool retry, reasons on total failure, current-school exclusion),
  `LoadoutParseTest` (0.5.0 compatibility, the `enabled` off switch, `native_attack`, clamps),
  `PersistentDataTest` (reads never write), `AttackGoalsTest`, plus tier/`enabled` cases added to
  `LoadoutResolveTest` — 55 tests total.
- New runtime GameTests: `witchCastsAlongsideItsRangedGoal`, `skeletonShootsAndCasts`,
  `woundedCasterHealsOutOfCombat`, `fullHealthCasterNeverCastsOutOfCombat`,
  `attackSpellIsNeverCastWithoutATarget`, `cooldownIsMeasuredInRealGameTicks`.
- `bootSanity` still passes offline with neither Iron's nor Recruits installed.

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
