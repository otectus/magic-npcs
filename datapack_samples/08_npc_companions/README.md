# 08 — NPC Companions

Loadouts for third-party NPC and companion mods. Most entity types here belong to a mod Magic
NPCs cannot compile against, so each of those needs its owning mod's `[compat]` toggle set before
the loadout applies at all — that's a deliberate, modpack-safe default. The one exception is
Recruits: it is a first-class integration that `[compat]` never gates, so `recruits_captain.json`
is live without any compat toggle.

## Files

| File | `entity_type` | Compat toggle |
|---|---|---|
| `guardvillagers_guard.json` | `guardvillagers:guard` | `compat.guardvillagers` |
| `recruits_captain.json` | `recruits:captain` | *(none — Recruits is never gated by `[compat]`; its own `[recruits] enabled`, on by default, applies)* |
| `easynpc_humanoid.json` | `easy_npc:humanoid` | `compat.easynpc` **and** `easynpc.enabled = true` |
| `easynpc_fairy.json` | `easy_npc:fairy` | `compat.easynpc` **and** `easynpc.enabled = true` |
| `customnpcs_guard.json` | `customnpcs:customnpc` | `compat.customnpcs` |
| `customnpcs_archmage.json` | `customnpcs:customnpc` | `compat.customnpcs` |
| `humancompanions_companion.json` | `humancompanions:human_companion` | `compat.humancompanions` |
| `minecolonies_barbarian.json` | `minecolonies:barbarian` | `compat.minecolonies` |

## What this teaches

- **`[compat]` toggles.** Loadouts on a third-party namespace are inert until the matching key
  under `[compat]` in `config/magicnpcs-common.toml` is `true` (e.g. `compat.guardvillagers` or
  `compat.customnpcs`). Easy NPC needs a second flag on top, `easynpc.enabled = true`, which
  switches on the adapter that makes Easy NPCs cast at all — the compat toggle alone only admits
  loadouts on the `easy_npc:` namespace. Recruits is the one namespace here with no `[compat]`
  toggle at all: it is a first-class integration gated only by its own `[recruits] enabled`
  switch (on by default).
- **One file per Easy NPC variant.** Easy NPC registers a separate entity type per NPC variant,
  all in the `easy_npc` namespace (`humanoid`, `fairy`, and many more). A loadout names one
  variant, so a pack using several needs one file per variant — `easynpc_humanoid.json` is a
  general attacker/healer, `easynpc_fairy.json` is a dedicated support caster.
- **`npc_traits` for CustomNPCs.** CustomNPCs registers a single entity type
  (`customnpcs:customnpc`) for every authored NPC, so `npc_traits` is what tells them apart.
  `customnpcs_guard.json` uses `any_of` (matches an NPC with at least one of the listed traits);
  `customnpcs_archmage.json` combines `all_of` (every listed trait required) with `none_of`
  (excludes NPCs carrying any of those) to carve out a narrower archetype, and the two files pool
  against each other by `pool_weight` for any NPC matching both. Trait ids are free-form strings
  from CustomNPCs itself — Magic NPCs does not validate them against a registry.
- **Automatic owner/faction protection.** Both Easy NPC (per its own `_comment`) and Human
  Companions (`targeting.protectOwners`, per `humancompanions_companion.json`) are protected from
  friendly fire automatically — an attack spell never lands on the NPC's owner, a sibling with the
  same owner, or (for Easy NPC) anything sharing its faction. This holds even when the adapter
  that makes the NPC cast is otherwise off.
- **A raider-only pattern for colony AI.** `minecolonies_barbarian.json` scopes to the barbarian
  raider entity type and adds `caster_chance: 0.3` plus a `difficulties` gate, because MineColonies
  citizens are driven by colony AI that a generic casting goal doesn't understand — raiders are
  the safe target, not citizens.

## Prerequisites

Iron's Spells 'n Spellbooks, the relevant third-party mod, and its `[compat]` toggle. **Third-party
entity-type ids and item ids in this pack are not verified against a live install** — confirm them
with `/magicnpcs loadout id <entity_type>` or the mod's own registry before relying on this pack.

## Try it

```
/magicnpcs validate
/magicnpcs loadout id easy_npc:humanoid
/magicnpcs why @e[type=customnpcs:customnpc,limit=1,sort=nearest]
```
