# Magic NPCs 0.6.1 failure analysis and remediation specification

**Audit date:** 2026-08-28  
**Repository:** <https://github.com/otectus/magic-npcs>  
**Audience:** a coding agent or maintainer preparing the next corrective release  
**Primary report:** a datapack is listed and `/magicnpcs validate` is clean, but skeletons do not cast; most other commands copied from the project description fail.

---

## Executive summary

The report is reproducible and is not one user mistake. It is the intersection of several confirmed product defects:

1. **The current public description advertises command roots, not executable command lines.** In the published 0.6.1 JAR, `/magicnpcs loadout` and `/magicnpcs school` need additional subcommands and arguments. `/magicnpcs config` is advertised but is not registered at all. `/magicnpcs validate` is the one advertised line that is already complete, which precisely explains why it works while the others produce Brigadier errors.
2. **A full datapack reload cannot turn an already-loaded ordinary skeleton into a caster.** The 0.6.1 reload handler iterates only mobs that already have a Magic NPCs spell goal. A skeleton that existed before the datapack was loaded has no such goal, so it is skipped. A skeleton spawned after `/reload` is processed by the entity-join handler and can work.
3. **`/magicnpcs validate` cannot validate the file most likely to be broken.** Invalid loadout JSON is logged and discarded before the command inspects the catalog. Wrongly located or undiscovered files never enter the catalog. The command examines only successfully parsed, post-override loadouts. Bundled optional loadouts can keep that successful snapshot nonempty, allowing the command to say “no issues” even when the user's skeleton file was rejected or never discovered.
4. **A skeleton needs a live hostile target.** An attack-only loadout never casts while idle. Creative and spectator players are not normal hostile targets; Peaceful, `NoAI`, range, line of sight, focus requirements, safety checks, and native-goal arbitration can also block a cast. The public quick-start does not put the test into a deterministic combat state.
5. **The published artifacts are inconsistent.** GitHub `main` identifies itself as 0.5.0 and does not contain the exact 0.6.1 implementation. The 0.6.1 binary gains some work but loses several classes and fields that are present in the published 0.6.0 binary and still advertised publicly, including `/magicnpcs config`, `caster_chance`, held-item requirements, `[builtinLoadouts]`, raid ally protection, and the sitting-tameable gate. The 0.6.1 changelog does not declare these as removals. This looks like an incomplete branch merge or release assembled from the wrong source line.
6. **The Iron's spell bridge does not implement Iron's mob-casting lifecycle.** It directly calls `onCast`, skips cast initiation, pre-cast hooks, cast ticks, and continuous casting, then unconditionally spends mana. The compatibility classifier explicitly maps only four spell paths and labels almost everything else as a simple supported spell. Long, continuous, player-only, addon, and special-preparation spells can therefore be reported as supported while producing partial effects, no effects, or the wrong effects.

The minimum safe release is not a documentation-only patch. The maintainer should first recover and commit the exact 0.6.0 and 0.6.1 source histories, deliberately merge the two feature lines, then repair resource diagnostics and live-entity reconciliation. Spell compatibility should either fail closed in the first hotfix or be replaced with a real cast-session state machine before claiming broad spell support.

---

## Scope, artifacts, and confidence

This audit compares public GitHub content with the published 0.6.0 and 0.6.1 JARs, and compares the 0.6.1 Iron's bridge with Iron's Spells 'n Spellbooks 3.16.1. JAR behavior was inspected statically from bytecode/decompiled classes; decompiled names are useful for locating behavior but are not a substitute for recovering the maintainer's original source.

### Artifact fingerprints

| Artifact | Identity | SHA-256 / commit |
|---|---|---|
| GitHub `main` at audit time | Repository source; `gradle.properties` says `mod_version=0.5.0` | `586c50a13fd7c75999bfea0efec84231ca972ac2` |
| Magic NPCs published JAR | `magicnpcs-0.6.0.jar` | `24d005afff8373025589c83cac584e6f33b294180eb655b28826132d31ee7306` |
| Magic NPCs current published JAR | `magicnpcs-0.6.1.jar`; CurseForge file 8729954 | `2a8170d08f94a41cc9e759e3503e494fcc60b77e08edd6ca13bf3bd509a0ad0b` |
| Iron's comparison JAR | `irons_spellbooks-1.20.1-3.16.1.jar` | `b847b5a0d0b8d81ebf9f21f29fee9ad180a235b8bf983efa902eb67fc1485862` |

### Evidence labels used below

- **Confirmed:** directly visible in the public source, published class/resource set, or executable command tree.
- **Regression:** present in 0.6.0, absent in 0.6.1, and not announced as a removal.
- **Contract gap:** code is internally consistent, but public behavior or diagnostics promise more than the implementation delivers.
- **Design risk:** likely to cause related failures and deserving a test/fix, but not needed to explain the quoted report.

### Important repository constraint

Do not implement the corrective release by editing GitHub `main` as though it were the released code. At the audited commit:

- `gradle.properties` says 0.5.0;
- there are no 0.6.0 or 0.6.1 tags/releases in GitHub;
- the 0.6.1 JAR contains classes absent from `main`;
- the 0.6.1 JAR also lacks classes present in the 0.6.0 JAR.

**P0 prerequisite:** recover the exact source used for both release binaries, commit each as a tagged historical point, and merge intentionally. If the original 0.6.1 source cannot be recovered, reconstruct it on a dedicated branch and mark every bytecode-derived section for human review.

---

## Exact answer to the affected player's report

### Why the commands fail

The 0.6.1 JAR registers this actual command tree. Angle brackets below are placeholders and must not be typed literally.

| Intent | Executable 0.6.1 command | Common non-executable line |
|---|---|---|
| Show help | `/magicnpcs` | — |
| Explain a live mob | `/magicnpcs why <targets>` | `/magicnpcs why` |
| Inspect a selected mob | `/magicnpcs loadout entity <targets>` | `/magicnpcs loadout` |
| Inspect an entity type | `/magicnpcs loadout id <entity_type>` | `/magicnpcs loadout` |
| Validate accepted loadouts | `/magicnpcs validate` | — |
| List spells | `/magicnpcs spells [filter]` | — |
| Assign a school | `/magicnpcs school set <targets> <school>` | `/magicnpcs school` |
| Inspect a school assignment | `/magicnpcs school info <targets>` | `/magicnpcs school` |
| Reroll/clear | `/magicnpcs school reroll <targets>` or `/magicnpcs school clear <targets>` | `/magicnpcs school` |
| Inspect school pools | `/magicnpcs school pool [school]` | `/magicnpcs school` |
| Inspect config | **Not present in 0.6.1** | `/magicnpcs config` is advertised but errors |

The diagnostic commands generally require permission level 2. The school command uses its configured permission level. The public description's short `/magicnpcs loadout` and `/magicnpcs school` labels are headings, not executable syntax, but they are presented as commands. `/magicnpcs validate` happens to be complete, which produces the user's exact asymmetry.

Copy-paste examples for a nearby skeleton are:

```mcfunction
/magicnpcs loadout id minecraft:skeleton
/magicnpcs loadout entity @e[type=minecraft:skeleton,sort=nearest,limit=1]
/magicnpcs why @e[type=minecraft:skeleton,sort=nearest,limit=1]
/magicnpcs spells magic_missile
```

### Why the skeleton does not cast

The most likely sequence is:

1. The skeleton was already loaded.
2. The datapack was added and `/reload` was run.
3. `LoadoutManager` reloaded the JSON.
4. `IronsSpellcasterHandler.onDatapackSync` scanned loaded entities, but continued unless `hasSpellGoal(mob)` was already true.
5. The ordinary skeleton had no spell goal, so the handler never called `tryInject` for it.
6. The skeleton remained an ordinary skeleton until it left/re-entered through an entity-join path or a new skeleton spawned.

This is a confirmed control-flow defect. The reload message “rebuilt N live casters” is narrowly accurate: it rebuilds existing *casters*, not every loaded mob newly matched by the updated datapack.

A second independent requirement is a target. `magic_missile` with `role: "attack"` is only eligible when the mob has a live target satisfying relationship, range, line-of-sight, safety, config, and mana checks. A skeleton staring at a Creative-mode operator is not a deterministic casting test.

### Immediate workaround for 0.6.1

This is a support workaround, not the product fix:

1. Confirm the server and client are using the same `magicnpcs-0.6.1.jar` and compatible Iron's dependencies.
2. Put the JSON at `data/<namespace>/spellcasters/<name>.json`, then run `/reload`.
3. Run `/magicnpcs loadout id minecraft:skeleton`. If it says no loadout is declared, inspect `latest.log`; `/validate` is not sufficient.
4. **Spawn a new skeleton after the reload** or unload/reload its chunk so the entity-join handler runs.
5. Test on Normal difficulty, without `NoAI`, with a Survival/Adventure player or another valid hostile target in the configured range and line of sight.
6. Run the full selector form of `/magicnpcs why ...` against that new skeleton.
7. Look for `Loaded ... spellcaster loadout(s)` and `Skipping invalid spellcaster loadout ...` in `latest.log`.

The following fixture is intentionally minimal:

```json
{
  "entity_type": "minecraft:skeleton",
  "replace": true,
  "max_mana": 200,
  "mana_regen": 10,
  "spells": [
    {
      "spell": "irons_spellbooks:magic_missile",
      "level": 1,
      "role": "attack",
      "min_range": 0,
      "max_range": 24,
      "safety_radius": 0,
      "cast_chance": 1,
      "cooldown": 20,
      "windup": 0
    }
  ]
}
```

Use the zero safety radius only for an isolated diagnostic fixture. It is not a recommended production balance setting.

### What `/validate` currently proves—and does not prove

It proves only that the post-parse, post-override `LoadoutManager.snapshot()` entries do not trigger its small set of spell/range checks. It does **not** prove that:

- the user's JSON was discovered;
- the JSON parsed;
- its entity type exists;
- it survived pack/source-tier overrides;
- it was not suppressed;
- the relevant entity was injected or reconciled;
- the mob currently has a casting goal;
- the spell bridge can execute the full lifecycle of the selected spell;
- the mob has a target or passes current runtime gates.

The success text must stop implying otherwise until validation owns the complete resource catalog.

---

## P0 and P1 issue index

| ID | Priority | Finding | User-visible consequence |
|---|---:|---|---|
| REL-001 | P0 | No released source/tag matches 0.6.1 | A coding agent can patch the wrong implementation and reintroduce lost features |
| REL-002 | P0 | 0.6.1 appears to omit multiple 0.6.0 features | Advertised JSON/config/safety behavior silently disappears on upgrade |
| CMD-001 | P0 | Public command roots are not executable | Most copied commands produce syntax errors |
| CMD-002 | P0 | `/magicnpcs config` is advertised but absent | The documented config diagnostic always errors |
| RLD-001 | P0 | Reload reconciles only mobs already having a spell goal | Existing skeletons never become casters after adding a datapack |
| VAL-001 | P0 | Rejected/undiscovered resources are absent from validation | “No issues” can coexist with a rejected skeleton file |
| SPI-001 | P0 | Iron's cast lifecycle is bypassed | Long/continuous/special spells are partial, no-op, or incorrect |
| SPI-002 | P0 | Compatibility registry maps only four spell paths; nearly all others default to supported | Validator and runtime produce false support claims |
| RCN-001 | P1 | No idempotent desired-vs-actual caster reconciler | Reload/config/manual-school transitions leak state or fail to apply |
| RCN-002 | P1 | Reapplication refills mana and resets cooldown/decision state | `/reload` changes combat balance and can enable burst casts |
| RCN-003 | P1 | Native attack suppression is destructive and not reversible | Removing/changing a loadout can permanently alter a live mob's AI |
| CFG-001 | P1 | Master switch is absent from a goal's live state blockers | Already-installed goals can keep casting after a live config change |
| TGT-001 | P1 | Adapter resolution selects one adapter rather than composing policies | Owner/team/raid safety can disappear when a specific adapter wins |
| TGT-002 | P1 | `RaidAllyAdapter` present in 0.6.0 is absent in 0.6.1 | Raiders can cast through raid allies despite public safety claims |
| TGT-003 | P1 | Sitting-tameable cast gate present in 0.6.0 is absent in 0.6.1 | Ordered-to-sit companions can keep casting |
| RCT-001 | P1 | Recruits Iron's-AI path discards per-entry policy | Levels, ranges, weights, cooldowns, safety, conditions, and windup do not mean what JSON says |
| RCT-002 | P1 | Recruits `IMagicEntity` mixin reports `isCasting=false` and no-ops lifecycle methods | `WizardAttackGoal` cannot maintain Iron's casting state correctly |
| TST-001 | P1 | Spellcasting GameTests are non-required and bypass datapack/injection paths | CI can be green while the documented quick-start is broken |

---

## Release and source divergence

### Confirmed release matrix

| Capability | 0.6.0 JAR | 0.6.1 JAR | Current public material | Required action |
|---|---:|---:|---:|---|
| `ConfigCommand` / `/magicnpcs config` | Present | **Absent** | Advertised | Restore and test, or remove every claim before release |
| `caster_chance` loadout field | Present | **Absent** | Advertised | Restore parser, persistence, diagnostics, and tests |
| `require_held_item`, `required_items`, `required_hand` | Present | **Absent** | Advertised | Restore or explicitly migrate; never silently ignore |
| `[builtinLoadouts]` toggles | Present | **Absent** | Advertised | Restore per-builtin toggles or publish an intentional replacement |
| Automatic cast-goal priority helper | Present | **Absent** | Earlier behavior/config implied | Merge intentionally with newer native-attack policy |
| Raid ally adapter | Present | **Absent** | “never own raid” behavior promised | Restore as a composable policy |
| Sitting tameable gate | Present | **Absent** | Companion command safety expected | Restore and test |
| `/magicnpcs why` and root help | Absent | Present | Useful current feature | Keep |
| Source-tier precedence | Different/older mechanism | Present via `LoadoutSourceTier` | “user datapack wins” | Keep, but expose records in diagnostics |
| `native_attack` policy | Absent/older behavior | Present | Current behavior | Keep only after making it reversible |
| school diagnostic/report classes | Absent | Present | Current behavior | Keep |
| school tome recipes | Absent | Present | Current behavior | Keep |

The pattern is not a normal linear upgrade. The next branch must not simply choose 0.6.0 or 0.6.1 wholesale. Build a feature inventory, port every intended capability, and write one regression test per row.

### `enabled: false` incompatibility

The public override guidance includes a same-path disabling stub such as:

```json
{ "enabled": false }
```

The 0.6.1 parser reads mandatory `entity_type` before it reads `enabled`. That stub is rejected. A disabled record can omit `spells`, but cannot omit `entity_type`; suppression also requires `replace: true` under the current resolver.

Choose and document one stable contract:

- **Preferred compatibility behavior:** when the highest pack resource at a logical path is an `enabled:false` stub, inspect the lower resource stack to infer the effective entity/profession key and suppress it. Preserve a catalog record explaining the inference.
- **Simpler breaking behavior:** require `entity_type` and `replace:true`, reject the bare stub with an actionable error, and update all examples. This is less compatible and should require a documented migration.

### Release provenance fix

Before functional work:

1. Create immutable `v0.6.0` and `v0.6.1` tags from recovered source.
2. Add `Implementation-Version`, git SHA, build timestamp, and dirty-tree status to generated build metadata.
3. Make CI fail if `gradle.properties`, `mods.toml`, changelog heading, and artifact filename disagree.
4. Attach source and checksums to the release.
5. Generate a class/resource inventory diff between the previous release and candidate. Require a reviewed allow-list for removed public classes, commands, JSON fields, configs, and bundled data.
6. Add a startup log line with Magic NPCs version, source SHA, Iron's detected version, Recruits detected version, and config paths.

---

## Command system corrections

### CMD-001: make intermediate nodes executable

Every advertised command node should either perform a useful default or print its child usage. It should never terminate in Brigadier's generic red syntax marker.

Recommended behavior:

| Input | Correct response |
|---|---|
| `/magicnpcs` | Concise command index and detected versions |
| `/magicnpcs loadout` | Print the two executable forms with copy-paste selector examples |
| `/magicnpcs loadout entity` | Explain that a selector is required and show nearest-mob example |
| `/magicnpcs school` | Print `info`, `set`, `reroll`, `clear`, and `pool` forms |
| `/magicnpcs why` | Explain selector requirement and show nearest-mob example |
| `/magicnpcs config` | Show effective values, source files, and whether restart/reload/reconcile is needed |

Register the `magicnpcs` root once in a central `MagicNpcsCommands` builder. Attach all children there, rather than registering several separate roots from separate classes. Add executable handlers to intermediate literals that return a normal command result after showing usage.

### CMD-002: restore `/config`

`ConfigCommand` exists in the 0.6.0 JAR and is omitted from 0.6.1. Port it forward and extend it to show:

- effective master switch and runtime state;
- server config: `<world>/serverconfig/magicnpcs-server.toml`;
- common config: `config/magicnpcs-common.toml`;
- current casting goal policy/priority;
- compat namespace toggles and whether the owner mod is installed;
- built-in loadout toggles after they are restored;
- spell allow/deny list counts;
- school settings;
- detected dependency versions;
- whether loaded entities have been reconciled against the current config generation.

Do not describe the server config as `config/magicnpcs-server.toml`: Forge's `SERVER` config is world-specific under `<world>/serverconfig` in a dedicated server/world.

### CMD-003: correct help syntax

The 0.6.1 help combines school forms as:

```text
/magicnpcs school set|reroll|clear <targets> [school]
```

The actual tree requires a school only for `set`. Print separate lines. Also change “stopping at the first blocker” for `/why`; the implementation emits multiple diagnostic sections and does not consistently stop at one blocker.

### Command acceptance tests

Use Brigadier parse and execute tests for every documented line:

- all complete examples parse with no unread input;
- all intermediate nodes return usage, not a syntax exception;
- literal angle-bracket examples are clearly marked as placeholders;
- selectors work for one and multiple mobs; `/why` retains its five-target cap and says so;
- permission failures are explicit;
- `/config` exists in the built JAR;
- README/CurseForge command snippets are extracted and executed automatically in CI where possible.

---

## Resource loading and validation corrections

### VAL-001: retain every resource outcome

`LoadoutManager.apply` currently catches parse exceptions, writes a logger line, and drops the record. `snapshot()` exposes only successfully parsed, resolved loadouts. Replace that split-brain model with a catalog that retains provenance and status for every discovered logical resource.

Suggested data model:

```java
record LoadoutResourceRecord(
    ResourceLocation resourceId,
    String packId,
    LoadoutSourceTier tier,
    Status status,                 // PARSED, REJECTED, SHADOWED, SUPPRESSED, ACTIVE
    @Nullable ResourceLocation entityType,
    @Nullable ResourceLocation profession,
    @Nullable SpellcasterLoadout loadout,
    List<LoadoutProblem> problems,
    String contentHash
) {}

record LoadoutProblem(
    Severity severity,             // INFO, WARNING, ERROR
    String code,
    String jsonPointer,
    String message,
    @Nullable String suggestion
) {}
```

Keep two views:

- `LoadoutCatalog.records()` for diagnostics, including rejected/shadowed/suppressed resources;
- `LoadoutCatalog.activeByType()` for fast runtime resolution.

Publish the complete catalog atomically only after the reload pass finishes. If a catastrophic loader failure occurs, keep the last known-good active catalog and publish the failed generation for diagnostics instead of replacing runtime state with a partial map.

### Required `/validate` output

At minimum, validation should report:

```text
Magic NPCs validation (generation 17)
Discovered: 8  Parsed: 7  Active: 5  Shadowed: 1  Suppressed: 1  Rejected: 1
ERROR my_magic:skeleton_missile (pack my_magic) /spells/0/spell:
      unknown spell irons_spellbook:magic_missile
      did you mean irons_spellbooks:magic_missile?
WARN  other_pack:skeleton: pooled with my_magic:skeleton_missile for minecraft:skeleton
Result: FAILED (1 error, 1 warning)
```

Add optional focused forms:

```mcfunction
/magicnpcs validate
/magicnpcs validate resource my_magic:skeleton_missile
/magicnpcs validate id minecraft:skeleton
```

If the runtime cannot discover a file because it is outside `data/*/spellcasters/`, the command cannot honestly claim to validate it. State this limitation and add a startup/help hint showing the exact folder. A separate build-time linter can scan an unpacked datapack for near-miss folders such as `spellcaster`, `spellcaster_loadouts`, or `magicnpcs/spellcasters` at the wrong level.

### Schema and semantic checks

The parser/validator must report, not silently normalize, the following:

#### Resource and registry checks

- `entity_type` exists in the entity registry and resolves to a `Mob`-capable type where determinable;
- optional villager `profession` exists;
- spell ID exists, is enabled, has the requested level within its min/max range, and has an explicit supported capability strategy;
- dimension, biome, biome tag, item, item tag, and school IDs resolve;
- dependency namespace toggles and owner-mod presence are shown as inactive reasons, not parse success;
- item tag is nonempty when focus is required.

#### Numeric and cross-field checks

- `min_range <= max_range`;
- `min_y <= max_y`;
- moon phases are integers from 0 through 7;
- chance/health fractions are within `[0,1]` and emit a warning if clamped;
- cooldown, windup, decision interval, weight, and mana are within documented safe bounds;
- requested spell level has a valid mana cost and at least one configured mana pool can afford it;
- a SUPPORT entry is compatible with self/ally targeting; a target-required spell cannot silently become self-cast;
- `enemies_within` and `enemies_radius` are coherent;
- `enabled:false` semantics are unambiguous;
- `replace`, pool weights, source tiers, and profession buckets produce a deterministic explanation.

#### Unknown keys

Unknown keys are currently ignored, so `max_manna`, `spell_id`, or `castchange` quietly becomes a default or missing field. Reject unknown keys in strict mode and warn by default. Allow only documented comment keys such as `_comment` and `__comment`, or a clearly specified prefix.

#### Silent invalid-list widening

Do not silently drop invalid values from a restriction list and leave the list empty, because an empty restriction can become “allow anywhere.” If a user supplied a nonempty `dimensions`, `difficulties`, `biomes`, or `required_items` list and no values resolve, make the record invalid. If only some fail, report each dropped value.

### VAL-002: make diagnostics describe active state

`/magicnpcs loadout entity` uses `LoadoutManager.peek(mob)`. That reports what resolves *now*, not necessarily the loadout captured by the already-installed `NpcSpellAttackGoal`. After a context/config/data change, it can lie about what the mob is actually running.

Print both when they differ:

```text
Desired: my_magic:skeleton (catalog generation 17, hash abc...)
Installed: old_pack:skeleton (generation 16, hash def...)
Status: STALE — queued for reconciliation
```

`/why` must also explain:

- no goal because the mob was never reconciled;
- missing mana attributes/application failure;
- stale desired vs installed loadout;
- global config generation mismatch;
- native attack currently winning/yielding;
- missing target, invalid relationship, range, LOS, safety blocker, focus, mana, cooldown, cast chance, pre-cast rejection, or unsupported lifecycle capability;
- Iron's `WizardAttackGoal` state when that optional path is active.

Use stable diagnostic reason codes so tests and support can refer to them without matching localized text.

---

## Live-entity reconciliation

### RLD-001: replace “rebuild existing casters” with desired-state reconciliation

The current reload algorithm is structurally wrong:

```java
for (loaded entity) {
    if (!(entity instanceof Mob mob) || !hasSpellGoal(mob)) continue;
    removeSpellGoals(mob);
    tryInject(mob);
}
```

The precondition must not be “already has our goal.” It must be “is a loaded mob whose desired managed state might differ.”

Introduce one idempotent entry point:

```java
ReconcileResult reconcile(Mob mob, ReconcileReason reason)
```

It should:

1. compute `DesiredCasterState` from master config, manual school state, catalog generation, profession, conditions, compat toggles, and entity identity;
2. inspect `InstalledCasterState` and managed side effects;
3. calculate a diff;
4. cancel any active cast safely if required;
5. add, replace, update, or remove only Magic NPCs-owned behavior;
6. preserve mana/cooldowns/equipment according to explicit transition rules;
7. record success/failure and reason codes;
8. return a truthful result used by logs and commands.

Invoke it on:

- server-side entity join/chunk load;
- full datapack reload, for **all loaded mobs**, including current non-casters;
- relevant config load/reload;
- manual school set/reroll/clear;
- profession change;
- dependency/compat state if hot reconfiguration is supported;
- an explicit admin repair command, e.g. `/magicnpcs reconcile [targets]`.

For large servers, queue reload reconciliation in bounded batches on server ticks. Report queued/completed/failed counts and catalog generation. Do not block the reload thread with an unbounded entity scan.

### Managed state and transition rules

Maintain state separate from the `Goal` instance. A capability or namespaced persistent NBT plus runtime component can include:

```text
catalog_generation
loadout_source
loadout_content_hash
manual_override_state
sticky_pool_choice
caster_chance_roll
current_mana
per_spell_cooldown_remaining_or_deadline
equipment_application_version
native_policy_lease
last_reconcile_result
```

Recommended transition rules:

- **First activation:** initialize mana once; perform the deterministic caster roll once; apply initial equipment once according to documented policy.
- **Same loadout/hash:** do not replace the goal, refill mana, reroll equipment, or reset cooldowns.
- **Changed loadout:** update the goal/session; preserve current mana capped to the new maximum; preserve cooldowns for spell IDs that still exist; define handling for removed spells.
- **Deactivation:** remove/cancel only Magic NPCs-owned behavior and modifiers; clear stranded telegraph state; release native-policy leases.
- **Reactivation:** do not treat `/reload` as free healing/mana unless explicitly configured.
- **Failure:** retain enough status for `/why`; never return success merely because `applyLoadout` was called.

### RCN-002: mana and cooldown corruption

`applyLoadout` calls `IronsBridge.initMana`, which sets mana to maximum every time. Reload removes and recreates `NpcSpellAttackGoal`, whose `readyAtTick` and `nextDecisionTick` are instance fields. Therefore reload can refill mana, erase cooldowns, and allow immediate casts.

Move mana/cooldowns to managed caster state. `applyLoadout` should return a structured result and accept a transition context such as `FIRST_ACTIVATION`, `UPDATE`, or `RESTORE`. Only first activation initializes to full mana.

### RCN-003: reversible native-attack policy

`native_attack: "suppress"` currently removes matching goals from `GoalSelector`. Changing to `coexist`, removing the loadout, disabling the mod, or failing reinjection does not reconstruct those original goal objects.

Preferred fix: **do not remove foreign goals.** Implement suppression as an owned arbitration lease checked through goal flags/policy, a wrapper/mixin hook with strict compatibility boundaries, or temporarily disable only while a managed spell session requires it. If removal is unavoidable, capture enough factory/state metadata to restore exact goals and test every transition; simple class-name recreation is not sufficient.

Other native-policy issues:

- simple/nested class-name matching is brittle for modded, anonymous, or renamed goals;
- `yield` can starve forever if the configured native goal continuously runs;
- a config list of class simple names is not a stable extension interface;
- the config comment says `GoalSelector` runs goals “in insertion order, not priority order.” Minecraft orders wrapped goals by priority; insertion order matters only as a tie detail. Correct the comment and test the chosen priority semantics.

### RCN-004: equipment policy

`LoadoutData.hasBeenEquipped` is a single permanent latch. A changed loadout cannot apply newly required gear, while a removed loadout does not undo managed equipment. Replace the boolean with an application version/source hash and define ownership:

- tag stacks granted by Magic NPCs with an internal marker where safe;
- never remove or overwrite user/other-mod equipment unless explicitly configured;
- on a loadout change, apply only missing managed equipment according to the new policy;
- make chance rolls sticky rather than rerolling on every load/reconcile.

### RCN-005: truthful application result

`applyLoadout` returns early if mana attributes are missing, but `tryInject` still returns `true`; reload counts that mob as rebuilt. Return:

```java
record ApplyResult(boolean installed, ReasonCode reason, List<String> details) {}
```

The caller must increment success counts only when a goal/session is actually installed. Missing attributes should be an error visible to `/why` and `/validate`'s environment section.

### CFG-001: live master switch

`onLivingTick` stops regeneration when `enableSpellcasting=false`, but an existing `NpcSpellAttackGoal.stateBlocker` does not check that switch. If the config changes while goals remain installed, they can continue selecting/casting.

Fix both layers:

- include the master switch and config generation in every live cast/session blocker;
- reconcile all managed mobs on config reload, cancel sessions, and remove goals when disabled.

Defense in depth is appropriate: a delayed reconciliation queue must not allow new casts while disabled.

### Contextual-condition semantics

The current loadout-level conditions are resolved on injection/load, and the goal captures the selected loadout. They do not dynamically switch while an already-loaded mob crosses a biome/Y boundary or day becomes night. This is close to the README's “checked when the mob spawns or loads” wording, but commands can resolve the current context and display a different desired loadout from the installed one.

Choose one contract:

- **Snapshot contract:** conditions are sticky until entity load or explicit reload; commands must say so and show the snapshot source.
- **Dynamic contract:** reconcile on coarse intervals and meaningful events, with hysteresis to avoid thrashing.

Do not leave runtime behavior snapshot-based while diagnostics pretend it is dynamic.

---

## AI goal, targeting, and friendly-fire corrections

### Goal contention

By default 0.6.1's casting goal claims no control flags and manually snaps rotation at fire time. This avoids obvious `LOOK` starvation but permits the bow/melee goal and casting goal to run concurrently. Another goal can update rotation later in the same tick/order, and the correctness depends on selector scheduling. Keep this only with integration tests that verify projectile trajectory—not merely mana expenditure.

Test at least:

- skeleton with bow: shoots arrows and casts accurately over several hundred ticks;
- witch with ranged goal;
- melee mob moving/looking during windup;
- equal and different priorities;
- `coexist`, `yield`, and `suppress` transitions;
- a custom modded attack goal not named in the default list.

### TGT-001: compose adapters instead of selecting one

`NpcAdapters.resolve` chooses the highest-priority applicable adapter. Policies are orthogonal:

- Recruits diplomacy and command state;
- owner/team protection;
- raid membership;
- sitting/passive state;
- mana/rank scaling;
- school eligibility.

A specific adapter winning should not erase generic owner, scoreboard-team, raid, or sitting safety. Split adapter responsibilities into composable policy chains:

```java
interface CastStatePolicy { Decision canCastNow(Mob caster, CastRole role); }
interface RelationshipPolicy { Relationship classify(Mob caster, LivingEntity other); }
interface ScalingPolicy { double manaScale(Mob caster); }
interface SchoolEligibilityPolicy { Decision eligible(Mob caster); }
```

Combine state blockers with logical AND, relationship protections with “most protective wins,” and scaling using one explicitly selected provider. Include each contributing policy in diagnostics.

### TGT-002 and TGT-003: restore regressed policies

Port `RaidAllyAdapter` from 0.6.0 and make it composable. It identifies same-raid raiders and normal allies. Add tests with two raiders in one raid, different raids, and a player target.

Restore `TamableAnimal.isOrderedToSit()` as a live cast blocker independent of whether owner protection is enabled. A pet ordered to sit should not start or continue a cast session unless a clearly named config opts in.

### Friendly-fire geometry

`LineOfFire` treats a spell's scalar `safety_radius` as distance from every protected entity's AABB to the straight caster-target segment. It also protects all villagers, golems, and tameable animals when bystander protection is enabled. This is a conservative heuristic, not actual spell geometry:

- projectile width, homing, ground placement, cone, chain, line, target area, and blast radius differ;
- legitimate hostile casts can be blocked by unrelated protected entity classes;
- addon spell behavior cannot be inferred from one number.

Move geometry into the spell capability registry: `PROJECTILE_CORRIDOR`, `TARGET_BLAST`, `CASTER_AOE`, `CONE`, `CHAIN`, `GROUND_POINT`, or `CUSTOM`. Keep `safety_radius` as an author override, but validate it against the strategy and explain the actual blocker entity in `/why`.

---

## Iron's spell bridge: critical compatibility repair

### What Iron's 3.16.1 does for its own casting mobs

The canonical `AbstractSpellCastingMob` flow is approximately:

1. select spell and level;
2. `checkPreCastConditions`;
3. prepare special mob data for certain spells (teleports, burning dash, ray aiming, and similar cases);
4. `MagicData.initiateCast(spell, level, spell.getEffectiveCastTime(...), CastSource.MOB, slot)`;
5. `spell.onServerPreCast(...)`;
6. each tick: `MagicData.handleCastDuration()` and, while casting, `spell.onServerCastTick(...)`;
7. LONG/INSTANT: call `onCast` at completion;
8. CONTINUOUS: call `onCast` on its cadence while the session remains active;
9. call `onServerCastComplete(...)`, including cancellation behavior.

### What Magic NPCs 0.6.1 does

`IronsBridge.cast`:

1. checks an allow-list and `SpellCompat` category;
2. optionally writes `TargetEntityCastData` for one of three mapped paths;
3. calls `checkPreCastConditions`;
4. directly calls `onCast`;
5. for LONG only, immediately calls `onServerCastComplete`;
6. unconditionally deducts mana;
7. clears additional cast data.

It does **not** call `MagicData.initiateCast`, `onServerPreCast`, `onServerCastTick`, or the continuous lifecycle. Windup is simulated outside Iron's using raw `getCastTime`, not `getEffectiveCastTime`.

### Confirmed scale of the mismatch

In the inspected Iron's 3.16.1 JAR:

- 31 spell classes override `checkPreCastConditions`;
- 1 overrides `onServerPreCast` (`FortifySpell`);
- 4 override `onServerCastTick` (`RayOfSiphoningSpell`, `TelekinesisSpell`, `StarfallSpell`, `BlazeStormSpell`);
- 13 override `getEmptyCastData`;
- 113 override `onCast`.

Examples:

| Spell | Required lifecycle/data | Current likely result |
|---|---|---|
| `fortify` | `onServerPreCast` creates target-area data/entity | Final buff may occur, but pre-cast area/telegraph state is skipped |
| `starfall` | repeated `onServerCastTick` tracks entities and shoots comets | Main repeated effect is skipped; direct `onCast` is not equivalent |
| `blaze_storm` | repeated cast ticks shoot fireballs | Repeated fireballs are skipped |
| `ray_of_siphoning` | mob aiming data plus server cast ticks; continuous `onCast` cadence | Aiming/channel behavior is absent or reduced to a direct call |
| `telekinesis` | target data then repeated server ticks move the target | Target can be selected, but the movement lifecycle is skipped |
| `recall`, `pocket_dimension` | pre-cast explicitly requires `ServerPlayer` | Runtime pre-check rejects them, but validation currently categorizes them as supported |
| `thunder_step`, multi-target/recast spells | multi-target/recast cast data | No explicit strategy; default classifier says supported |

### SPI-002: compatibility classification is effectively allow-all

`SpellCompat.BY_PATH` contains only:

```text
root   -> TARGET_ENTITY_REQUIRED
devour -> TARGET_ENTITY_REQUIRED
wisp   -> TARGET_ENTITY_REQUIRED
stomp  -> GROUND_AOE_FORWARD
```

Every other spell defaults to `DIRECT_PROJECTILE_OR_SIMPLE_SELF`. The enum contains `MULTI_TARGET_REQUIRED` and `PLAYER_ONLY_OR_UNSUPPORTED`, but no inspected mapping assigns either. Therefore “unsupported spells are skipped” and the validator's `supportedForMob` field are false for almost the entire registry.

### Required architecture: `MobCastSession`

Replace the one-shot bridge with a state machine owned by the managed caster:

```text
IDLE -> PREPARING -> WINDUP/CHANNEL -> COMMIT -> COMPLETE
                         |              |
                         +-> CANCELLED <-+
```

Suggested API:

```java
interface MobSpellStrategy {
    Capability capability(AbstractSpell spell);
    PrepareResult prepare(Mob caster, @Nullable LivingEntity target,
                          AbstractSpell spell, int level, MagicData data);
    void tick(MobCastSession session);
    CastOutcome commit(MobCastSession session);
    void cancel(MobCastSession session, CancelReason reason);
}

record CastOutcome(
    boolean effectCommitted,
    boolean consumeMana,
    boolean startCooldown,
    ReasonCode reason
) {}
```

The session must:

- call `checkPreCastConditions` before committing resources;
- use `getEffectiveCastTime(level, caster)`;
- initialize and retain `MagicData` cast state;
- call pre-cast, tick, continuous, completion, and cancellation hooks in the same order as the supported Iron's API;
- retain additional cast data for the entire session;
- track/turn toward the target as the strategy requires;
- cancel when the target dies, becomes friendly, leaves allowable range, loses LOS where required, caster sits/dies/is disabled, or reconciliation invalidates the loadout;
- charge mana and start cooldown exactly once according to a documented transaction point;
- return a failure when no effect/session was committed, so a no-op does not consume mana;
- clean telegraphs and synchronized state on every exit path.

Do not blindly reuse player `SpellPreCastEvent`/`SpellOnCastEvent`; those are player-oriented. If addons need extension hooks for mobs, define a Magic NPCs mob-cast event or strategy SPI with stable context and cancellation semantics.

### Capability registry: fail closed

Replace path-keyword assumptions with an explicit registry keyed by full spell ID and/or a strategy provider interface:

```text
DIRECT_PROJECTILE
TARGET_ENTITY
TARGET_POSITION
CASTER_AOE
TARGET_AREA
MULTI_TARGET
LONG_TICKED
CONTINUOUS_TICKED
PLAYER_ONLY
CUSTOM
UNSUPPORTED
```

For built-in Iron's spells, generate a reviewed manifest for each supported Iron's version. For addon spells, require an adapter/provider or conservatively mark unknown lifecycle capabilities unsupported. `/magicnpcs spells` and `/validate` must show `SUPPORTED`, `UNSUPPORTED`, or `UNVERIFIED`, the strategy, and the reason. “Unverified” must not execute by default in a release that promises safety.

### Version contract

The 0.6.1 `mods.toml` accepts Iron's versions from `1.20.1-3.15.0` up to but excluding `1.20.1-4.0.0`. GitHub properties say the compile API was 3.15.2 and the dev runtime was 3.16.1. That broad range is not justified by the current cast bridge.

Either:

- test the minimum, every API-behavior boundary, and the latest version admitted by the range; or
- narrow the runtime range to the tested versions.

Add a startup warning and diagnostic status for an unverified version. Never silently claim full support across an untested minor line.

---

## Recruits Iron's-AI path

The optional `recruits.useIronsAI` path needs isolation until corrected.

### RCT-001: JSON policy is discarded

`IronsGoalFactory` passes only lists of `AbstractSpell` objects into `WizardAttackGoal`. It discards per-entry:

- requested spell level;
- weight;
- min/max range;
- safety radius;
- cast chance;
- explicit/multiplied cooldown;
- windup;
- reactive condition;
- focus and safety gates;
- native attack policy.

Iron's then chooses levels from its quality settings. The same loadout therefore means materially different behavior depending on one config toggle. The current README mentions some gate differences, but not the loss of most per-entry contract.

### RCT-002: fake `IMagicEntity` lifecycle

The recruit mixin's `initiateCastSpell` calls the same one-shot bridge, while:

- `isCasting()` always returns false;
- `cancelCast()` and `castComplete()` do nothing;
- `setSyncedSpellData()` does nothing;
- special preparation methods are empty/false.

`WizardAttackGoal` is designed around a real `IMagicEntity` cast state. This implementation cannot provide it.

### Corrective options

1. **Safest hotfix:** force `recruits.useIronsAI=false`, warn if configured true, and remove the public compatibility claim until a full adapter exists.
2. **Full fix:** implement a real recruit casting component satisfying `IMagicEntity`, route it through `MobCastSession`, preserve synchronized state, implement special preparation, and create a translation layer from each loadout entry to Iron's selection constraints.
3. **Alternative:** keep Magic NPCs' goal and port only desired movement/fleeing behavior instead of impersonating Iron's casting mob.

Do not retain the current hybrid path merely because a test observes mana decreasing; that is not evidence of correct AI or spell execution.

---

## Schools, manual overrides, and School Tome

### Manual override fallback defect

`tryInject` checks a manual school first. If the manual school builds a loadout, it wins. If the school was manually cleared, casting remains suppressed. But if a non-null manual school becomes unusable—schools disabled, school removed/not allowed, or pool empty—the function falls through to an explicit datapack loadout.

That contradicts the command/help claim that manual assignment “overrides any loadout and persists.” Model manual state explicitly:

```text
AUTO
MANUAL_SCHOOL(school_id)
MANUAL_DISABLED
```

`MANUAL_SCHOOL` should either install that school or remain blocked with a diagnostic; it should not silently fall back. Add an explicit command to return to `AUTO` if desired.

### School pool classification

Support classification uses spell-name keywords such as `heal`, `cure`, `blessing`, `regen`, `haste`, `shield`, `ward`, and `fortify`. This is not a targeting contract and will misclassify addons or unusually named spells. Consume the same capability/targeting registry as the cast bridge.

### Villager self-defense ownership

When enabled, Magic NPCs adds a `HurtByTargetGoal` but does not track/remove that owned goal when school casting is cleared or config changes. Include it in managed reconciliation with an ownership marker/reference and reversible transitions.

### School Tome documentation is reversed

0.6.1 code does:

- normal right-click: inspect;
- sneak-right-click: cycle schools;
- cycling past the final usable school: clear casting.

Current public material says right-click cycles and sneak clears. Align implementation, tooltip, README, CurseForge copy, and tests. Prefer an explicit, discoverable interaction contract rather than overloading “cycle past end” as clear.

---

## Testing and CI specification

### Why current tests missed the report

The spellcasting GameTests in 0.6.1 specify `required=false` and skip successfully when Iron's is absent. Most construct `SpellcasterLoadout` in code and insert `NpcSpellAttackGoal` directly. The basic skeleton test removes all vanilla goals. The coexistence test keeps bow AI, but still manually injects the casting goal and repeatedly pins a target.

Those tests can verify selected goal internals, but they bypass:

- datapack file discovery and pack provenance;
- malformed/rejected resource retention;
- source-tier and same-path resource stacks;
- `/reload` and `OnDatapackSyncEvent`;
- existing non-caster reconciliation;
- entity-join injection;
- command registration and documented syntax;
- config reload;
- release-version feature inventory;
- full Iron's cast lifecycle.

### Required test layers

#### 1. Pure unit tests

- schema keys, unknown-key warnings, and JSON pointers;
- all numeric/cross-field rules;
- every resource status transition;
- pack/source-tier override and suppression truth tables;
- bare `enabled:false` contract;
- sticky pool/caster/equipment rolls;
- cooldown/mana transition rules;
- adapter composition;
- strategy/capability lookup and fail-closed default;
- command builder parse tree.

#### 2. Loader integration tests

Use a real or faithful `ResourceManager` with resource stacks:

- valid skeleton file becomes ACTIVE;
- malformed skeleton file remains REJECTED and appears in `/validate`;
- unknown entity and spell IDs are errors;
- user datapack outranks built-in at the effective key;
- two files pool when intended and `replace` wins when intended;
- same logical path and different logical paths are both tested;
- disabled records remain diagnosable;
- a catastrophic reload retains the last known-good runtime catalog.

#### 3. Required GameTests with Iron's present

Create a release test profile where failures fail CI:

- skeleton spawned **before** reload gains a goal after adding/reloading its loadout;
- existing caster loses the goal when its loadout is removed/disabled;
- new skeleton after reload casts at a naturally acquired Survival target;
- `/reload` preserves mana and cooldowns;
- config master switch immediately blocks and then reconciles existing casters;
- changing `native_attack` is reversible;
- `loadout entity` shows desired and installed generations;
- `why` explains a non-reconciled mob and an invalid runtime gate;
- raid allies are protected;
- sitting tameable stops/cancels casting;
- school manual modes do not silently fall through;
- School Tome interactions match tooltip/docs;
- Recruits tests run only in a separate dependency-present profile and are required there.

#### 4. Spell lifecycle conformance suite

At minimum cover representative strategies:

| Representative | Assertion |
|---|---|
| `magic_missile` | projectile/effect commits toward target; mana/cooldown once |
| `root` or `devour` | explicit target data survives through session |
| `fortify` | pre-cast hook and final effect both occur |
| `starfall` | repeated cast ticks generate expected repeated effect |
| `blaze_storm` | tick cadence fires multiple projectiles |
| `ray_of_siphoning` | aiming data updates and continuous effect cadence runs |
| `telekinesis` | target moves over multiple ticks and session cancels cleanly |
| `recall` / `pocket_dimension` | validation says player-only; mob never spends mana |
| one multi-target/recast spell | either full adapter works or validation says unsupported |
| unknown addon spell | fail closed as UNVERIFIED unless provider is registered |

Assert world effects, cast data, session state, and mana/cooldown—not merely mana loss.

#### 5. Dependency/build matrix

Run at least:

- Minecraft 1.20.1 / Forge minimum actually supported;
- Iron's 3.15.2 (declared compile/verified target);
- Iron's 3.16.1 (declared dev-runtime target used in this audit);
- latest Iron's version admitted by `mods.toml`, or narrow the range;
- Recruits absent and present at every declared boundary;
- dedicated server boot plus client/server join.

#### 6. Documentation contract tests

- extract every `/magicnpcs` line from README/docs/release description and parse it;
- lint every JSON example with the production schema;
- verify every documented config key exists in generated config;
- verify generated config paths and sides;
- diff public features against a checked-in release feature manifest.

---

## File-by-file implementation map

Paths below refer to package intent. Reconcile them with the recovered release source before editing.

| Area | Existing/new file | Required work |
|---|---|---|
| Bootstrap | `MagicNpcs.java` | Restore regressed adapter registration; listen for config reload; log version/provenance |
| Command root | new `command/MagicNpcsCommands.java` | Register one root and executable usage nodes |
| Config command | port `command/ConfigCommand.java` from 0.6.0 | Restore and extend effective-config diagnostics |
| Loadout command | `command/LoadoutCommand.java` | Use full catalog; show desired vs installed; focused validation forms |
| Why command | `command/WhyCommand.java` | Stable reason codes, apply/reconcile/session failures, Wizard path |
| Help | `command/HelpCommand.java` | Correct syntax and copy-paste selectors |
| Loader | `core/loadout/LoadoutManager.java` | Stop dropping failures; retain resource stacks/provenance; atomic catalog generation |
| Schema | `core/loadout/LoadoutJson.java` | Restore 0.6.0 fields; strict known-key validation; disabled-stub contract |
| Catalog | new `core/loadout/LoadoutCatalog.java` | All discovered records and active runtime view |
| Problems | new `core/loadout/LoadoutProblem.java` | Structured severity/code/pointer/suggestion |
| Reconciler | new `core/caster/CasterReconciler.java` | Desired-vs-installed diff for all lifecycle events |
| Managed state | new `core/caster/ManagedCasterState.java` | Generation/hash, mana, cooldowns, rolls, leases, last result |
| Handler | `integration/irons/IronsSpellcasterHandler.java` | Delegate join/reload/config/manual operations to reconciler; scan all loaded mobs |
| Goal | `integration/irons/NpcSpellAttackGoal.java` | Consume managed state and real cast sessions; master switch; truthful blockers |
| Cast session | new `integration/irons/MobCastSession.java` | Full Iron's lifecycle, cancel/cleanup, transactional outcome |
| Bridge | `integration/irons/IronsBridge.java` | Thin version adapter; no direct one-shot pseudo-lifecycle |
| Compatibility | replace/expand `integration/irons/SpellCompat.java` | Explicit capability registry; unsupported/unverified fail closed |
| Iron's goal factory | `integration/irons/IronsGoalFactory.java` | Remove/disable hybrid path or preserve the complete loadout contract |
| Recruits mixin | `mixin/recruits/MixinAbstractRecruitEntityMagic.java` | Real cast state or removal of false `IMagicEntity` implementation |
| Adapter registry | `core/adapter/NpcAdapters.java` | Compose policies rather than select one |
| Raid policy | port `compat/generic/RaidAllyAdapter.java` | Restore same-raid protection as composable relationship policy |
| Owner policy | `compat/generic/OwnableTeamAdapter.java` | Restore sitting state; retain owner/team relationships |
| Native goals | `core/util/AttackGoals.java` | Replace destructive removal/simple-name matching with reversible ownership/arbitration |
| Friendly fire | `core/util/LineOfFire.java` | Use strategy-specific geometry and report blocker identity |
| School pools | `integration/irons/SchoolSpellPool.java` | Use capability metadata, not name keywords |
| School operations | handler/`SchoolCommand.java`/`SchoolData.java` | Explicit AUTO/MANUAL_SCHOOL/MANUAL_DISABLED states |
| Tome | `SchoolTomeInteraction.java`, tooltip/lang | One documented interaction model |
| Tests | `gametest/*`, `integration/irons/IronsCastingTests.java` | Required profiles; real datapack/reload/command/lifecycle coverage |
| Build | Gradle, CI, `mods.toml` generation | Source SHA, version consistency, dependency matrix, feature inventory |
| Docs | README, CurseForge description, `docs/*`, changelog | Generate from one command/schema source and publish corrected support runbook |

---

## Ordered remediation plan

### Phase 0 — establish a trustworthy branch

1. Recover exact 0.6.0 and 0.6.1 source.
2. Tag both; record the binary hashes above.
3. Generate a semantic feature/class/config/schema diff.
4. Merge intended 0.6.0 features into the 0.6.1 line, retaining newer help/why/source-tier/native-policy work only where correct.
5. Make a clean build and compare its class/resource inventory with the published JAR.

**Exit criterion:** a reviewer can map every published 0.6.1 class/resource to committed source and explain every intentional 0.6.0 removal.

### Phase 1 — hotfix the reported path

1. Centralize/fix command registration and restore `/config`.
2. Retain rejected resources and make `/validate` truthful.
3. Reconcile all loaded mobs on datapack reload.
4. Stop reload from refilling mana/resetting cooldowns.
5. Add a required regression test: skeleton exists before reload, then casts afterward.
6. Replace the quick-start with full commands and a deterministic target setup.

**Exit criterion:** the exact quoted report cannot be reproduced with a valid pack, and an invalid pack produces an actionable command error.

### Phase 2 — make state transitions safe

1. Implement `CasterReconciler` and managed state.
2. Make config/manual school/profession/loadout transitions idempotent.
3. Replace destructive native-goal suppression.
4. Restore/combine raid, owner/team, sitting, and mod-specific policies.
5. Make diagnostics compare desired and installed state.

**Exit criterion:** a transition matrix can add/update/remove a caster repeatedly without leaks, free mana, lost cooldowns, stranded glow, foreign goal loss, or misleading diagnostics.

### Phase 3 — fix spell execution

1. Temporarily fail closed for unverified long/continuous/special spells.
2. Implement `MobCastSession` in canonical lifecycle order.
3. Build the full Iron's built-in capability manifest.
4. Add strategy-provider SPI for addons.
5. Correct Recruits path or keep it disabled.

**Exit criterion:** representative lifecycle conformance tests assert actual effects and cleanup across every capability category.

### Phase 4 — harden releases

1. Make runtime GameTests required in dependency-present CI.
2. Add dependency and dedicated-server matrix.
3. Generate commands/config/schema docs.
4. Add release feature inventory diff and provenance metadata.
5. Reconcile license metadata: the JAR/repository say GNU GPLv3 while CurseForge metadata says All Rights Reserved.

**Exit criterion:** one tagged commit reproducibly generates an artifact whose commands, schema, configs, license, version, and behavior match every public page.

---

## Acceptance checklist for the corrective release

### Player report

- [ ] `/magicnpcs loadout` prints useful usage rather than syntax error.
- [ ] `/magicnpcs school` prints useful usage rather than syntax error.
- [ ] `/magicnpcs config` is present if advertised.
- [ ] All public examples include required subcommands and real selector examples.
- [ ] A skeleton loaded before `/reload` becomes a caster when the new datapack matches.
- [ ] A fresh skeleton with a valid target casts the fixture `magic_missile`.
- [ ] `/validate` reports malformed/rejected skeleton JSON and its pack/resource ID.
- [ ] `/validate` success explicitly distinguishes resource validity from live runtime state.

### State safety

- [ ] Reload does not refill mana.
- [ ] Reload does not reset spell cooldowns or decision cadence.
- [ ] Disabling/removing a loadout removes owned behavior and restores/releases native policy.
- [ ] Config disable blocks immediately and reconciles existing mobs.
- [ ] Repeated reconcile is idempotent.
- [ ] Desired and installed generations/hashes are visible.
- [ ] Apply failures are counted as failures.

### Compatibility and combat safety

- [ ] 0.6.0 public fields/features have explicit keep/migrate/remove decisions.
- [ ] Raid allies are protected.
- [ ] Sitting tameables do not cast by default.
- [ ] Adapter policies compose.
- [ ] Every executing spell has an explicit supported strategy.
- [ ] Player-only/unverified spells do not spend mana or start cooldowns.
- [ ] LONG and CONTINUOUS hooks run in canonical order.
- [ ] Recruits Iron's-AI mode is either correct and tested or unavailable with a clear warning.

### Release integrity

- [ ] Git tag, Gradle version, `mods.toml`, filename, changelog, and public page agree.
- [ ] Artifact embeds source SHA and dependency versions.
- [ ] Release class/resource/schema/config diff is reviewed.
- [ ] Dependency ranges equal the tested matrix.
- [ ] GitHub has the released source and license metadata agrees across platforms.

---

## Suggested user-facing support response

The maintainer can use this immediately while the patch is being prepared:

> You have hit two real 0.6.1 problems. The short command names on the project page are incomplete: use `/magicnpcs loadout id minecraft:skeleton`, `/magicnpcs loadout entity @e[type=minecraft:skeleton,sort=nearest,limit=1]`, and `/magicnpcs why @e[type=minecraft:skeleton,sort=nearest,limit=1]`. `/magicnpcs config` is mistakenly advertised but is missing from 0.6.1.
>
> Also, `/reload` only rebuilds mobs that were already Magic NPCs casters. Spawn a new skeleton after `/reload` (or unload/reload the chunk), test on Normal with a valid Survival target in sight, and then run the full `/why` command above. `/magicnpcs validate` currently checks only loadouts that parsed successfully, so “no issues” does not prove your skeleton JSON was discovered. Run `/magicnpcs loadout id minecraft:skeleton` and check `latest.log` for `Skipping invalid spellcaster loadout`.
>
> We are treating the command documentation, existing-mob reload, and validator blind spot as bugs rather than asking you to rebuild the same datapack again.

---

## Reference links

- Repository: <https://github.com/otectus/magic-npcs>
- Audited GitHub commit: <https://github.com/otectus/magic-npcs/commit/586c50a13fd7c75999bfea0efec84231ca972ac2>
- Version/dependency properties at that commit: <https://github.com/otectus/magic-npcs/blob/586c50a13fd7c75999bfea0efec84231ca972ac2/gradle.properties>
- README at that commit: <https://github.com/otectus/magic-npcs/blob/586c50a13fd7c75999bfea0efec84231ca972ac2/README.md>
- Current CurseForge project page: <https://www.curseforge.com/minecraft/mc-mods/magic-npcs>
- Published 0.6.1 file: <https://www.curseforge.com/minecraft/mc-mods/magic-npcs/files/8729954>
- Iron's Spells 'n Spellbooks: <https://www.curseforge.com/minecraft/mc-mods/irons-spells-n-spellbooks>

---

## Bottom line

The immediate skeleton failure is primarily a reload-reconciliation bug, amplified by a validator that cannot see rejected resources and a quick-start that does not establish a valid target. The command failures are a direct documentation/registration mismatch, including one command missing from the binary. These are symptoms of the larger release-branch divergence: 0.6.1 is not a complete successor to 0.6.0, and GitHub `main` is not the released implementation.

Recover the release source first. Then fix the command/resource/reconciliation path as the hotfix, preserve combat state across reload, and replace the one-shot Iron's bridge with an explicit, fail-closed cast-session architecture. Anything less will make the quoted report disappear in one narrow test while leaving the same class of silent failure elsewhere.
