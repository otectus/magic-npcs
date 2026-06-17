# Magic schools for recruits & villagers

Magic NPCs can assign each individual **Villager Recruit** and **Villager** a specific
Iron's Spells school of magic. The NPC's spell pool is then built **dynamically** from
that school's currently-registered spells — so addon-added spells are picked up
automatically.

## The nine schools

`irons_spellbooks:fire`, `:ice`, `:lightning`, `:holy`, `:ender`, `:blood`,
`:evocation`, `:nature`, `:eldritch`.

## How assignment works

1. **Automatic on spawn (persisted).** When an eligible recruit/villager spawns it is
   rolled once against its caster-chance and, if it wins, assigned a school. The result
   (a school, or a sticky "not a caster" mark) is stored in the entity's persistent data,
   so it never re-rolls and survives save/load.
   - **Recruits:** eligible when `schools.recruits.enabled` and the recruit's rank ≥
     `minRankToCast`. School chosen by `assignmentMode`:
     - `RANDOM` — random from `allowedSchools`
     - `BY_TYPE` — from the `typeSchools` map (e.g. `recruits:captain=irons_spellbooks:fire,irons_spellbooks:holy`)
     - `BY_RANK` — deterministic by rank (`allowed[rank % count]`)
   - **Villagers:** eligible when `schools.villagers.enabled` and the profession is in
     the `professionSchools` map (or `unmappedGetRandom` is true). **A magic villager
     only actually casts when it has a target** — i.e. during raids or when a guard/NPC
     mod gives it combat AI. Plain villagers stay fully passive.
2. **Command.** `/magicnpcs school <set|info|reroll|clear> <targets> [school]`
   (permission level `schools.command.permissionLevel`, default 2):
   - `set @e[type=recruits:recruit,distance=..10] irons_spellbooks:fire`
   - `info @e[...]` — show each target's assignment
   - `reroll @e[...]` — assign a fresh random allowed school
   - `clear @e[...]` — remove the assignment and casting goal
3. **School Tome item** (`magicnpcs:school_tome`, in the Tools & Utilities creative
   tab): right-click an NPC to cycle to the next allowed school; sneak-right-click to
   clear. Toggle with `schools.item`.

## Spell pool filters (`[schools]`)

A school's pool is `SpellRegistry.getSpellsForSchool(school)` filtered by:
- enabled spells only, **INSTANT** cast type only (the NPC goal fires one-shot spells);
- rarity ≤ `maxRarity`; spell level ≤ `maxSpellLevel`;
- the global `spellBlacklist` / `spellWhitelist`.
Up to `spellsPerSchool` are sampled (weighted by `weightingMode`). Spells in
`supportSpellIds` (or matching heal/cure/blessing/regen/haste/shield/ward) become
SUPPORT (self-cast when hurt); the rest are ATTACK. Mana comes from `baseMaxMana` /
`baseManaRegen` (then scaled by recruit rank, `manaMultiplier`, and difficulty).

## Disabling

- `schools.enableSchools = false` turns the whole system off.
- Per-side: `schools.recruits.enabled`, `schools.villagers.enabled`.
- Set `casterChance` to 0 to keep assignment available (command/item) but stop
  automatic casters.
- `schools.command.enabled` / `schools.item` disable the manual tools.

## Notes for pack authors

- Villager casting is intentionally inert without external combat AI — pair with Guard
  Villagers / MCA guards / raids for it to matter.
- Profession ids are vanilla (`minecraft:cleric`, …); More Villagers / VillagersPlus
  professions use their own ids — add them to `professionSchools`.
- An assigned school whose `SchoolType` isn't present in the installed Iron's build
  simply produces no caster (fails safe).
