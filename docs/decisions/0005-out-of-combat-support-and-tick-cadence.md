# ADR 0005 — Out-of-combat SUPPORT casting, and a tick-accurate decision cadence

**Status:** Accepted (0.6.0)
**Date:** 2026-08-14
**Verified against:** MC 1.20.1 `Mob.serverAiStep`; Iron's 3.15.2 `HealSpell.onCast` (see
`docs/findings/0.6.0-investigation.md`).

## Context

Two separate problems that must be decided together, because the first one adds work to the very tick
path the second one is trying to make cheaper and more accurate.

**(1) SUPPORT never fired outside combat.** `canUse()` hard-required `mob.getTarget() != null`, so a
support NPC could only heal after something attacked it — the reported symptom. Every layer below that
line was already written for a null target.

**(2) Cooldowns ran at half the configured rate.** `Mob.serverAiStep` calls `goalSelector.tick()` — the
only path that reaches `canUse()` — on alternating ticks. `tickCooldowns()` and `decisionTimer--` lived
inside `canUse()`, so `minCooldownTicks = 20` meant ≈40 game ticks. The wind-up countdown, which lives in
`tick()` and is protected by `requiresUpdateEveryTick()`, was already in real ticks — so the two halves of
the same feature disagreed about what a tick was.

## Decision

### Out-of-combat SUPPORT

- `canUse()` proceeds with `target == null`; in that case `choose()` considers **only** `Role.SUPPORT`
  entries. ATTACK entries are never selectable without a target — they would fire into empty air, and
  `snapFacing` has nothing to aim at. This is asserted by a test rather than left to reading.
- **A separate, much slower cadence.** `balance.decisionIntervalTicks` (10) is a *combat* cadence; an idle
  NPC re-evaluating heals twice a second is pure waste. Out of combat the goal uses
  `balance.supportOutOfCombatIntervalTicks` (default **100**). Master switch:
  `balance.supportOutOfCombat` (default **true**); set it false for byte-for-byte pre-0.6.0 behaviour.
- **Anti-loop floor.** The existing hurt gate (`health < maxHealth * supportHealthThreshold`) is the
  natural guard, but a SUPPORT entry carrying an explicit `condition` *replaces* that gate — so a pack
  could write a condition with no health term and have it fire forever while idle. Out of combat, a
  SUPPORT entry whose condition contains no health-related term (`self_hp_below`, or
  `when_recently_hurt`) must additionally satisfy the hurt gate. In combat the 0.4.0 semantics are
  untouched.
- **No combat tell for an idle self-heal.** `Telegraphs.play` is suppressed on the out-of-combat path;
  a mob quietly topping itself up should not broadcast a wind-up burst every time.
- Every existing gate still applies: `canCastInCurrentState()`, mana, per-spell cooldown, `castChance`.

**Self only.** Ally-targeted support ("heal a wounded neighbour") is a genuine feature and a different
data model — it needs a `"target": "self" | "ally"` field on the entry, an ally search, and a targeting
policy per adapter. It is deliberately **not** in 0.6.0; self-only matches the current model and is the
smaller, verifiable change.

### Tick-accurate cadence

- `decisionTimer` (a decrementing counter) becomes `nextDecisionTick`, an absolute `mob.tickCount`
  deadline. Per-spell cooldowns become a `spell id → readyAtTick` map on the same clock.
- Because `canUse()` is still only evaluated on alternating ticks, a deadline is honoured to within one
  tick. That residue is documented, not hidden: configured values now mean *game* ticks, ±1.
- Cooldown ticking is no longer O(spells) work on every evaluation — a map lookup at decision time
  replaces `replaceAll` over the whole map.

## Rejected alternatives

- **Just delete the null-target check.** Would let ATTACK spells be selected with no target; they would
  cast into whatever direction the mob happened to face, and `LineOfFire`/range checks would be
  meaningless. The role restriction is the point.
- **Reuse `decisionIntervalTicks` for the idle path.** Ten-tick idle re-evaluation across a large world
  of casters is exactly the per-entity-per-tick cost 0.6.0 is removing elsewhere (B1/B11). A separate,
  slower knob costs one config key.
- **Drive out-of-combat support from the tick handler instead of the goal.** Would duplicate every gate
  (state, mana, cooldown, condition, cast chance) outside the goal that owns them, and would run for
  every mob rather than only mobs whose goal is scheduled. Rejected as a second source of truth.
- **Compensate the cadence fix by doubling the default cooldowns.** Tempting — it would make the upgrade
  invisible — but it would silently change what every *explicit* `"cooldown": 100` in an existing pack
  means relative to the defaults, and it bakes a bug's arithmetic into the defaults forever. Chosen
  instead: fix the clock, state the ~2× speed-up loudly under **Migration**, and give packs
  `balance.cooldownMultiplier` (which already exists) as the one-line compensation.
- **Fix the clock by ticking counters from `tick()` instead of `canUse()`.** `tick()` only runs while the
  goal is *running*, which for an instant cast is a single tick. Cooldowns must advance while the goal is
  idle, so this does not work.

## Consequences

- A wounded caster with no target self-heals within `supportOutOfCombatIntervalTicks` + wind-up; a
  full-health caster with no target never casts.
- **Configured cooldown/decision values now correspond to real game ticks.** Existing packs' casters are
  roughly twice as fast until re-tuned — the single loudest line in the 0.6.0 migration notes.
- Idle overhead is bounded by `supportOutOfCombatIntervalTicks`, and only for mobs that actually own a
  SUPPORT entry: the goal short-circuits out-of-combat evaluation entirely when its loadout has none.
