# ADR 0011 — Native cast-time override and casting-driver observability

**Status:** Accepted (0.9.0)
**Date:** 2026-09-03

## Context

Three separate problems that motivated this release:

**(1) No way to shorten Iron's native spell cast time.** Before 0.9.0 `windup` was the only per-spell
timing field; it removes only Magic NPCs' own pre-cast delay and cannot touch Iron's own LONG/CONTINUOUS
cast duration. A pack author with a 15-tick spell they want to cast in 8 ticks had no datapack-level
solution — custom code was the only path.

**(2) No observability into casting-goal health.** The goal is installed into a mob's `GoalSelector`,
but if a mod bypasses the vanilla goal system, the goal never runs and the mob silently never casts.
Before 0.9.0 there was no way to surface this: `/magicnpcs why` reported the goal as "not found" but
did not explain *why* it was not found — the mob's AI simply does not use the vanilla path. The
consequence: a pack author seeing a non-casting Luminous mob had no diagnostic to know whether the
problem was their loadout or the mob's AI architecture.

**(3) Edge case in the session tick driver.** When a goal starts (`start()` is called), it ticks the
session on that same tick. Then `GoalSelector.tickRunningGoals(true)` also ticks it the same tick,
advancing the session twice. This is rare (only happens on the start tick) but real, and it causes a
one-tick skew in cast duration counting.

## Decision

### Native cast-time override

Two new optional per-spell fields give pack authors control:

- **`cast_time`** (ticks): absolute override, highest precedence. A LONG spell with `"cast_time": 6`
  casts in exactly 6 ticks, ignoring Iron's default. Applies to LONG and CONTINUOUS spells only;
  INSTANT/NONE spells ignore it. Parser rejects non-integer or negative values (error
  `CAST_TIME_NEGATIVE`), failing the whole loadout record.
- **`cast_time_multiplier`** (number): scales Iron's effective cast time. A 15-tick spell with
  `"cast_time_multiplier": 0.5` resolves to 8 ticks (rounded). Applies to LONG and CONTINUOUS spells
  only; INSTANT/NONE spells ignore it. Parser rejects non-finite or negative values (error
  `CAST_TIME_MULTIPLIER_INVALID`), failing the whole loadout record.

**Precedence:** `cast_time` (absolute) > `cast_time_multiplier` (scaling) > neither (Iron's timing).
When both are set, info-level log `CAST_TIME_ABSOLUTE_WINS` and both values are kept for diagnostics.

**Multiplier applies to Iron's effective time, not the base.** Iron's `AbstractSpell.getEffectiveCastTime`
already incorporates caster-side modifiers (speed effects, etc.). The multiplier scales the result, so
a caster with a speed boost sees the boost × the multiplier, not the base × the multiplier.

**Backward compatibility:** With both fields `null`, the input cast time is returned verbatim, with
no normalisation. A loadout written before these fields existed casts on exactly Iron's timing — zero
change in behaviour.

**LONG vs CONTINUOUS:** For LONG spells the cast duration is time-to-effect release; for CONTINUOUS
spells, it is also the channel length. Neither field changes the lifetime of an effect spawned *after*
casting completes (e.g., a black hole's 20-tick existence from `gravity_fissure` is unaffected by
shortening its 15-tick cast).

**Floor of 1 tick:** INSTANT/NONE spells always resolve to 0 and ignore both fields. LONG/CONTINUOUS
spells with an override always resolve to at least 1 tick, even if `cast_time: 0` or a multiplier
rounds to 0. A spell must occupy at least one tick to charge at all.

**Typo aliases:** `cast_duration` → `cast_time`, `cast_duration_multiplier` → `cast_time_multiplier`,
`casttime` → `cast_time`, `casttime_multiplier` → `cast_time_multiplier`. These are suggestion aliases
in the schema, never accepted as keys.

### Session double-tick guard

`MobCastSession.tick()` now guards against a second call in the same game tick with an `Integer.MIN_VALUE`
baseline for `lastTickedAt`. A duplicate call on the same tick is silently ignored.

**One-tick consequence:** A `windup: 0` LONG/CONTINUOUS cast previously advanced twice on its start
tick (goal's `start()` ticks it, then `GoalSelector.tickRunningGoals(true)` ticks it again) and now
advances once. Such casts complete one tick later than in 0.8.0. INSTANT casts are unaffected (they
complete in a single `tick()` call).

### Casting-driver observability

The goal heartbeat is now surfaced in `ManagedCasterState`:

- **`heartbeat(now)`** — stamps the tick the goal was last evaluated (called by the goal's `canUse()`
  and `tick()` at the top of each).
- **`goalHeartbeatAge(now)`** — returns `now - lastHeartbeat`, the number of ticks since the last stamp.

`/magicnpcs why` output now includes:
- `casting driver: goal` — states that the injection uses the goal path.
- `goal heartbeat: N tick(s) ago` (or `never` if never stamped) — the age of the last evaluation.

When the age exceeds `GOAL_STALE_TICKS` (40 ticks), a blocker appears: `injected casting goal has not
been evaluated for N ticks — this mob's AI may not run the vanilla goal selector [GOAL_NOT_EVALUATED]`.

**Why 40 ticks as the threshold:** Vanilla `GoalSelector` evaluates each goal's `canUse()` on
alternating ticks (called from `Mob.serverAiStep` once per tick, but only for *running* goals on
alternating ones). So 40 ticks ≈ 20 missed evaluations — enough to be confident the goal is not
running.

**Signal for pack authors:** A goal heartbeat of 40+ ticks means the goal is installed but not running.
This is the diagnostic for a modded mob whose AI bypasses the vanilla goal selector. Magic NPCs currently
instruments this detection for known mods; if a mob never casts and `/magicnpcs why` shows
`[GOAL_NOT_EVALUATED]`, the problem is the mob's AI architecture, not the loadout.

## Rejected alternatives

### Cast-time override: mutations vs per-cast parameters

**Rejected: mutate `AbstractSpell.getCastTime()` per cast.** Would require reflection or mixin, and
the shared spell object lives across multiple mobs with potentially different overrides. Violates the
invariant that a shared object's state is immutable.

**Chosen: per-cast parameter.** The session boundary (`MobCastSession` / `MagicData.initiateCast`) is
the natural seam. Each cast carries its own duration; the shared spell object is never modified.

### Multiplier direction

**Rejected: `0.5` = twice as slow.** Counterintuitive. Cooldown multiplier uses the opposite direction
(bigger = slower). For consistency with that, cast-time multiplier needed a name that did not assume
direction.

**Chosen: `0.5` = twice as fast.** Intuitive scaling: 0.5× time = faster, 2.0× time = slower. Matches
the direction of "time multiplier" in everyday language.

### Script-cast override

**Rejected: apply overrides to scripts/detached casts.** Detached casts are driven outside the goal
path; they call `MagicData.initiateCast` with a fixed `AbstractSpell` at its native time. There is no
per-mob context to apply an override to.

**Chosen: goal path only.** AI-selected casts get the override (called from
`NpcSpellAttackGoal.beginCast`); scripted casts keep Iron's timing. This is documented as a design
boundary: script paths do not read loadout timing fields. The two paths are intentionally separate.

### Parser strategy for malformed values

**Rejected: clamp negatives to 0, silently drop non-numbers.** Cooldown and windup do this (for backward
compatibility with shipped packs). A `cast_time: -1` silently becoming `cast_time: 0` looks like an instant
cast — false success that breaks the spell at runtime.

**Chosen: reject as file-level errors.** Malformed `cast_time` or `cast_time_multiplier` fails the whole
loadout record (status REJECTED, `loadout() == null`, `LoadoutManager.parse` throws — same as `BAD_ROLE`).
The asymmetry (cooldown/windup clamp, cast_time rejects) is deliberate for 0.9.0, recorded as pre-1.0
cleanup.

### Casting-driver architecture (deferred)

The long-term solution for Luminous (and other Brain-driven mobs) is a shared controller with two
drivers: a goal (runs via `GoalSelector`) and a fallback (runs via `serverTick` when the goal is stale).

**Rejected in 0.9.0: deploy the fallback driver now.** We have a heartbeat signal but no runtime proof
that the fallback is needed. Deploying without real data risks introducing a second source of truth and
duplicated state logic.

**Chosen: instrument and defer.** The heartbeat and stale detection are in place. The fallback is
designed (single shared session, two drivers, positive detection via heartbeat age, no entity-ID special
cases, no priority-0 hacks) but deliberately not implemented until a runtime run on Luminous shows
`[GOAL_NOT_EVALUATED]` at scale, proving the need. When that happens, the ADR and the code are ready.

## Consequences

- Pack authors can now shorten spells without custom code: `"cast_time": 6` or `"cast_time_multiplier": 0.5`.
- Backward compatibility is total: pre-0.9.0 loadouts cast on exactly Iron's timing with both fields
  absent.
- A `windup: 0` LONG/CONTINUOUS cast is one tick longer in 0.9.0 than 0.8.0 due to the session guard.
  INSTANT casts and casts with non-zero windup are unaffected.
- `/magicnpcs why` now names the casting driver and its heartbeat age, surfacing the `[GOAL_NOT_EVALUATED]`
  blocker. This is the answer to "why is this Luminous mob not casting?"
- Typo suggestions for cast-time fields exist but are not accepted as keys; only the canonical names
  parse.
- The `-PluminousRuntime` dev profile loads LUMINOUS: BEASTS for integration testing. The fallback driver
  is instrumented but not deployed until runtime proof of the need.

## See also

- [ADR 0002](0002-casting-goal-injection.md) — goal injection flags/priority/coexistence
- [ADR 0005](0005-out-of-combat-support-and-tick-cadence.md) — out-of-combat SUPPORT and tick cadence
- [ADR 0007](0007-precast-lifecycle.md) — pre-cast lifecycle
- [ADR 0008](0008-cast-session-and-reconciliation.md) (if it exists) — cast session and reconciliation
