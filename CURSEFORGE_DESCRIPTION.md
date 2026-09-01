# Magic NPCs

**Give your NPCs real spells.** Magic NPCs makes mobs cast spells from *Iron's
Spells 'n Spellbooks* — driven by datapacks and config, so any mob can become a
spellcaster, with first-class support for **Villager Recruits** and a per-NPC
**magic-school** system for recruits and villagers.

## What it does

- **Datapack-driven.** Drop a small JSON in `data/<pack>/spellcasters/` naming an
  entity type and a list of Iron's spells — that mob now casts them. No tags, no
  add-on mods. Shipped loadouts cover **optional NPC mods** (Recruits, Guard
  Villagers) and stay inert unless that mod is installed — vanilla mobs cast nothing
  until you opt them in (copy the skeleton example below). **Your datapack always beats a
  loadout the mod itself ships**, so you never get a mix of your spells and ours. When two
  *datapacks* target one entity they **pool** by default; add `"replace": true` to override
  cleanly, or `"enabled": false` to switch a mob type off entirely.
  `/magicnpcs loadout id minecraft:skeleton` and `/magicnpcs validate` show exactly what each mob
  resolves to, and what every loadout file in your pack did or did not do.
- **It tells you why.** `/magicnpcs why @e[type=minecraft:skeleton,sort=nearest,limit=1]` explains,
  for a live mob, exactly why it is or isn't casting right now — goal injection, reconciliation state,
  the goal that's blocking it, state gates, target and line of sight, mana, and a per-spell table with
  the first blocker for each spell. `/magicnpcs validate` reports every loadout file it discovered and
  what happened to it, **including files that failed to load**. `/magicnpcs config` shows the effective
  settings and where they are read from. Type `/magicnpcs` for the full list — every line it prints is
  a command you can paste.
- **Coexists with a mob's own AI.** Mobs with built-in ranged attacks (the vanilla witch, modded
  casters) cast *alongside* that AI; a bow skeleton shoots **and** casts. Per-loadout
  `"native_attack"` lets you convert a mob to a pure caster instead, or make it defer.
- **Support out of combat.** A wounded support NPC heals itself without having to be attacked
  first, on its own slow cadence — and never at full health.
- **Magic schools (recruits & villagers).** Assign each individual recruit or villager
  one of the nine Iron's schools — fire, ice, lightning, holy, ender, blood, evocation,
  nature, eldritch. Its spell pool is built **dynamically** from that school, so it
  even uses spells added by Iron's add-ons. Schools are assigned automatically on spawn
  (recruits by chance/rank, villagers by profession), and you can set any individual's
  school with the **`/magicnpcs school`** command or the craftable **School Tome** item
  (**right-click** an NPC to see what it is set to; **sneak-right-click** to change it — cycling past
  the last usable school stops it casting).
- **Real Iron's casting.** Spells run Iron's own cast lifecycle — cast initiation, pre-cast, per-tick
  channelling, completion and cancellation — in the same order Iron's own casting mobs use, so
  channelled and continuous spells behave as designed rather than firing once and stopping. You get
  the genuine projectiles, particles, and sounds. Mobs have a real mana pool that regenerates and
  gates how often they cast, charged once per cast.
- **It refuses spells it hasn't verified.** Magic NPCs ships a reviewed, per-spell manifest of what a
  non-player caster can actually do with each Iron's spell. Anything outside it — an add-on spell, or
  a newer Iron's than this build was checked against — is skipped and reported rather than mis-fired,
  so a spell never silently spends mana and does nothing. Opt in with `spells.allowUnverifiedSpells`
  if you would rather try them.
- **Smart and safe.** Casters check line of sight, range, mana, and cooldowns; they
  won't cast while sleeping, dead, or on Peaceful, and mana scales with difficulty.
  Friendly-fire protection keeps villagers, iron golems, pets, owners, and
  teammates out of the blast (players optionally too) — and Recruits cast **only at enemies** using
  their own diplomacy, respect their command state, and scale mana with **rank**.
- **Works with your NPC mods.** Beyond Recruits, optional config-gated support covers
  **Guard Villagers, MCA Reborn, MineColonies, Easy NPC, Human Companions, More
  Villagers, and VillagersPlus** — each behind a toggle that defaults **off**, so
  nothing changes until you opt in. Owned/teamed NPCs (e.g. Human Companions) get
  friendly-fire safety automatically.
- **Deeply tunable.** A per-world server config controls global on/off, mana/cooldown/regen
  balance, decision interval, difficulty scaling, line-of-sight and friendly-fire
  rules, a spell allow/deny list, equipment requirements, and the full magic-school system;
  a second, pack-level config in `config/` holds the per-mod compat toggles so modpack authors set
  them once. Individual shipped loadouts can be switched off from config alone, with no datapack.

## Make any mob a spellcaster (datapacks)

Magic NPCs is datapack-driven: drop a small JSON naming an entity type and a list of
Iron's spells, and that mob casts them. A complete, minimal pack is two files under
`<world>/datapacks/my_magic/`:

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

Run `/reload`. Every loaded mob is re-evaluated, so skeletons that were already standing there become
casters too — you do not need to respawn them.

To actually see a cast, give one something to fight: **Normal difficulty or harder**, a Survival or
Adventure player (a Creative player is not a valid hostile target), inside 24 blocks and in line of
sight. An attack-only caster never casts while idle. If nothing happens, run:

```mcfunction
/magicnpcs why @e[type=minecraft:skeleton,sort=nearest,limit=1]
```

It names the first thing standing in the way. `/magicnpcs validate` checks your JSON and reports every
file it found — including ones that failed to load.

Each spell also accepts `min_range`/`max_range`, `safety_radius` (friendly-fire clearance — larger for
AoE spells), and `role` (`attack` or `support`). Add `"caster_chance": 0.15` to make only some
skeletons mages, or `"profession": "minecraft:cleric"` to scope a villager loadout to a single
profession. The full guide — annotated multi-spell examples, modded mobs, the spell-focus tag, and
explicit-loadouts vs. magic-schools — is in the README and `docs/loadouts/`.

## Requirements

- **Forge 47.4.0+** for Minecraft **1.20.1**
- **Iron's Spells 'n Spellbooks** (`1.20.1-3.15.x` – `1.20.1-3.16.x`, the range this build was
  verified against) + its dependencies **GeckoLib**, **Curios API**, **PlayerAnimator** — required for
  any spellcasting
- **Villager Recruits** (1.15.0+) — *optional*, enables the recruit features

Magic NPCs does not include these mods; install them separately. Every NPC-mod
integration is optional and loads safely whether or not that mod is present.

## Notes

- Server-side config; settings sync to clients automatically.
- Villagers given a school only cast offensively when something gives them a target — a guard/NPC
  mod, or the opt-in `schools.villagers.selfDefense`. (Raids do not: vanilla never targets for a
  villager.) Their normal, peaceful behavior is otherwise preserved.
- Open source under **GNU GPL-3.0** — the same licence declared in the jar's `mods.toml` and in the
  repository. If this project's CurseForge metadata says anything else, the GPL-3.0 declaration in the
  source is authoritative.
- Iron's Spells and Villager Recruits are compile-only dependencies — neither is bundled or
  redistributed.
