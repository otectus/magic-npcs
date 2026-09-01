# ADR 0003 — Loadout source tiering: a datapack beats the jar automatically

**Status:** Accepted (0.6.0)
**Date:** 2026-08-14
**Verified against:** Forge 47.4.16 `ResourcePackLoader.createPackForMod`,
`ServerLifecycleHooks.buildPackFinder`, `net.minecraft.server.packs.resources.Resource`,
`SimpleJsonResourceReloadListener`.

## Context

Magic NPCs ships an **active** `guardvillagers:guard` loadout in its own jar. A pack author who wrote
their own guard loadout got *both*: `LoadoutManager.resolve` pools loadouts that share an entity type and
sticky-picks one per NPC by `pool_weight`, so some guards used the author's spells and some used ours —
permanently, with no error.

0.5.0 added `"replace": true` as the escape hatch. It works, but it is the wrong default: `replace` is
meant to arbitrate *datapack vs datapack*, not to be the thing every user must discover in order to beat
the mod's own defaults. "My file loses to the mod's file until I find an undocumented flag" is a bug
report generator.

## Decision

**Loadouts carry a source tier, and a higher tier wins outright.**

```
BUILT_IN (0)  — the file came from a mod jar's own data pack
DATAPACK (1)  — the file came from any other pack (world datapack, global pack, OpenLoader, …)
```

Resolution order per effective key (entity type + optional `profession`), applied at load time in
`LoadoutManager.applyOverrides`:

1. **Tier** — keep only loadouts at the highest tier present in that key's group; drop the rest.
2. **`replace`** — unchanged 0.5.0 semantics, now applied *within* the surviving tier.
3. **Pool** — unchanged 0.4.0 semantics for whatever remains.

**How the tier is derived.** `SimpleJsonResourceReloadListener` hands `apply()` only file ids, so the tier
is looked up from the `ResourceManager` that is passed alongside them:
`rm.getResource(getPreparedPath(id))` → `Resource.isBuiltin()` / `Resource.sourcePackId()`.
Forge builds each mod's data pack as a `PathPackResources` constructed with `isBuiltin = true`
(`ResourcePackLoader.createPackForMod`) and registers one server-data `Pack` per mod file directly from
that object (`ServerLifecycleHooks.buildPackFinder`), so `isBuiltin()` is an accurate jar/not-jar signal.
A pack id beginning `mod:` is accepted as a second, independent signal.

**Deliberately not** matched on the `magicnpcs:` namespace: a pack may legitimately place files under our
namespace (and OpenLoader packs frequently reuse namespaces), so the namespace says nothing about who
shipped the file.

**Failure mode is benign by construction.** If tier detection ever mis-classifies a file, both loadouts
land in the same tier and resolution falls back to exactly the 0.5.0 `replace`-then-pool behaviour. There
is no configuration in which tiering can make things *worse* than the previous release. The detected
pack id is printed by `/magicnpcs loadout entity` and `/magicnpcs loadout id`, so a misdetection is
visible rather than mysterious.

## Rejected alternatives

- **Stop shipping third-party loadouts entirely.** Considered seriously — the `guard.json` case is
  exactly the cost. Rejected because the shipped loadouts are the "it just works when I enable the compat
  toggle" onboarding path, and tiering makes them safe. (They remain gated behind their `[compat]`
  toggle, which still defaults off, so they are doubly opt-in.)
- **Move shipped loadouts into a bundled optional datapack the user enables.** Solves the conflict but
  adds a second thing to discover and would sit at `DATAPACK` tier, back to pooling with the author's
  file. Strictly worse than tiering.
- **Make `replace` the shipped default on our own loadouts.** Inverts the bug: the jar would then beat
  every datapack.
- **Order by datapack load order / pack priority.** `apply()` receives a `Map` keyed by file id with no
  ordering guarantee, and pack priority is not exposed per-entry here. It would also be
  non-deterministic across runs — `applyOverrides` is deliberately pure and order-independent so it can
  be unit-tested without a Minecraft runtime.
- **A numeric `"priority"` field on every loadout.** More expressive, but it makes the common case
  ("my pack should just win") a thing you configure rather than a thing that happens. Kept as possible
  future work layered *inside* a tier.

## Consequences

- A datapack loadout for `guardvillagers:guard` fully replaces the shipped one with **no** `replace` flag
  and no config change — the reporter's original ask.
- `"replace": true` keeps its exact meaning for datapack-vs-datapack conflicts; nothing existing breaks.
- Packs that *deliberately* pooled with the mod's own loadout (unlikely, undocumented) lose that pooling.
  Called out under Migration in `CHANGELOG.md`.
- `SpellcasterLoadout` gains a `tier` component; in-code loadouts (data generator, GameTests) default to
  `DATAPACK` so programmatic construction is never silently outranked.
