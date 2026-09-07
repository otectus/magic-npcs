# 04 — The Wither Court

A phased boss encounter built from four cooperating loadouts: a lord, a ritualist, a bruiser, and
a swarm of adds. This pack is the one place in this folder that exercises every field of the
per-spell `condition` block, alongside cast-timing controls the other packs only touch lightly.

## Files

| File | `entity_type` | `goal_priority` | `native_attack` | Notes |
|---|---|---|---|---|
| `wither_skeleton_lord.json` | `minecraft:wither_skeleton` | 0 | `suppress` | The boss; every spell carries its own `condition` |
| `evoker_ritualist.json` | `minecraft:evoker` | 1 | `suppress` | `cast_time` and `cast_time_multiplier` examples |
| `ravager_champion.json` | `minecraft:ravager` | 2 | `suppress` | Melee bruiser with an `enemies_within` finisher |
| `vex_swarm.json` | `minecraft:vex` | 3 | *(default)* | Fast, fragile adds; short cooldowns, small mana pool |

## What this teaches

- **Every `condition` field, across the pack:**
  - `self_hp_below` — `evoker_ritualist.json`'s `shield` (0.5), `wither_skeleton_lord.json`'s
    `ice_block` (0.25)
  - `target_hp_below` — `wither_skeleton_lord.json`'s `heartstop` finisher (0.35), so it's held
    back until the target is nearly dead
  - `enemies_within` / `enemies_radius` — `ravager_champion.json`'s `earthquake` and
    `wither_skeleton_lord.json`'s `blood_slash`; `enemies_radius` is only meaningful alongside
    `enemies_within`
  - `when_recently_hurt` / `recent_damage_window` — `wither_skeleton_lord.json`'s `ice_block`
    panic button (window clamped to 100 ticks max)
- **`cast_chance`** — a hesitation roll independent of `condition`; `ravager_champion.json`'s
  `earthquake` (0.5), `wither_skeleton_lord.json`'s `heartstop` (0.6), and `vex_swarm.json`'s
  `acid_orb` (0.4).
- **`goal_priority`** — each of the four files sets a different value (0–3) so all four casting
  goals can coexist without fighting over goal-selector priority.
- **`native_attack: suppress`** — the lord, ritualist, and champion all hold their own vanilla
  attack goals inert (a "pure caster" conversion) and switch on Magic NPCs' caster movement: with
  nothing else telling the mob where to stand, it keeps itself in the band between the widest
  `min_range` and narrowest `max_range` of its own attack spells.
- **`cast_time` vs `cast_time_multiplier` vs `windup`** — `evoker_ritualist.json`'s
  `gravity_fissure` sets `cast_time: 6` (an absolute native cast duration, which always wins over
  a multiplier if both are set), while its `wisp` sets `cast_time_multiplier: 2.0` (twice the
  normal channel length). Both are distinct from `windup`, which is Magic NPCs' own pre-cast aim
  delay, not Iron's native cast/channel time.

## Prerequisites

Iron's Spells 'n Spellbooks. No `[compat]` toggle needed — every entity type here is vanilla.

## Try it

```
/summon minecraft:wither_skeleton ~ ~ ~
/magicnpcs loadout entity @e[type=minecraft:wither_skeleton,limit=1,sort=nearest]
/magicnpcs why @e[type=minecraft:wither_skeleton,limit=1,sort=nearest]
```

Watch `heartstop` stay unused until the target's health drops under 35%, and watch the lord
reposition on its own once it engages — that's `native_attack: suppress` driving caster movement.
