# ADR 0004 — Config split: installation facts to COMMON, gameplay balance stays SERVER

**Status:** Accepted (0.6.0)
**Date:** 2026-08-14

## Context

A modpack author reported: *"your configs are only server side and not client… I had to change the
server side config then place that file into defaultconfig so every new world has those configs"*.

`MagicNpcs.java` registered exactly one spec, `ModConfig.Type.SERVER`, which Forge writes to
`saves/<world>/serverconfig/magicnpcs-server.toml`. That is per-world — correct for gameplay balance,
but it means a pack author has no pack-level default.

**Side audit (Phase 1).** Every config read in the tree executes on the server: `Telegraphs` early-returns
unless `caster.level() instanceof ServerLevel`; the goal, the spawn handler, the loadout manager, the
commands and the school code all run on the server thread. There is **no** client-side read. So "make it
a CLIENT config" is not the fix and no CLIENT spec was added — the reporter's wording described the
symptom, not the remedy.

What they actually needed is (a) documentation of `defaultconfigs/`, which is the supported Forge
mechanism and already works, and (b) for the handful of settings that are *installation facts* rather
than per-world balance to stop being per-world at all.

## Decision

**Two specs.**

- `magicnpcs-server.toml` (`ModConfig.Type.SERVER`, per-world, synced to clients on login) keeps every
  gameplay tunable: `[balance]`, `[targeting]`, `[equipment]`, `[reactive]`, `[feedback]`, `[spells]`,
  `[recruits]`, `[schools]`, and the `[general]` gameplay switches (`enableSpellcasting`,
  `castingGoalPriority`, `castingGoalUsesLookFlag`, `disabledEntityTypes`, `suppressibleAttackGoals`).
- `magicnpcs-common.toml` (`ModConfig.Type.COMMON`, in `config/`, applies to every world) takes the
  settings that describe *the installation* rather than *this world's balance*:
  - the whole `[compat]` namespace-toggle block — "is Guard Villagers installed and do I want its
    entities to use loadouts" is a property of the pack, not of save file #3;
  - `general.debugLogging` — a troubleshooting switch you flip once while diagnosing.

**Migration without silent resets.** Moving a key between files normally resets it to default for
existing users. Instead, the moved keys are **retained in the SERVER spec for one release** under
`[legacy]`, and the accessor is the union:

```java
compatEnabled(ns) == COMMON_TOGGLE.get() || LEGACY_SERVER_TOGGLE.get()
```

Because every moved key is a boolean defaulting to `false`, OR-ing is exactly "whatever you had switched
on stays on". A one-time WARN names any legacy key still carrying a non-default value and tells the user
where to move it. The legacy block is scheduled for removal in 0.7.0 and is documented as such in
`CHANGELOG.md` under **Migration**.

**Documentation is half the fix.** `README.md` gains a "Modpack authors" section giving the exact paths:
`defaultconfigs/magicnpcs-server.toml` seeds every *new* world; an *existing* world needs the file at
`saves/<world>/serverconfig/magicnpcs-server.toml`; `config/magicnpcs-common.toml` is global and needs
neither.

## Rejected alternatives

- **Add a CLIENT spec because the reporter said "not client".** The audit found no client-side read. A
  CLIENT spec would be a file that does nothing, and it would invite future gameplay logic to read from a
  side where the value is not authoritative.
- **Move everything to COMMON.** Loses per-world balance and, more importantly, loses the automatic
  server→client sync that SERVER configs get. Multiplayer packs legitimately want different balance per
  world.
- **Documentation only, no split.** Closes the literal report but leaves "which mods' entities may cast"
  as a per-world setting, which is the part that genuinely surprises pack authors.
- **Hard-move the keys with no fallback.** Every existing user's enabled compat toggles would silently
  revert to `false` on update — i.e. their NPCs would stop casting, with no error. Unacceptable for a
  setting whose whole purpose is opt-in.

## Consequences

- Pack authors set `[compat]` once, in `config/magicnpcs-common.toml`, and it applies to every world.
- Existing worlds keep working untouched thanks to the legacy union; nothing resets.
- Two config files instead of one — a real cost, mitigated by the fact that the common file has exactly
  eight keys and a header comment explaining the division.
- `MagicNpcsConfig` now guards reads with `isLoaded()` on both specs, because a COMMON spec is loaded at
  a different lifecycle point than the SERVER one and early reads (e.g. during `RegisterCommandsEvent` in
  the gametest runtime) must not throw.
