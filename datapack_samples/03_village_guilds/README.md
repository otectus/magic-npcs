# 03 — Village Guilds

Every villager profession shares the single entity type `minecraft:villager`, so a loadout has to
use the optional `profession` field to tell them apart. This pack gives four professions their
own kit, a profession-less fallback for everyone else, and an iron golem to show what a villager
*can't* do.

## Files

| File | `entity_type` | `profession` | Notes |
|---|---|---|---|
| `cleric.json` | `minecraft:villager` | `minecraft:cleric` | Attack (`guiding_bolt`) + two support spells |
| `farmer.json` | `minecraft:villager` | `minecraft:farmer` | Support-only kit; `caster_chance: 0.5` |
| `librarian.json` | `minecraft:villager` | `minecraft:librarian` | Attack + support |
| `toolsmith.json` | `minecraft:villager` | `minecraft:toolsmith` | Attack + support |
| `villager_fallback.json` | `minecraft:villager` | *(none)* | Fallback for every profession not named above; `caster_chance: 0.2` |
| `iron_golem.json` | `minecraft:iron_golem` | — | Attack + support kit for a mob that actually has combat AI; also sets `conditions.difficulties` (all four difficulties) |

## What this teaches

- **`profession` scoping.** A loadout with `profession` set applies only to villagers of that
  profession; villagers of any other profession fall through to the next candidate.
- **The profession-less fallback.** A `minecraft:villager` loadout with no `profession` field
  (`villager_fallback.json`) is a fallback for professions no file in the pack names — a
  profession match always beats it, it is never merged with a profession-specific file.
- **Why plain villagers get support-only kits.** A vanilla villager has an empty goal selector and
  nothing ever calls `setTarget` on it — not even during a raid, where villagers only hide — so an
  attack spell on a villager without outside help (a guard mod, or the opt-in
  `schools.villagers.selfDefense`) would sit unused. `farmer.json` and `villager_fallback.json`
  are support-only for exactly this reason; `cleric.json`, `librarian.json`, and `toolsmith.json`
  include an attack spell anyway to show the schema, but it needs one of those enabling conditions
  to actually fire.
- **Iron golems are different.** `iron_golem.json` is not a villager profession; it's a separate
  entity type with its own combat AI and target, so its attack spells (`guiding_bolt`, `stomp`)
  are actually usable. Its `stomp` entry also shows the forward-ground-AoE pattern: short
  `max_range`, wide `safety_radius`, because `stomp` lands in front of the caster rather than at
  the target. It's also the pack's only use of `conditions`: it lists all four `difficulties`
  (`peaceful`, `easy`, `normal`, `hard`) so the golem stays a caster even on Peaceful, though its
  own comment notes `balance.peacefulDisablesCasting` can still switch casting off there
  regardless of that list.
- **`caster_chance`.** Rolled once per villager and persisted; `farmer.json` (0.5) and
  `villager_fallback.json` (0.2) show two different rarities.

## Prerequisites

Iron's Spells 'n Spellbooks. No `[compat]` toggle needed — this is vanilla.

## Try it

```
/summon minecraft:villager ~ ~ ~ {VillagerData:{profession:"minecraft:cleric"}}
/magicnpcs loadout entity @e[type=minecraft:villager,limit=1,sort=nearest]
/magicnpcs why @e[type=minecraft:villager,limit=1,sort=nearest]
```

`/magicnpcs why` on an ordinary (non-guard, non-raided) villager is the clearest way to see that
an attack spell is present in the loadout but never fires for lack of a target.
