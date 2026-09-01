# ADR 0001 — Iron's mob-casting integration model

**Status:** Accepted (Phase 0 spike)
**Superseded in part:** the cast path by [ADR 0008](0008-cast-session-and-reconciliation.md); the "implement `IMagicEntity` via Mixin onto `AbstractRecruitEntity`" proposal below by [ADR 0009](0009-caster-movement-and-rank-scaling.md), which rejects it and says why.
**Date:** 2026-06-03
**Verified against:** `irons-spells-n-spellbooks-855414` file `7402504` (v3.15.2, 1.20.1, parchment 2023.09.03), decompiled from the local ForgeGradle deobf cache via `javap`.

## Context

MagicNPCs must drive Iron's Spells from *foreign* mobs (vanilla/modded, e.g. Villager Recruits) on Forge 1.20.1. Before building, the Phase-0 spike confirmed exactly how Iron's exposes mob casting, so the architecture rests on verified bytecode rather than the original spec's (wrong) assumptions.

## Verified facts

1. **Every `LivingEntity` already owns a `MagicData`.** `MagicData.getPlayerMagicData(LivingEntity)` is an unconditional `checkcast` to `MagicData$IExtendedEntity` + `irons_spellbooks$getMagicData()`. Iron's Mixins `IExtendedEntity` onto the base `LivingEntity`, so any mob — Recruit included — has a retrievable `MagicData`. **→ no custom mana store, no Mixin needed for the universal path.**
2. **Effect entry point:** `AbstractSpell.onCast(Level, int spellLevel, LivingEntity, CastSource, MagicData)`. Applies the spell's effects and spawns its particles/sounds server-side. **→ never replay visuals from a client packet (would double-play).**
3. **`CastSource.MOB.consumesMana() == false` and `respectsCooldown() == false`.** (`consumesMana` is true only for `SPELLBOOK`, or `SWORD` when `ServerConfigs.SWORDS_CONSUME_MANA`; `respectsCooldown` is true only for `SPELLBOOK`/`SWORD`.) Iron's intentionally does **not** gate mob casts on mana/cooldown — its own mobs pace via AI attack-interval timers. **→ MagicNPCs fully owns mana + cooldown accounting for NPC casters; `onCast` applies effects only.**
4. **No Iron's mana regen for foreign mobs.** `MagicManager.regenPlayerMana(ServerPlayer, MagicData)` is player-only; `AbstractSpellCastingMob` regens inside its own `protected customServerAiStep()`. A foreign mob gets neither. **→ we tick regen ourselves: read `AttributeRegistry.MANA_REGEN`, apply on the `MagicManager.MANA_REGEN_TICKS` cadence, `MagicData.addMana(...)`.**
5. **`IMagicEntity` is in the stable `io.redspace.ironsspellbooks.api.entity` package** (14 methods: `getMagicData`, `setSyncedSpellData`, `isCasting`, `initiateCastSpell(AbstractSpell,int)`, `cancelCast`, `castComplete`, `notifyDangerousProjectile`, `setTeleportLocationBehindTarget`, `setBurningDashDirectionData`, `getItemBySlot`, `isDrinkingPotion`, `getHasUsedSingleAttack`, `setHasUsedSingleAttack`, `startDrinkingPotion`). Needed only for the Phase-4 Mixin that reuses Iron's `WizardAttackGoal` family.
6. **`SpellRegistry.MAGIC_MISSILE_SPELL`** (and `MAGIC_ARROW_SPELL`) exist as `RegistryObject<AbstractSpell>` — Phase-1 test spell.

## Decisions

- **Universal path (Phases 1–2):** add `MAX_MANA`/`MANA_REGEN` attributes to tagged entity types; read/write mana via `MagicData.getPlayerMagicData(mob)`; a custom `Goal` calls `onCast(level, spellLevel, mob, CastSource.MOB, magicData)`. We enforce mana pre-check, deduction, cooldown, and regen ourselves.
- **Cooldown storage:** use the mob's own `MagicData.getPlayerCooldowns().addCooldown(spell, ticks)` + `isOnCooldown(spell)` (works without a `ServerPlayer`; no client sync needed for a mob). Fall back to a scheduler in `SpellcasterHandler` only if `PlayerCooldowns` proves awkward for mobs.
- **No double visuals:** rely solely on Iron's server-side effect spawning; client packets (if any) carry only `SpellcasterHandler` UI state, never re-spawn particles.
- **Phase-4 hybrid:** implement `IMagicEntity` via Mixin onto `AbstractRecruitEntity` to unlock Iron's `WizardAttackGoal`/`WarlockAttackGoal`/`SpellBarrageGoal`.

## Consequences

Full control over NPC balance (mana/cooldown are ours). We must replicate a minimal mana-regen tick. The integration is thin and contained in `core/IronsBridge.java`, so an Iron's API shift touches one file.
