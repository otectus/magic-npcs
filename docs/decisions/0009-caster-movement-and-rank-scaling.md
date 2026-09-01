# ADR 0009 — Casters reposition through the adapter seam, not through Iron's goal

**Status:** Accepted (0.6.3)
**Date:** 2026-08-28
**Verified against:** Iron's `1.20.1-3.16.3` and Villager Recruits `1.20.1-1.15.2` bytecode —
`WizardAttackGoal.<init>` / `.handleAttackLogic` / `.doSpellAction`,
`AbstractRecruitEntity.registerGoals` / `.getMeleeStartRange` / `.getShouldRanged`,
`RecruitMeleeAttackGoal.canUse` / `.stop`, `RecruitRangedBowAttackGoal`.

Supersedes the "Phase-4 hybrid: implement `IMagicEntity` via Mixin onto `AbstractRecruitEntity`"
proposal in [ADR 0001](0001-irons-mob-casting.md). Builds on
[ADR 0002](0002-casting-goal-injection.md) (no control flags) and
[ADR 0008](0008-cast-session-and-reconciliation.md) (cast sessions, managed state).

## Context

Recruits have cast spells since 0.4.0. What they could not do was behave like casters.

`AbstractRecruitEntity.registerGoals()` registers a `RecruitMeleeAttackGoal` on **every** recruit;
`BowmanEntity` and `CrossBowmanEntity` add a ranged goal on top. `getMeleeStartRange()` returns 32.0
on the base recruit against 5.0 on a Bowman. So a plain `recruits:recruit` handed a magic-missile
loadout has a melee goal, no ranged goal, and a melee-start range that means "always melee": it walks
into sword range and casts on the way in.

Only `recruits:recruit` is that mob. `CaptainEntity` extends `AbstractLeaderEntity` →
`AbstractChunkLoaderEntity` → **`BowmanEntity`**, so a captain already owns a ranged goal and a
melee-start range of 5.0, exactly like a bowman. The three ranged types position themselves better
than this goal can — they know their own `stopRange`, formations and `fleeEntity` — which is one more
reason the movement goal stands down whenever their attack AI is running.

The obvious lever does not exist. `RecruitMeleeAttackGoal.canUse()` gates on `getShouldMount()`,
`getShouldMovePos()`, `getState()` and `needsToGetFood()` — not on ranged-ness. `getShouldRanged()`
appears only in that goal's `stop()`, where it switches a Bowman's mainhand back to its bow. Setting
`setShouldRanged(true)` would not stop anything charging.

0.6.1 had shipped an answer to a related question — `recruits.useIronsAI`, which replaced our casting
goal with Iron's `WizardAttackGoal` — and 0.6.2 removed it because it discarded every per-entry
setting in the loadout. The question of whether to bring it back, properly this time, had to be
settled before designing anything else.

## Decision

**Do not use Iron's `WizardAttackGoal`, and add no Mixin.** Three findings, each independently
disqualifying:

1. `WizardAttackGoal`'s constructor does `this.mob = (PathfinderMob) spellCastingMob` — a hard
   `checkcast`. The `IMagicEntity` must **be** the mob, so a wrapper object is impossible and a Mixin
   onto a third-party All-Rights-Reserved jar is mandatory.
2. It declares `EnumSet.of(MOVE, LOOK, TARGET)` and consults none of `getShouldHoldPos()`,
   `getShouldFollow()` or the formation flags. It would override the player's orders — and claiming
   MOVE and LOOK is exactly the goal starvation ADR 0002 exists to prevent.
3. Its API is four spell lists plus `setSpellQuality(float, float)`. There is no way to express
   `min_range`, `max_range`, `safety_radius`, `cast_chance`, `cooldown`, `weight` or `condition`
   through it. **Delegating selection to it loses the loadout contract by construction** — which was
   the original defect, not an implementation slip that a better Mixin would fix.

**Instead, model it on Recruits' own ranged goal.** `RecruitRangedBowAttackGoal` solves the same
problem for bows: it repositions while branching over `handleFollow` / `handleHoldPos` /
`handleWander`. It declares `LOOK` and drives the navigation directly — notably **not** `MOVE`. Flags
govern goal *scheduling*; they do not gate the navigation API. That is the mechanism this goal uses.

The flags the Recruits goals actually declare settle the priority question outright:

| Goal | Priority | Flags |
|---|---:|---|
| `RecruitMeleeAttackGoal` | 2 | `MOVE, LOOK` |
| `RecruitHoldPosGoal` | 3 | `MOVE` |
| `RecruitRangedBowAttackGoal` | 4 | `LOOK` |
| `RecruitFollowOwnerGoal` | 2 | none |

At the default `castingGoalPriority = 2`, claiming `MOVE` would be starved by `RecruitMeleeAttackGoal`
(`canBeReplacedBy` needs a strictly lower priority number, and `2 < 2` is false) *and* would preempt
`RecruitHoldPosGoal` at priority 3, kiting a recruit off a hold-position order. Both failure modes at
once.

Concretely:

- `NpcAdapter` gains `movementPolicy(Mob)` returning `FREE` / `ANCHORED(anchor, leash)` / `PINNED`.
  Adapters compose it by **most restrictive wins**, the same direction as every other rule in
  `NpcAdapters`: registering an adapter may only ever make an NPC less free.
- `CasterMovementGoal` (vanilla-only, in `core/caster/`) holds the band between the widest
  `min_range` and the narrowest `max_range` among the ATTACK entries **that survived the casting
  goal's own filtering** — sizing it from the raw loadout would have the mob hold a range for a spell
  the blacklist or the manifest already removed. It declares no control flags, and stands down when
  anything else holds the vanilla `MOVE` lock, when the mob is ridden or leashed, or when its
  adapter reports `PINNED`. Reading the lock rather than claiming it is what makes a hold-position
  order from a mod we have never heard of win structurally, with no class name to match on.
- **It runs only where the mob's own attack AI is suppressed.** Stopping the charge is not its job:
  `"native_attack": "suppress"` already does that, reversibly, since 0.6.2. This supplies the missing
  half — where to stand instead.
- Rank raises spell level, with the loadout's `level` as a floor and a configured cap.

## Consequences

- **No behaviour change for anyone who has not opted in.** The shipped recruit loadouts keep the
  default `coexist`, so they remain battlemages. The movement goal engages only for loadouts already
  declared pure casters.
- The feature is **universal**, not Recruits-specific: a skeleton, a Guard Villager or an MCA
  villager with a `suppress` loadout gets the same repositioning. A Mixin-based answer could only
  ever have helped recruits.
- `general.suppressibleAttackGoals` now lists the Recruits attack goals by default, because
  `"native_attack": "suppress"` was silently a no-op on a recruit without them.
- **`IMagicEntity` is not implemented, and recruits are not Iron's casting mobs.** Scanning the
  Iron's jar, the only consumers of that interface are Iron's own mob goals and one sync packet, so
  the interop value of implementing it without using those goals is close to nil. The cost — a Mixin
  against an ARR jar that cannot be exercised in CI — is not.
- Cast animations remain absent. Recruits are not GeckoLib entities and Iron's cast animations are
  driven from `AbstractSpellCastingMob`'s animation controllers; there is no route to them that does
  not involve becoming one.

## Alternatives considered

- **Subclass `WizardAttackGoal` and override `doSpellAction()`/`getNextSpellType()`** to keep Iron's
  movement while restoring our own selection. Genuinely tempting — both are `protected`. Rejected:
  it still requires the Mixin (the constructor cast), still claims MOVE/LOOK/TARGET and so still
  overrides hold-position, and subclassing a third-party class across its protected surface is
  fragile in exactly the way a soft dependency should not be.
- **Set `setShouldRanged(true)` on casting recruits.** Rejected on the evidence: that flag does not
  gate `RecruitMeleeAttackGoal.canUse()`, so it would not have worked, and it mutates another mod's
  synched state for no benefit.
- **Claiming the `MOVE` flag** so the selector arbitrates for us. Rejected: see the flag table above —
  at the default priority it loses to the melee goal and beats the hold-position goal, which is the
  worst of both.
- **Make the movement goal claim `MOVE`.** Rejected: ADR 0002, and Recruits' own ranged goal does not
  either. The `nativeAttackSuppressed` gate removes the competitor that would have justified it.
- **Re-evaluate the standoff band every tick.** Rejected: re-pathing twenty times a second fights the
  navigator and visibly jitters. It re-paths on a ten-tick cadence and holds still while channelling.
