# Datapack samples

Ten example datapacks, each demonstrating one aspect of Magic NPCs' loadout and spell-manifest
schema. Every folder under `datapack_samples/` is a **complete, independently installable
datapack** — its own `pack.mcmeta` and `data/` tree. None of this is shipped or active by
default; these are examples to copy into your own datapack (or modpack) and edit, not a feature
the mod turns on for you.

Only two folders are meaningful to Magic NPCs itself:

- `data/<namespace>/spellcasters/*.json` — per-entity loadouts
- `data/<namespace>/spell_manifests/*.json` — add-on spell capability declarations

Everything else a sample uses (item tags, functions, advancements, loot tables, recipes) is
ordinary vanilla/Forge datapack content that happens to interact with Magic NPCs (e.g. giving out
`magicnpcs:school_tome`, calling `/magicnpcs school set`).

## Installing a sample

1. Copy the whole numbered folder (e.g. `02_regional_magic/`) into a world's `datapacks/` folder,
   keeping the folder name and its `pack.mcmeta` at the top.
2. Or, for **OpenLoader**, copy its `data/` contents under
   `config/openloader/data/<pack>/data/...`, mirroring the sample's own `data/` tree.
3. Run `/reload`. Every already-loaded mob is re-evaluated, not just newly spawned ones, so an
   existing skeleton picks up a loadout the moment its file lands and you reload — it does not
   need to be respawned.

See [`docs/loadouts/README.md`](../docs/loadouts/README.md) for the full authoring reference this
folder is built against.

## The ten packs

| # | Folder | Teaches |
|---|---|---|
| 01 | `01_hostile_arcana` | Opting vanilla mobs into casting at all: attack vs support roles, `windup`/`cooldown`/`safety_radius`, `native_attack: coexist` vs `yield` |
| 02 | `02_regional_magic` | The full `conditions` block (dimensions, biome ids/`#tags`, time, `min_y`/`max_y`, difficulties, storms, raids, moon phases) and pooling four skeleton files by `pool_weight` |
| 03 | `03_village_guilds` | `profession` scoping, the profession-less fallback, and why plain villagers need support-only kits |
| 04 | `04_wither_court` | A phased boss encounter: per-spell `condition` blocks, `cast_chance`, `goal_priority`, `native_attack: suppress` and the caster movement it switches on, `cast_time` vs `cast_time_multiplier` vs `windup` |
| 05 | `05_staff_and_focus` | `equipment` blocks, `require_held_item`/`required_items`/`required_hand`, extending the `magicnpcs:spell_focuses` item tag |
| 06 | `06_addon_spells` | `spell_manifests`: every capability value, `verified_against`, the `_review` comment channel, resource-id merge order, and a loadout consuming the declared spells |
| 07 | `07_overrides_and_off_switches` | Datapack-beats-jar, `replace`, the bare `{"enabled": false}` shadow stub, `enabled`+`replace` group suppression, profession-scoped suppression, a parked draft |
| 08 | `08_npc_companions` | Third-party NPC mods, `[compat]` toggles, one file per Easy NPC variant, `npc_traits` (`any_of` vs `all_of`+`none_of`) for CustomNPCs |
| 09 | `09_arcane_progression` | Vanilla datapack surfaces: a function tag, mcfunctions driving `/magicnpcs school`, advancements with function rewards, a loot table, School Tome recipe overrides |
| 10 | `10_rare_elite_casters` | Rarity through pooled `pool_weight` tiers at a shared `caster_chance`, difficulty gating |

## Capability coverage matrix

Every schema field or datapack surface in this project, and the pack(s) where it is introduced or
best demonstrated. The everyday fields — `max_mana`, `mana_regen`, `level`, `weight`, `role`,
`min_range`, `max_range`, `safety_radius`, `cooldown`, `windup` — recur in nearly every pack; the
rest point at the pack that teaches them.

| Field / surface | Demonstrated in |
|---|---|
| `entity_type` | 01 (all packs use it; 01 is the minimal example) |
| `profession` | 03, 07 |
| `max_mana` / `mana_regen` | 01 (recurs in every pack) |
| `pool_weight` | 02, 08, 10 |
| `caster_chance` | 02, 03, 08, 09, 10 |
| `goal_priority` | 04, 07, 10 |
| `native_attack: coexist` | 01 |
| `native_attack: yield` | 01 |
| `native_attack: suppress` (+ caster movement) | 04, 08, 10 |
| `replace` | 02, 07 |
| `enabled` (bare stub, group suppression, parked draft) | 07 |
| `equipment` (mainhand/offhand, chance, only_if_empty) | 05, 08, 10 |
| `conditions.dimensions` | 02 |
| `conditions.biomes` (plain id and `#tag`) | 02, 10 |
| `conditions.time` | 02 |
| `conditions.min_y` / `max_y` | 02 |
| `conditions.difficulties` | 02, 03, 08, 10 |
| `conditions.require_raid` | 02 |
| `conditions.require_storm` | 02 |
| `conditions.moon_phases` | 02 |
| `conditions.npc_traits` (`any_of`, `all_of`+`none_of`) | 08 |
| spell `level` / `weight` / `role` | 01 (recurs in every pack) |
| spell `min_range` / `max_range` / `safety_radius` | 01 (recurs in every pack) |
| spell `cast_chance` | 04, 08, 10 |
| spell `cooldown` | 01 (recurs in every pack) |
| spell `cooldown_multiplier` | 02 |
| spell `windup` | 01 (recurs in every pack) |
| spell `cast_time` | 04 |
| spell `cast_time_multiplier` | 04 |
| spell `condition.self_hp_below` | 01 (recurs across 02, 03, 04, 07, 08, 10) |
| spell `condition.target_hp_below` | 04 |
| spell `condition.enemies_within` / `enemies_radius` | 04, 08, 10 |
| spell `condition.when_recently_hurt` / `recent_damage_window` | 03, 04, 10 |
| `require_held_item` / `required_items` / `required_hand` | 05 |
| `data/<namespace>/spell_manifests/*.json` | 06 |
| `data/magicnpcs/tags/items/spell_focuses.json` extension | 05 |
| `minecraft:load` function tag + mcfunctions | 09 |
| advancements with function rewards | 09 |
| custom loot table | 09 |
| School Tome recipe overrides (`forge:conditional`) | 09 |

## Troubleshooting a mob that isn't casting

- `/magicnpcs validate` — lists every discovered loadout and manifest file with its status
  (ACTIVE, SHADOWED, SUPPRESSED, REJECTED, SKIPPED). Start here after installing a sample: a typo
  or a missing mod shows up as REJECTED or SKIPPED instead of the mob silently doing nothing.
- `/magicnpcs why <targets>` — explains why a specific mob is or isn't casting right now,
  including which goal (if any) is blocking it.
- `/magicnpcs loadout entity <targets>` — shows which loadout a mob actually resolved to and which
  pack it came from.
- `/magicnpcs spells [filter]` — the live, authoritative spell list with each spell's mob-cast
  verdict.

## Installing more than one at once

Several of these packs deliberately target the **same** entity types — `minecraft:skeleton`,
`minecraft:witch`, `minecraft:zombie`, and `minecraft:villager` all recur across multiple packs
(01, 02, 05, 07, 09, and 10 all define a skeleton loadout, for example) — specifically so you can
see pooling, `replace`, and suppression in action. Installing all ten together makes those packs
contend with each other for the same mobs, which is instructive but not representative of a real
modpack. Install one or two at a time to see a single pack's behaviour cleanly; use
`/magicnpcs validate` to see exactly which files are pooling, shadowing, or suppressing which.
