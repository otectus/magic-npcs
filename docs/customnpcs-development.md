# CustomNPCs integration: design and development

This doc covers the CustomNPCs bridge for contributors and advanced users diagnosing issues.

## Isolation rules

The CustomNPCs integration is physically separated so Magic NPCs works cleanly with and without that mod:

1. **No `noppes` outside `compat/customnpcs/`** — the integration's package is self-contained. If that mod is absent, the package is never loaded and neither is CustomNPCs' own code.

2. **Reflective entry point** — `CustomNpcsCompat` is neutral: it names no CustomNPCs type and reaches the typed integration through `Class.forName`, so a version mismatch is a link error caught at runtime rather than a link error at classload. If the API shape is wrong, the bridge cleanly reports `PROBE_FAILED` and continues.

3. **Iron's-guarded functional hooks** — anything that reads or writes a caster's state (mana, cooldown, school) passes through a test of `IronsCompat.isLoaded()` first. A CustomNPCs NPC with no Iron's attached is detected as `NOT_CASTER` before any framework-specific logic runs.

4. **One reflective internal touch** — `CustomNpcsScriptGlobal` is the only place inside the integration that **creates** a script engine and inspects it. Everywhere else, the script surface is defined by interface (`CustomNpcsScriptFacade`, `CustomNpcsScriptApi`) so no implementation details leak, and script invocation is confined.

## Architecture

The integration spans four threads of responsibility:

### 1. The public-API surface (`CustomNpcsAdapter`, `CustomNpcsEventBridge`, `CustomNpcsIntegration`)

- **`CustomNpcsAdapter`** — implements `NpcAdapter`, so the universal casting path knows a CustomNPC's ownership, faction, role, job, AI modes, and movement policy without importing CustomNPCs. Reads from the NPC's live API every call; never caches (faction ownership can change in the CustomNPCs GUI).
- **`CustomNpcsEventBridge`** — bridges Forge events (`MagicNpcCastEvent`, `MagicNpcSchoolChangedEvent`) to CustomNPCs' own script trigger and mailbox systems via `NpcAdapter.publish()`.
- **`CustomNpcsIntegration`** — wires the entry point: registers the adapter, the event bridge, and the diagnostic hooks on the MOD bus and server bus.

**Status state machine:** starts at `ABSENT`, advances to `PRESENT_UNSUPPORTED` if version check fails, or to `ACTIVE_PUBLIC_API` if the adapter and event bridge initialize. Promotes to `ACTIVE_FULL` once deeper features (AI repair, script global) succeed. Can degrade to `DEGRADED_AI_REPAIR` if repair is failing, or `DISABLED_ERROR` if a fatal problem stops the whole thing.

### 2. AI repair (`CustomNpcsAiRepair`)

CustomNPCs rebuilds both goal selectors on a fixed cadence, clearing any goal another mod injected. Without repair, a caster would cast once and silently stop.

The repair queues a reconcile operation through the existing reconcile queue rather than hooking directly into the CustomNPCs update event, so the repair path does not run on the CustomNPCs event bus itself — it is scheduled for the next server tick on the Forge bus. This keeps the dependency flow clean: the CustomNPCs bridge does not know about the casting reconcile logic, and the casting logic does not import CustomNPCs.

### 3. Script bridge (`CustomNpcsScriptBridge`, `CustomNpcsScriptGlobal`, `CustomNpcsScriptFacade`, `CustomNpcsScriptApi`)

**`CustomNpcsScriptBridge`** registers the script global on the CustomNPCs script engine.

**`CustomNpcsScriptGlobal`** is the entry point from the script engine — it is the one place that creates and inspects script objects. It unwraps CustomNPCs wrappers and calls into the facade.

**`CustomNpcsScriptFacade`** is what scripts actually see as `MagicNPCs`. It takes CustomNPCs' `IEntity` wrappers, unwraps them to vanilla `Mob`s, and delegates to the API layer. This separation means the Iron's-backed implementation can take plain `Mob`s and never name CustomNPCs.

**`CustomNpcsScriptApi`** and **`CustomNpcsScriptApiIrons`** define the contract and implementation. The interface takes vanilla `Mob`s, not wrappers, so `CustomNpcsScriptApiIrons` avoids importing `noppes`. The null object (`CustomNpcsScriptApi.inactive()`) answers `BRIDGE_INACTIVE` to every operation when Iron's is absent or the bridge is down.

Result codes ensure a script never sees an exception: catches are at the boundary, and the caller gets a `Result` with a code and a message.

### 4. Diagnostic output (`CustomNpcsDiagnostics`)

The adapter contributes to `/magicnpcs why` output: role, job, faction, dialog state, movement policy. The startup line and `/magicnpcs config` report CustomNPCs presence and status.

## Event buses

Three buses handle casting and school events:

| Bus | Handler | Purpose |
|---|---|---|
| **FORGE_BUS** | `MagicNpcCastEvent` (Pre, Started, Completed, Cancelled) | Mod-agnostic Forge event; any listener can react without importing Magic NPCs internals or NPC mods |
| **FORGE_BUS** | `MagicNpcSchoolChangedEvent` | School assignment changes |
| **CustomNPCs script trigger** (via `CustomNpcsEventBridge`) | Posted by `MagicNpcEvents` → bridge → script trigger + mailbox | Framework-specific reaction: scripts and CustomNPCs stored data |

**Emission point:** `core.caster.MagicNpcEvents` is the single point where events are posted and signals are published. This ensures a listener and a script each see the event exactly once, and neither can be the cause of the other.

## Building and testing

### Compile-time

The CustomNPCs GBPort jar is required to build:

1. Fetch the jar from the GBPort project and save it as `libs/CustomNPCs-1.20.1-GBPort-Unofficial-1.20.1.20260711.jar`.
2. Verify its SHA-256 against `gradle.properties` (`customnpcs_jar_sha256`). The build fails if the hash mismatches.
3. `./gradlew compileJava` now includes the `compat/customnpcs/` package.

### Runtime testing

**With CustomNPCs (-PcustomNpcsRuntime):**
```bash
./gradlew runGameTestServer -PcustomNpcsRuntime
```

Boots with CustomNPCs only (not the full Iron's stack). Verifies that the adapter, event bridge, and script facade classload and register correctly. The casting hooks stay dormant (Iron's absent), but the isolation tests run.

**Full runtime (-PdevRuntime):**
```bash
./gradlew runGameTestServer -PdevRuntime
```

Boots with Iron's + Recruits + Easy NPC + CustomNPCs. Runs the casting GameTests including the AI-rebuild repair test.

### GameTests

`CustomNpcsCompatGameTests` covers:

- **Isolation:** the bridge is neutral, reflecting mode on unsupported versions, and graceful degradation
- **Public-API:** adapter reports role/job/faction/etc.; event bridge fires script triggers
- **AI repair:** a caster's goals are re-installed after CustomNPCs rebuilds AI (the critical "no silent casting loss" test)
- **Script surface:** read/write operations work and return proper result codes

The isolation unit test runs offline (Iron's absent) and verifies the bridge does not crash. The repair test requires the CustomNPCs runtime jar in `libs/` — it is marked `required = false` so it skips offline, and the CI build gates the release on passing it.

## Release gates

The build is marked ready to ship only after:

1. ✅ `./gradlew build` succeeds (standard build + unit tests)
2. ✅ The isolation unit test passes (bridge neutral without Iron's/CustomNPCs)
3. ✅ `./gradlew runGameTestServer -PdevRuntime` passes, including the **AI-rebuild GameTest in `CustomNpcsCompatGameTests`** — this requires the CustomNPCs runtime jar in `libs/` and is the only test that proves the repair hook actually works

The third gate is manual: it needs a human to run the command and verify the log shows the test passed. The reason: the test requires production-build jars that are platform-specific and cannot be vendored, and network-fetching them on every CI run is not practical. See `docs/dev-runtime.md` for how to stage the jars.

## State machine walkthrough

On server start:

1. `CustomNpcsCompat.init()` is called
2. Check: is CustomNPCs installed? → set `ABSENT` if no
3. Check: is the version on `SUPPORTED_VERSIONS`? → set `PRESENT_UNSUPPORTED` if no
4. Reflectively load `CustomNpcsIntegration` and call its `init()`
5. The integration wires the adapter, event bridge, and diagnostics
6. Check: does the adapter's reflective check of the API succeed? → set `PROBE_FAILED` if no
7. Set `ACTIVE_PUBLIC_API`
8. Check: do the script bridge hooks load and register? → log a detail line; promote to `ACTIVE_FULL` if yes
9. Check: does the AI repair hook register? → set `DEGRADED_AI_REPAIR` with a detail if it fails, but keep running

At runtime:

- If an adapter, bridge or repair method throws: `DISABLED_ERROR`, retain the first exception, log it once
- On server stop: `CustomNpcsCompat.shutdown()` tears down the integration
- On `/reload`: the AI repair hook re-queues goal repair for affected casters

## Debugging

Check CustomNPCs status:

```
/magicnpcs config
```

Look for the line `CustomNPCs: <status> (<version>)`. Statuses:

- `absent` — CustomNPCs is not installed
- `present_unsupported (<version>)` — CustomNPCs is installed but the version is not on `SUPPORTED_VERSIONS`
- `probe_failed (<version>)` — the API probe failed; the CustomNPCs public-API classes are not accessible or have an incompatible shape
- `active_public_api (<version>)` — the adapter and event bridge are running; script bridge did not initialize
- `active_full (<version>)` — everything is running
- `degraded_ai_repair (<version>)` — repair is failing; casters may lose goals
- `disabled_error (<version>)` — the bridge shut down after an error; check the server log for the exception

If a status is not what you expected, check the server log for warnings and errors tagged with `[magicnpcs]`.

## Version stability

CustomNPCs for 1.20.1 is a community port (GBPort) with no API stability promise. `SUPPORTED_VERSIONS` is a hand-maintained set in `CustomNpcsCompat` — the sole source of truth for what this build supports.

If you are updating to a newer CustomNPCs build:

1. Fetch the new jar into `libs/`
2. Run `./gradlew compileJava --refresh-dependencies` to verify it compiles
3. Update `customnpcs_jar_version` and `customnpcs_jar_sha256` in `gradle.properties`
4. Add the version string to `CustomNpcsCompat.SUPPORTED_VERSIONS`
5. Run `./gradlew runGameTestServer -PcustomNpcsRuntime` and `./gradlew runGameTestServer -PdevRuntime` to verify the tests pass
6. Update this doc if the API changed
