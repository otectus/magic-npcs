# ADR 0010 — Easy NPC integration: adapter, objective, action and conditions

**Status:** Accepted (0.7.0)
**Date:** 2026-09-01
**Verified against:** Easy NPC **Core 7.11.0** for MC 1.20.1 Forge — CurseForge project `1308987`,
file `8779221`. Class and method shapes were read out of that jar with `javap`, not from the wiki
(the wiki still documents 6.6.x and states there is no objective registry, which is wrong as of 7.x —
Easy NPC's own `api/package-info.java` says the source is authoritative when they disagree).

## Context

Magic NPCs shipped "Easy NPC support" that was a name in a config array. `COMPAT_NAMESPACES` carried
an `easy_npc` entry, `docs/loadouts/README.md` carried an example flagged *"verify; Easy NPC ids vary
by variant"*, and there was no compile dependency, no adapter, no `mods.toml` entry and no test. A
pack author running both mods had to guess entity ids, discover an undocumented toggle, and hand-author
JSON per entity type, with no per-individual control at all.

Easy NPC is the natural counterpart to this mod — it authors characters, we make them cast — and since
6.8.0 it has a real third-party API. The question was how much of it to use, and where the seams go.

## Decision

### 1. Compile against Easy NPC **Core**, pinned by file id

Easy NPC 7.x is three separately published mods: core (`easy_npc`, all the classes), config UI
(`easy_npc_config_ui`), and a dependency-only bundle. We compile against core.

The dependency is pinned by **CurseForge file id**, not by version string, because every loader shares
the version number `7.11.0` and `maven.modrinth:easy-npc-core:7.11.0` resolves to the *Fabric* jar. A
file id names exactly one loader's build. There is no public Maven for Easy NPC; the
`de.markusbordihn.easynpc:easy_npc-forge-1.20.1` coordinates in its `DEVELOPMENT.md` are
mavenLocal-only, used for its own inter-module build.

`mods.toml` declares `easy_npc` at `[7.11,8.0)`. The range is narrow because Easy NPC's
`api/package-info.java` says the API "is currently in development" and that "breaking changes may
occur in minor versions during the experimental phase". `easy_npc_config_ui` is deliberately **not**
declared: nothing here needs it, and a core-only install must stay fully supported.

### 2. Two registration halves, two guards

`compat/EasyNpcCompat` gates the whole integration. Inside it, `EasyNpcIntegration.init()` registers
the **adapter and the diagnostics unconditionally**, and defers the casting hooks (objective, action,
conditions, state listener) behind a second `IronsCompat.isLoaded()` guard, because those reach into
`integration.irons` and would classload Iron's.

An install with Easy NPC and no Iron's therefore still gets correct owner and faction protection —
there is simply nothing to protect anyone from yet.

### 3. The adapter uses faction data to **protect**, never to permit

`EasyNpcAdapter` maps Easy NPC's `*DataCapable` interfaces onto the existing `NpcAdapter` seam:
`OwnerDataCapable` and `FactionDataCapable` for allies, `ProgressionDataCapable.getExperienceLevel()`
for rank-scaled mana, `NavigationDataCapable` for the movement policy, and
`EasyNPCPauseHandler.isPaused` for the state gate.

`canCastAt` is `!isAlly`, and it is deliberately **not** `FactionHandler.isHostile(...)`.
That predicate returns `false` for a faction-less NPC, for a faction-less target, and whenever no
hostile relation has been configured, so using it as the permission to cast would mean a freshly
created Easy NPC could never cast at anything — the "silently never casts" class of defect already
recorded as B7. Explicit hostility still gets the last word: an entity the faction system calls
hostile is not treated as an ally whatever group name it shares.

`appliesTo` is not gated on `easynpc.enabled`, following the rule `RecruitsAdapter` documents:
disabling an integration must stop casting, never remove the rules about who may not be cast at.

### 4. School assignment is routed by a per-mod policy

`NpcAdapter.schoolRollPolicy(Mob)` is new. Previously the progression branch of `rollSchool` was
gated on `schoolAssignable` alone and read `[schools.recruits]` directly — so the moment a second
adapter answered `schoolAssignable` true, its NPCs were silently rolled under Villager Recruits' caster
chance, rank threshold and type map, and their own config section did nothing. Each adapter now
publishes its own settings and the assignment code reads them, with no mod named in
`integration.irons`.

### 5. The casting objective returns the reconciler's goal, not a new one

`magicnpcs:cast_spell` is registered through `ObjectiveRegistry`. Its factory runs the ordinary
`CasterReconciler.reconcile` and returns `findSpellGoal(mob)` — the very goal that produced. Easy NPC
then removes and re-adds that same instance at the objective's priority, which is harmless: one object,
one goal selector, found by the same lookup as always, so reconciliation still sees it as installed and
never duplicates it.

Two consequences had to be handled:

- **Stale references.** Easy NPC caches the `Goal` its factory returned on the `ObjectiveDataEntry`.
  A loadout change makes the reconciler build a new goal and drop the old one, and Easy NPC would later
  re-add the dead instance. `core/caster/CasterGoalListeners` is a one-way publish from the reconciler
  outward; the Easy NPC side subscribes and calls `rebuildCustomObjective`.
- **Re-entrancy.** That rebuild calls `getGoal`, which calls `createGoal`, which reconciles, which
  fires the listener. A `ThreadLocal` guard in `EasyNpcSpellObjective` suppresses the rebuild while we
  are inside our own `createGoal`; a rebuild is only ever needed for a change that happened outside it.

### 6. Scripted casts get a driver of their own

`magicnpcs:cast <spell> [level] [self|target]` is registered through `ActionRegistry` as an
`ActionDataType.CUSTOM` handler. A cast triggered from a dialog button has no goal behind it, and
`MobCastSession` must be ticked to completion — begun and abandoned, it would charge the mana, call
Iron's `initiateCast`, and leave `MagicData.isCasting()` true forever, blocking every later cast on
that mob. `integration/irons/DetachedCastDriver` holds such sessions and ticks them from the existing
server-tick handler, cancelling cleanly when the caster dies or unloads.

The action applies the same filters the AI path applies — `MobCastSession.begin` runs the spell
allow-list, the mob-castability manifest, the mana check and Iron's pre-cast conditions — plus the
adapter's `canCastNow`/`canCastAt`. A scripted route that ignored the rules the AI route obeys is how
"I disabled this and it still happened" reports are made (defect B6).

### 7. Diagnostics get a contributor seam

`core/diag/DiagnosticContributors` lets a mod-specific integration add rows to `/magicnpcs why`
without `CasterDiagnostics` importing that mod. The Easy NPC contributor reports pause state, owner,
faction, progression level, navigation, and whether the casting objective is present and registered.

## Rejected alternatives

- **A configuration tab inside Easy NPC's UI.** Not possible. `data.configuration.ConfigurationType`
  is a closed enum of ~53 constants and `configui.menu.MenuHandlerInterface` resolves menus *by enum
  value*; there is no registration hook. Reaching it would need a mixin into a separate, optional mod
  whose API is explicitly pre-stable — and this project deleted all its mixins in 0.6.3. Per-NPC
  configuration will instead be a standalone Magic NPCs screen opened from the School Tome, whose
  `PlayerInteractEvent.EntityInteract` handler already cancels before an entity's own GUI opens. That
  is 0.8.0 work; it is the project's first client code and first network channel, and it does not
  belong in the same release as a behaviour change.
- **A marker objective whose factory returns `null`.** Rejected after reading
  `ObjectiveDataCapable.addOrUpdateCustomObjective`: a null goal falls into `handleUnusedObjective`,
  which either logs a warning or — if `isCompatible` is false — marks the entry permanently registered
  so it is never retried. Abusing that to mean "declarative marker" would produce misleading logs and
  a dead objective.
- **Building a second casting goal in the factory.** A parallel path that could disagree with
  `CasterReconciler` about mana, equipment, cooldowns and the native-attack policy. ADR 0008 made the
  reconciler the single owner of casting state precisely so that cannot happen.
- **Persisting our per-NPC data through Easy NPC.** Not available: `SynchedDataIndex` is a closed enum
  and `EntityDataSerializersManager` is an internal map. We keep using `Entity#getPersistentData()`,
  which is also what makes the data travel in Easy NPC presets — `PresetDataCapable.serializePresetData`
  calls `entity.saveWithoutId(tag)`. That last claim is **untested** and is the open item below.
- **New Easy NPC action *types*.** `ActionDataType` and `ActionEventType` are closed enums.
  `ActionDataType.CUSTOM` plus a registered executor is the supported route and is sufficient.
- **Reflection instead of a compile dependency.** Rejected for the reason the Recruits integration was:
  the call descriptors would silently disagree with the real mod at runtime, and none of it would be
  verifiable at build time.

## Consequences

- Easy NPCs cast, with owner and faction protection, level-scaled mana, and movement that respects an
  immovable flag or a home position.
- `[easynpc]` and `[schools.easynpc]` both default **off**; nothing changes for an existing pack until
  a pack author opts in.
- The version range is narrow and Easy NPC releases often. Re-verifying against each new minor is a
  standing maintenance cost, accepted knowingly.
- Custom objectives have no UI in Easy NPC, so `magicnpcs:cast_spell` must be applied by preset NBT or
  command. Documented rather than worked around.

## Open — not done in 0.7.0

**Preset portability is asserted, not verified.** `serializePresetData` uses `saveWithoutId`, so the
`magicnpcs{}` compound inside `ForgeData` *should* export and import with a preset. The merge path in
`importPresetData` explicitly strips some subtrees, and `Entity#load` on an existing entity is not a
fresh spawn. This needs a GameTest round trip (export a configured caster, import onto a fresh NPC,
assert it still casts) plus a reconcile after import, since goals are not persisted. Until that test
exists, the claim should not be made in user-facing docs.
