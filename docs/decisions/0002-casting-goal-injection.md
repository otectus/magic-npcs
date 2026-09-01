# ADR 0002 — Casting-goal injection: flags, priority, and coexistence with native attack AI

**Status:** Accepted (0.6.0)
**Date:** 2026-08-14
**Verified against:** Minecraft 1.20.1 / Forge 47.4.16 official-mapped sources
(`GoalSelector`, `WrappedGoal`, `Mob`, `Witch`, `AbstractSkeleton`, `RangedAttackGoal`).
**Supersedes:** the hard-coded `addGoal(2, …)` + `setFlags(EnumSet.of(Flag.LOOK))` of 0.1.0–0.5.0.

## Context

Two reports that look opposite have one cause (see `docs/findings/0.6.0-investigation.md`, W2/W3):

- A **witch** with a loadout never casts.
- A **skeleton** with a loadout casts *instead of* using its bow, far too often.

`GoalSelector.tick()` starts a non-running goal only when every `Goal.Flag` it declares is free or held
by a goal that `canBeReplacedBy(candidate)`, and `WrappedGoal.canBeReplacedBy` is
`isInterruptable() && other.getPriority() < this.getPriority()` — **strictly** lower priority number.
We injected at priority 2 declaring `LOOK`:

| Mob | Native goal | Its priority | Its flags | Outcome |
|---|---|---|---|---|
| `minecraft:witch` | `RangedAttackGoal` | 2 | `MOVE`, `LOOK` | `2 < 2` false ⇒ **we never start** |
| `AbstractSkeleton` | bow goal | 4 | `MOVE`, `LOOK` | `2 < 4` true ⇒ **we preempt the bow every time** |

Any modded mob registering a ranged/attack goal at priority ≤ 2 with `LOOK` is in the witch's bucket.

The `LOOK` flag was never actually needed: `NpcSpellAttackGoal.snapFacing()` writes yaw/pitch (head and
body) directly at cast time precisely *because* `LookControl` applies its rotation after the goal tick
and is useless for the cast frame.

## Decision

1. **The casting goal declares no `Goal.Flag` by default.** A goal with an empty flag set iterates zero
   flags in `goalCanBeReplacedForAllFlags`, so it can start and run *alongside* the mob's own attack
   goal instead of fighting it. This single change fixes both the witch (can now start) and the skeleton
   (no longer stops the bow goal), and it needs no per-mob knowledge, so it also covers modded mobs we
   cannot compile against.
   Restore the old behaviour with `general.castingGoalUsesLookFlag = true`.

2. **Injection priority is configurable** — `general.castingGoalPriority` (default **2**, unchanged) and
   an optional per-loadout `"goal_priority"`. Priority is deliberately *not* the primary lever: with no
   flags declared it only matters for the `suppress`/`yield` policies and for mods whose goals declare
   flags we do want to preempt. `GoalSelector.tick()` iterates `availableGoals` in insertion order, not
   priority order, so priority has no effect on selection *order* — a subtlety worth stating because it
   is the natural (wrong) assumption.

3. **An explicit coexistence policy per loadout**, root-level `"native_attack"`:
   - `"coexist"` (**default**) — run alongside the mob's own attack goals.
   - `"suppress"` — remove the mob's native ranged/melee attack goals at injection, for a "pure caster"
     conversion. Implemented with `goalSelector.removeAllGoals(predicate)` over a conservative
     allow-list of vanilla attack-goal classes plus a config-driven class-name list
     (`general.suppressibleAttackGoals`), and every removal is logged by class name.
   - `"yield"` — cast only while no other attack goal is currently running (checked per decision
     against the same class list).

4. **The trade-off is accepted and documented:** with no flags the mob may cast while strafing or
   pathing under its native goal. In practice that reads *better* — a witch that throws potions and casts,
   a skeleton that shoots and casts — and it is the behaviour packs asked for.

## Rejected alternatives

- **Inject at priority 1 (or 0) keeping `LOOK`.** Fixes the witch by outranking it, but makes the
  skeleton *worse*: it would preempt the bow goal even harder, and it would preempt `FloatGoal`
  (priority 1 on many mobs), drowning casters. Ranking above every native goal is exactly the failure
  mode W3 reported.
- **Per-mob priority table shipped in the jar.** Cannot cover mods we can't compile against, needs a
  release to fix each new report, and silently rots. Rejected in favour of a mechanism that works
  without knowing the mob, plus `/magicnpcs why` to diagnose the exceptions.
- **Always remove native attack goals (`suppress` as the default).** Turns every loadout into a
  conversion. A pack that adds one spell to guards does not want the guards to stop swinging swords, and
  it would break Recruits' own combat AI. Kept as an opt-in policy.
- **Mixin into `GoalSelector` to special-case our goal.** Global, fragile, hostile to other mods, and
  unnecessary once the flag set is empty.
- **`RangedAttackMob#performRangedAttack` interception** instead of a goal. Only works for mobs that
  implement `RangedAttackMob` *and* actually route through it; would not have helped the melee/other
  cases, and duplicates the whole selection pipeline. Kept on the table as a future fallback for mobs
  that never tick `goalSelector` at all.

## Consequences

- Mobs with native ranged AI (witch, and modded equivalents that use `goalSelector`) now cast.
- Skeletons with a loadout both shoot **and** cast; the cast rate is governed by the cooldown/decision
  cadence, which 0.6.0 also made tick-accurate (ADR 0005).
- Mobs that do not use `goalSelector` at all remain unsupported — now *visibly* so, via `/magicnpcs why`.
- `general.castingGoalUsesLookFlag = true` reproduces pre-0.6.0 scheduling exactly, for anyone who
  depended on it.
