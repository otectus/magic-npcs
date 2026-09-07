# 07 — Overrides and Off Switches

Six files, each showing a different way to win against, replace, or turn off a loadout — from a
one-line stub to a full profession-scoped suppression.

## Files

| File | `entity_type` | Mechanism |
|---|---|---|
| `data/magicnpcs/spellcasters/guard.json` | *(inferred)* | Bare `{"enabled": false}` stub, at the exact data path of the mod's own shipped `guardvillagers:guard` loadout |
| `data/magicnpcs/spellcasters/recruit.json` | `recruits:recruit` | A same-path full replacement of the shipped `recruits:recruit` loadout — no `replace` flag needed |
| `data/overrides_demo/spellcasters/skeleton_master_off_switch.json` | `minecraft:skeleton` | `enabled: false` + `replace: true` — suppresses every `minecraft:skeleton` loadout from every pack |
| `data/overrides_demo/spellcasters/villager_cleric_off.json` | `minecraft:villager` (`profession: minecraft:cleric`) | Same suppression, scoped to one profession only |
| `data/overrides_demo/spellcasters/witch_authoritative.json` | `minecraft:witch` | `replace: true` on a live loadout — wins the pool against other packs defining `minecraft:witch` |
| `data/overrides_demo/spellcasters/zombie_disabled_draft.json` | `minecraft:zombie` | `enabled: false` with no `replace` — a parked draft, inert but preserved |

## What this teaches

- **Datapack always beats jar data.** `recruit.json` replaces the mod's bundled
  `recruits:recruit` loadout just by existing at the same key — a datapack outranks mod-jar data
  for the same `entity_type` (+ `profession`) automatically. `replace` is not needed for this;
  it only arbitrates between *two datapacks*.
- **The bare `{"enabled": false}` shadow stub.** `guard.json` has no `entity_type` field at all.
  Because it sits at the same data path (`magicnpcs:spellcasters/guard.json`) as the loadout it's
  switching off, the entity type is inferred from the file being shadowed. `/magicnpcs validate`
  records that inference so you can confirm it caught the right key.
- **`enabled: false` + `replace: true` — the blunt off switch.** `skeleton_master_off_switch.json`
  and `villager_cleric_off.json` both use this combination to suppress every loadout for a key
  (not just this pack's own), across every other datapack too. A disabled loadout can omit
  `spells` entirely.
- **Profession-scoped suppression.** `villager_cleric_off.json` shows the suppression is scoped to
  the `entity_type` + `profession` key: it turns off cleric casting only, leaving every other
  villager profession (and the profession-less fallback) untouched.
- **`replace: true` winning a pool.** `witch_authoritative.json` shows plain `replace` (no
  `enabled: false`) — it beats other datapacks' `minecraft:witch` loadouts outright instead of
  pooling with them. If two packs both set `replace: true` for the same key, neither wins; they
  pool against each other instead, and `/magicnpcs validate` reports `POOLED` so you know to drop
  one of the flags.
- **A parked draft.** `zombie_disabled_draft.json` sets `enabled: false` with no `replace`, so it
  only removes itself — it's inert but still shows up in `/magicnpcs validate` as `SUPPRESSED`
  rather than vanishing. Flip `enabled` to `true` to bring it back.

## Config-side alternatives

- **`[builtinLoadouts]`** in `magicnpcs-server.toml` turns off one of the mod's own shipped
  loadouts (like `guard` or `recruit`) by config key — exactly equivalent to the `guard.json` stub
  above, without writing a datapack.
- **`general.disabledEntityTypes`** — a config list of entity-type ids that never get a casting
  goal, regardless of any loadout.
- **`general.enableSpellcasting = false`** — turns the whole feature off, immediately, even for
  already-spawned casters.

## Prerequisites

Iron's Spells 'n Spellbooks. `recruit.json` needs Recruits installed for there to be a shipped
`recruits:recruit` loadout to replace — Recruits is a first-class integration with no `[compat]`
toggle to set. The Guard Villagers stub needs Guard Villagers installed for there to be anything
to shadow.

## Try it

```
/magicnpcs validate
/magicnpcs loadout entity @e[type=minecraft:skeleton,limit=1,sort=nearest]
```

Check `/magicnpcs validate` for the `guard.json` and `recruit.json` entries specifically — it will
show the inferred entity type for the stub and confirm the recruit replacement is `ACTIVE`.
