# 02 — Regional Magic

The `conditions` block gates a loadout on the world state around the mob: where it is, when, and
under what circumstances. This pack exercises every `conditions` field across nine spellcaster
files, and pools two different entity types — four `minecraft:skeleton` files, and a second
`minecraft:pillager` file against `pillager_raid.json` — to show how loadouts targeting the same
entity type **pool** by `pool_weight` instead of merging.

## Files

| File | `entity_type` | `pool_weight` | Conditions used |
|---|---|---|---|
| `skeleton_forest_night.json` | `minecraft:skeleton` | 4 | `dimensions`, `biomes` (`#tag`), `time: night`, `min_y` |
| `skeleton_frozen.json` | `minecraft:skeleton` | 3 | `dimensions`, `biomes` (plain ids, no single vanilla tag covers snowy biomes) |
| `skeleton_nether.json` | `minecraft:skeleton` | 5 | `dimensions`, `biomes` (`#tag`); spells use `cooldown_multiplier` instead of `cooldown` |
| `skeleton_deep_dark.json` | `minecraft:skeleton` | 2 | `dimensions`, `max_y`, `difficulties` |
| `enderman_end.json` | `minecraft:enderman` | — | `dimensions`, `biomes` (`#tag`), `time: any` (explicit default) |
| `pillager_raid.json` | `minecraft:pillager` | — | `require_raid`, `difficulties` |
| `pillager_daylight_patrol.json` | `minecraft:pillager` | 2 | `time: day`, `difficulties`, `dimensions`, `min_y`; also sets `caster_chance: 0.4` — the pack's only `caster_chance` and only `time: day` example, pooling against `pillager_raid.json` |
| `witch_full_moon.json` | `minecraft:witch` | — | `time: night`, `moon_phases`; also sets `replace: true` |
| `zombie_thunderstorm.json` | `minecraft:zombie` | — | `require_storm`, `time: night` |

## What this teaches

- **`conditions.dimensions`** — plain resource ids only; dimension tags are not accepted (see the
  `_comment` in `skeleton_forest_night.json`).
- **`conditions.biomes`** — accepts a plain biome id *or* a `#tag`. `skeleton_frozen.json` uses
  plain ids because no single vanilla biome tag covers every snowy biome; the other skeleton files
  use `#minecraft:is_forest` / `#minecraft:is_taiga` / `#minecraft:is_jungle` and
  `#minecraft:is_nether`.
- **`conditions.time`**, **`min_y`/`max_y`** — `skeleton_deep_dark.json` uses `max_y: 0` to gate
  on the deepslate layers; Y values are absolute world Y, so what's "sensible" differs per
  dimension. `pillager_daylight_patrol.json` is the pack's only `time: day` example.
  `enderman_end.json` also sets `time: any` explicitly, even though `any` is already the default,
  to document intent rather than change behaviour.
- **`conditions.difficulties`**, **`require_raid`**, **`require_storm`**, **`moon_phases`** —
  in `pillager_raid.json` (raid + difficulty), `pillager_daylight_patrol.json` (a second
  `difficulties` example), `zombie_thunderstorm.json` (storm), and `witch_full_moon.json` (moon
  phase 0/1/7 — 0 is the full moon). `require_storm` only tests whether the dimension is actually
  thundering (`LoadoutConditions`'s `!level.isThundering()` check) — ordinary rain never satisfies
  it, so `zombie_thunderstorm.json` is rarer than a plain rain check would be.
- **Pooling by `pool_weight`, on two entity types.** The four skeleton files target
  `minecraft:skeleton` with no `replace`, so they pool: each skeleton whose location satisfies one
  or more of the files' `conditions` sticky-picks *one* file, weighted by `pool_weight` among the
  matching files. `pillager_daylight_patrol.json` gives the pack a second pooled entity type: it
  pools against `pillager_raid.json` on `minecraft:pillager` (`pool_weight: 2` vs. no explicit
  weight), so a pillager satisfying both a raid and daylight patrol conditions still sticky-picks
  just one of the two. Spell lists are never merged.
- **`cooldown_multiplier`** — `skeleton_nether.json` uses `0.75` (faster) on `firebolt` and `1.5`
  (slower) on `magma_bomb` instead of an explicit `cooldown`.
- **`replace: true`** — `witch_full_moon.json` sets it so this loadout wins outright against any
  other pack that also defines `minecraft:witch`, instead of pooling with it.

## Prerequisites

Iron's Spells 'n Spellbooks. No `[compat]` toggle needed (vanilla entity types).

## Try it

```
/magicnpcs validate
/magicnpcs loadout entity @e[type=minecraft:skeleton,limit=1,sort=nearest]
```

Spawn skeletons in different biomes/dimensions and re-run `loadout entity` to see which of the
four files each one resolved to. `/magicnpcs validate` reports pooling status for the skeleton
files directly.
