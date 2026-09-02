# Magic NPCs

**Give your NPCs real spells.** Magic NPCs makes mobs cast spells from *Iron's Spells 'n
Spellbooks* — driven by datapacks and config, so **any** mob can become a spellcaster. It has
first-class, purpose-built support for **Villager Recruits** and **Easy NPC**, a per-NPC
**magic-school** system, and a diagnostic command that tells you exactly why a mob is or isn't
casting.

No add-on mods, no tags, no code. One small JSON file is the entire opt-in.

## What it does

- **Datapack-driven.** Drop a JSON in `data/<pack>/spellcasters/` naming an entity type and a list of
  Iron's spells — that mob now casts them. **Your datapack always beats a loadout the mod itself
  ships**, so you never get a mix of your spells and ours. When two *datapacks* target one entity they
  **pool** by default; add `"replace": true` to override cleanly, or `"enabled": false` to switch a mob
  type off entirely. Vanilla mobs cast nothing until you opt them in.

- **It tells you why.** `/magicnpcs why @e[type=minecraft:skeleton,sort=nearest,limit=1]` explains, for
  a live mob, exactly why it is or isn't casting right now — goal injection, reconciliation state, the
  goal that's blocking it, state gates, target and line of sight, mana, and a per-spell table naming
  the first blocker for each spell. `/magicnpcs validate` reports every loadout file it discovered and
  what happened to it — active, shadowed, suppressed, or **failed to load**. Type `/magicnpcs` for the
  full tree; every line it prints is a command you can paste.

- **Real Iron's casting.** Spells run Iron's own cast lifecycle — initiation, pre-cast, per-tick
  channelling, completion and cancellation — in the same order Iron's own casting mobs use. Channelled
  spells actually channel instead of firing once and stopping, and an interrupted cast is torn down
  cleanly rather than leaking. You get the genuine projectiles, particles and sounds. Mobs have a real
  mana pool that regenerates and gates how often they cast.

- **It refuses spells it hasn't verified.** Magic NPCs ships a reviewed, per-spell manifest of what a
  non-player caster can actually do with each Iron's spell, derived from the Iron's jar rather than
  guessed. Anything outside it — an add-on spell, or a newer Iron's than this build was checked
  against — is **skipped and reported**, not mis-fired, so a spell never silently spends mana and does
  nothing. A handful (`teleport`, `frost_step`, `blood_step`, `burning_dash`, `ray_of_siphoning`) are
  marked unsupported because Iron's prepares them through hooks a foreign mob cannot provide. Opt in to
  the unknowns with `spells.allowUnverifiedSpells`.

- **Coexists with a mob's own AI.** Mobs with built-in ranged attacks — the vanilla witch, modded
  casters — cast *alongside* that AI; a bow skeleton shoots **and** casts. Per-loadout
  `"native_attack"` lets you pick: `coexist` (default), `yield` (only when its own attack AI is idle),
  or `suppress` to convert it into a pure caster. Suppression is **reversible** — the original goals
  are held, not destroyed, and handed back intact when the loadout changes or the mod is disabled.

- **Casters that know where to stand.** A mob converted with `native_attack: suppress` has nothing left
  telling it where to position, so Magic NPCs keeps it in the band between the widest `min_range` and
  narrowest `max_range` of its own attack spells — backing off when a target closes inside that band,
  advancing when one drifts out, and holding still while channelling so it doesn't throw away its aim.
  Where the NPC's own mod has a command system this is respected: a Recruit holding a position gets a
  short leash, one following its owner a longer one, and one in a formation or marching doesn't move at
  all. Tunable with `balance.casterMovement` and `balance.casterMovementSpeed`.

- **You can see a cast coming.** Attack spells play a wind-up tell — particles tinted to the spell's
  Iron's school, plus a sound — so a fireball to the face isn't unannounced. All server-spawned vanilla
  effects, safe on dedicated servers. Configurable under `[feedback]`: `telegraphs`, `schoolParticles`,
  `telegraphGlow` (an outline during wind-up, off by default), `telegraphVolume`, and `minDangerTier`
  to telegraph only the spells actually worth dodging. Out-of-combat self-heals are never telegraphed.

- **Support out of combat.** A wounded support NPC heals itself without having to be attacked first, on
  its own slow cadence — and never at full health.

- **Magic schools, and a Tome to set them.** Assign an individual NPC one of the nine Iron's schools —
  fire, ice, lightning, holy, ender, blood, evocation, nature, eldritch. Its spell pool is built
  **dynamically** from that school, so it even picks up spells added by Iron's add-ons. Schools are
  assigned automatically on spawn (Recruits by chance and rank, villagers by profession, Easy NPCs by
  their own opt-in settings), and you can override any individual with the **`/magicnpcs school`**
  command or the craftable **School Tome**: right-click an NPC to inspect what it's set to,
  sneak-right-click to cycle it — past the last usable school stops it casting. A manual choice
  outranks everything, and `/magicnpcs school auto` hands it back.

- **Smart and safe.** Casters check line of sight, range, mana and cooldowns; they won't cast while
  sleeping, dead, or on Peaceful, and mana scales with difficulty. Friendly-fire protection keeps
  villagers, iron golems, pets, owners and teammates out of the blast (players optionally too).

- **Deeply tunable.** A per-world server config covers the master switch, mana/cooldown/regen balance,
  decision cadence, difficulty and rank scaling, line-of-sight and friendly-fire rules, a spell
  allow/deny list, equipment requirements, wind-up feedback, and the whole magic-school system. A
  second, pack-level config in `config/` holds the per-mod compat toggles, so modpack authors set them
  once instead of per world. Individual shipped loadouts can be switched off from config alone, with no
  datapack.

## Purpose-built NPC mod support

Two mods get a real compiled integration rather than a generic loadout. Both are **progression-aware**:
an NPC that ranks up grows its mana pool and, up to a capped bonus, the level it casts its spells at
(`balance.rankLevelPerRank`, `balance.rankLevelMaxBonus` — a loadout's own `level` is a floor this
raises, never lowers).

**Villager Recruits** — recruits cast **only at enemies**, using Recruits' own diplomacy rather than a
reimplementation of it; they respect their command state (a recruit ordered passive won't spell-spam,
but may still heal itself); and a casting recruit that repositions still honours a hold, follow or
formation order rather than walking out of the line you put it in.

**Easy NPC** *(new in 0.7.0)* — an Easy NPC caster never casts an attack spell at its **owner**, at a
sibling NPC with the same owner, or at anything sharing its **faction**. It scales with its Easy NPC
**experience level**, won't cast while paused, and stays put when marked immovable or given a home
position. It also plugs into Easy NPC's own systems:

- **Cast from a dialog or trigger** — add an Easy NPC action of type `CUSTOM` reading
  `magicnpcs:cast <spell> [level] [self|target]`, e.g. `magicnpcs:cast irons_spellbooks:heal 2 self`.
- **Gate dialog options** on `magicnpcs:has_school`, `magicnpcs:can_cast` and `magicnpcs:has_mana`, so
  you never offer a player something the NPC can't actually do.
- **A `magicnpcs:cast_spell` objective**, registered with Easy NPC's own objective system.

Owner and faction protection stay active even with the integration switched off — disabling it stops
Easy NPCs casting, it never removes the rules about who they may not cast at.

Beyond those two, config-gated datapack support covers **Guard Villagers, MCA Reborn, MineColonies,
Human Companions, More Villagers and VillagersPlus** — each behind a toggle that defaults **off**, so
nothing changes until you opt in. Any owned or teamed NPC gets friendly-fire safety automatically, with
no hard dependency.

## Make any mob a spellcaster (datapacks)

A complete, minimal pack is two files under `<world>/datapacks/my_magic/`:

**`pack.mcmeta`** — for 1.20.1, `pack_format` is **15**:

```json
{ "pack": { "pack_format": 15, "description": "My Magic NPCs loadouts" } }
```

**`data/my_magic/spellcasters/skeleton.json`**:

```json
{
  "entity_type": "minecraft:skeleton",
  "max_mana": 100,
  "mana_regen": 10,
  "spells": [
    { "spell": "irons_spellbooks:magic_missile", "level": 1, "weight": 3, "max_range": 16.0, "role": "attack" },
    { "spell": "irons_spellbooks:heal", "level": 1, "role": "support" }
  ]
}
```

Run `/reload`. Every loaded mob is re-evaluated, so skeletons already standing there become casters
too — you don't need to respawn them.

To actually see a cast, give one something to fight: **Normal difficulty or harder**, a Survival or
Adventure player (a Creative player is not a valid hostile target), within the spell's `max_range` —
16 blocks in the example above — and in line of sight. An attack-only caster never casts while idle.
If nothing happens:

```mcfunction
/magicnpcs why @e[type=minecraft:skeleton,sort=nearest,limit=1]
```

It names the first thing standing in the way.

**There is a lot more in a loadout than the example shows.** Each spell entry also takes `min_range`,
`safety_radius` (friendly-fire clearance — widen it for AoE), `cast_chance`, `cooldown`,
`cooldown_multiplier`, `windup`, and a reactive `condition` block (`self_hp_below`, `target_hp_below`,
`enemies_within`, `when_recently_hurt`). At the root you can add `caster_chance` to make only some
skeletons mages, `profession` to scope a villager loadout to one job, `equipment` for weighted starting
gear, `pool_weight`, `goal_priority`, and a `conditions` block restricting the loadout by `dimensions`,
`biomes`, `difficulties`, `time`, `moon_phases`, `min_y`/`max_y`, `require_raid` or `require_storm`.
The full guide is in the README and `docs/loadouts/`.

## Commands

`/magicnpcs why` · `loadout entity` · `loadout id` · `validate` · `spells` · `config` · `reconcile` ·
`school info|set|reroll|clear|auto|pool` · `help`

## Requirements

- **Minecraft 1.20.1** on **Forge 47+** (built and tested against 47.4.16)
- **Iron's Spells 'n Spellbooks** — required for any spellcasting — plus its own dependencies
  **GeckoLib**, **Curios API** and **PlayerAnimator**. Verified against `1.20.1-3.15.x`–`1.20.1-3.16.x`.
- **Villager Recruits** 1.15.0+ — *optional*, enables the recruit features
- **Easy NPC: Core** 7.11+ — *optional*, enables the Easy NPC features. The separate Easy NPC
  configuration-UI module is **not** required; a core-only install is fully supported.

Magic NPCs does not include any of these; install them separately. Every integration is optional and
loads safely whether or not that mod is present — with Iron's absent, the mod boots cleanly and simply
does nothing.

## Notes

- Gameplay settings are server-side and sync to clients automatically. Modpack authors: the per-mod
  compat toggles live in `config/magicnpcs-common.toml`, so they're set once rather than per world.
- Villagers given a school only cast offensively when something gives them a target — a guard or NPC
  mod, or the opt-in `schools.villagers.selfDefense`. (Raids don't: vanilla never targets for a
  villager.) Their normal peaceful behaviour is otherwise preserved.
- Mobs that don't use Minecraft's goal system at all (some animation- or brain-driven modded mobs)
  can't be reached by goal injection. `/magicnpcs why` shows this plainly rather than failing silently.
- Open source under **GNU GPL-3.0** — the same licence declared in the jar's `mods.toml` and in the
  repository. If this project's CurseForge metadata says anything else, the GPL-3.0 declaration in the
  source is authoritative.
- Iron's Spells, Villager Recruits and Easy NPC are compile-only dependencies — none is bundled or
  redistributed.
