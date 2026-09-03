# Magic NPCs — Next Major Update (0.6.0)
## Investigation, Planning & Implementation Brief for a Coding Agent

**Repo:** `C:\Projects\magic-npcs` (mod id `magicnpcs`, "Magic NPCs", MC 1.20.1 / Forge 47.4.16, current `mod_version=0.5.0`)
**Upstream API:** Iron's Spells 'n Spellbooks (`compileOnly`, target 3.15.2, dev runtime 3.16.1)
**Source of requirements:** three rounds of feedback from a modpack author using the mod, plus a full repository audit.

> All file:line references below were read from the working tree at the time this brief was written. Re-verify line numbers before editing — treat the quoted code as the anchor, not the number.

---

## 0. How to use this document

Work in four phases. **Do not skip phase 1.** Several reported symptoms have more than one plausible cause, and two of them are almost certainly the *same* root cause; guessing will produce a fix that papers over one report and breaks another.

| Phase | Output | Gate |
|---|---|---|
| **1. Investigate** | A findings note per workstream: confirmed root cause, the exact code path, and a reproduction (GameTest or in-world steps from `docs/dev-runtime.md`) | Do not write production code until every P0 workstream has a *confirmed* root cause, not a hypothesis |
| **2. Plan** | One ADR in `docs/decisions/` for each behavioural change with a design choice in it (goal-injection strategy, config split, loadout precedence). Follow the style of `0001-irons-mob-casting.md` | An ADR must state the rejected alternatives |
| **3. Implement** | Code + tests + docs, one workstream per commit | Every behaviour change ships with a test (see §5) |
| **4. Verify** | Full checklist in §6 green | `bootSanity` must still pass with **neither** Iron's nor Recruits present |

### Repository invariants — do not violate these

1. **Iron's isolation.** Every `io.redspace.ironsspellbooks.*` import lives under `com.otectus.magicnpcs.integration.irons` (plus `compat/IronsCompat`). That package is only classloaded behind `IronsCompat.isLoaded()` in `MagicNpcs.java:53`. `core/`, `config/`, `command/`, `data/` must stay Iron's-free — `SchoolCommand` is the model for how to touch school logic without importing Iron's.
2. **Recruits isolation.** Same rule for `compat.recruits` / `mixin.recruits`, gated by `RecruitsCompat.isLoaded()`.
3. **Datapack backward compatibility.** Every new loadout JSON field is optional with a documented default. Never remove or rename an existing field.
4. **Config keys need translations.** Any new `ForgeConfigSpec` entry needs a matching `magicnpcs.configuration.*` key in `src/main/resources/assets/magicnpcs/lang/en_us.json`.
5. **Docs are part of the change.** `CHANGELOG.md`, `README.md`, `docs/loadouts/README.md`, and `docs/schools.md` are user-facing contracts. A behaviour change that isn't in the changelog is a bug report waiting to happen — this brief exists partly because 0.5.0 fixed the skeleton complaint and the reporter never knew.
6. **Verify Iron's API against the jar, never from memory.** `Plan.md` documents a previous cycle lost to a hallucinated method signature. Use the dev runtime (`-PdevRuntime`, see `docs/dev-runtime.md`) or decompile the dep.

### Build / run

```
./gradlew build                 # compile + JUnit
./gradlew runGameTestServer     # GameTests (Iron's-free subset)
./gradlew runServer -PdevRuntime  # with Iron's + deps in libs/ — see docs/dev-runtime.md
```

---

## 1. Reported issues → workstreams

### Triage summary

| ID | Reported symptom | Confirmed cause status | Priority |
|---|---|---|---|
| **W1** | Support NPCs only heal after entering combat | **Confirmed in code** — `canUse()` hard-requires a target | P0 |
| **W2** | Mobs with built-in ranged AI (Luminous Monsters phoenix / witch doctor, vanilla witch) never cast | **Strong hypothesis, needs runtime confirmation** — goal-flag/priority conflict | P0 |
| **W3** | Skeletons cast Magic Missile far too often; no way to disable it | **Confirmed, two parts** — (a) fixed in unreleased 0.5.0, (b) no disable mechanism exists at all | P0 |
| **W4** | `/magicnpcs school reroll` reports "0 NPCs"; schools appear and disappear from the pool | **Confirmed in code** — silent `applySchool` failure + an over-narrow spell filter | P0 |
| **W5** | Config is server-only; had to hand-copy into `defaultconfigs` | **Confirmed** — single `ModConfig.Type.SERVER` registration | P1 |
| **W6** | Guard Villagers use the mod's spells, not the author's JSON | **Confirmed** — the jar ships an *active* `guardvillagers:guard` loadout that pools with theirs | P0 |

---

### W1 — Support spells never cast outside combat  ·  P0

**Report:** *"is it possible to make the npc use spells like heal if they are not in combat cause when they are set on support they only use heal when entering combat"*

**Root cause — confirmed.** `NpcSpellAttackGoal.canUse()` (`integration/irons/NpcSpellAttackGoal.java`, ~line 85):

```java
LivingEntity t = mob.getTarget();
if (t == null || !t.isAlive()) {
    return false;          // <-- SUPPORT can never be considered without a hostile target
}
```

Everything downstream is already target-optional and was clearly designed for this:

- `choose()` passes `null` as the target to a SUPPORT entry's condition (`cond.evaluate(mob, null, adapter)`, ~line 301).
- `windupTargetValid()` short-circuits `return true` for non-ATTACK roles (~line 251).
- `LoadoutEntry`'s javadoc and `CastCondition`'s javadoc both describe SUPPORT as "self-cast when hurt", target-less.
- `SchoolSpellPool` assigns SUPPORT entries `max_range = 0.0`, i.e. explicitly not range-gated.

So the target requirement is the single line that defeats the whole SUPPORT design.

**Investigate**
1. Confirm by GameTest: spawn a caster with a `heal` SUPPORT entry, damage it to 40% HP with no target set, assert no cast; that test should fail after the fix.
2. Check whether Iron's `heal`/`greater_heal` self-target correctly for a non-player caster when `onCast` is called with `CastSource.MOB` and no `TargetEntityCastData` — read the spell class in the dev jar. If any support spell in `SCHOOLS_SUPPORT_IDS` needs target data, it must be classified in `SpellCompat.BY_PATH` and either self-targeted or dropped.
3. Decide whether out-of-combat support should also cover **allies** (heal a wounded neighbour), or self only. Self-only is the smaller change and matches the current data model; ally-targeted support is a genuine feature and deserves its own ADR + loadout field (`"target": "self" | "ally"`). Recommendation: ship self-only in 0.6.0, note ally support as follow-up.

**Design**
- Let `canUse()` proceed with `target == null`, and in that case restrict `choose()` to `Role.SUPPORT` entries. Do **not** simply remove the null check — ATTACK entries must never be selected without a target (they'd cast into empty air and `snapFacing` would NPE-guard around a null).
- Give out-of-combat support its own, much slower cadence. `decisionIntervalTicks` (default 10) is a combat cadence; an idle NPC re-evaluating heals twice a second is pure waste. Add `balance.supportOutOfCombatIntervalTicks` (default ~100) and `balance.supportOutOfCombat` (bool, default `true`).
- Keep the existing gates: `canCastInCurrentState()` (peaceful/sleeping/no-AI/adapter-busy/focus), mana affordability, per-spell cooldown, `castChance`.
- **Anti-loop guard:** a caster at full health must never cast a heal. The current `hurt` gate (`mob.getHealth() < maxHealth * supportHealthThreshold`) already covers that — but a SUPPORT entry carrying an explicit `condition` *replaces* the hurt gate (`NpcSpellAttackGoal` ~line 299-307), so a pack author can write a condition-less-of-health support spell that fires forever out of combat. Add a hard floor: out of combat, a SUPPORT entry with no health-related condition requires `hurt` regardless.
- Telegraphs (`Telegraphs.play`) currently fire for every wind-up. An idle NPC self-healing shouldn't play a combat tell every time; gate the out-of-combat path on `FEEDBACK_MIN_DANGER_TIER` or suppress it.

**Acceptance criteria**
- A wounded caster with `mob.getTarget() == null` self-heals within `supportOutOfCombatIntervalTicks` + windup.
- A full-health caster with no target never casts.
- ATTACK spells are never selected when the target is null (assert in a test).
- With `supportOutOfCombat = false`, behaviour is byte-for-byte the pre-0.6.0 behaviour.
- Idle overhead: measure that a world of 100 idle casters costs no more per tick than before (see W7 perf work — the two interact).

---

### W2 — Mobs with native ranged-attack AI never cast  ·  P0

**Report:** *"some mobs with the ai range attack built into them are not using the spells correctly, very good example is the two mobs from luminous monsters (phoenix, and witch doctor), they are not using the spells at all"* — and separately *"witch still dont seem to be working"*.

**Hypothesis (strong, code-grounded — must be confirmed at runtime).** This is a `GoalSelector` flag/priority conflict.

The casting goal is always injected at a hard-coded priority of **2** and declares `Flag.LOOK`:

- `IronsSpellcasterHandler.applyLoadout()`: `mob.goalSelector.addGoal(2, new NpcSpellAttackGoal(mob, loadout));` (~line 123, and the Iron's-AI branch at ~117)
- `NpcSpellAttackGoal` constructor: `setFlags(EnumSet.of(Flag.LOOK));` (~line 72)

In 1.20.1, `GoalSelector.tick()` only starts a non-running goal if **every** flag it wants is either free or held by a goal that `canBeReplacedBy(candidate)` — and `WrappedGoal.canBeReplacedBy` is `isInterruptable() && other.getPriority() < this.getPriority()`, i.e. **strictly lower priority number**. Vanilla `RangedAttackGoal` declares `EnumSet.of(Flag.MOVE, Flag.LOOK)`.

Therefore:

- **`minecraft:witch`** registers `new RangedAttackGoal<>(this, 1.0D, 60, 10.0F)` at **priority 2**. Equal priority ⇒ `2 < 2` is false ⇒ **our goal can never start while the witch's ranged goal is running**, which is whenever it has a target. Matches the report exactly.
- **`AbstractSkeleton`** adds its bow goal at **priority 4**. `2 < 4` ⇒ our goal *preempts the bow every time*. That is the mechanism behind W3's "shooting magic missile so frequently that it's getting out of hand" — the skeleton isn't casting *often*, it's casting *instead of* shooting.
- Any modded mob that registers its ranged/attack goal at priority ≤ 2 with `Flag.LOOK` is in the witch's bucket. Luminous Monsters' phoenix and witch doctor are the reported instances.

One symptom set, one root cause, two opposite-looking outcomes. Fixing them separately would be a mistake.

**Investigate**
1. **Build the diagnostic first** (see §3 — `/magicnpcs why`). Add a goal dump: for the targeted mob, list every `WrappedGoal` as `priority | class | flags | running?`, plus whether our goal was injected. This turns W2 from archaeology into a one-command answer for every future "mob X doesn't cast" report, including mods you can't compile against.
2. Run it on `minecraft:witch` in the dev runtime with a witch loadout. Confirm our goal is present, `canUse()` would pass, and it is being blocked at the flag gate.
3. Then run it on the Luminous Monsters phoenix and witch doctor. **Check specifically whether those two use `goalSelector` at all.** Some modded mobs (GeckoLib/animation-driven, or Brain/`Behavior`-based) either never tick `goalSelector`, gate attacks behind an animation state machine, or override `Mob#tick`/`aiStep`. If they don't use `goalSelector`, no priority change will help them and the workstream needs a different hook (documented limitation + a `RangedAttackMob#performRangedAttack` interception path is the likely candidate — evaluate, don't assume).
4. Record the finding per-mob. It's fine and correct for the outcome to be "phoenix is unsupported because it doesn't use the goal system" — but say so in the docs rather than leaving it silently broken.

**Design**
- **Make injection priority configurable**: `general.castingGoalPriority` (int, default chosen from the investigation) plus an optional per-loadout `"goal_priority"` field so a pack author can tune one entity type without touching the rest.
- **Reconsider `Flag.LOOK`.** The goal does not need the LOOK lock: it snaps rotation directly in `snapFacing()` precisely because `LookControl` is applied too late (documented at `NpcSpellAttackGoal` ~line 192-196). Declaring no flags means the goal can run *alongside* a native attack goal instead of fighting it — which is the desired behaviour for both the witch and the skeleton. Make this the default, with `general.castingGoalUsesLookFlag` to restore the old behaviour. Note the trade-off in the ADR: with no flags the mob may cast while strafing/pathing under its native goal, which generally looks *better*, not worse.
- **Add an explicit coexistence policy** as a per-loadout field, since packs will want both behaviours:
  - `"native_attack": "coexist"` (default) — run alongside the mob's own attack goal.
  - `"native_attack": "suppress"` — remove the mob's native ranged/melee attack goals on injection (this is what a pack wants for a "pure caster" conversion).
  - `"native_attack": "yield"` — only cast when the mob has no other running attack goal.
  Implement `suppress` via `goalSelector.removeAllGoals(predicate)` with a conservative predicate over known vanilla goal classes plus a config-driven class-name list, and log exactly what was removed.
- Ship a tested `minecraft:witch` example loadout in `docs/loadouts/examples/` once it's confirmed working.

**Acceptance criteria**
- A witch with a loadout casts (GameTest).
- A skeleton with a loadout still shoots arrows *and* casts, at a rate the config controls (GameTest asserting both goals run).
- `/magicnpcs why <mob>` names the blocking goal by class and priority when a mob is starved.
- Documented per-mob results for the Luminous Monsters mobs, including "unsupported, here's why" if that's the finding.

---

### W3 — Skeleton casting frequency, and no way to turn a loadout off  ·  P0

**Report:** *"is there a way to disable your skeleton edit, skeletons are shooting magic missile so frequently that its getting out of hand"*

Three distinct issues hide in this one sentence.

**(a) The shipped skeleton loadout.** Already resolved in the unreleased 0.5.0 — `CHANGELOG.md` records the removal of the active `minecraft:skeleton` loadout from jar data. The reporter is on ≤ 0.4.0. **Action: ship 0.5.0.** Nothing to build.

**(b) Frequency.** Two contributing mechanisms, both real:
- The priority preemption from W2 — the skeleton casts *instead of* using its bow.
- **Cooldowns and the decision interval tick at roughly half the documented rate.** `tickCooldowns()` and `decisionTimer--` both live inside `canUse()` (`NpcSpellAttackGoal` ~lines 77, 81-84, 380). In 1.20.1, `Mob#serverAiStep` only calls `goalSelector.tick()` (the path that reaches `canUse`) on alternating ticks; the other tick calls `tickRunningGoals(false)`. So `minCooldownTicks = 20` is really ~40 game ticks, and `CooldownResolver`'s carefully documented "20 ticks = 1 second" arithmetic doesn't correspond to game time. Verify this against the actual 1.20.1 `Mob#serverAiStep` source before fixing, then move both counters onto a `mob.tickCount`-based deadline so configured tick values mean game ticks.
  *Note:* fixing this makes every existing pack's casters roughly **twice as fast**. Call it out loudly in the changelog and consider compensating the shipped defaults.

**(c) There is no way to disable a loadout.** This is the actual missing feature, and it will keep generating support load. Today the only levers are the `[compat]` namespace toggles (which don't exist for `minecraft:`), a global `spellBlacklist`, or editing the jar. A `"replace": true` loadout with an empty spell list can't be used either — `LoadoutManager.parse` throws `"loadout has no spells"` (~line 293).

**Design**
- Root-level `"enabled": false` in a loadout JSON, honoured in `applyOverrides` — a disabled loadout removes itself *and*, when combined with `replace`, suppresses the whole group for that entity type + profession key. Allow an empty `spells` array when `enabled` is false.
- Config `general.disabledEntityTypes` (list of entity-type ids) as a no-datapack escape hatch, checked in `LoadoutManager.resolve` (not just at injection — see backlog item **B4**).
- Surface both in `/magicnpcs validate` output and the README's troubleshooting section.

**Acceptance criteria**
- One JSON file or one config line stops a given entity type casting, with no jar edits, and survives `/reload`.
- Cooldown values in config correspond to real game ticks (GameTest measuring inter-cast interval).

---

### W4 — School reroll reports "0 NPCs"; schools come and go  ·  P0

**Report:** *"when re-rolling a school, some schools come and go from the random pool, saying 're-rolled schools for 0 npcs', but later reappear in exchange for another school disappearing"*

**Root cause — confirmed.** Two bugs compounding.

**(1) `reroll` picks exactly one random school and gives up silently.** `SchoolCommand.reroll` (~line 69-85):

```java
ResourceLocation pick = allowed.get(mob.getRandom().nextInt(allowed.size()));
if (IronsSpellcasterHandler.applySchool(mob, pick)) { n++; }
```

`applySchool` returns `false` whenever `SchoolSpellPool.buildLoadout(school, mob)` returns `null`. No retry, no other school tried, no message naming the school, no reason. The count reported is the count of *successes*, so a failed roll prints "Re-rolled schools for 0 NPC(s)" with zero information. Because the pick is random per invocation, the same command succeeds or fails run to run — exactly the "come and go" the reporter describes.

**(2) `buildLoadout` returns `null` for whole schools under the default config.** `SchoolSpellPool.buildLoadout` (~line 44-68) drops a spell if **any** of these hold:

| Filter | Line | Default | Impact |
|---|---|---|---|
| `spell.getCastType() != CastType.INSTANT` | ~45 | — | **The big one.** Excludes every LONG and CONTINUOUS spell. Schools whose enabled spell set skews long-cast can end up empty. |
| `!spell.isEnabled()` | ~45 | — | Iron's per-spell config |
| `!MagicNpcsConfig.isAllowed(id)` | ~49 | — | user blacklist/whitelist |
| `rarity(level) > maxRarity` | ~53 | `RARE` | drops EPIC/LEGENDARY-only schools |
| `level = clamp(min, max, maxSpellLevel)` | ~52 | `3` | interacts with the rarity check — a spell's rarity is level-dependent |
| `support && !includeSupport` | ~57 | `true` | — |

Note the inconsistency: the `INSTANT`-only filter dates from before 0.5.0, which **added LONG-cast channelling to the goal** (`resolveWindup` uses `IronsBridge.castTime`, and `IronsBridge.cast` calls `onServerCastComplete` for long casts). The school pool is filtering out spells the goal now handles correctly.

**Investigate**
1. Write a throwaway harness (or extend `/magicnpcs spells`) that, for each of the nine schools, prints the survivor count after each filter stage under the *current* config. This is the ground truth for which schools are empty and why.
2. Confirm whether Iron's `SpellRegistry.getSpellsForSchool` returns disabled spells (it appears to, given the explicit `isEnabled()` check) and whether addon spells register into vanilla schools.

**Design**
- **`applySchool` must return a reason, not a boolean.** Introduce a small result type (`SchoolAssignResult { OK, UNKNOWN_SCHOOL, NO_CASTABLE_SPELLS, NOT_A_MOB }`) so callers can report it. Keep it Iron's-free so `SchoolCommand` can consume it.
- **`reroll` should try the whole allowed pool.** Shuffle `allowedSchoolIds()`, exclude the mob's current school, take the first that yields a loadout. Report per-mob outcomes and a summary of which schools failed and why. Only report 0 when *no* allowed school works for that mob.
- **Align the cast-type filter with the goal's real capability.** Accept `INSTANT` and `LONG`; keep excluding `CONTINUOUS` (nothing drives a channel loop). Make it configurable: `schools.allowedCastTypes` (default `["INSTANT","LONG"]`).
- **Startup / reload diagnostic.** After config load, log one line per allowed school: `fire: 7 castable spells`, `eldritch: 0 castable (all above maxRarity=RARE)`. A school that can never be assigned should be visible without a command.
- **New subcommand `/magicnpcs school pool [school]`** — per-school survivor counts and the drop reason per spell. This is the direct answer to "is there something I'm missing in the configs?"
- Also fix the related sticky-state bug: when a mob already has a stored school and `buildLoadout` now returns `null` (caps tightened after assignment), `trySchoolLoadout` marks nothing and injects nothing, but `onLivingTick` still treats it as a school caster and regenerates its mana forever (`IronsSpellcasterHandler` ~lines 152-159 vs ~282-285). Handle the non-fresh path.

**Acceptance criteria**
- `/magicnpcs school reroll` never reports 0 unless genuinely no allowed school can be assigned, and when it does it says which schools it tried and why each failed.
- `/magicnpcs school pool` explains the pool composition without reading source.
- A school that yields no spells is logged at startup, once.

---

### W5 — Config is server-only  ·  P1

**Report:** *"your configs are only server side and not client... I had to change the server side config then place that file into defaultconfig so every new world has those configs"*

**Root cause — confirmed.** `MagicNpcs.java:39` registers a single spec:

```java
ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MagicNpcsConfig.SPEC, "magicnpcs-server.toml");
```

`SERVER` configs are per-world (`saves/<world>/serverconfig/`), which is correct for gameplay balance but means a modpack author has no pack-level default without the `defaultconfigs/` trick they discovered.

**Investigate**
1. Audit every config read for which logical side it runs on. Current reading suggests **all** reads are server-side (`Telegraphs` spawns server-side particles; feedback options are consumed on the server). Confirm — if that holds, a CLIENT config is not the answer and shouldn't be added just because the reporter said "not client".
2. What the reporter actually needs is *pack-level defaults*. `defaultconfigs/` is the supported Forge mechanism and works — the failure is documentation, not code.

**Design**
- **Document it properly** in the README (a "Modpack authors" section): `defaultconfigs/magicnpcs-server.toml` seeds every new world; existing worlds need the file in `saves/<world>/serverconfig/`. This alone closes the report.
- **Split the spec by audience** where it's genuinely right, via ADR:
  - Keep in `SERVER` (per-world, synced): all `[balance]`, `[targeting]`, `[reactive]`, `[schools]` gameplay values.
  - Move to `COMMON` (`config/magicnpcs-common.toml`, applies to every world): `[compat]` namespace toggles and `general.debugLogging`. These are installation-level facts about what mods are present, not per-world balance — a pack author sets them once.
  - Consider a `CLIENT` spec **only** if the investigation finds a genuinely client-side read; otherwise don't.
- **Migration matters.** Moving a key between files silently resets it to default for existing users. Either read the old location as a fallback for one release, or document the migration prominently and bump the minor version.

**Acceptance criteria**
- A `defaultconfigs/` workflow is documented with exact paths.
- Any key that moves file is listed in the changelog under a "Migration" heading.
- No config read happens on a side where the spec isn't loaded (`MagicNpcsConfig.SPEC.isLoaded()` guards already exist in `onRegisterCommands` — apply the same care to any new early read).

---

### W6 — Author's JSON loses to the mod's shipped loadout  ·  P0

**Report:** *"how do I make guard villagers, human companions, minecolonies use my json files instead of the one used by you, since I only made guard villagers use the spell throw but they are using magic bolt and other stuff"*

**Root cause — confirmed, and the reporter's observation is precisely correct.** The jar ships an **active** loadout for Guard Villagers at `src/generated/resources/data/magicnpcs/spellcasters/guard.json`:

```json
{ "entity_type": "guardvillagers:guard", "spells": [
    { "spell": "irons_spellbooks:magic_missile", "weight": 3, ... },
    { "spell": "irons_spellbooks:guiding_bolt",  "weight": 2, ... },
    { "spell": "irons_spellbooks:heal",          "weight": 1, "role": "support" } ] }
```

That is literally "magic bolt and other stuff". Their own loadout doesn't replace it — `LoadoutManager.resolve` **pools** loadouts sharing an entity type and sticky-picks one per NPC by `pool_weight` (~lines 79-125). So some guards use theirs, some use the mod's, permanently.

Human Companions and MineColonies ship **no** loadout, so their problem there is only the `[compat]` toggle (default off) and correct entity ids — which they've already solved for Guard Villagers and Human Companions.

**Design — three layers, ship all three**

1. **Immediate answer (already built in 0.5.0):** `"replace": true` on their loadout. Plus `/magicnpcs loadout entity <target>` and `/magicnpcs validate` to see what's resolving. Ship 0.5.0 and make this prominent in the README troubleshooting section — the load-time pooling warning already exists in `logOverrideDiagnostics` but nobody reads latest.log until told to.

2. **The real fix — source tiering.** `replace` should be the escape hatch for *datapack vs datapack*, not the thing every user must discover to beat the mod's own defaults. A datapack loadout should beat a jar-shipped loadout **automatically**. Implement a tier on `SpellcasterLoadout` (derived at load time from whether the source pack is the mod's own jar — check the `ResourceManager` pack source, don't string-match the namespace, since a pack may legitimately use `magicnpcs:`). Resolution order: highest tier wins outright; within a tier, existing `replace`-then-pool semantics unchanged. Log it. This needs an ADR — it changes the meaning of existing packs' behaviour.

3. **Reconsider shipping active loadouts for third-party mods at all.** The `guard.json` case shows the cost. Options: keep them but move to a bundled *optional* datapack the user enables; or keep as jar data at the lowest tier (option 2 makes this safe). Document the decision.

**Acceptance criteria**
- A datapack loadout for `guardvillagers:guard` fully replaces the shipped one with no `replace` flag and no config change.
- `/magicnpcs loadout entity <guard>` shows which source won and why.
- A JUnit test in the style of `LoadoutResolveTest` covers tier > replace > pool ordering.

---

## 2. Repository audit — additional defect backlog

Found by reading the tree, independent of the user reports. Verify each before fixing; a few of the perf items interact with W1 and W2.

### P0 — correctness / server health

| ID | Location | Defect | Why it matters |
|---|---|---|---|
| **B1** | `core/SchoolData.java` `root()` + `IronsSpellcasterHandler.onLivingTick` (~282-285) | `root()` is a **mutating getter**: it `put`s an empty `magicnpcs{}` compound into `getPersistentData()` on every read. `onLivingTick` runs for *every* `Mob` in the world and calls `SchoolData.getSchool(mob)` before the early-out. | Every mob on the server gets `ForgeData` forced into existence and an empty compound written — permanently, to disk. Save bloat plus per-entity-per-tick work the early-out was meant to avoid. Fix: make `root()` non-mutating (create only on write), and reorder the tick guard so `SchoolData` is consulted only after a cheap caster check. |
| **B2** | `IronsSpellcasterHandler.rollSchool` (~182-190) + `pickVillagerSchool` (~213-230) | `EntityJoinLevelEvent` fires for a newly spawned/bred villager while its profession is still `minecraft:none`. That profession isn't in `professionSchools`, and with `unmappedGetRandom = false` (default) `pickVillagerSchool` returns null → `markNonCaster()`, which is **sticky**. | Every villager that wasn't already professioned is permanently barred from ever becoming a caster. With default config this silently disables the villager-schools feature. Fix: don't roll (and don't mark) a `minecraft:none` villager; re-roll on profession change. |
| **B3** | `LoadoutManager.resolve` (~74-76) | Same root cause from the loadout side: a `profession`-scoped loadout is evaluated at join time when profession is `none`, and the goal is only injected at join. | `"profession": "minecraft:cleric"` loadouts never apply to naturally-spawned villagers. Fix: re-check on profession change (or a slow villager-only cadence). |
| **B4** | `IronsSpellcasterHandler.onLivingTick` (~282-293) vs `onEntityJoin` (~89-94); `LoadoutCommand` (~66) | The compat-toggle gate (`isLoadoutEnabledFor`) is applied at injection but **not** in `onLivingTick`, which still `rescaleMaxMana` + `tickRegen` on the mob, nor in the loadout command, which reports the loadout as active. | Mana attributes are overwritten on entities that deliberately have no casting goal; the diagnostic command lies. Fix: apply the gate inside `LoadoutManager.resolve` so all consumers inherit it. |
| **B5** | `LoadoutManager.resolve` (~113-125), called from `LoadoutCommand` | `resolve()` **mutates entity NBT** (`LoadoutData.setSource`) and consumes `mob.getRandom()` when a pool has >1 member — and it is called from the documented read-only inspection command. | Running a diagnostic permanently assigns a variant and perturbs the mob's RNG stream. Fix: split `resolve(mob, boolean persist)` / a pure `peek`. |
| **B6** | `IronsGoalFactory` (~29-41) + `mixin/recruits/MixinAbstractRecruitEntityMagic` (~44-49) | The Iron's-AI path skips both `MagicNpcsConfig.isAllowed()` and `SpellCompat.supportedForMob()` that `NpcSpellAttackGoal` applies (~53-71), builds a goal even when every id failed to resolve, and the mixin calls `IronsBridge.cast(self, spell, level)` with a **null target**. | With `recruits.useIronsAI = true`: blacklisted spells cast anyway, and every `TARGET_ENTITY_REQUIRED` spell (root/devour/wisp) is silently rejected at `IronsBridge` (~214-221). Fix: apply the same filters, return null when nothing survives, pass the mob's current target through. |

### P1 — "silently never casts" class of bug (same family as W2)

| ID | Location | Defect |
|---|---|---|
| **B7** | `core/util/LineOfFire.java` (~39, 55-60) + `NpcSpellAttackGoal` (~281-282) | `protectBystanders` and `friendlyFireCheck` both default true, so the friendly-fire scan runs for adapter-less hostile mobs — and `isProtectedBystander` treats **`Player`** and every `TamableAnimal` as protected. In multiplayer, a hostile caster fighting player A silently never casts while player B stands anywhere near the firing line. Fix: exclude entities the caster is hostile to (and players it's targeting) from the bystander set, or only apply bystander protection when the adapter tracks allies. |
| **B8** | `core/loadout/CastCondition.java` (~79-83) | `countHostiles` filters by `adapter.canCastAt()`, but the default adapter returns `true` for everything — so `enemies_within` counts cows and allies. An `enemies_within: 3` AoE condition fires in a pasture. |
| **B9** | `NpcSpellAttackGoal` (~52) | The adapter is resolved once in the constructor. `OwnableTeamAdapter.appliesTo` depends on `getTeam()`/`OwnableEntity` state and `RecruitsAdapter.appliesTo` reads a config toggle — a mob tamed or teamed after spawn keeps the no-op default adapter for life, so friendly-fire protection silently never engages. Fix: resolve lazily per decision or on a cheap cadence. |
| **B10** | `IronsSpellcasterHandler.applyLoadout` / `applyEquipment` (~105-125, 332-338) | Goals aren't persisted, so `hasSpellGoal` is false on every chunk reload and equipment is re-granted. With `only_if_empty: false` the mob's held item is replaced on every reload; the global `spawnWithGearChance` path re-rolls indefinitely until it wins. Fix: persist an "equipped" flag in `LoadoutData`. |

### P2 — performance

| ID | Location | Defect |
|---|---|---|
| **B11** | `LoadoutManager.resolve` called from `onLivingTick` (~282) | Full loadout resolution — two `ArrayList` allocations plus `LoadoutConditions.test()`, which does a **biome lookup** (`level.getBiome(mob.blockPosition())`) and `getRaidAt(...)` — runs 20×/second per caster, when the tick handler only needs it on the mana-regen cadence. Fix: cache the resolved loadout at join time (on the goal or a per-entity field). |
| **B12** | `NpcSpellAttackGoal.choose` (~318) | `LineOfFire.clear` is called **inside the per-spell loop**, and each call builds a corridor AABB and does an unfiltered `getEntitiesOfClass(LivingEntity.class, ...)` — no predicate, so the full list is materialised before filtering. A 6-spell loadout at 20 blocks = 6 corridor scans per decision. Fix: hoist to one scan at the largest `safety_radius` per decision; pass a predicate. |

### P3 — polish

| ID | Location | Defect |
|---|---|---|
| **B13** | `NpcSpellAttackGoal.stop()` (~174-183) | `decisionTimer` is only set in `fire()` and the cast-chance branch. An interrupted wind-up (target ducks behind a block) sets nothing, so `canUse()` restarts the wind-up almost immediately — replaying the telegraph particle burst and sound on repeat. Fix: set `decisionTimer` in `endAttempt()`. |
| **B14** | `compat/generic/OwnableTeamAdapter` (~55-66) | The same-owner sibling check is nested inside `if (owner != null)`, and `getOwner()` returns null when the owning player is offline — so pets blast their litter-mates while the owner is logged out. Fix: compare `getOwnerUUID()` directly. |
| **B15** | `core/feedback/Telegraphs.clearGlow` (~68-71) | Gated on the live config value: toggling `telegraphGlow` off mid-wind-up leaves a mob permanently glowing; when on, it clears a glow tag another mod may have set. Fix: track whether *we* set it. |
| **B16** | `command/LoadoutCommand` (~73) | The "1 of N pooled" count uses `loadoutsFor(type).size()`, which includes profession-scoped loadouts excluded from this mob's pool. Reports a misleading N. |
| **B17** | `core/loadout/CastCondition` (~63-68) | `recent_damage_window` above 100 silently caps at 100 (vanilla clears `lastHurtByMob` at 100 ticks). Clamp at parse time or document. |
| **B18** | `core/loadout/LoadoutConditions` (~55-59) | `"time": "day"` can never pass in the Nether/End (`Level.isDay()` is false with fixed time). Document or bypass for fixed-time dimensions. |

**Verified clean** (don't re-audit): `CooldownResolver` precedence/floor logic, `LoadoutEquipment.pick` and `LoadoutManager.weightedPick` weight math, `MagicNpcsMixinPlugin`, `SpellDiagnostic`/`SpellInfo` null-safety, `LoadoutManager.applyOverrides` determinism, `NpcSpellAttackGoal.snapFacing` yaw/pitch math.

---

## 3. Cross-cutting: make the mod explain itself

**Build this first — it pays for itself inside W2 and W4, and it is the single highest-leverage user-experience change in this release.**

Four of the six reports are "X isn't happening and I can't tell why". The mod already has excellent *static* diagnostics (`/magicnpcs spells`, `loadout`, `validate`) but nothing that answers **"why is this specific mob, right now, not casting?"**

### `/magicnpcs why <target>`

For the selected mob, print in order and stop at the first blocker:

1. **Injection** — is a casting goal present? Which one (`NpcSpellAttackGoal` / `WizardAttackGoal`)? If absent: is there a loadout for this type? Did the compat toggle block it? Did it roll a school? Is it a sticky non-caster?
2. **Goal environment** — every `WrappedGoal`: `priority | class | flags | running`. Explicitly flag "goal X at priority P holds LOOK and outranks the casting goal" (**this is the W2 answer**).
3. **State gates** — alive / removed / sleeping / no-AI / peaceful / adapter `canCastNow` / focus requirement — with the failing one named.
4. **Target** — current target, distance, line of sight.
5. **Per-spell table** — for each loadout entry: cooldown remaining, mana cost vs current mana, role, range check, LOS check, friendly-fire check result *and which entity blocked it*, condition evaluation, resolved cast chance.
6. **Mana** — current / max, regen per cadence.

Op-only, read-only (and genuinely read-only — see **B5**). Iron's-specific rows go through the `IronsBridge`/`SpellDiagnostic` seam so the command stays Iron's-free.

### Supporting diagnostics

- `/magicnpcs school pool [school]` — per-school survivor counts and drop reasons (**W4**).
- Startup summary line: loadouts loaded, schools with zero castable spells, compat toggles on with the owning mod absent (and vice versa — *"guardvillagers is installed but compat.guardvillagers is off"* is a one-line fix for a whole class of support question).

---

## 4. Suggested release shape

**M0 — Ship 0.5.0 as-is.** It already fixes W3(a) and gives the `replace` answer to W6. Don't hold those behind 0.6.0.

**M1 — Diagnostics** (§3). No behaviour change; unblocks M2.

**M2 — Casting reaches the mobs it should** — W2 (goal priority/flags/coexistence), W1 (out-of-combat support), B7 (bystander starvation), B6 (Iron's-AI path filters). These are all "the NPC doesn't cast when it should"; ship them together with one migration note.

**M3 — Predictability & control** — W6 (source tiering), W3(c) (disable mechanism), W4 (reroll + school pool), W3(b) (real-tick cooldowns).

**M4 — Correctness & performance** — B1–B5, B10–B12, then the P3 polish list.

**M5 — Docs, config split (W5), changelog, migration guide.**

Ordering rationale: M2 changes *when* NPCs cast, M3 changes *what* they cast and who wins — mixing them into one release makes user-reported regressions impossible to attribute.

---

## 5. Testing requirements

Follow the existing patterns: JUnit under `src/test/java` for pure logic, GameTests in `gametest/MagicNpcsGameTests.java` + `integration/irons/IronsCastingTests.java` for runtime behaviour, run via `-PdevRuntime` (`docs/dev-runtime.md`).

Minimum new coverage:

| Workstream | Test |
|---|---|
| W1 | GameTest: wounded caster, no target → heals. Full-health caster, no target → never casts. ATTACK never selected with null target. |
| W2 | GameTest: witch with a loadout casts. Skeleton both shoots and casts. JUnit over the priority/flag resolution helper. |
| W3(b) | GameTest measuring real inter-cast tick interval against the configured cooldown. |
| W3(c) | JUnit: `"enabled": false` and the config disable list both remove a type from resolution. |
| W4 | JUnit: reroll retries the whole allowed pool and returns a reason on total failure. JUnit on the cast-type filter. |
| W6 | JUnit extending `LoadoutResolveTest`: tier > replace > pool. |
| B1 | JUnit: reading school data does not mutate the entity's persistent data. |
| B2/B3 | GameTest: a villager spawned professionless and later given a profession becomes eligible. |
| B5 | JUnit: `peek` doesn't write NBT or draw from the RNG. |

`bootSanity` (no Iron's, no Recruits) must stay green throughout.

---

## 6. Definition of done

- [ ] Every P0 workstream has a confirmed root cause documented, not a hypothesis
- [ ] An ADR exists for: goal-injection strategy (W2), loadout source tiering (W6), config split (W5)
- [ ] `./gradlew build` clean; all JUnit + GameTests pass; `bootSanity` passes with neither optional mod
- [ ] No new per-entity-per-tick work; B1 and B11 measurably reduced
- [ ] Every new config key has an `en_us.json` translation and a comment
- [ ] Every new datapack field is optional and documented in `README.md` **and** `docs/loadouts/README.md`
- [ ] `CHANGELOG.md` has Added / Fixed / Changed / **Migration** sections; the cooldown-rate change (W3b) and any moved config key are called out explicitly
- [ ] Iron's imports still confined to `integration.irons`; Recruits imports to `compat.recruits`/`mixin.recruits`
- [ ] `/magicnpcs why` answers all six original reports without reading source

---

## Appendix A — What to tell the reporter today (no code required)

1. **Skeletons:** 0.5.0 removes the shipped `minecraft:skeleton` loadout entirely. Until then, add `irons_spellbooks:magic_missile` to `spellBlacklist` in `magicnpcs-server.toml`, or raise `cooldownMultiplier` / `minCooldownTicks`.
2. **Guard Villagers using the wrong spells:** the jar ships an active `guardvillagers:guard` loadout (magic missile + guiding bolt + heal). On 0.5.0, add `"replace": true` to the root of their own guard JSON. Then `/magicnpcs loadout entity <guard>` confirms which source won, and `/magicnpcs validate` lists every pooled conflict in the pack.
3. **Human Companions / MineColonies:** no loadout ships for those — only the `[compat]` toggle plus a correct `entity_type`. `/magicnpcs loadout id <entity_type>` verifies the id resolves.
4. **Witch / phoenix / witch doctor:** confirmed as a goal-priority conflict with those mobs' built-in ranged attack AI; no workaround on the current build, fix targeted for 0.6.0.
5. **Heal out of combat:** confirmed limitation, fix targeted for 0.6.0.
6. **Reroll "0 NPCs":** not a config mistake on their end — the command tries exactly one random school and reports nothing when that school has no castable spells under `maxRarity` / `maxSpellLevel` / the INSTANT-only filter. Raising `schools.maxRarity` to `EPIC` and `maxSpellLevel` will make more schools succeed in the meantime.
7. **Pack-level config defaults:** their `defaultconfigs/` approach is the correct and supported method — it will be documented properly rather than replaced.
