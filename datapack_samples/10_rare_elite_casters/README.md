# 10 — Rare Elite Casters

Rarity tiers built entirely from `pool_weight` and `caster_chance`, plus one entity type gated to
hard difficulty only.

## Files

| File | `entity_type` | `pool_weight` | `caster_chance` | Notes |
|---|---|---|---|---|
| `skeleton_tier1_apprentice.json` | `minecraft:skeleton` | 12 | 0.15 | Common tier: cheap kit, `magic_missile` + `snowball` |
| `skeleton_tier2_adept.json` | `minecraft:skeleton` | 4 | 0.15 | Mid tier: `guiding_bolt`, `ice_spikes`, `oakskin` |
| `skeleton_tier3_archmage.json` | `minecraft:skeleton` | 1 | 0.15 | Rare tier: gated to `difficulties: normal, hard`; `native_attack: suppress`, spawn `equipment` |
| `husk_desert_adept.json` | `minecraft:husk` | — | 0.2 | A second entity type, gated to desert/badlands biomes and normal/hard difficulty |
| `zombie_hard_only.json` | `minecraft:zombie` | — | 0.1 | Gated to `difficulties: hard` only |

## What this teaches

- **Rarity through pooled `pool_weight`.** The three skeleton files all target
  `minecraft:skeleton` with the *same* `caster_chance` (0.15), so `pool_weight` alone controls
  which tier a given caster skeleton gets: 12 + 4 + 1 = 17, so the archmage tier is about 1-in-17
  (~6%) of the skeletons that pass the `caster_chance` roll — per the pack's own comment in
  `skeleton_tier1_apprentice.json`. Both the pool pick and the `caster_chance` roll happen once per
  mob and are persisted, so neither re-rolls on `/reload`.
- **Difficulty gating as a rarity lever.** `skeleton_tier3_archmage.json` additionally requires
  `normal` or `hard` difficulty — on `easy`, that file drops out of the pool entirely before the
  `pool_weight` pick runs, leaving only the two lower tiers. `zombie_hard_only.json` takes this
  further, requiring `hard` specifically; its own `_comment` notes that
  `balance.peacefulDisablesCasting` can also switch off casting on peaceful regardless of what a
  loadout says.
- **A second entity type in the same pack.** `husk_desert_adept.json` shows the pattern applies
  per-entity-type, not just within one pool — a husk elite gated to desert/badlands biomes,
  independent of the skeleton tiers.
- **`matchedConditionWeightBonus`.** The `_comment` in `husk_desert_adept.json` notes this
  `[reactive]` config option nudges a spell's effective weight up when its own `condition` block
  matches, so a conditional entry (like its `fortify` on `when_recently_hurt`) is picked more often
  than its raw `weight` value alone would suggest once the condition is true.
- **`equipment` on a rare tier.** `skeleton_tier3_archmage.json` spawns holding a
  `irons_spellbooks:pyrium_staff` (`chance: 1.0`, `only_if_empty: false`), visually marking the
  rare tier before it ever casts.

## Prerequisites

Iron's Spells 'n Spellbooks. No `[compat]` toggle needed — every entity type here is vanilla.

## Try it

```
/magicnpcs validate
/magicnpcs loadout entity @e[type=minecraft:skeleton,limit=1,sort=nearest]
```

Spawn a handful of skeletons and check `/magicnpcs loadout entity` on each — most should resolve
to the apprentice tier, a few to the adept tier, and the archmage tier only rarely (and never on
`easy`).
