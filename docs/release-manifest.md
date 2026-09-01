# Release feature manifest — 0.6.3

The checked-in inventory of everything Magic NPCs exposes publicly: commands, loadout JSON fields,
config keys, and shipped data. Its purpose is to make a removal **deliberate**.

The 0.6.1 release lost `/magicnpcs config`, the `caster_chance` loadout field, the per-spell
held-item requirement, the `[builtinLoadouts]` toggles, raid ally protection and the sitting-companion
gate. None of those removals was announced, none was intended, and the project page went on advertising
several of them. Nothing in the build could have noticed, because there was no statement anywhere of
what the previous release had contained.

**How to use it.** Diff this file between releases. Every line that disappears must correspond to an
entry in the changelog's "Removed" section, or it is a regression. `./gradlew check` enforces that the
version in `gradle.properties`, `CHANGELOG.md` and `mods.toml` agree; keeping this file current is a
review step, not an automated one.

---

## Commands

Registered in one place: [`MagicNpcsCommands`](../src/main/java/com/otectus/magicnpcs/command/MagicNpcsCommands.java).
`DocumentedCommandsTest` checks that documentation never names a path that is not here.

| Command | Permission | Since |
|---|---|---|
| `/magicnpcs` | 0 (index only) | 0.6.1 |
| `/magicnpcs help` | 0 | 0.6.2 |
| `/magicnpcs why <targets>` | 2 | 0.6.1 |
| `/magicnpcs loadout entity <targets>` | 2 | 0.5.0 |
| `/magicnpcs loadout id <entity_type>` | 2 | 0.5.0 |
| `/magicnpcs validate` | 2 | 0.5.0 |
| `/magicnpcs validate resource <resource_id>` | 2 | 0.6.2 |
| `/magicnpcs validate id <entity_type>` | 2 | 0.6.2 |
| `/magicnpcs config` | 2 | 0.6.0, **absent in 0.6.1**, restored 0.6.2 |
| `/magicnpcs reconcile [targets]` | 2 | 0.6.2 |
| `/magicnpcs spells [filter]` | 2 | 0.4.0 |
| `/magicnpcs school info <targets>` | `schools.control.commandPermissionLevel` | 0.6.0 |
| `/magicnpcs school set <targets> <school>` | as above | 0.6.0 |
| `/magicnpcs school reroll <targets>` | as above | 0.6.0 |
| `/magicnpcs school clear <targets>` | as above | 0.6.0 |
| `/magicnpcs school auto <targets>` | as above | 0.6.2 |
| `/magicnpcs school pool [school]` | as above | 0.6.0 |

Every intermediate literal (`loadout`, `school`, `validate resource`, …) is executable and prints its
usage. Nothing in this tree terminates in a bare Brigadier syntax error.

## Loadout JSON — root fields

Names in [`LoadoutJson`](../src/main/java/com/otectus/magicnpcs/core/loadout/LoadoutJson.java);
the accepted set is [`LoadoutSchema.ROOT_KEYS`](../src/main/java/com/otectus/magicnpcs/core/loadout/LoadoutSchema.java).

| Field | Type | Since |
|---|---|---|
| `entity_type` | id, **required** (inferred for a bare `enabled:false` stub) | 0.1.0 |
| `profession` | id | 0.4.0 |
| `max_mana` | number | 0.1.0 |
| `mana_regen` | number | 0.1.0 |
| `spells` | array, required when enabled | 0.1.0 |
| `equipment` | object | 0.5.0 |
| `conditions` | object | 0.4.0 |
| `pool_weight` | int | 0.4.0 |
| `replace` | bool | 0.5.0 |
| `enabled` | bool | 0.6.0 |
| `goal_priority` | int 0–99 | 0.6.0 |
| `native_attack` | `coexist` / `suppress` / `yield` | 0.6.0 |
| `caster_chance` | fraction 0–1 | 0.6.0, **absent in 0.6.1**, restored 0.6.2 |

Comment keys allowed anywhere: `_comment`, `__comment`, `$comment`.

## Loadout JSON — per-spell fields

| Field | Type | Since |
|---|---|---|
| `spell` | id, **required** | 0.1.0 |
| `level`, `weight` | int | 0.1.0 |
| `min_range`, `max_range`, `safety_radius` | number | 0.1.0 |
| `role` | `attack` / `support` | 0.1.0 |
| `cast_chance` | fraction 0–1 | 0.3.0 |
| `cooldown`, `cooldown_multiplier`, `windup` | number | 0.3.0 |
| `condition` | object | 0.4.0 |
| `require_held_item` | bool | 0.6.0, **absent in 0.6.1**, restored 0.6.2 |
| `required_items` | array of item ids / `#tags` | as above |
| `required_hand` | `main` / `off` / `either` | as above |

Nested key sets: `equipment` (`mainhand`, `offhand`, `item`, `weight`, `chance`, `only_if_empty`),
`conditions` (`dimensions`, `biomes`, `difficulties`, `time`, `min_y`, `max_y`, `require_raid`,
`require_storm`, `moon_phases`), `condition` (`self_hp_below`, `target_hp_below`, `enemies_within`,
`enemies_radius`, `when_recently_hurt`, `recent_damage_window`).

## Config — `<world>/serverconfig/magicnpcs-server.toml`

| Section | Keys |
|---|---|
| `general` | `enableSpellcasting`, `castingGoalPriority`, `castingGoalUsesLookFlag`, `disabledEntityTypes`, `suppressibleAttackGoals`, `strictLoadoutSchema` *(0.6.2)*, `reconcileBatchSize` *(0.6.2)*, `debugLogging` *(deprecated)* |
| `balance` | `manaMultiplier`, `cooldownMultiplier`, `regenMultiplier`, `decisionIntervalTicks`, `castChance`, `minCooldownTicks`, `supportHealthThreshold`, `supportOutOfCombat`, `supportOutOfCombatIntervalTicks`, `friendlyFireCheck`, `peacefulDisablesCasting`, `difficultyScaling`, `casterMovement` *(0.6.3)*, `casterMovementSpeed` *(0.6.3)*, `rankLevelPerRank` *(0.6.3)*, `rankLevelMaxBonus` *(0.6.3)* |
| `targeting` | `requireLineOfSight`, `castWindupTicks`, `protectBystanders`, `protectBystanderPlayers`, `protectOwners`, `protectRaidAllies` *(0.6.2)*, `sittingPetsMayCast` *(0.6.2)* |
| `equipment` | `requireSpellFocus`, `spawnWithGearChance` |
| `reactive` | `enabled`, `matchedConditionWeightBonus` |
| `feedback` | `telegraphs`, `schoolParticles`, `telegraphGlow`, `telegraphVolume`, `minDangerTier` |
| `spells` | `spellBlacklist`, `spellWhitelist`, `allowUnverifiedSpells` *(0.6.2)* |
| `recruits` | `enabled`, `manaPerLevel` — the three `ironsAi*` keys were **removed in 0.6.3** |
| `builtinLoadouts` | `recruit`, `bowman`, `crossbowman`, `captain`, `guard` — 0.6.0, **absent in 0.6.1**, restored 0.6.2 |
| `compat` | *deprecated copies of the common-config toggles; removed in 0.7.0* |
| `schools` | `enableSchools`, `allowedSchools`, `maxRarity`, `maxSpellLevel`, `spellsPerSchool`, `includeSupportSpells`, `supportSpellIds`, `allowedCastTypes`, `weightingMode`, `attackMaxRange`, `baseMaxMana`, `baseManaRegen`, `schoolAwareFocus` |
| `schools.recruits` | `enabled`, `casterChance`, `assignmentMode`, `typeSchools`, `minRankToCast` |
| `schools.villagers` | `enabled`, `casterChance`, `professionSchools`, `selfDefense`, `unmappedGetRandom` |
| `schools.control` | `commandEnabled`, `commandPermissionLevel`, `itemEnabled` |

## Config — `config/magicnpcs-common.toml`

| Section | Keys |
|---|---|
| `general` | `debugLogging` |
| `compat` | `guardvillagers`, `mca`, `minecolonies`, `easynpc`, `humancompanions`, `morevillagers`, `villagersplus` |

## Shipped data

| Resource | Applies to | Toggle |
|---|---|---|
| `magicnpcs:recruit` | `recruits:recruit` | `builtinLoadouts.recruit` |
| `magicnpcs:bowman` | `recruits:bowman` | `builtinLoadouts.bowman` |
| `magicnpcs:crossbowman` | `recruits:crossbowman` | `builtinLoadouts.crossbowman` |
| `magicnpcs:captain` | `recruits:captain` | `builtinLoadouts.captain` |
| `magicnpcs:guard` | `guardvillagers:guard` | `builtinLoadouts.guard` + `compat.guardvillagers` |

Item tag `magicnpcs:spell_focuses` (empty by default). Item `magicnpcs:school_tome` and its recipe.

## Adapters

| Adapter | Applies when | Provides |
|---|---|---|
| `RecruitsAdapter` | the mob is a Villager Recruit | rank mana scaling, diplomacy targeting, command-state gate |
| `OwnableTeamAdapter` | `targeting.protectOwners` and the mob is ownable or teamed | owner/sibling/team ally protection |
| `RaidAllyAdapter` | `targeting.protectRaidAllies` and the mob is a raider in a raid | same-raid ally protection — **absent in 0.6.1**, restored 0.6.2 |
| `SittingPetAdapter` | the mob is a `TamableAnimal` and `targeting.sittingPetsMayCast` is false | blocks casting while ordered to sit — **absent in 0.6.1**, restored 0.6.2 |

Since 0.6.3 an adapter may also supply a `movementPolicy` (`FREE` / `ANCHORED` / `PINNED`), consumed
by `CasterMovementGoal`. `RecruitsAdapter` derives it from the Recruits command system; policies
compose most-restrictive-wins.

Since 0.6.2 every applicable adapter contributes: state blockers combine with AND, ally relationships
take the most protective answer, and only mana/rank scaling comes from the highest-priority one.

## Removed in 0.6.2 (deliberate)

| Removed | Why |
|---|---|
| `IronsGoalFactory` and the `recruits.useIronsAI` code path | it discarded every per-entry setting in a loadout, so one config toggle changed what a datapack meant |
| `MixinAbstractRecruitEntityMagic` and `magicnpcs.mixins.json` | the mixin's only purpose was the path above, and it declared recruits `IMagicEntity` while reporting `isCasting() == false` and no-oping the lifecycle methods that interface exists for |
| `IronsBridge.cast(...)` | replaced by `MobCastSession`, which runs Iron's real cast lifecycle |
| `SpellCompat.Category` | replaced by `SpellManifest.Capability` plus an explicit `Support` verdict |

## Removed in 0.6.3 (deliberate)

| Removed | Why |
|---|---|
| `recruits.useIronsAI` | inert since 0.6.2; see [ADR 0009](decisions/0009-caster-movement-and-rank-scaling.md) for why Iron's `WizardAttackGoal` is not coming back |
| `recruits.ironsAiSpeed`, `recruits.ironsAiIntervalTicks` | had **no readers at all** in any version that shipped them |
