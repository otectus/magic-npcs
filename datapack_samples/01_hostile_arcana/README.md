# 01 — Hostile Arcana

The starting point. Magic NPCs ships **no** active loadout for vanilla mobs, so a vanilla
skeleton, witch, or zombie casts nothing until a datapack opts it in — the presence of an
`entity_type` file is the opt-in. This pack gives six vanilla hostiles a small kit each and is
the smallest, least conditional example in this folder.

## Files

| File | `entity_type` | What it shows |
|---|---|---|
| `skeleton.json` | `minecraft:skeleton` | The minimum viable caster: two `attack` projectile spells, `native_attack: coexist` (explicit, though it's the default) |
| `evoker.json` | `minecraft:evoker` | `native_attack: yield` — casts only while none of its own attack goals (fangs, vexes, wololo) is running, plus a `support` spell gated on `self_hp_below` |
| `witch.json` | `minecraft:witch` | Keeps its own potion-throwing AI (default `coexist`); `heal` is `support` and only fires below 40% HP |
| `zombie.json` | `minecraft:zombie` | A melee bruiser: both spells use a short `max_range` because a mob won't reposition to set up a longer-range spell |
| `stray.json` | `minecraft:stray` | An ice-projectile attacker plus one `support` self-buff |
| `vindicator.json` | `minecraft:vindicator` | Melee-range spells plus a `support` `shield` |

## What this teaches

- **`entity_type` as the opt-in.** The file's existence, not any special flag, is what makes a
  vanilla mob cast — see the `_comment` in `skeleton.json`.
- **`role: attack` vs `role: support`.** Attack spells target an enemy; support spells are
  self-cast and fire when the mob is out of combat or hurt (subject to a `condition`). `witch.json`
  and `evoker.json` both gate their support spell with `condition.self_hp_below`.
- **`windup` / `cooldown` / `safety_radius`.** Most attack entries set a `windup` (ticks of
  aim/telegraph before the spell fires) — `witch.json`'s `acid_orb` is the one exception, with no
  `windup` at all — and every attack entry sets a `cooldown` (ticks between casts); AoE-ish entries
  widen `safety_radius` to keep the spell from hitting the caster's own allies.
- **`native_attack`.** `"coexist"` (skeleton, witch, zombie, default) runs the casting goal
  alongside the mob's own attack AI. `"yield"` (evoker) only casts while the mob's own attack
  goals are idle.

## Prerequisites

Iron's Spells 'n Spellbooks must be installed — every spell id here is `irons_spellbooks:*`. No
`[compat]` toggle is needed; these are vanilla entity types.

## Try it

```
/summon minecraft:skeleton ~ ~ ~
/magicnpcs validate
/magicnpcs loadout entity @e[type=minecraft:skeleton,limit=1,sort=nearest]
/magicnpcs why @e[type=minecraft:skeleton,limit=1,sort=nearest]
```

`/magicnpcs why` is the fastest way to confirm a mob picked up this loadout and see whether its
casting goal is actually running.
