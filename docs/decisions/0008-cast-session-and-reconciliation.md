# ADR 0008 — Casting is a session; managed state is reconciled, not re-injected

**Status:** Accepted (0.6.2)
**Date:** 2026-08-28
**Verified against:** Iron's `1.20.1-3.16.3` bytecode —
`AbstractSpellCastingMob.initiateCastSpell` / `.customServerAiStep` / `.castComplete`,
`MagicData.initiateCast` / `.handleCastDuration` / `.resetCastingState`,
`AbstractSpell.onServerPreCast` / `.onServerCastTick` / `.onServerCastComplete` /
`.getEffectiveCastTime` / `.getEmptyCastData` / `.shouldAIStopCasting`.

Supersedes the cast-path half of [ADR 0001](0001-irons-mob-casting.md) and extends
[ADR 0007](0007-precast-lifecycle.md); the injection policy of
[ADR 0002](0002-casting-goal-injection.md) is unchanged.

## Context

Two independent problems in 0.6.1 turned out to have the same shape: a one-shot operation standing in
for something that is really a state machine.

**Casting.** `IronsBridge.cast` checked pre-cast conditions, called `AbstractSpell.onCast` directly,
called `onServerCastComplete` for LONG spells only, and deducted mana unconditionally. Iron's own
casting mobs do something quite different. `AbstractSpellCastingMob` initiates a cast on `MagicData`,
runs `onServerPreCast`, then on every server tick calls `handleCastDuration()`, calls
`onServerCastTick` while the cast is live, fires `onCast` at the end for LONG/INSTANT or on a ten-tick
cadence for CONTINUOUS, and finishes through `castComplete()`. Spells are written against that
sequence. Skipping it meant every spell whose effect lives in a cast tick did nothing observable while
the mod still charged mana and started a cooldown — starfall's comets, blaze storm's fireballs, ray of
siphoning's channel, telekinesis' pull. The mod also had no cancel path at all, so anything a pre-cast
hook created was stranded when a wind-up was interrupted.

**Managed state.** Four call sites each did their own "remove the goal and add it again": entity join
(which no-oped whenever a goal was present), the school command, the villager re-check, and the reload
handler. The reload handler additionally skipped any mob that did not *already* have a Magic NPCs goal
— which is exactly the set of mobs a newly added datapack is supposed to reach, and is the reported
bug: a skeleton loaded before the pack was added never became a caster however many times its owner ran
`/reload`. Because "re-inject" meant "construct a fresh goal", it also reset everything the goal
happened to own: the per-spell cooldown map, the decision deadline, and — via `initMana` — the mob's
mana. Editing a datapack mid-fight healed every caster and let it fire its whole rotation again.

## Decision

**Casting is a session.** `MobCastSession` owns one cast from initiation to completion and reproduces
Iron's canonical order, including cancellation. `IronsBridge` goes back to being a thin version seam:
resolution, mana attributes, regen, telegraph data, diagnostics. The goal's wind-up remains its own
telegraph phase and no longer doubles as a hand-rolled channel — a channelled spell's cast time is now
Iron's channel, run by Iron's rules.

Mana and cooldown have one documented transaction point: the moment Iron's accepts the cast
(`initiateCast`), which is where a player pays. A refusal before that costs nothing. A channel
interrupted after it keeps both spent, deliberately — refunding would let a caster restart a
telegraphed channel every tick, which is the same replay loop backlog B13 already existed to stop.

**Managed state is reconciled.** `CasterReconciler.reconcile(mob, reason)` is the single idempotent
entry point. It computes the desired state from the catalog, config, manual assignment and profession,
compares it with what is installed, and applies only the difference. Every lifecycle event routes
through it: entity join, datapack reload, config reload, manual school changes, profession changes,
`/magicnpcs reconcile`, and tests. Combat state that must survive a goal being replaced — cooldowns,
the decision deadline, whether mana has been initialised, whether a native-attack lease is held — moves
out of the `Goal` instance into `ManagedCasterState`, keyed by entity.

Reload reconciles **every loaded mob**, not just existing casters, queued in bounded batches across
server ticks so a large world does not stall on `/reload`.

Every reconcile returns a typed `ReconcileResult` with a stable reason code. Callers count outcomes
rather than assuming success from having made the call — 0.6.1's `tryInject` returned `true` even when
application had bailed out for a mob with no mana attributes, so a reload could report rebuilding
casters it had not built.

## Consequences

- LONG and CONTINUOUS spells behave as designed. Spells needing Iron's mob-specific preparation
  (`teleport`, `frost_step`, `blood_step`, `burning_dash`, `ray_of_siphoning`) are marked
  **unsupported** rather than half-implemented: Iron's prepares them through `IMagicEntity` hooks a
  foreign mob cannot provide, and pretending otherwise is what produced the false support claims this
  release is fixing. See [`SpellManifest`](../../src/main/java/com/otectus/magicnpcs/integration/irons/SpellManifest.java).
- A reload that changes nothing does nothing: the goal is not replaced, mana is not refilled, cooldowns
  are not cleared, and the decision cadence is not reset.
- `ManagedCasterState` is in-memory and keyed by entity UUID. Cooldown deadlines are `mob.tickCount`
  values, which are not saved, so persisting them would be meaningless — an entity that unloads is no
  longer in a fight. State is dropped when the mob leaves the world and when the server stops.
- Loadout context `conditions` remain a **snapshot** contract: they are evaluated when a mob is
  reconciled, not continuously. The docs now say so. Making them dynamic would need coarse re-evaluation
  intervals plus hysteresis to avoid thrashing at a biome boundary, and that is a separate decision.
- `recruits.useIronsAI` is gone as a functioning path. It handed Iron's `WizardAttackGoal` a bare list
  of spells and discarded every per-entry setting in the loadout, while the mixin behind it reported
  `isCasting() == false` and no-oped the lifecycle methods that goal depends on. One config toggle
  therefore changed what a datapack *meant*. In 0.6.2 the option was still read, honoured as off, and
  warned.

  **Superseded in 0.6.3.** The key itself — and the two `ironsAi*` knobs beside it, which never had
  any readers — are removed outright, and the question is settled rather than deferred:
  [ADR 0009](0009-caster-movement-and-rank-scaling.md) records why `WizardAttackGoal` cannot carry the
  loadout contract at all, and what replaced it.

## Alternatives considered

- **Keep the one-shot bridge and special-case the channelled spells.** Rejected: the special cases are
  a moving target across Iron's versions, and the general lifecycle is both smaller and correct for
  spells nobody has enumerated.
- **Persist cooldowns in entity NBT.** Rejected: tick-count deadlines do not survive an unload, and
  converting to wall-clock would change what a cooldown means. In-memory state matches the lifetime of
  the fight it belongs to.
- **Reconcile inline on the reload thread.** Rejected: an unbounded entity scan on `/reload` is a stall
  proportional to world population. Queuing is a few lines and reports its own progress.
