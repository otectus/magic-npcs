# ADR 0006 — A manually assigned school outranks a loadout, and persists

**Status:** Accepted (0.6.1)
**Date:** 2026-08-21
**Verified against:** MC 1.20.1 `GoalSelector#removeAllGoals` / `#removeGoal`; Forge 47.4.16
`Entity` patch (`ForgeData` is written whenever `persistentData != null`, empty or not).

## Context

A player set a school on their Villager Recruit with the School Tome, and it reverted to the bundled
Magic Missile loadout. `/magicnpcs school clear` behaved the same way: the recruit stopped casting,
then started again after a chunk reload.

The cause is an ordering one. Casting goals are not persisted, so every chunk load re-runs
`tryInject`, which resolved in this order:

1. explicit loadout (`LoadoutManager.assign`)
2. magic school, only if no loadout matched

`recruit`, `bowman`, `crossbowman` and `captain` all ship enabled bundled loadouts, so step 1 always
won and the school stored in NBT was never consulted. `applySchool` had written it and injected the
right goal, so the assignment *looked* like it worked until the chunk unloaded.

This also made the whole `schools.recruits.*` config block — `casterChance`, `assignmentMode`,
`typeSchools`, `minRankToCast` — dead configuration for exactly the four recruit types that ship, and
left `/magicnpcs school info` reporting a school the mob demonstrably was not using.

## Decision

**Distinguish a player's choice from an automatic roll, and let the player's choice win.**

`SchoolData` gains a `manual` flag, written by `applySchool` and `clearSchool` (the Tome and the
command) and never by the spawn roll. `tryInject` resolves in three steps:

1. **manual override** — a hand-set school, or a hand-set "not a caster" mark
2. explicit loadout
3. automatic school

Automatic precedence is therefore unchanged: a datapack loadout still beats an automatically rolled
school, which is what pack authors rely on for designed encounters. Only a deliberate, per-entity
human decision jumps the queue.

A manual school that yields no pool today (the caps were tightened, or the school left this Iron's
build) falls through to the loadout rather than leaving the mob mute.

### Two supporting details

**Goal removal must call `stop()`.** `GoalSelector#removeAllGoals` is a plain `removeIf` — it never
stops a running goal. `GoalSelector` only releases a locked `Goal.Flag` when its holder reports
`!isRunning()`, and an unregistered goal is never ticked again, so re-schooling a mob mid-cast leaked
its MOVE/LOOK/TARGET locks permanently (Iron's `WizardAttackGoal` claims all three) and skipped the
`Telegraphs.clearGlow` in `stop()`. Both call sites now use `removeGoal`, matching what
`AttackGoals.suppressNativeAttackGoals` already documented and did.

**The manual flag must be readable without creating persistent data.** `Entity#getPersistentData()`
allocates the tag on read, and Forge's `Entity` patch writes `ForgeData` whenever it is non-null —
*including when empty*. Asking every joining mob "are you manually assigned?" would stamp an empty
compound onto every cow and bat in the world (the other half of backlog B1, which 0.6.0 only half
fixed). So the check is gated on `mayHaveSchoolData`: a `Villager`, an adapter-declared
school-assignable NPC, or a mob carrying the `magicnpcs.school` **vanilla scoreboard tag**, which
manual assignment stamps. Scoreboard tags are plain saved NBT and cost nothing to read.

## Alternatives rejected

- **Make automatic school assignment beat the bundled jar loadouts too.** This would give recruits
  varied schools out of the box, which is closer to what `docs/schools.md` describes — but it silently
  changes default behaviour for every existing pack, and the bundled loadouts are the documented
  default. Rejected in favour of documenting the precedence and making the manual route work.
- **Delete the bundled recruit loadouts.** Same objection, plus it removes the mod's only
  out-of-the-box demonstration that recruits cast at all.
- **Store the override outside entity NBT** (a level-attached map keyed by UUID). More moving parts,
  and it would not survive an entity being copied between dimensions the way persistent data does.
- **Have `clearSchool` write an `"enabled": false` loadout.** Loadouts are per entity *type*; the
  request is per individual.

## Consequences

- The Tome and `/magicnpcs school` now work on every NPC type, including the four bundled recruits.
- `"enabled": false` + `"replace": true` still suppresses the school path for a type (that is the
  documented kill switch), but a manual override remains available as the per-entity escape hatch.
- One new NBT key and one scoreboard tag per manually assigned entity. Both are absent on
  automatically assigned entities, so existing worlds are byte-identical until someone uses the Tome.
