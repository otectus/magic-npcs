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
     the `professionSchools` map (or `unmappedGetRandom` is true). A magic villager only casts
     **offensively** when something gives it a target. A vanilla villager has an empty `GoalSelector`
     and nothing ever calls `setTarget` on it — **not even a raid**, where villagers only hide — so
     that means either a guard/NPC mod granting combat AI, or the opt-in
     `schools.villagers.selfDefense`, which lets a school villager retaliate when attacked. Without
     one of those it can still self-cast a support spell when wounded. Plain villagers stay passive.
     - **A school with no support spell is not given to a villager that cannot fight back.** Under the
       stock spell lists `fire`, `ice`, `lightning`, `ender` and `nature` contain nothing classified as
       support, so such a villager would hold a casting goal it could never use.
     - **A villager that has not taken a job yet is left alone.** `EntityJoinLevelEvent` fires while a
       newly spawned or bred villager's profession is still `minecraft:none`; before 0.6.0 the failed
       roll was recorded as a sticky "not a caster", which permanently disqualified essentially every
       naturally spawned villager. Professionless villagers are now skipped and re-checked once they
       take a job.
2. **Command.** Each form is a complete command in its own right — only `set` takes a school
   (permission level `schools.control.commandPermissionLevel`, default 2):

   ```mcfunction
   /magicnpcs school info <targets>
   /magicnpcs school set <targets> <school>
   /magicnpcs school reroll <targets>
   /magicnpcs school clear <targets>
   /magicnpcs school auto <targets>
   /magicnpcs school pool [school]
   ```

   `/magicnpcs school` on its own prints this list rather than a syntax error, and `pool` takes no
   targets.
   - `set @e[type=recruits:recruit,distance=..10] irons_spellbooks:fire` — reports a reason per NPC
     when a school can't be assigned, instead of a bare count
   - `info @e[...]` — show each target's assignment
   - `reroll @e[...]` — try every allowed school (shuffled, excluding the NPC's current one) and take
     the first that yields a castable pool. Only reports failure when **no** allowed school works, and
     then names every school it tried and why each failed.
   - `clear @e[...]` — stop casting: marks the NPC a **sticky non-caster** and removes the
     casting goal, so it won't auto re-roll on the next chunk reload. Re-enable later with
     `set`/`reroll` or the Tome.
   - `auto @e[...]` — undo a manual assignment entirely and hand the NPC back to automatic
     assignment, so its datapack loadout or spawn roll applies again. Before 0.6.2 there was no way
     back: `set` and `clear` both marked the NPC as player-decided and nothing removed that mark.
   - `pool [school]` — what a school's generated pool contains right now, and the exact filter that
     dropped each spell that didn't make it. Run this first when a reroll fails.
3. **School Tome item** (`magicnpcs:school_tome`, in the Tools & Utilities creative
   tab): **right-click an NPC to inspect it** — it reports the assigned school and which source is
   actually driving the mob — and **sneak-right-click to cycle** to the next allowed school, skipping
   any school whose pool is empty. Cycling past the last school clears the assignment (the same
   sticky non-caster mark as the command), so "stop casting" stays reachable from the item.
   Toggle with `schools.control.itemEnabled`.

   A school set this way is a **manual override**: it outranks any explicit loadout and survives
   chunk reloads, which is what makes the Tome usable on recruits at all.

   The override has three states, and `/magicnpcs school info` names the one an NPC is in:

   | State | Meaning |
   |---|---|
   | `AUTO` | nobody has touched it; the spawn roll and datapack loadouts decide |
   | `MANUAL_SCHOOL` | a player assigned a school; it wins over any loadout, **or the NPC does not cast** |
   | `MANUAL_DISABLED` | a player cleared it; it does not cast, whatever any datapack says |

   "…or the NPC does not cast" is the 0.6.2 correction. If a hand-assigned school yields nothing today
   — schools disabled, the school gone from Iron's, the pool emptied by a config cap — 0.6.1 quietly
   installed the datapack loadout instead, which contradicted the command's own promise that a manual
   assignment overrides any loadout. A manual school now installs that school or nothing, and
   `/magicnpcs school info` says which, with a pointer to `/magicnpcs school pool <school>`.

## Spell pool filters (`[schools]`)

A school's pool is `SpellRegistry.getSpellsForSchool(school)` filtered, in this order, by:

| Filter | Config key | Default |
|---|---|---|
| enabled in Iron's own spell config | *(Iron's)* | — |
| cast type | `allowedCastTypes` | `["INSTANT", "LONG"]` |
| spell blacklist / whitelist | `spellBlacklist` / `spellWhitelist` | empty |
| castable by a mob at all | *(built in)* | — |
| rarity at the capped level | `maxRarity` | `RARE` |
| spell level cap | `maxSpellLevel` | 3 |
| support spells included | `includeSupportSpells` | true |

Up to `spellsPerSchool` are sampled (weighted by `weightingMode`). Spells in
`supportSpellIds` (or whose name matches heal/cure/blessing/regen/haste/shield/ward/
fortify) become SUPPORT (self-cast when hurt); the rest are ATTACK. Mana comes from
`baseMaxMana` / `baseManaRegen` (then scaled by recruit rank, `manaMultiplier`, and difficulty).

**If a school yields nothing, it can never be assigned** — that is what "re-rolled schools for 0
NPC(s)" used to mean, with no explanation. Two things make it visible now: the server log prints one
line per school on every reload (`fire: 7 of 19 spells castable`, and a warning for any empty one),
and `/magicnpcs school pool <school>` lists each dropped spell with the filter that dropped it.

> **0.6.0 change:** `LONG` cast-type spells are now included. They were excluded even though 0.5.0
> taught the casting goal to channel them correctly, which on its own emptied several schools.
> `CONTINUOUS` stays out — nothing drives a channel loop for a mob.

## Disabling

- `schools.enableSchools = false` turns the whole system off.
- Per-side: `schools.recruits.enabled`, `schools.villagers.enabled`.
- Set `casterChance` to 0 to keep assignment available (command/item) but stop
  automatic casters.
- `schools.control.commandEnabled` / `schools.control.itemEnabled` disable the manual tools.

## Notes for pack authors

- Offensive villager casting is inert without external combat AI. Pair with Guard Villagers / MCA
  guards, or set `schools.villagers.selfDefense = true`. Raids do not help: vanilla raids never give
  a villager a target.
- Profession ids are vanilla (`minecraft:cleric`, …); More Villagers / VillagersPlus
  professions use their own ids — add them to `professionSchools`.
- An assigned school whose `SchoolType` isn't present in the installed Iron's build
  simply produces no caster (fails safe). Since 0.6.0 the NPC is also marked a non-caster in that
  case, so it stops being treated as one by the mana tick; `set`/`reroll` clears the mark.
- If a reroll reports that nothing could be assigned, the fastest fixes are usually raising
  `maxRarity` to `EPIC`, raising `maxSpellLevel`, or checking `allowedCastTypes` — run
  `/magicnpcs school pool <school>` first, it names the responsible filter per spell.
- **School-aware focus:** with `equipment.requireSpellFocus` on, set
  `schools.schoolAwareFocus = true` to let a school caster satisfy the focus requirement by
  holding a focus for its **own** school (Iron's per-school `irons_spellbooks:<school>_focus`
  tag), in addition to the generic `magicnpcs:spell_focuses` tag. Spawn-with-gear then
  prefers a school-appropriate focus too.
