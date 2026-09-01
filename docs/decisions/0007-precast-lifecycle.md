# ADR 0007 — Every spell gets its pre-cast step

**Status:** Accepted (0.6.1)
**Date:** 2026-08-21
**Verified against:** Iron's 3.15.2 bytecode — `HasteSpell.checkPreCastConditions` and
`HasteSpell.onCast`, `AbstractSpell.checkPreCastConditions`,
`TargetedTargetAreaCastData(LivingEntity, TargetedAreaEntity)`,
`TargetedAreaEntity.createTargetAreaEntity`.

## Context

A number of Iron's spells did nothing at all when an NPC cast them, while the mod still deducted the
mana cost and set the cooldown — a silent mana sink that looked, in game, like a caster idly wasting
its turn.

`IronsBridge.cast` called `spell.checkPreCastConditions(...)` **only inside** the branch for the four
target-locked spells in `SpellCompat.BY_PATH` (`root`, `devour`, `wisp`, `stomp`). Everything else
went straight to `onCast`.

That is the wrong shape, because in Iron's `checkPreCastConditions` is not merely a predicate — for
many spells it is where the cast data is *built*. `HasteSpell.checkPreCastConditions` calls
`Utils.preCastTargetHelper` to raycast for a target, installs a `TargetEntityCastData`, spawns a
`TargetedAreaEntity` sized from the spell's own radius, and installs a `TargetedTargetAreaCastData`.
`HasteSpell.onCast` then does `instanceof TargetedTargetAreaCastData` and, finding nothing, branches
straight past its entire effect.

The same pattern holds for roughly twenty spells, including `blessing_of_life` and `haste` — both of
which ship in the **default** `schools.supportSpellIds`, so a stock holy caster's support rotation
contained permanent no-ops.

## Decision

**Call `checkPreCastConditions` for every spell, immediately before `onCast`, and honour its answer.**

- Target-locked spells still get their `TargetEntityCastData` installed *first*, because their
  pre-cast step reads it.
- A `false` return aborts the cast: no `onCast`, no mana deducted, no cooldown set. If Iron's says the
  spell cannot cast, spending the mana anyway was never right.
- Any cast data the pre-cast step installed is reset afterwards, alongside data we installed
  ourselves, so nothing leaks into the next cast.

Ordering matters for aiming: `Utils.preCastTargetHelper` raycasts along the caster's look angle, and
the goal's `snapFacing` already runs before `IronsBridge.cast` for ATTACK spells, so the raycast finds
the intended target.

## Alternatives rejected

- **Extend `SpellCompat.BY_PATH` with each affected spell and hand-build its cast data.** This was the
  first plan. It means constructing `TargetedAreaEntity` instances ourselves with radius and colour
  values we would have to guess per spell — duplicating logic Iron's already runs correctly, and
  guaranteed to drift as Iron's adds spells. The curated map stays only for the target-locked spells,
  whose data must be installed *before* the pre-cast step.
- **Mark the affected spells unsupported so they are filtered out with a diagnostic.** Honest, and it
  would have stopped the mana drain, but it removes ~20 working spells — including the school system's
  main healing options — for no reason once the real cause is understood.
- **Leave it and document it.** The failure is invisible: the spell animates, mana drops, nothing
  happens.

## Consequences

- Spells that were quietly doing nothing now work, so existing loadouts and school pools will visibly
  change behaviour. Called out in the changelog's Migration section.
- A spell whose pre-cast conditions genuinely fail (no valid target in range for a targeted buff) now
  costs nothing instead of costing a full mana payment and a cooldown.
- `SpellCompat.supportedForMob` remains the escape hatch for categories a mob truly cannot satisfy
  (`MULTI_TARGET_REQUIRED`, `PLAYER_ONLY_OR_UNSUPPORTED`). Note that no spell currently classifies
  into those, so those branches stay unexercised until a spell is mapped into them.
