# Mob-friendly spell recommendations

Not every Iron's spell works well on a generic mob. Mobs aim with their head/body rotation and have no
sense of self-positioning, line-of-fire discipline, or combos, so spells that assume a thinking player
(precise aiming, teleport-and-strike, terrain utility) tend to whiff or do nothing. Magic NPCs aims the
caster at its target right before casting (see the wind-up/aim section in the main README), which makes
**target-aimed projectile** and **self/support** spells the most reliable choices.

All ids below are `irons_spellbooks:<id>`. Use `/magicnpcs spells` for the live list with cooldowns.

## ✅ Reliable projectile spells (best default for attackers)
Instant, fired straight at the target — exactly what the aim step is built for.

`magic_missile`, `magic_arrow`, `fireball`, `firebolt`, `fire_arrow`, `magma_bomb`, `icicle`,
`ice_spikes`, `ray_of_frost`, `snowball`, `acid_orb`, `poison_arrow`, `eldritch_blast`,
`lightning_lance`, `ball_lightning`, `blood_needles`, `wither_skull`, `guiding_bolt` (homing — very
forgiving for mobs).

Good starting ranges: `min_range: 3`, `max_range: 16–24`. Give them a `windup` of 10–20 so the cast is
telegraphed and aimed.

## ✅ Reliable self / support spells (role: `support`)
Self-cast; no aiming needed. Set `"role": "support"` so they fire when the caster is hurt (or attach a
reactive `condition`).

`heal`, `greater_heal`, `healing_circle`, `blessing_of_life`, `cloud_of_regeneration`, `oakskin`,
`shield`, `fortify`, `haste`, `evasion`, `invisibility`, `ice_block` (panic defensive).

(`wisp` looks support-ish but actually targets an enemy — see the target-locked section below.)

## ⚔️ Melee / close-range spells
Effective only when the caster is on top of the target — pair with a low `max_range` (≤ 4) and a melee
mob. Mobs won't reposition cleverly, so treat these as opportunistic.

`flaming_strike`, `fang_strike`, `fang_swirl`, `shadow_slash`, `divine_smite`, `spectral_hammer`,
`volt_strike`, `blood_slash`, `heartstop`. (`root` and `stomp` are also close-range but need special
handling — see the next section.)

## 🎯 Target-locked & channelled spells (`root`, `devour`, `wisp`, `stomp`)
These work on mobs as of 0.5.0, but have requirements worth knowing:

- **`root`, `devour`, `wisp`** read a **target entity** during the cast. Magic NPCs supplies the
  caster's current target automatically, so give them `"role": "attack"` (a `support`/self-cast role
  has no target and the spell is skipped with a debug reason). `root` and `wisp` are also **long
  (channelled)** casts — the caster faces the target for the spell's full cast time before it lands,
  and cancels if the target dies or leaves range. `devour` is instant. Good ranges: `min_range: 0`,
  `max_range: 6–10`.
- **`stomp`** is a **long, forward ground-AoE** — it lands in front of the caster, not at the target,
  so keep the caster close and facing: `min_range: 0`, `max_range: 5`, `safety_radius: 4`. A long
  `max_range` makes the AoE fall short of the target; `/magicnpcs validate` warns about it.

```json
{ "spell": "irons_spellbooks:stomp", "level": 1, "role": "attack",
  "min_range": 0.0, "max_range": 5.0, "safety_radius": 4.0 }
```

If a spell needs data a mob can't provide (multi-target or player-only), it's dropped from the
loadout with a clear log line rather than cast into the void. Use `/magicnpcs loadout entity <target>`
to see each spell's compatibility category and any skip reason.

## 💥 AoE spells (need a high `safety_radius`)
Big blasts that will hit allies/bystanders unless you widen the friendly-fire clearance. Use
`safety_radius: 3–6` (the telegraph also flags them as higher danger).

`blaze_storm`, `wall_of_fire`, `cone_of_cold`, `blizzard`, `frostwave`, `thunderstorm`,
`chain_lightning`, `shockwave`, `starfall`, `raise_hell`, `black_hole`, `gravity_fissure`,
`poison_splash`, `firefly_swarm`, `sculk_tentacles`, `dragon_breath`, `fire_breath`, `poison_breath`,
`earthquake`, `sunbeam`.

## 🚫 Not recommended for generic mobs
These need player-like intent — teleporting into position, terrain utility, summons that a mob won't
manage, or conversions. They'll often appear to "do nothing" on a mob.

`teleport`, `recall`, `portal`, `pocket_dimension`, `summon_ender_chest`, `planar_sight`,
`telekinesis`, `touch_dig`, `wololo`, `counterspell`, `summon_horse`, `summon_vex`, `summon_swords`,
`summon_polar_bear`, `raise_dead`, `arcane_shackle`, `slow`, `burning_dash`, `thunder_step`,
`frost_step`, `charge`, `ascension`.

> These are guidelines, not hard rules — a tightly-tuned pack can absolutely use a "melee" or "AoE"
> spell well. Start from the ✅ lists for hands-off reliability, then experiment.

## Example: a solid projectile attacker

```json
{
  "entity_type": "minecraft:stray",
  "max_mana": 120,
  "mana_regen": 10,
  "spells": [
    { "spell": "irons_spellbooks:icicle", "level": 2, "role": "attack",
      "min_range": 3, "max_range": 20, "windup": 15, "cooldown": 60 },
    { "spell": "irons_spellbooks:ice_spikes", "level": 1, "role": "attack",
      "min_range": 2, "max_range": 14, "windup": 10, "cooldown": 80 },
    { "spell": "irons_spellbooks:oakskin", "level": 1, "role": "support" }
  ]
}
```

## Example: a melee-range bruiser

```json
{
  "entity_type": "minecraft:vindicator",
  "max_mana": 100,
  "mana_regen": 8,
  "spells": [
    { "spell": "irons_spellbooks:flaming_strike", "level": 2, "role": "attack",
      "min_range": 0, "max_range": 4, "windup": 8, "cooldown": 50 },
    { "spell": "irons_spellbooks:fang_swirl", "level": 1, "role": "attack",
      "min_range": 0, "max_range": 5, "safety_radius": 2.0, "windup": 10, "cooldown": 70 }
  ]
}
```
