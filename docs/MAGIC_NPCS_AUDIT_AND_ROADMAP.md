# Magic NPCs — Audit and Implementation Roadmap

**Repository:** `otectus/magic-npcs`  
**Reviewed revision:** `main` at `5e27810c3f65fd8bb64f7673c23c92f026b4b495`  
**Audit date:** September 12, 2026  
**Project version:** `0.9.0`  
**Target:** Minecraft 1.20.1 · Forge 47.4.16 · Java 17  
**Change authority:** Audit and planning only. No production source, dependency pin, or version was changed.

> **Evidence boundary:** This is an integration-focused source audit, with limited isolated execution, not a certification that the mod works in Minecraft. The repository was inspected through immutable GitHub reads. Cloning into the execution environment failed because GitHub DNS resolution was unavailable; the user's checkout and local `/libs` were not accessible. Full Gradle, JUnit, dedicated-server, integrated-server, and GameTest runs were therefore not performed. Two dependency-free production helpers were copied into an isolated audit directory, verified against their Git blob hashes, compiled, and exercised. Their results and independent reproductions are reported separately below.

## Contents

1. [Executive assessment and verified baseline](#1-executive-assessment-and-verified-baseline)
2. [Architecture and review-coverage map](#2-architecture-and-review-coverage-map)
3. [Integration matrix and end-to-end assessment](#3-integration-matrix-and-end-to-end-assessment)
4. [Prioritized findings](#4-prioritized-findings)
5. [Enhancement backlog](#5-enhancement-backlog-ranked-by-benefit-risk-and-effort)
6. [Phased implementation plan](#6-phased-implementation-plan)
7. [Tests, release acceptance and blockers](#7-test-results-regression-matrix-release-acceptance-and-blockers)

## 1. Executive assessment and verified baseline

### 1.1 Assessment

Magic NPCs has a substantially more developed foundation than its oldest plans suggest. It already has a real Iron's cast-session lifecycle, composable adapters, managed cooldown state, reload reconciliation, out-of-combat self-support, native-attack coexistence modes, school generation, layered spell manifests, authoring samples, and extensive diagnostics. Replacing these systems with Iron's player casting path, a fake player, another authoritative capability, or a second autonomous caster would discard useful behavior and create additional ownership problems. The accepted movement ADR specifically rejects replacing selection with `WizardAttackGoal`; the present implementation follows that decision. [Architecture and promises][readme] · [Accepted decision][adr9] · [Current lifecycle][session] · [Current adapter composition][adapters]

The highest-value work is to make **all casting entry points uphold the same mandatory safety and accounting contract**, make reconciliation **genuinely stable and ownership-aware**, and correct integration **server-lifecycle handling**. Several concrete paths violate these invariants even though the happy-path implementation is sophisticated. In particular:

| Priority | Finding | Why it matters |
|---|---|---|
| P1 | MN-002 / MN-003 | CustomNPCs' exact-build gate does not protect every generic/manual path, and external cast requests omit checks applied by automatic casting. |
| P1 | MN-004 / MN-013 | Exception and reentrant callback paths can leave casting state inconsistent or invalidate the detached-driver iterator. |
| P1 | MN-006 / MN-008 / MN-014 | Generated school selections can change during reconciliation; partial goal damage can be treated as unchanged; an unchanged reload still replaces goals. |
| P1 | MN-007 | Support-role target spells can be unusable while idle and can target an enemy during combat. This does **not** mean ordinary self-healing is absent. |
| P1 | MN-009 / MN-010 | Self-defense cleanup can remove another mod's target goal; server shutdown tears down integration listeners without a matching next-world startup path. |
| P1 | MN-011 / MN-012 | Line-of-fire geometry has an independently reproduced false negative, and safety checks are not all repeated at the point of effect. |
| P1 validation | MN-015 | Optional integration tests can report success without spawning the required NPC or successfully starting a cast. |
| P2 | MN-001 / MN-005 / MN-016 / MN-017 | Correct dependency provenance, canonical cooldown keys, misleading documentation, and positive cooldown overflow. |

These priorities are engineering recommendations, not claims that every failure has been observed in a running world. Each finding identifies whether its evidence is a source-level proof, isolated execution, or a still-unverified integration hypothesis.

### 1.2 Verified project baseline

| Item | Verified result | Evidence / qualification |
|---|---|---|
| Remote branch and commit | `main`, `5e27810c3f65fd8bb64f7673c23c92f026b4b495` | Pinned branch/tree read. Commit dated September 7, 2026; message: “Add datapack_samples: ten worked examples of the datapack surface.” |
| User's local branch / worktree | **Unknown** | No access to the user's checkout. This audit does not assert that it is clean or matches remote `main`. |
| Mod ID / version | `magicnpcs` / `0.9.0` | `gradle.properties`, `mods.toml`, first changelog entry. |
| Minecraft / loader | `1.20.1` / Forge `47.4.16` | Project properties; no Fabric or NeoForge substitution. |
| Java | Toolchain and source target `17` | Build configuration. Audit environment had OpenJDK `21.0.11`, not a local Java 17 installation. |
| ForgeGradle | `[6.0,6.2)` | Build script; a range, not a locked resolved plugin artifact. |
| Mappings | Official Minecraft mappings for the configured version | Build script. |
| Iron's advertised compile label | `1.20.1-3.16.3` | Property used for compile-version provenance. |
| Actual pinned Iron's compile artifact | Curse Maven `855414:7402504`, published as **`1.20.1-3.15.0`** | Exact official file page identifies `irons_spellbooks-1.20.1-3.15.0.jar`. This is MN-001, not merely the intended compile/runtime difference. |
| Iron's development runtime | Local `1.20.1-3.16.1` JAR | Build/properties; absent from this audit environment. |
| Iron's accepted loader range | `[1.20.1-3.15.0,1.20.1-3.17.0)` | Optional dependency metadata. Range acceptance is not proof of behavior for every member. |
| Built-in spell manifest reference | `1.20.1-3.16.3` | `SpellManifest.VERIFIED_AGAINST`; a reviewed classification claim, not an in-game pass list. |
| Villager Recruits | Local `recruits-1.20.1-1.15.2.jar`; accepted `1.15.0+` | Build/property pin and loader range. Exact local JAR not available. |
| Easy NPC Core | Intended `1.20.1-7.11.0`, Curse `1308987:8779221`; accepted `[7.11,8.0)` | Project pin. Exact file body / binary identity was not independently retrieved; Core is distinct from optional configuration UI. |
| CustomNPCs | **GBPort Unofficial `1.20.1.20260711` only** | Exact bridge allowlist and local compile JAR. Do not broaden it. |
| CustomNPCs SHA-256 | `604916a1cd0949fd426cab5fad8f839037d7e6f74d9f18a9afbeaa7731edfc38` | Project's required hash; no local bytes existed to compare against it. |
| Luminous runtime profile | LUMINOUS: BEASTS `V1.2.7`, Forge 1.20.1, Curse `1089627:8165506` | Exact official file identity verified. Runtime behavior not verified. |
| Test infrastructure | JUnit Jupiter `5.10.2`, platform launcher `1.10.2`, Forge GameTest run configuration | Build script and source/test inventory. Actual Gradle task graph was not executed. |
| Runtime profiles | `-PdevRuntime`, `-PeasyNpcRuntime`, `-PcustomNpcsRuntime`, additive `-PluminousRuntime` | Configured profiles, not executed profiles. |
| Other development runtime pins | Iron's library `1.20.1-1.1.0`, GeckoLib `4.8.3`, PlayerAnimator `1.0.2-rc1+1.20`, Curios `5.14.1+1.20.1` | Project's development setup. Their compatibility must be checked against the **actual selected Iron's runtime JAR metadata**, not a different Iron's branch. |

Sources: [Properties][baseline], [build configuration][build], [loader metadata][mods], [changelog][changelog], [exact Iron's compile file][iron-file], [exact Luminous file][luminous-file], [Recruits release][recruits-release], [Easy NPC Core project][easy-core].

The root tree contains no `AGENTS.md` or `.github/` directory. Contributor guidance was reviewed in the README, the CustomNPCs development document, and the accepted movement/rank ADR. This is **not** a claim that every nested instruction file, historical plan, or externally configured CI service was inspected. The GitHub issue collection returned one closed, merged historical PR concerning cast pacing, not a current open bug queue. Its description is historical context, not proof that later paths remain correct. [Pinned root][root-tree] · [Development instructions][cn-dev] · [Historical PR][pr1]

### 1.3 Evidence labels

**Confirmed—source:** A failing control/data path is identifiable in the pinned code. The prescribed reproduction is still a regression test to run unless explicitly marked executed.

**Confirmed—isolated execution:** A production pure helper or an exact arithmetic reproduction was actually executed. This does not imply Minecraft, Forge, or a dependency integration ran.

**Likely / verification required:** There is a credible mechanism, but an exact dependency implementation, event ordering, or live-world condition remains unverified. Do not describe these as reproduced defects in a changelog.

**Intentional limitation:** The behavior is deliberately scoped or documented. A change is a product decision, not an automatic correctness fix.

P1 means resolve before claiming the affected integration is production-verified. P2 means important correctness, authoring, or maintenance work. Effort uses relative sizes: S = localized logic and tests; M = several existing systems; L = cross-integration behavior and substantial runtime validation. These are scope estimates, not delivery-time promises.

## 2. Architecture and review-coverage map

### 2.1 Current ownership and execution flow

```text
Datapack resources / server configuration / manual school assignment
        |
        v
LoadoutManager + SchoolData + SchoolSpellPool
        |
        v
CasterReconciler ----> owned casting goal + owned movement goal
        |                         |
        |                         v
        |                 NpcSpellAttackGoal
        |                  selection + wind-up
        |                         |
        |                         v
        +-----------------> MobCastSession ----> Iron's spell hooks/effects
                                  ^
                                  |
Easy NPC action / CustomNPCs API -> DetachedCastDriver

Shared state: ManagedCasterState (cooldowns / activation / reconciliation metadata)
Iron's state: MagicData and magic attributes, accessed through IronsBridge
Safety/ownership: NpcAdapters composite + live framework adapters + generic policies
Events: MagicNpcEvents -> Forge event -> adapter signal -> framework script bridge
```

There is one session implementation, but **not yet one complete validation/commit boundary**. The automatic goal has substantial selection and continuation checks; the detached driver reaches the same session with fewer checks. Sharing `MobCastSession` alone therefore does not establish behavioral parity. [Automatic goal][goal] · [Goal continuation][goal-tick] · [Detached driver][detached] · [Session preparation][session]

| Concern | Intended/current owner | Invariant to preserve |
|---|---|---|
| Target acquisition | Native NPC framework; limited explicit villager self-defense | Do not install a second authoritative target selector for CustomNPCs or overwrite recruit orders. |
| Relationship permission | Live adapter composition | All applicable safety restrictions contribute; a high-priority adapter must not erase generic owner/raid/sitting restrictions. |
| Movement and navigation | Native framework first; Magic NPCs' existing movement goal only in its allowed suppressed-combat role | Never stop, replace, or restore another system's path or goals merely because they share a class name. |
| Selection / wind-up | `NpcSpellAttackGoal` and authored external request | Preserve per-entry conditions, ranges, weight, timing, and deliberate author intent. |
| Cast lifecycle | `MobCastSession` | Exactly one driver advances each session once per entity tick; no shared `AbstractSpell.castTime` mutation. |
| Mana value / attributes | Iron's `MagicData` and attributes, mediated by Magic NPCs | No parallel mana capability. Accepted NPC casts are charged according to the existing NPC policy. |
| NPC cooldown and activation bookkeeping | `ManagedCasterState` | One canonical spell key and one accepted-cast charge; reconciliation is not a refill mechanism. |
| School / loadout choice / equipment latch | Existing persistent entity data plus resolved catalog | No-op reconciliation must not reroll choices or regrant gear. |
| Inventory | Framework inventory API where provided | Respect authored equipment and persistent grant markers. |
| Diagnostic/script events | `MagicNpcEvents` and existing adapter publication | Distinguish rejected requests from started casts; callbacks must not corrupt the active-driver collection. |

Sources: [Adapter composition][adapters], [managed state][state], [reconciler][reconciler], [movement][movement], [event dispatch][events], [CustomNPCs adapter][cn-adapter], [Recruits adapter][recruits].

### 2.2 Coverage map

“Read” means the indicated source or range was examined, not compiled or exercised in Minecraft. Large-file partial reads and inventory-only areas are deliberately visible.

| Area | Examined | Remaining uncertainty |
|---|---|---|
| Baseline / build | Root tree, branch/commit, properties, build script, loader metadata, profile declarations | User worktree, exact resolved Gradle graph, toolchain provisioning, complete local `/libs`, final packaged artifact. |
| Core Iron's path | `MobCastSession`, `NpcSpellAttackGoal` across its main sections, `IronsBridge`, `DetachedCastDriver`, `SpellCompat`, `SpellManifest`, `ManifestReconciler` | Actual hooks for every supported spell and add-on; exact binary linkage across the accepted range. |
| Reconciliation | `CasterReconciler`, `ManagedCasterState`, main handler and school/reload paths, `SchoolSpellPool` | All framework-specific rebuild sequences, persistence across actual saves/clones, high-count runtime costs. |
| Adapters | Composite resolver; Recruits; Easy NPC; CustomNPCs; owner/team, raid, and sitting policies | Exact Recruits/Easy/CustomNPCs bytecode and their full faction/order semantics. |
| Easy NPC | Core/casting initialization, objective, cast action, state listener, adapter, conditions | Optional UI binary, client presentation, exact callback timing and objective rebuild behavior in Core 7.11.0. |
| CustomNPCs | Exact gate, initialization/shutdown, adapter, repair queue, event bridge, script bridge and Iron's-backed API | Exact pinned JAR; full script-engine/global internals, inventory bridge implementation, clone/respawn behavior and every activity-state hook. |
| Native AI / protection | `AttackGoals`, `CasterMovementGoal`, `LineOfFire` | Foreign navigation ownership, AI outside `GoalSelector`, actual projectiles/AoEs/chains/summons after release. |
| Data resolution | `LoadoutManager` selection and catalog publication/source-tier sections; parser entity/profession/spell/range/tuning section; support resolver | Full malformed-input surface, manifest loader implementation, every serializer and replacement permutation. |
| Resources / samples | Metadata and sample authoring index; built-in loadout inventory in config; schema usage in README | All ten sample packs' actual JSON, functions, recipes, advancements, loot, generated resources, and merged-pack behavior were **not** exhaustively parsed or run. |
| Documentation | README baseline/data sections; current changelog section; historical `Plan.md` opening; accepted ADR 0009; CustomNPCs development guidance; sample index | Entire older audits, every ADR, complete public-page text and all compatibility guides. |
| Tests | Build/JUnit infrastructure, test-tree inventory, CustomNPCs GameTest bodies for rebuild/activity and cast lifecycle; two exact production helpers | Full JUnit bodies and all Iron's/GameTests, real test execution, external CI evidence. |
| Commands / networking / items | Command contracts visible in documentation and the inspected script/action boundaries | Complete command permission tree, School Tome interaction ownership checks, client/server packet handling and all client-only class references remain unverified. No claim of a complete network-security audit. |
| Upstream | Version-labelled Iron's 3.16.3 source for native mob lifecycle, `AbstractSpell`, target helper and Blessing of Life; exact official compile/Luminous file identities | Source-to-release-binary equivalence and exact Recruits/Easy/CustomNPCs JAR APIs. |

This map intentionally does not invent a percentage of “repository audited.” Different files have very different behavioral importance, and source inspection is not runtime coverage.

## 3. Integration matrix and end-to-end assessment

### 3.1 Inventory and verification status

| Integration / adapter | Version / gate | What it actually supplies | Ownership boundary and verification status |
|---|---|---|---|
| Iron's Spells 'n Spellbooks | Optional loader dependency; accepted 3.15.x–3.16.x; compile/runtime/manifest distinctions in §1 | Registry access, spell lifecycle/effects, magic attributes/data, capability classification and pool generation | Magic NPCs owns foreign-NPC scheduling/accounting; Iron's owns spell implementation. Main code and selected upstream paths read; **no live compatibility certification**. |
| Iron's spell add-ons | No universal exact-version profile established by this audit | Generic registry/school inclusion plus config overrides, datapack manifests and namespace trust | No dedicated add-on behavior adapter was verified. Declared capability and namespace membership are not gameplay evidence. |
| Villager Recruits | Compile pin 1.15.2; loader accepts 1.15.0+; live integration toggle | Rank-based mana/level inputs, `shouldAttack` targeting, owner/recruit ally checks, order-sensitive movement policy | Recruits remains authority for orders, diplomacy and equipment AI. Source integration read; exact JAR/order interactions blocked. |
| Easy NPC Core | Project pin 7.11.0; range `[7.11,8.0)` | Framework adapter, spell objective, cast action, three conditions, state-change reaction and diagnostics | Core owns authored objectives and state. Objective returns the existing Magic NPCs casting goal rather than introducing an independent caster. Source read; ABI/runtime blocked. |
| Easy NPC configuration UI | Separately detected optional component | Optional authoring UI availability; it is not the core casting dependency | Do not require UI for server/core behavior. Full UI/client path not verified. |
| CustomNPCs GBPort Unofficial | Exact `1.20.1.20260711` bridge allowlist plus checksum-gated compile JAR | Factions/roles/jobs/orders, deferred AI repair, dialogue/script suspension hooks, script API/mailbox/events, diagnostics | CustomNPCs owns NPC construction/AI/script/inventory; Magic NPCs owns only its established casting state. Version facade exists, but MN-002 bypass remains. Binary/runtime blocked. |
| Generic owner/team | Vanilla `OwnableEntity` / scoreboard team; `protectOwners` | Owner UUID and sibling protection even when the owner entity is unavailable | Composes with dedicated adapters. Uses live UUID/team data, not a separate ownership database. Source read. |
| Generic raid ally | Vanilla `Raider` with current raid; `protectRaidAllies` | Same-raid protection | Uses same raid object, not “all raiders are allies.” Source read. |
| Generic sitting pet | `TamableAnimal`; `sittingPetsMayCast` | Blocks both combat and support while ordered to sit unless opted in | Independent of owner protection. Source read; detached parity still needs MN-003. |
| Generic loadout mobs | Registered entity type + applicable datapack/config selection | Can receive a casting goal and loadout, subject to selection/attribute/compatibility filters | Does not establish that a Brain, custom AI loop, flying navigator, or native spellcaster cooperates. |
| LUMINOUS: BEASTS | Development profile V1.2.7 / file 8165506 | Staging and casting-driver diagnostics, not a verified dedicated runtime adapter | Changelog explicitly says Phoenix/Witch Doctor compatibility is instrumented but **not runtime-verified**. A fallback driver is deliberately not implemented without evidence. |

Sources: [Adapter composition][adapters], [Recruits][recruits], [Easy initialization][easy-init], [Easy objective][easy-objective], [Easy action][easy-action], [Easy conditions][easy-conditions], [CustomNPCs gate][cn-gate], [CustomNPCs bridge][cn-init], [owner/team][owner], [raid][raid], [sitting pet][sitting], [manifest layers][support-resolver], [Luminous status][changelog].

The configuration inventory contains **eight** generic namespace switches: `guardvillagers`, `mca`, `minecolonies`, `easy_npc`, `customnpcs`, `humancompanions`, `morevillagers`, and `villagersplus`. These are opt-in loadout gates, not eight fully implemented behavioral adapters. The built-in loadout inventory names Recruits' recruit/bowman/crossbowman/captain and Guard Villagers' guard. No exact dependency version was established for the purely generic namespace entries. [Actual configuration declarations][config-inventory]

MCA's shared villager types, MineColonies' colony AI, and profession mods using vanilla villager types need special care: a namespace toggle or one entity type cannot express every role or brain behavior. The config already warns about MCA's broad entity-type coverage and MineColonies' citizen AI. Preserve these cautions rather than advertising universal NPC compatibility. [Configuration cautions][config-inventory]

### 3.2 Iron's lifecycle: what is already correct and what remains exposed

The current session obtains per-caster effective timing, prepares cast data, invokes Iron's precast checks, initiates casting, runs precast/tick/effect/completion hooks, and clears session-owned data. LONG and CONTINUOUS are not simply reduced to an immediate `onCast`. The current continuous path uses the native-style ten-tick effect cadence, and a same-entity-tick guard prevents double advancement. The inspected 3.16.3-labelled upstream source uses corresponding lifecycle stages. The two pure timing resolvers passed the isolated ordinary-range assertions in §7. **Do not resurrect historical “no continuous driver” or “shared cast-time mutation” findings as current defects.** [Session][session] · [Session tick/finish][session-end] · [Native mob lifecycle][up-mob] · [Effective timing and hooks][up-abstract] · [Cast-time resolver][cast-time]

The upstream comparison is pinned to commit `cae63a6999e24ed3deaa012ecb8c262e0e377816`, whose properties identify Minecraft 1.20.1 and mod version 3.16.3. It is not an assertion that those source bytes are identical to the production JAR. The exact 3.15.0 compile and 3.16.1 development runtime binaries still need their own lifecycle/API verification. [Upstream properties][up-version]

| Spell family | Current path | Required verification beyond registration |
|---|---|---|
| INSTANT | No native charge duration; entry/session lifecycle still applies | One effect, one cost/cooldown, valid aim and permission on the initiating tick. |
| LONG / charge | Effective duration and optional per-entry override | Release tick, interruption, modifiers, wind-up plus duration, no shared-object changes. |
| CONTINUOUS / channelled | Per-tick hook plus periodic effect emission | Pulse count, stop conditions and cleanup. Short explicit channel durations must be tested for useful effects; a completed lifecycle need not have emitted a pulse. |
| Projectiles / forward effects | Facing/look control plus spell-owned projectile | Actual release direction, moving target, foreign look controller and downstream ownership. |
| Target-entity spells | `TargetEntityCastData` plus native precast helper | Native helper can raycast and replace target data; requested target and actual effect target must remain consistent with safety policy. |
| Areas / ground effects | Capability-selected safety shape plus spell implementation | Actual effect center, size, repeated damage, ground placement and collateral relationships. |
| Summons | Manifest allows selected summon spells | Summoner attribution, summoned entity ownership, faction/diplomacy, lifetime and secondary attacks. A SUMMON verdict does not certify these. |
| Movement / special preparation | Several spells deliberately rejected | Native mob-specific cast data and destination rules must be implemented and tested per spell before changing verdict. |
| Player-only / utility | Deliberate unsupported categories | Keep refusal; never introduce fake players or broadly relabel these as DIRECT. |
| Support | Existing health-driven role plus native spell behavior | Self versus ally intent, no hostile fallback, useful buff conditions and actual healing recipient. |

The cast-data classification is not a complete effect-geometry model. `TARGET_ENTITY` says how a spell obtains a target, not that its subsequent effects are narrow or ally-safe. Namespace-trusted `ADDON_DEFAULT` uses weak defaults and opportunistic target data; it must remain visibly weaker than a version-specific reviewed spell profile. [Spell capability handling][spellcompat] · [Built-in verdicts][manifest] · [Resolver provenance][support-resolver]

`ManifestReconciler` compares registered and listed **Iron's namespace IDs**. It does not validate damage, targeting, support usefulness, summons, every add-on namespace, or binary compatibility throughout the accepted version range. Its result is a registry reconciliation, not a behavioral score. [Implementation][manifest-reconcile]

### 3.3 Recruits: diplomacy, progression and native combat

The adapter reads rank and orders live, uses `shouldAttack(target)` for direct targeting, and distinguishes passive combat behavior from allowed support. Movement is constrained for march/formation/hold/follow states. Existing mana scaling is refreshed through the handler, and spell-level calculation uses adapter progression. These are implemented capabilities, not backlog features. [Recruits adapter][recruits] · [Rank/regen handler][handler-school] · [Goal level/timing resolution][goal-resolvers]

Verification must include ownership transfer, owner logout, neutral versus allied players, same/different teams, passive orders, holding position, following, formation, mounts, and rank increases both **before** and **after** caster eligibility. Direct `shouldAttack` permission and the collateral `isAlly` predicate are distinct code paths: verify that diplomacy-protected non-targets cannot be hit merely because they are not represented by the generic owner/team checks. Do not assume every “must not attack” entity is intentionally an ally; test the project's protection policy explicitly. [Recruits predicates][recruits] · [Generic relationship policy][owner] · [Collateral protection][line-fire]

Native ranged/melee behavior must be tested in all three modes. The existing suppression wrapper is preferable to deleting foreign goals and later rebuilding them from cached class names. `coexist` intentionally retains native attacks; `yield` can intentionally produce few or no combat casts while native attacks remain active; `suppress` must restore only goals that Magic NPCs actually wrapped and that still belong to the relevant selector. Rank reconciliation must preserve earned XP, school choice, gear, mana balance and retained cooldowns. [Suppression implementation][attack-goals] · [Movement ADR][adr9]

### 3.4 Easy NPC: core and authoring integration

Core integration is not contingent on installing a client configuration UI. The code provides `magicnpcs:cast_spell` as an objective/action surface and `has_school`, `can_cast`, and `has_mana` conditions. The objective hands Easy NPC the existing owned casting goal and has a rebuild reentrancy guard; it should not be replaced with another casting scheduler. [Core initialization][easy-init] · [Objective][easy-objective] · [Conditions][easy-conditions]

The action wrapper performs some checks, but it then calls the under-validated detached driver. Missing or invalid target selection can reach that driver as `null`; a direct/aimed spell may still start. This must be an explicit action outcome rather than an accidental “cast at whatever the NPC faces.” The state listener currently reconciles synchronously, which needs exact Core 7.11.0 event-order verification before calling it safe during objective reconstruction. Listener removal at shutdown is a separate source-level defect, MN-010. [Action path][easy-action] · [State listener][easy-state] · [Lifecycle registration][easy-casting]

Preserve the distinction between the existing general `can_cast` condition and a hypothetical spell-specific readiness query. A condition with no spell ID cannot promise that a particular spell is off cooldown, affordable, or has a valid recipient. Better diagnostics must clarify that distinction rather than silently changing old dialogue behavior. [Condition definitions][easy-conditions]

### 3.5 CustomNPCs: strict pin, repair and scripts

The exact supported-build check is real and should be retained. Typed integration classes are reached through a neutral reflective facade. Deferred repair reuses the existing UUID-deduplicated reconcile queue rather than modifying goal selectors directly from the CustomNPCs update event. The script bridge consumes mailbox operations and uses an in-flight guard around emitted script triggers. These are useful existing protections. [Version facade][cn-gate] · [Repair][cn-repair] · [Script/mailbox bridge][cn-script]

However, the gate is not a universal eligibility barrier; a generic loadout with the CustomNPCs namespace switch enabled, or an existing/manual school path, can avoid the dedicated adapter's safeguards when the bridge is unsupported or inactive. Partial repair can also be accepted as unchanged, and a removed in-progress goal may require session cleanup beyond simply reinstalling a new goal. Those are distinct concerns and must not be “fixed” by removing the version check, creating another capability, or installing new target goals. [Eligibility/reconciliation][reconciler] · [Namespace and bridge controls][config-gates] · [AI repair][cn-repair]

Mailbox processing occurs in the event bridge after **requesting** repair, not necessarily after repair has completed on the Forge queue. API responses must not imply that an NPC is repaired simply because a repair request was enqueued. Test direct script calls and mailbox calls separately: the mailbox's in-flight guard does not make the detached cast driver's collection safe against all synchronous callbacks. [Event order][cn-event] · [Mailbox execution][cn-script] · [Direct API][cn-api] · [Detached driver][detached]

### 3.6 Generic mobs, flying entities and native spellcasters

The generic ownership/raid/sitting adapters use vanilla contracts and compose conservatively. In contrast, native AI compatibility is behavioral: a successfully installed goal may never be evaluated by a custom driver; a flying mob may not use the pathfinding/movement assumptions of `CasterMovementGoal`; a large or multipart hitbox can expose geometric weaknesses; and a native Iron's caster may already own its own casting data. [Generic policies][owner] · [Movement constraints][movement] · [Goal instrumentation][changelog]

Keep Luminous' current “instrumented, unverified” status until a real profile demonstrates the failure. Do not attach the detached driver as a speculative universal fallback: that would risk two drivers once native goal evaluation resumes. A future fallback needs a demonstrated host lifecycle, one-driver ownership, and handover tests, not just a stale heartbeat threshold. [Current explicit Luminous limitation][changelog]


## 4. Prioritized findings

### MN-001 — The compile-version label disagrees with the pinned artifact

**Status:** Confirmed by project configuration and exact official file identity. **Priority:** P2, foundational verification. **Effort:** S.

**Evidence.** `gradle.properties` records the compile label as 3.16.3, but `build.gradle` resolves Curse artifact `855414:7402504`. That file is published as 3.15.0. The 3.16.1 development runtime difference is separate and may be deliberate. [Properties, lines 1–61][baseline] · [Dependency declarations][build] · [Exact file][iron-file]

**Expected / actual.** Build provenance should distinguish the actual compile artifact, tested runtime, accepted range, and manifest reference. Currently one of those labels is demonstrably inaccurate; a successful compile would not test the version named by that label.

**Implementation task.** Add a verification task that reads the resolved JAR's mod metadata and records its version and SHA-256 alongside the declared label. Fail a provenance check on disagreement. Decide explicitly whether the intended compatibility floor is 3.15.0; correcting the label is not authorization to upgrade the dependency. Keep the separate 3.16.1 and 3.16.3 profiles only with accurately named results.

**Preserve / migration.** Preserve current dependency choices until their intent is resolved. No save migration. Do not narrow or widen supported ranges just to make a test green.

**Definition of done.** The build report names actual artifacts; release evidence covers compile-floor/runtime/manifest-reference distinctions; a deliberately incorrect label fails the new verification test. Validate transitive dependencies from each tested JAR's metadata, not from another version's documentation.

### MN-002 — The CustomNPCs build gate does not cover generic/manual casting eligibility

**Status:** Confirmed gate-coverage defect in source; resulting gameplay must be tested with the exact fixture. **Priority:** P1. **Effort:** M.

**Failing path.** Unsupported build → `CustomNpcsCompat.init` refuses typed bridge → ordinary `CasterReconciler.desiredFor` can still resolve a generic datapack loadout when `compat.customnpcs` is enabled, or process a manual/stored school → normal installation is attempted without the dedicated CustomNPCs adapter's faction, activity and repair protections. The namespace toggle exists and is normally conservative; the problem is that it is not the exact-build gate. This is **not** a claim that every unsupported NPC automatically casts with default configuration. [Version facade][cn-gate] · [`desiredFor` and reconciliation, lines 1–300][reconciler] · [Manual school entry points, lines 296–480][handler-school] · [Config gates, lines 1000–1320][config-gates]

**Expected / actual.** Unsupported CustomNPCs must remain ineligible through every Magic NPCs entry point. Current eligibility can fall back to generic behavior; actual installation also depends on loadout/attribute conditions.

**Implementation task.** Add a neutral framework-eligibility decision at the shared installation/request boundary, before modifying assignments, equipment, attributes or cast state. Recognize the known CustomNPCs entity family without loading unsupported typed classes. Apply the exact supported-build and active-bridge policy to automatic assignment, manual school changes, external cast requests and reconciliation. On degradation/disable, cancel owned sessions and remove owned goals through the safe queue; preserve the saved author assignment for a later supported installation.

**Preserve / migration.** Keep `1.20.1.20260711` and its existing hash. No unsafe-fallback option, fake player, new target goal, or second capability. Do not erase existing school/loadout NBT merely because a dependency is temporarily unsupported.

**Definition of done.** With bridge unsupported, probe-failed, disabled or faulted, each entry point returns a precise refusal and installs no Magic NPCs goals. Repeat with the namespace switch on, an existing school tag, and an explicit datapack. Supported-build behavior still passes its positive control.

### MN-003 — External casting lacks a complete shared policy and input contract

**Status:** Confirmed source-level validation gaps. **Priority:** P1. **Effort:** M–L because multiple existing callers must converge.

**Failing path.** `CustomNpcsScriptApiIrons.cast` → `DetachedCastDriver.cast` → `MobCastSession.begin`. The CustomNPCs API does not apply all master/integration/activity/relationship checks. Easy NPC's action applies some checks first but still leaves differences. Detached continuation checks caster death/removal and spell-specific stop logic, not the automatic goal's live target/relationship/LOS policy. Its level is also passed to spell hooks without the automatic goal's effective-level treatment. The specific CustomNPCs `canCast` query checks a smaller subset than actual compatibility/readiness. [CustomNPCs API][cn-api] · [Easy action][easy-action] · [Detached driver][detached] · [Automatic checks, lines 311–650][goal-tick] · [Session preparation, lines 1–260][session]

**Reproduction to implement.** Give an eligible scripted NPC sufficient mana. Request a spell after a relevant global/integration disable or scripted suspension; request an offensive spell at a friendly target; start a LONG cast and change the target's relationship; submit negative and excessively large levels. Compare with the same request through automatic casting. Exact outcomes depend on spell-specific hooks, but the missing shared checks are visible in the call path.

**Implementation task.** Extract a side-effect-free request validator and a shared commit/continuation policy beside the existing session. Normalize the spell, validate level bounds, capability, configured enablement, required target identity/world/liveness, mandatory framework gates, mana and canonical cooldown. Verify `spell.isEnabled()` for explicit/scripted paths as well as generated pools. Use the same result vocabulary for API readiness and actual requests. Keep source-specific authoring semantics explicit: an authored scripted action need not use automatic random selection or wind-up, and an AI-only eligibility rule should not silently become a universal scripting restriction.

**Preserve / migration.** Preserve one session implementation and the current NPC economy. No hidden exemptions from hard version gates or mandatory safety. Do not break existing general Easy NPC `can_cast` semantics by pretending it is a spell-specific query. Add explicit reason codes and document any intentional action-policy changes; no save-format change is necessary.

**Definition of done.** Equivalent mandatory checks have the same outcome across automatic, Easy action, CustomNPCs direct API and mailbox paths. Refusal before acceptance spends no mana/cooldown. Continuation responds to relevant target/relationship changes. Extreme levels never reach a spell unbounded. A diagnostic/readiness success cannot contradict a deterministic gate checked immediately by the actual request.

### MN-004 — Hook exceptions are not a transaction-safe terminal path

**Status:** Confirmed failure-handling gap; fault-injection trigger, not an observed crash in a particular installed spell. **Priority:** P1. **Effort:** M.

**Proof.** `MobCastSession.begin/prepare` mutate cast data and initialization state around external hooks without a complete rollback boundary. A precast exception can escape before the caller owns a usable session handle. Tick/effect hooks can escape. Completion cleanup uses `finally`, but terminal session state is assigned after the completion call; if that hook throws, cleaned data and a still-running session enum can disagree. Boundary catches in a script API do not repair this internal state. [Begin/prepare, lines 1–260][session] · [Tick/finish, lines 261–432][session-end] · [API catch boundary][cn-api]

**Expected / actual.** Any accepted session reaches one terminal state and relinquishes its owned data. A rejected preparation cannot strand `isCasting`/channel state. Current exceptional paths do not guarantee those conditions.

**Implementation task.** Track preparation, acceptance, effect-started and terminal transitions explicitly within the existing session. Make cleanup idempotent and set terminal ownership before invoking externally reentrant terminal notifications. Isolate exceptions at the per-session boundary so one failed spell cannot prevent other detached sessions or reconciliation from advancing. Run spell completion cleanup at most once when appropriate to the stage, then clear only data that the session owns.

**Accounting rule.** Before acceptance: no charge. After acceptance: retain the existing interruption policy. After entering an effect hook, do **not** automatically refund and retry: the spell may have spawned an effect before throwing. Log one classified failure with spell/version/stage, not a warning every tick.

**Definition of done.** Inject faults separately in precast checks, precast hook, tick, effect, completion and event listeners. Assert terminal state, data cleanup, one charge at most, no duplicate effect retry, correct next-cast availability and continued processing of other NPCs. No new capability or broad exception swallowing.

### MN-005 — Alias spell IDs create separate cooldown identities

**Status:** Confirmed source-level identity mismatch. **Priority:** P2. **Effort:** S–M.

**Proof / reproduction.** The resolver supports bare/`minecraft:` aliases for Iron's spells, as documented. The detached driver and goal cooldown bookkeeping can use the requested/loadout ID instead of the resolved spell's canonical ID. With enough mana, cast a spell using its canonical ID, wait for that session to finish but not for its cooldown, then request the same spell through its bare alias. The same spell resolves while a different cooldown key is consulted. Test the reverse direction and AI-to-script crossover. [Alias resolution][irons-bridge] · [Cooldown use][detached] · [Managed cooldown map][state] · [Documented aliases, README lines 176–210][readme-data]

**Implementation task.** Normalize to `spell.getSpellResource()` before all readiness checks, cooldown reads/writes, retained-key calculations, duplicate-entry comparison and cast-event payloads. Preserve the original author text only for diagnostics. Collapse duplicate aliases consistently without multiplying cast opportunities.

**Preserve / migration.** Keep accepted legacy aliases. Existing cooldown state is in-memory; when normalizing live keys, retain the longest remaining deadline for equivalent IDs. Do not reset a cooldown simply because a loadout spelling changes.

**Definition of done.** Canonical/alias inputs share cooldown and effective overrides in both directions, across all casting sources and a no-op reload. Unknown aliases still produce an actionable error.

**Fixture qualification.** First verify that both identifiers pass the normal eligibility layers in the chosen profile. If an earlier capability/filter gate rejects the alias, record that profile as preventing the bypass, not as a reproduced cooldown exploit. The canonical-key inconsistency still needs correction at the shared boundary; do not disable safety to manufacture a positive reproduction.

### MN-006 — Generated school spell subsets are resampled during reconciliation

**Status:** Confirmed source-level state-instability defect. **Priority:** P1. **Effort:** M.

**Proof.** `SchoolSpellPool.buildLoadout` samples candidates using `mob.getRandom()` each time it builds the loadout. `CasterReconciler` builds the desired school loadout again during reconciliation. With a candidate pool larger than `spellsPerSchool`, unchanged reconciliation can produce different spell IDs/order and a different content hash. Replacement then retains cooldowns only for IDs in the new loadout. This is separate from the persistent school/caster-chance roll, which may remain unchanged. [Sampling, lines 33–159][school-pool] · [Desired/reconcile path, lines 1–300][reconciler] · [Replacement and cooldown retention, lines 298–580][reconciler-mid]

**Reproduction to implement.** Use a school with more eligible spells than the configured subset, a fixed NPC and unchanged config/catalog. Record assigned spell IDs, current mana, cooldowns and gear. Reconcile repeatedly, including the villager recheck path. The subset must not change; an expired/removed-then-returning spell must not obtain an artificial cooldown reset.

**Implementation task.** Preserve sampled canonical spell IDs in the existing assignment/persistent-data model, or use a stable per-NPC assignment seed plus a carefully defined candidate-set fingerprint. Preserve valid choices when pools change; replace only invalid/missing slots. Avoid unrelated entity RNG consumption during a no-op query/reconcile. Validate a manual assignment without independently resampling it again during installation.

**Preserve / migration.** Never reroll the caster-chance result, earned rank, chosen school or gear latch. For existing loaded casters, capture the current valid selection when possible; initialize an absent selection once. Explicit reroll/reset commands must remain the only intentional full reroll path.

**Definition of done.** Stable canonical subset across 100 reconciliations, no-op reload, chunk unload/reload and restart under unchanged inputs. A removed spell is minimally repaired; unaffected cooldowns and selections survive. Test support-slot requirements without allowing them to force a fresh full sample.

### MN-007 — Support-role target binding can reject idle support or heal the hostile target

**Status:** Confirmed source-level behavior, cross-checked against version-labelled Iron's target/heal implementation; in-game reproduction pending. **Priority:** P1. **Effort:** M.

**Proof.** Default support IDs include `blessing_of_life`, `healing_circle` and `ice_block`, while the built-in manifest classifies them as target-entity spells. Idle SUPPORT selection passes no hostile target, so the session rejects required-target preparation. During combat, support selection can carry the mob's hostile target into preparation. The current facing snap aims at that target. In the inspected Iron's 3.16.3 source, Blessing of Life's precast helper raycasts and replaces target data; its effect heals the resolved target. That path contains no friendly-recipient check. Thus simply classifying it as SUPPORT does not make it self-cast. [Default support list, lines 680–742][config-support] · [Role construction, lines 161–283][school-role] · [Goal selection][goal] · [Session preparation][session] · [Blessing effect][up-heal] · [Native target helper, lines 655–700][up-target]

**Expected / actual.** Existing SUPPORT documentation promises self-casting when hurt. Some target-dependent entries cannot satisfy that idle intent and may instead act on an enemy in combat. Native `heal` and other genuinely self-usable support spells are not implicated by this finding.

**Implementation task.** Separate cast intent/recipient from the hostile combat target. Add reviewed per-spell support eligibility and target strategy rather than deriving it only from keywords or a support-ID list. Validate the **actual prepared target data** after native precast preparation. Until a correct self/ally strategy exists for a target-raycast spell, exclude it from generated self-support and explain why; do not reclassify a healing spell as an attack to hide the problem.

**Preserve / migration.** Do not bypass native precast checks or assume passing the caster as `target` makes a raycast hit itself. Keep existing support timing, thresholds and out-of-combat toggle. Repair affected generated slots minimally under MN-006; warn on explicit incompatible support entries.

**Definition of done.** A wounded idle caster with each reviewed support spell receives the intended effect without a hostile target. In combat, the hostile target never receives a friendly effect through SUPPORT fallback. A raycast interception cannot silently change the authorized recipient. Include healing, cleansing, shields and target-dependent buffs separately.

### MN-008 — Reconciliation can treat an incomplete or duplicated owned-goal set as unchanged

**Status:** Confirmed source-level convergence gap. **Priority:** P1. **Effort:** M.

**Proof.** Repair detects missing/incorrect owned goal shape, but reconciliation's unchanged decision depends on finding an installed casting goal and matching desired identity/hash/generation. That does not establish that the movement goal exists or that exactly one casting goal exists. A fixed datapack loadout isolates this from MN-006: remove only the owned movement goal, request repair, and the unchanged branch can leave the damage unrepaired. Removal logic must also handle residual owned movement state when no spell goal remains. [Reconcile/unchanged path, lines 1–300][reconciler] · [Install/remove helpers, lines 298–580][reconciler-mid] · [CustomNPCs integrity request][cn-repair]

**Expected / actual.** Reconcile should converge to the complete desired owned state. Current “same loadout” is not sufficient evidence of structural integrity.

**Implementation task.** Include owned-goal shape in the unchanged predicate. Repair only missing or extra Magic NPCs goals; do not force a full reinstall when a single owned movement goal is absent. Handle desired-none cleanup even with no surviving spell goal. Keep lifecycle cleanup for a genuinely replaced casting goal separate from structural repair. Queue selector mutations safely.

**Preserve / migration.** No mana initialization reset, cooldown clearing, resampling, gear regrant, foreign-goal changes or selector replacement. No disk migration.

**Definition of done.** With a fixed resolved loadout, test missing movement, missing spell, duplicate spell, duplicate movement, and both missing; repeated repair must converge to exactly the intended owned set. Test removal while only an owned movement goal survives. Assert unchanged economics and foreign selector entries.

### MN-009 — Villager self-defense cleanup can remove foreign `HurtByTargetGoal`s

**Status:** Confirmed source-level ownership violation. **Priority:** P1. **Effort:** S–M.

**Proof.** Self-defense installation can treat any existing `HurtByTargetGoal` as satisfying/granting the feature, while removal matches the class rather than an owned instance/subclass. Clearing or changing the school can therefore remove a target goal installed by another mod. [Self-defense helpers in `CasterReconciler`, inspected lines 580–719][reconciler-end] · [`clearSchool`, lines 296–480][handler-school]

**Reproduction to implement.** Install a sentinel foreign `HurtByTargetGoal` on a villager. Enable/assign and then clear Magic NPCs self-defense/school behavior. Compare exact foreign goal identity, priority and membership before and after; no Magic NPCs-owned self-defense goal need have been added for this test to be meaningful.

**Implementation task.** Use a dedicated owned goal type or an explicit identity handle for the self-defense goal Magic NPCs creates. Observing an existing foreign target goal must not confer removal ownership. Remove only the owned goal; reset only the corresponding owned marker.

**Preserve / migration.** Retain native/third-party retaliation and all CustomNPCs target selectors. Existing ambiguous in-memory markers must be handled conservatively: lack of ownership proof means do not delete the foreign goal. No save migration is required merely to narrow cleanup.

**Definition of done.** A school clear/disable/reload removes exactly the owned self-defense goal and leaves every foreign target goal untouched, including subclasses and multiple pre-existing retaliation goals.

### MN-010 — Integration shutdown has no matching second-world activation path

**Status:** Confirmed source-level server-lifecycle gap; integrated-server reproduction pending. **Priority:** P1. **Effort:** M.

**Proof.** `MagicNpcs` initializes the bridges during mod construction and calls shutdown on `ServerStoppedEvent`. CustomNPCs shutdown unregisters its event bridge, clears script/API/global state and resets repair state; Easy NPC casting shutdown unregisters the state listener. No corresponding per-server restart initialization was found. A source search for `CustomNpcsCompat.init` found the constructor call and documentation, not a server-start call. [Mod initialization/shutdown][main] · [CustomNPCs init/shutdown][cn-init] · [Easy NPC listener lifecycle][easy-casting] · [Documentation's server-start claim][cn-dev]

**Expected / actual.** Opening a second integrated world in the same client process should provide the same integration behavior as the first. Components torn down at the first stop are not explicitly reactivated by the inspected code. Re-running the entire mod-construction registration would introduce duplicates and is not an acceptable fix.

**Implementation task.** Separate one-time adapter/registry registration from per-server activation and teardown. Reattach server-lifetime listeners/script state exactly once at the correct lifecycle event, and deactivate idempotently. During shutdown, first reject new cast requests, then terminate owned sessions, then remove listeners and clear state. Clearing event guards before cancellation can suppress expected terminal reporting; cleanup callbacks must not recreate state after the final clear.

**Preserve / migration.** No duplicate adapters, objective/action registry entries or listeners. Preserve per-world separation; never carry NPC references or active sessions into the next world.

**Definition of done.** Run world A → title screen → world B → title screen → world A in one JVM, including normal exit and an interrupted cast. Check listener counts, working state changes, script API/global availability, truthful status, empty old queues and no doubled events. Repeat a dedicated-server lifecycle test where the harness permits it.

### MN-011 — The segment-to-hitbox distance formula can miss an intersecting protected entity

**Status:** Confirmed by source analysis and an executed independent arithmetic reproduction. **Priority:** P1. **Effort:** S–M.

**Proof.** `LineOfFire.distanceToSegmentSqr` projects the AABB's center onto the segment and measures that point's distance to the box. That is not the minimum distance between the entire segment and the box. A different point on the segment can intersect the box even though the center-projected point is outside it. [Implementation: `core/util/LineOfFire.java`, `distanceToSegmentSqr`][line-fire]

Executed counterexample:

```text
segment a = (-5, -4, 0.5), b = (15, 14, 0.5)
AABB min = (0, 0, 0), max = (10, 1, 1)
safety radius = 1.5; squared radius = 2.25
current formula: squared distance = 3.944942156832818 -> CLEAR
independent exact witness: t = 13/50
point a + t(b-a) = (1/5, 17/25, 1/2), strictly inside the AABB
true minimum squared distance = 0 -> MUST BLOCK
```

This was a mathematical reproduction, not a GameTest or live entity hit. The witness does not depend on the production approximation, making it a useful independent oracle.

**Implementation task.** Replace the center projection with a correct segment–AABB distance calculation. A conservative segment intersection against an inflated AABB is a simpler alternative only if its extra corner false positives are an explicit policy choice. Add exact intersection and independently checked distance cases, including degenerate segments, diagonals, large/flat/tall boxes and moving/multipart entities where relevant.

**Preserve / migration.** Keep the existing protection config and scan reuse. Do not replace entity boxes with centers/feet. No data migration.

**Definition of done.** The certificate above blocks; nonintersecting reference cases remain correct; property tests compare with an independent oracle; actual large-hitbox integration fixtures confirm scan inclusion as well as geometry.

### MN-012 — Collateral/focus checks can become stale between selection and release

**Status:** Confirmed source-level validation window. **Priority:** P1. **Effort:** M; share work with MN-003.

**Proof.** Automatic selection performs the line-of-fire scan and per-entry requirements, but subsequent `windupTargetValid`/session continuation do not repeat every such check. In particular, a protected entity can enter the corridor after selection. The existing AI path **does** recheck target liveness, maximum range, relationship and configured LOS; this finding must not be summarized as “wind-up never validates its target.” Per-entry held-item changes also need a commit-time check. [Selection, lines 1–310][goal] · [Wind-up/continuation, lines 311–650][goal-tick] · [Line-of-fire scan][line-fire]

**Reproduction to implement.** Select a LONG projectile spell with a clear lane, then move an ally into it during wind-up or native charge. Separately remove the per-entry required focus before acceptance. Compare the actual release with the latest policy inputs.

**Implementation task.** Revalidate mandatory request conditions before acceptance and validate live effect safety at release/continuous emission boundaries. Reuse a bounded scan where possible, but never reuse a snapshot after its documented validity window. Separate “target still legal” from “collateral path still clear.” Integrate with the shared policy rather than adding one-off checks to only the AI goal.

**Preserve / migration.** Do not stretch Iron's effective duration or silently slow continuous pulses to avoid checks. Interrupted accepted casts follow the established cost policy. Existing projectiles already in flight cannot be retroactively made safe by cancelling the session.

**Definition of done.** Ally entry, focus removal, LOS change and relationship change have explicit tested outcomes at each cast phase. Add a control showing that unrelated configuration changes do not spuriously cancel safe casts. Document the remaining in-flight/AoE/summon limitations.

### MN-013 — Synchronous completion callbacks can invalidate the detached-driver iterator

**Status:** Confirmed source-level reentrancy hazard; Java collection mechanism reproduced in isolation. **Priority:** P1. **Effort:** M.

**Proof.** `DetachedCastDriver.tickAll` iterates the active `ArrayList`. A session posts a synchronous completion event through `MagicNpcEvents`; a listener can request another detached cast, which adds to the same list. The outer iterator's subsequent removal/advance can throw `ConcurrentModificationException`. The CustomNPCs script trigger guard does not protect arbitrary Forge listeners or all direct API requests. [Detached list/tick loop][detached] · [Synchronous event order, lines 90–144][events] · [Script publication][cn-script]

The audit executed the equivalent `ArrayList` iterator → callback addition → iterator removal sequence and reproduced the exception. It did **not** run the actual Forge event path.

**Implementation task.** Use a pending-request/addition queue or a stable tick snapshot with explicitly staged structural mutations. New casts requested during a terminal callback must be processed at a defined boundary, not inserted into the collection currently being iterated. Reject additions during shutdown. Complete cleanup and old-session ownership before allowing a callback-created replacement session to use the same caster.

**Preserve / migration.** Do not disable lifecycle events, block all legitimate scripted cast chaining, or replace the list with a synchronized collection and assume that solves reentrancy. Keep exactly-once accounting; no save migration.

**Definition of done.** A completion listener starts a different spell on the same caster and another caster; a cancellation listener requests a cast; shutdown callbacks also attempt one. No iterator failure, stale cleanup of the new cast, duplicate advancement, lost event or unbounded recursive chain occurs.

### MN-014 — Catalog generation alone forces replacement on an unchanged reload

**Status:** Confirmed source-level no-op reconciliation mismatch. **Priority:** P2, but fix before claiming uninterrupted no-op reloads. **Effort:** M.

**Proof.** `LoadoutManager` increments catalog generation on publication. The reconciler's unchanged check requires matching generation as well as identity/hash. Consequently even a fixed datapack loadout with identical effective content can take the replacement path after `/reload`. Economics may be preserved by managed state, but goal identity and an active cast are not “left completely alone” as the README promises. [Generation publication, lines 290–353][loadout-publish] · [Unchanged decision, lines 1–300][reconciler] · [README promise, lines 10–20][readme]

**Implementation task.** Distinguish resource/catalog generation from an NPC's effective behavior fingerprint. A new generation must trigger reconsideration, not automatically force reinstall. Include relevant spell verdicts, configuration, equipment policy and owned-goal integrity in the change decision; do not fix this by blindly ignoring generation and thereby missing real config/manifest changes.

**Preserve / migration.** No-op reload preserves goal/session identity, mana, deadlines, assignments and gear. A genuinely changed/disabled spell may require a safe cancellation. No disk migration.

**Definition of done.** With a fixed loadout, reload during LONG and CONTINUOUS casts without edits: no regrant/restart/reroll or lost cooldown. Then change a relevant capability, focus rule, native mode and enabled state independently: each meaningful change is applied once.

### MN-015 — Positive integration tests can succeed without exercising the positive path

**Status:** Confirmed test-design weakness. **Priority:** P1 release-validation blocker. **Effort:** M.

**Proof.** CustomNPCs GameTests are `required=false` and explicitly call `helper.succeed()` when dependencies or a spawnable NPC are unavailable. The rebuild test counts casting goals, not successful post-rebuild spell behavior, movement-goal integrity or resource preservation. The accepted-cast lifecycle test has a branch for a refused start, so its name does not establish that a cast actually started. These patterns can be useful for a developer's absent-mod smoke profile, but cannot certify the installed integration. [GameTest gates/rebuild assertions, lines 39–174][cn-tests] · [Cast tests, lines 231–300][cn-tests-casts] · [Documented release gate][cn-dev]

**Implementation task.** Separate absent-mod smoke tests from required positive integration profiles. A selected positive profile must fail its preflight when the exact dependency or entity is unavailable. Report executed, skipped and failed counts separately. Required positive tests must assert the actual casting/repair state and effect they are named for; do not accept “refused” as a successful cast scenario.

**Preserve / migration.** Keep easy offline development and optional dependencies. Do not make every absent-mod test a failure in the base profile. This is a harness/reporting change, not a mandatory runtime dependency.

**Definition of done.** Intentionally remove a required JAR, break NPC spawning and force a precast refusal: the positive profile must fail for the correct reason. A repair test observes a real cast before and after reconstruction, unchanged resource state, one owned cast goal, expected owned movement state and untouched foreign targets. Record actual test names and assertions, not just a zero exit code.

### MN-016 — Documentation mixes obsolete plans, current intent and unverified support

**Status:** Confirmed documentation inconsistencies. **Priority:** P2. **Effort:** S–M.

The historical root `Plan.md` recommends replacing resource/casting structures and introducing a new capability, while accepted later ADRs explicitly preserve the current selection/movement boundary. The config's generated-school cast-type comment still says no mob continuous loop exists, despite the current session implementation. The CustomNPCs development guide describes initialization “on server start,” while the inspected call is in mod construction. README's unchanged-loadout promise is stronger than current generation-based replacement. [Historical plan, lines 1–85][plan] · [Accepted ADR][adr9] · [Stale cast-type comment, lines 680–742][config-support] · [Actual session][session-end] · [Lifecycle claim][cn-dev] · [Current initialization][main]

**Implementation task.** Mark historical plans and audit reports as historical, with an explicit pointer to current accepted decisions and the code baseline. Distinguish loader-accepted, compile-tested, runtime-tested, manifest-classified and namespace-trusted. State actual reload/unload persistence semantics. Explain that continuous spells may remain excluded from generated pools by conservative default even though the lifecycle supports them. Keep Luminous' explicit unverified status and avoid turning heuristic audit output into a certified spell list.

**Definition of done.** The contributor entry point cannot plausibly instruct a coding agent to resurrect the rejected rewrite. Every advertised integration has an exact version/evidence status; examples and test commands match the actual project; stale comments are removed or corrected without altering intended defaults.

### MN-017 — Large positive cooldown multipliers can wrap to the minimum cooldown

**Status:** Confirmed by execution of the unchanged, blob-hash-verified production helper. **Priority:** P2. **Effort:** S–M.

**Proof.** `CooldownResolver.resolve` rounds to a `long`, casts to `int`, then applies the floor. The inspected parser accepts a positive per-entry multiplier without an upper-bound check at that read. A large result can wrap negative and become the minimum rather than a long cooldown. [Resolver, lines 25–44][cooldown] · [Parser tuning section, lines 345–380][parser]

```text
CooldownResolver.resolve(null, 30000000.0, 1.0, 100, 20)
mathematical scaled cooldown: 3,000,000,000 ticks
actual result executed in this audit: 20 ticks
```

**Expected / actual.** An excessive author value should be rejected clearly or handled by a documented safe bound, never interpreted as a fast cast.

**Implementation task.** Validate finite and supported duration/multiplier ranges in the parser and final runtime resolver; use saturating arithmetic or reject overflow before narrowing. Review cooldown deadline addition/comparison as part of the fix so saturating to `Integer.MAX_VALUE` does not merely move the overflow into the deadline. Keep diagnostics specific to the offending file/entry.

**Preserve / migration.** Ordinary cooldown precedence and rounding must remain unchanged. Existing pathological values should produce a visible warning/error and documented fallback, not silently faster spells. No broad save migration.

**Definition of done.** Ordinary tests still pass. Add boundary, huge finite, overflow, non-finite and deadline-wrap cases. Increasing a positive multiplier within the valid domain must never shorten the result.

### 4.18 Likely issues and incomplete verification: do not present these as reproduced bugs

| ID | Hypothesis / incomplete area | Evidence and exact next step |
|---|---|---|
| MN-R01 | Whole-selector reconstruction may orphan an in-progress session if the host removes goals without invoking their normal stop path. | Session ownership lives in the old goal, while repair can install a new one. Inspect the **pinned** CustomNPCs rebuild bytecode and reconstruct during LONG/CONTINUOUS casting. Assert old-session cleanup before repair and no permanent `MagicData.isCasting`. Do not assume host `removeAllGoals` semantics. [Repair][cn-repair] [Session][session] |
| MN-R02 | Easy NPC state callbacks may mutate selectors while the host is iterating or reconstructing them. | `EasyNpcStateListener` reconciles immediately. Trace the Core 7.11.0 event emitter and objective rebuild sequence. Prefer the existing deduplicated server queue where mutation timing is not guaranteed safe; prove no lost author configuration. [Listener][easy-state] [Objective][easy-objective] |
| MN-R03 | Movement cleanup can stop a navigation path it did not start. | The movement goal is flagless and calls navigation stop while also yielding to native policy. Test native MOVE acquisition/path replacement between `canUse`, `tick` and `stop`, including formation/hold/follow. Track an owned path/lease where necessary instead of unconditional cancellation. This is a credible interference mechanism, not a tested Recruits failure. [Movement][movement] [ADR][adr9] |
| MN-R04 | Some UUID-only integration bookkeeping may grow with historical NPCs until server stop; discovery scans can be a burst cost. | `CustomNpcsAiRepair` and `CustomNpcsScriptBridge` contain per-NPC maps; inspect all leave/death cleanup before calling them leaks. They are not, by themselves, strong Mob-reference leaks. Profile full loaded-entity discovery separately from the already bounded reconcile drain. [Repair][cn-repair] [Script bridge][cn-script] [Queue handler][handler] |
| MN-R05 | A recruit that initially fails the minimum-rank eligibility threshold may not be reconsidered promptly when rank later rises. | Existing casters' mana/level scaling is present; that is not the issue. Trace event/periodic reconciliation for **non-casters** crossing the threshold without reload. Add a targeted rank/eligibility invalidation only if the gap reproduces. [Rank/eligibility paths][handler-school] [Adapter][recruits] |
| MN-R06 | Native casters and AI-bypassing/flying mobs may be double-managed or never driven. | Verify native `IMagicEntity`/host casting ownership before installing a loadout. Use Luminous' existing profile and heartbeat diagnostics to prove whether a driver is absent. Do not deploy a universal fallback based only on type matching. [Current diagnostics/status][changelog] [Native Iron's owner][up-mob] |
| MN-R07 | Aim and target-data fidelity can differ from native Iron's behavior after preparation. | Native mob code forces facing during casting; the foreign-NPC session also relies on look control, which another AI may override. Test projectile release direction and target-data identity while targets move or foreign look control runs. Distinguish a visual head turn from the actual vector used by the spell. [Native loop][up-mob] [Session tick][session-end] [Native target helper][up-target] |

Additional validation gaps are not silently promoted to defects: every manifest loader malformed-input branch; exact inventory mirroring and clone semantics; packet/command/item authorization; all add-on namespaces; damage attribution and summon diplomacy; registry namespaces that differ from owning mod IDs; broad COMMON-config reload behavior; and all shipped/sample data combinations. Each belongs in the release matrix, not in a fabricated “fixed bugs” list.

### 4.19 Intentional limitations and behaviors to preserve

**MN-L01 — Session/economy state is not all persistent.** `ManagedCasterState` is explicitly in-memory and is forgotten when entities leave; a fresh activation can initialize mana and cooldown state again. Persistent school/loadout/gear choices are a different concern. Treat stronger anti-unload economy persistence as an explicit policy enhancement, not proof that the current author intentionally promised every combat deadline survives restart. Document it clearly and evaluate abuse/balance implications. [Managed state contract][state] · [Leave/activation handlers][handler]

**MN-L02 — Launch-time protection is not universal immunity to friendly fire.** Direct validation, a corridor scan and an AoE clearance estimate cannot guarantee every spell's projectile, explosion, chain, ground field or summon remains safe after release. A single global damage cancellation hook is also not automatically complete: ownership attribution can differ by effect. Claim only the families actually tested, and preserve native damage ownership instead of fabricating player attackers. [Protection implementation][line-fire] · [Capability geometry][spellcompat]

**MN-L03 — Trust is authorization, not verification.** Config overrides and datapack manifests can intentionally override built-in classifications. Namespace trust is explicitly a weak claim, and registry reconciliation only checks IDs. Keep these provenance distinctions visible and never recommend blanket trust as the resolution of an NPC compatibility defect. [Support resolver][support-resolver] · [Registry reconciliation][manifest-reconcile]

**MN-L04 — Native behavior may intentionally win.** `yield`, passive orders, sitting restrictions, authored navigation, support-out-of-combat off, a failed persistent caster-chance roll, and conservative unsupported-spell verdicts can correctly result in no cast. A new eligible entity receiving a newly added loadout after reload is different from rerolling an established failed chance roll. The queue already visits loaded non-casters; do not add that feature again. [Handler][handler] · [Selection][loadout] · [Sitting policy][sitting]


## 5. Enhancement backlog, ranked by benefit, risk and effort

The items below extend existing functionality rather than renaming it. They are **proposals**, not descriptions of shipped behavior. Correctness findings in §4 take precedence. Existing self-support, weighted spell selection, reactive conditions, native-attack modes, telegraphs, school assignment, manifests, diagnostics and audit tools should remain the implementation starting points. [Existing feature inventory][changelog] · [Existing authoring surface][samples]

| Rank / ID | Proposal | Unmet need | Benefit | Risk / effort | Prerequisites |
|---|---|---|---|---|---|
| 1 · MN-E01 | Behavioral spell/integration verification profiles | Existing registry and lifecycle checks cannot certify recipient, effect, ownership or usefulness | Very high for all integrations | Medium / M–L | MN-001, MN-003, MN-004, MN-015 |
| 2 · MN-E02 | Spell-specific authoring validation and side-effect-free previews | Authors need to see why an apparently valid configuration cannot achieve its intended role | High | Low–medium / M | MN-003, MN-006, MN-007, MN-016 |
| 3 · MN-E03 | Explicit allied support and useful buff selection | Current self-support does not constitute a healer/cleanser role for a party or settlement | High | Medium–high / L | MN-003, MN-006, MN-007, MN-011, MN-012 |
| 4 · MN-E04 | Better script readiness and request receipts | General conditions and API success codes do not fully explain a particular authored cast | High for scripted NPCs | Medium / M | MN-003, MN-005, MN-010, MN-013 |
| 5 · MN-E05 | Budgeted discovery, lifecycle metrics and performance profiles | Bounded queue draining alone does not bound discovery bursts or expose stale bookkeeping | High for servers | Medium / M | MN-006, MN-008, MN-010, MN-014; investigate MN-R04 |
| 6 · MN-E06 | Optional tactical selection policy | Weighted randomness and reactive conditions cannot always avoid redundant or ineffective choices | Medium–high | Medium / M–L | MN-E01, MN-E02, MN-006, MN-007 |
| 7 · MN-E07 | Optional casting-state readability | Existing spell particles/telegraphs do not always explain charge, recipient or interruption | Medium | Low–medium / M | MN-003, MN-004, MN-007 |
| 8 · MN-E08 | Explicit encounter-economy persistence | Unload/restart can reset transient combat economy; some authored encounters need continuity | Pack-dependent | High / M–L | MN-005, MN-006, MN-008, MN-010; policy decision |

### MN-E01 — Extend the existing audit tools into behavioral verification profiles

**Current distinction.** The current in-game spell auditor and offline heuristic manifest generator already exist. The changelog describes CAST-mode zombie dummies with a short overridden duration and lifecycle/entity/mana observations. That is useful instrumentation, but neither an observed spawn nor a completed two-tick session proves normal-duration gameplay, correct damage ownership, ally safety or useful support. The full auditor/tool implementations were not exhaustively audited here, and no current audit result is certified by this report. [Existing tools and limitations][changelog]

**Proposed behavior.** A named, opt-in test profile evaluates a small reviewed spell cohort against a particular host and exact JAR set. Results distinguish `RESOLVED`, `PREPARED`, `STARTED`, `LIFECYCLE_COMPLETED`, `EFFECT_OBSERVED`, `RECIPIENT_CORRECT`, `OWNERSHIP_CONFIRMED` and `BEHAVIOR_VERIFIED`. These are separate facts, not an automatic progression to “supported.” A player-only refusal is expected in its negative fixture; a supposedly supported healer affecting the wrong recipient is a failure.

**Implementation.** Extend the existing audit/report path and GameTests, not the production spell registry or casting authority. Record dependency filenames, hashes, metadata versions, tested spell/level, caster type, request source, effective native duration, explicit overrides, target/ally arrangement and outcome evidence. Add family-specific observers for HP/effects, projectile owner, summon owner and pulse count. Never shorten native duration in a test claiming timing equivalence. Keep destructive cast tests behind explicit disposable-world consent and the existing permission boundary; bound and clean up spawned fixtures/effects as far as the family permits.

**Controls, risks and migration.** Verification profiles are development/operator data, disabled during ordinary gameplay. No mandatory runtime dependency. A passing profile applies only to the tested behavior/version/host, not an entire namespace. Historical heuristic manifests remain drafts until reviewed; do not silently promote them.

**Acceptance.** A profile fails when the effect lands on the wrong recipient even if the lifecycle completes, distinguishes missing dependencies from passes, reproduces native duration/pulses, and exports enough exact inputs for another developer to repeat it. Start with the smallest representative core cohort before adding add-on spells.

### MN-E02 — Extend validation and previews with intent-aware explanations

**Current distinction.** `/magicnpcs validate`, `why`, loadout inspection, school-pool reports, schema diagnostics, provenance and ten datapack samples already address much of authoring. The remaining opportunity is to connect **valid data** with **achievable behavior**, and make all previews observational. [Existing diagnostics][changelog] · [School-pool report][school-pool] · [Samples][samples]

**Proposed behavior.** An author can inspect a resolved entry and see its actual source, capability provenance, role/recipient strategy, effective spell level, native duration, additional wind-up, cooldown and relevant exclusion reasons without rolling a school, spending mana or changing equipment. Flag contradictory combinations such as a target-raycast heal in a self-only support slot, an empty generated pool after safety filtering, or an unsupported build behind an enabled namespace toggle.

**Implementation.** Reuse the real parser, resolver and shared request validator. Add structured warnings rather than a separate heuristic schema that drifts from runtime. Keep “configuration invalid,” “dependency absent,” “temporarily ineligible,” “unverified” and “permanently unsupported strategy” distinct. Exercise all ten samples independently and selected combinations through the actual parser; a combined test must respect their intentional overlap rather than expect every sample to win simultaneously.

**Controls, risks and migration.** Keep runtime behavior unchanged by default. New warnings should identify source resource and entry, with strict failure reserved for structurally impossible or unsafe declarations. No automatic conversion of author roles or blanket allowlisting. Cache immutable pool summaries by relevant configuration/manifest generation, not mutable NPC RNG.

**Acceptance.** Repeated preview commands leave assignment, RNG-dependent selection, mana, cooldown, equipment and goal identities unchanged. Diagnostic outcomes match the next request under unchanged world state. Sample CI catches a deliberately misspelled ID and an intentionally incompatible role with the correct explanation.

### MN-E03 — Add explicit allied healing, cleansing and buff support

**Current distinction.** Wounded NPC self-support already exists. This proposal is a genuine **ally-recipient role**, not a reimplementation of idle healing. MN-007 must first make current self-support safe. [Existing support configuration][config-support] · [Current selection][goal]

**Proposed behavior.** An opt-in support NPC chooses a permitted ally with a meaningful need: health deficit, a removable negative effect, or a missing/expiring reviewed buff. A healer never uses its hostile combat target as an implicit recipient. Owner transfer, diplomacy changes, ally death, full healing by someone else and a blocked raycast trigger reconsideration or cancellation before emission. Summons are not automatically allies merely because they are nearby.

**Implementation.** Extend existing entry intent/recipient data and adapter relationship queries; do not replace native target acquisition. Preserve the hostile target separately while selecting a support recipient. Use a bounded nearby scan on the existing decision cadence, with deterministic scoring and optional small hysteresis so the NPC does not oscillate between equally wounded allies. Require reviewed per-spell targeting semantics; a raycast-based heal must validate the final prepared recipient. Use short-lived shared reservations only if simultaneous healers demonstrably waste resources, and only as advisory selection state, never a second economy.

**Controls.** Proposed optional entry fields should express recipient scope, range, health/buff need and owner preference. Defaults preserve current self-support. No automatic adoption by authored Easy NPC or CustomNPCs characters. Separate “may support” from “may attack” where the host already distinguishes those permissions.

**Risks / migration.** More useful healing can substantially change encounter balance. Bound range, scan cadence and eligible recipients; retain mana/cooldown costs. Existing SUPPORT entries stay self-oriented unless explicitly migrated. Test native spell rules rather than bypassing them to make a healer work.

**Acceptance.** Test two allied wounded NPCs, one enemy, a neutral bystander and simultaneous healers. Only permitted recipients receive effects; unnecessary buff refresh is avoided; costs are charged once; changing relationships during a cast prevents an unauthorized emission. Native combat targeting and follow/hold orders remain intact.

### MN-E04 — Make scripted readiness and cast receipts precise

**Current distinction.** Easy NPC already has general `has_school`, `can_cast`, `has_mana` conditions and a cast action; CustomNPCs already has direct API/mailbox operations and result codes. A general `can_cast` condition is not a spell-specific affordability/target/cooldown guarantee. [Conditions][easy-conditions] · [Action][easy-action] · [Script API][cn-api] · [Mailbox bridge][cn-script]

**Proposed behavior.** Add a side-effect-free spell-specific readiness query returning the canonical spell ID, validated level, intended recipient, duration, current cooldown/mana requirement and stable reason code. A submitted request receives a receipt distinguishing queued, refused, accepted and terminal outcomes. “Repair requested” must not mean “casting restored.”

**Implementation.** Build on MN-003's shared validator and current events. Introduce request/cast correlation identifiers only where needed to prevent confusion between successive casts and mailbox retries. Consume each mailbox request once; bound pending receipts and expire them. Preserve the current direct API as a thin delegate, not an independent casting path. A follow-up cast from a completion callback goes through the staged queue from MN-013. Emit readiness/cast events on the server thread and distinguish API result transport from permission to mutate gameplay.

**Controls, risks and migration.** Add new query/condition names rather than changing the meaning of existing dialogue predicates. Keep mutation switches and the exact-build gate authoritative. Redact internal stack traces from ordinary author-facing messages while retaining actionable diagnostics in logs. Preserve old request forms where safe, normalizing aliases through MN-005.

**Acceptance.** A repeated mailbox request cannot cast twice; a request rejected during suspension costs nothing; readiness and request agree under identical state; receipts survive an asynchronous repair decision without falsely reporting acceptance; old dialogue conditions behave as before.

### MN-E05 — Budget discovery work and expose lifecycle health

**Current distinction.** Reconciliation already uses a deduplicated queue with a configurable drain batch. The enhancement is to measure and, where needed, bound **discovery, repeated invalidation and bookkeeping lifetime**, not to add a queue that already exists. [Queue and reload handlers][handler] · [Repair bookkeeping][cn-repair]

**Proposed behavior.** Operators can distinguish an NPC waiting in a healthy queue from one never evaluated by host AI. A server-level diagnostic reports queued work, oldest request age, coalesced reasons, reconciliations changed/unchanged, session counts and high-water marks. Large reloads converge gradually rather than spending an unmeasured burst on discovery.

**Implementation.** Profile first. If full loaded-entity enumeration dominates, stage discovery using an existing safe server lifecycle/index or a bounded snapshot/cursor whose invalidation semantics are explicit. Do not retain a mutable world iterator across ticks without a documented validity contract. Coalesce to the latest desired generation, avoid replaying obsolete work, and release all entity references/UUID bookkeeping on the appropriate lifecycle. Keep counters cheap and aggregate logs instead of writing one line per NPC per tick.

**Controls, risks and migration.** Preserve existing batch defaults initially; add optional time/work budgets only after measurements. A small budget delays convergence, so report the backlog rather than silently leaving newly eligible casters inactive. Never drop cleanup work just to satisfy a cap. No save migration or mandatory profiler dependency.

**Acceptance.** Measure 0/32/128/512 loaded-NPC profiles, mixed active/inactive casters and repeated reloads. No stale work survives a world stop. Queue age and completion are bounded for a fixed workload, and a newer reload supersedes obsolete work without rerolling assignments or repeating gear grants.

### MN-E06 — Optional tactical scoring on top of existing selection

**Current distinction.** Weighted random choice, reactive conditions, range checks, mana, cooldowns and suppressed-mode standoff movement already exist. Do not call their addition an enhancement. [Selection][goal] · [Movement][movement] · [Current entry controls][readme-data]

**Proposed behavior.** An optional tactical policy prioritizes spells that can achieve a useful effect: avoids refreshing an already-sufficient buff, prefers an affordable attack rather than waiting indefinitely for an expensive one, and responds to reviewed range/recipient constraints. Default loadouts keep their current weighted behavior.

**Implementation.** Score only the already eligible candidate set. Use reviewed profile metadata for effects and targeting, not spell-name guesses about armor, resistance, damage or utility. Preserve author weights as a preference or selectable policy, and use bounded per-decision calculations. A failed preparation may receive a short, reason-specific retry backoff; do not blacklist a spell permanently because of a transient wall or moving target. Retain native Iron's timing and accounting.

**Controls, risks and migration.** Proposed policy options should be per-loadout, with `weighted` as the compatibility default. Tactical mode can increase effective NPC strength without changing raw damage; benchmark fights with fixed gear/levels. Avoid “intelligent” navigation changes until MN-R03 proves movement ownership safe. No resampling of school identities.

**Acceptance.** Fixed scenarios demonstrate fewer redundant casts without bypassing eligibility, changing cooldowns or increasing scans per candidate. With the default policy, distributions and author conditions match the existing behavior. No target or navigation oscillation is introduced.

### MN-E07 — Improve readability without replacing host renderers

**Current distinction.** Existing telegraphs, school particles and spell-generated sound/effects already provide feedback. The proposal is a small, optional layer for **cast state and intended recipient**, not duplicated Iron's effects or a new renderer. [Existing feedback][changelog] · [Event stream][events]

**Proposed behavior.** Players can tell that an NPC is charging, whom a support cast intends to help, and whether a cast was interrupted. An operator can inspect a specific caster's current state without globally enabling verbose logging. Critical information must not depend solely on color.

**Implementation.** Drive feedback from authoritative session transitions and recipient intent. Reuse existing feedback hooks; send only necessary transition/tracking updates where networking is required. Do not assume Recruits or every generic mob supports GeckoLib/Iron's animations. Keep presentation independent from cast permission and session completion.

**Controls, risks and migration.** Default extra indicators off or minimal, with reduced-particle and volume settings. Respect entity tracking range, privacy of authored dialogue, large-NPC counts and accessibility. No save migration. Do not restart a cast solely because a viewer begins tracking the NPC.

**Acceptance.** Dedicated and integrated clients show one start and one terminal indication per accepted cast, late trackers receive a coherent state, and no extra spell effect/sound is duplicated. Disabling presentation does not alter gameplay, timings or resource cost.

### MN-E08 — Optional persistence for authored encounter economy

**Current distinction.** Persistent school/choice data already exists, but transient `ManagedCasterState` is not a guarantee that mana/cooldown deadlines survive unload or restart. Whether this should change is an explicit balance policy. [State contract][state] · [Lifecycle handlers][handler]

**Proposed behavior.** A pack author may opt an encounter into preserving its mana balance and remaining cooldowns across chunk reload and server restart. The behavior for dimension transfer, NPC clone, respawn and offline elapsed time is declared rather than accidental.

**Implementation.** Serialize the existing authoritative values into the established entity persistence surface; do not add a second live capability. Use canonical spell keys and bounded, versioned data. Never persist `mob.tickCount` as a portable absolute deadline. Decide whether cooldowns pause while unloaded or use world game time, and implement that policy consistently. Retain only valid current spell IDs; reconcile maxima without refilling. A cloned NPC needs an explicit “copy encounter state” or “fresh actor” policy so duplication cannot accidentally reproduce a spent encounter's identity.

**Controls, risks and migration.** Keep current transient behavior as the default unless the owner expressly approves a global migration. Per-loadout opt-in is safer for authored bosses. No real-world wall-clock regeneration by default. This increases saved data and requires careful old-save compatibility.

**Acceptance.** Tests compare mana/cooldowns before and after unload, restart, dimension transfer, rank change, clone and respawn under each supported policy. No negative/wrapped deadlines, repeated initialization or unbounded stale spell entries occur.

### 5.1 Additional integrations: evidence-based candidate selection

Do not start a new mandatory NPC integration in the correctness release. The highest-return expansion is to verify currently advertised paths and publish precise profiles.

**Iron's add-ons.** The existing manifest/override mechanism is the reusable entry point. Select an exact Forge 1.20.1 add-on JAR from the user's actual pack, verify its declared dependencies, inspect its spell classes, then test a small cohort through MN-E01. The repository's namespace examples, including `traveloptics`, are authoring examples, not evidence that all of that add-on's spells work. Maintain unsupported player-only/special-preparation verdicts until a real non-player implementation exists. No additional add-on build was independently verified in this audit. [Existing add-on surface][changelog] · [Manifest layers][support-resolver]

**Guard Villagers / vanilla-like companions.** Improve reusable ownership, retaliation and native-ranged tests before adding another adapter. Guard Villagers already has a shipped loadout and namespace gate; Human Companions already has a gate plus potential vanilla ownership coverage. A dedicated adapter is justified only when an exact target-version host API exposes an unmet order/faction boundary that generic policies cannot safely represent. [Inventory][config-inventory] · [Generic ownership][owner]

**MCA / MineColonies.** Preserve the repository's caution: generic entity-type eligibility is not a role-aware citizen integration. Before proposing support for guards, citizens or family-specific behavior, establish exact JARs, which host owns role/targeting/navigation, and whether the NPC uses `GoalSelector` at all. Do not treat the namespace toggle as a request to turn every citizen into a combat caster. These remain research candidates, not implementation commitments. [Namespace cautions][config-inventory]

**Luminous.** Complete the existing V1.2.7 Phoenix/Witch Doctor runtime investigation before designing a fallback. If goal evaluation is demonstrably absent, an integration-specific driver handover may be justified, but only with exactly-one-session ownership and host lifecycle evidence. A generic stale-heartbeat fallback is not approved by this roadmap. [Existing explicit scope][changelog] · [Exact test-profile artifact][luminous-file]

## 6. Phased implementation plan

### 6.1 Working rules for the implementing agent

First compare the actual checkout with this report's SHA and record local changes. If code has changed, revalidate each referenced path before applying a fix; this report is not authority to overwrite newer work. Preserve Forge 1.20.1, Java 17, existing dependency choices and the CustomNPCs exact pin. Keep changes in narrowly reviewable commits grouped by invariant, not by a broad “compatibility rewrite.”

Every task must add its regression before or alongside the correction, document the affected entry points, and retain a positive control demonstrating unchanged intended behavior. New test helpers may expose existing boundaries, but must not replace real integration tests with mocks of the same assumptions. Do not introduce a second cast scheduler, target authority, mana capability or fake player. No version change is part of this audit's authorization.

### 6.2 Phase 0 — Reproducible baseline and trustworthy test outcomes

| Task | Action | Completion artifact |
|---|---|---|
| MN-P0.1 | Capture checkout branch/SHA/worktree, Java 17, Gradle wrapper version, resolved dependencies and all local JAR hashes. Compare with §1 without discarding local edits. | Machine-readable baseline and command log tied to the checkout. |
| MN-P0.2 | Resolve MN-001 by identifying actual compile/runtime artifacts and fixing provenance validation, not silently upgrading dependencies. | Metadata/hash consistency check; separately named runtime profiles. |
| MN-P0.3 | Implement MN-015's distinction between absence smoke tests and required positive integration tests. Preflight dependency presence and NPC creation. | Tests proving a missing dependency or refused expected-positive cast fails its positive profile. |
| MN-P0.4 | Port the isolated timing/overflow/geometry cases into the appropriate project tests, retaining independent oracles where applicable. | Actual JUnit results; report the new tests separately from this audit's harness. |

**Exit gate.** The selected profile can say what actually ran. A green result cannot hide an absent host or a spell that never started. Exact CustomNPCs/Recruits build guards remain intact. Compilation at this stage is only compilation, not permission to mark an integration verified.

### 6.3 Phase 1 — A shared, exception-safe request/session boundary

Implement **MN-002, MN-003, MN-004, MN-005 and MN-013** in a coordinated change to the existing session/request seam.

Recommended order: establish canonical spell identity and immutable normalized request intent; centralize hard framework and spell eligibility; make session acceptance/cleanup exception-safe; route external callers through the boundary; finally stage reentrant additions/removals in the detached driver. The validator must not mutate assignments, spend mana, publish start events or consume selection RNG. Normalize before cooldown lookup. Validate again at the point relevant state is committed.

Automatic selection remains in `NpcSpellAttackGoal`; external authors retain explicit action semantics. The shared contract should return structured outcomes and own acceptance, not become a general-purpose replacement AI. Include an explicit shutdown guard so terminal callbacks cannot create new work during teardown. Prepare event correlation and terminal ownership without changing all script event names unnecessarily. [Existing session][session] · [Detached driver][detached] · [Event publication][events]

**Exit gate.** Automatic and external requests agree on mandatory restrictions; unsupported CustomNPCs cannot bypass the gate; fault injection leaves no stuck state; alias requests share cooldown; synchronous callback chaining does not corrupt collections; no charge occurs before acceptance and no accepted effect is automatically retried after an exception.

### 6.4 Phase 2 — Stable, owned and restart-safe reconciliation

Implement **MN-006, MN-008, MN-009, MN-010 and MN-014**. Solve generated selection stability before relying on generated-school fixtures for repair: random rebuilding can otherwise mask a broken unchanged predicate.

Separate desired-state identity from catalog observation generation. Maintain an explicit owned-goal inventory and repair structural differences without resetting economics or granting equipment. Use an owned self-defense goal identity/type; never infer removal ownership from a foreign class match. Split one-time integration registration from per-server activation and cleanup. Preserve current persistent choice data; any minimal new selection metadata must have a deterministic compatibility path for existing NPCs.

Investigate **MN-R01 and MN-R02** with the exact host JARs while implementing this phase. If the host can discard a running goal without cleanup, terminate the old owned session through the existing authority before installing a replacement; do not solve an orphaned goal by creating an independent second session. Defer unsafe selector mutations through the existing queue, with deduplicated reasons and bounded work. [Reconciler][reconciler] · [School selection][school-pool] · [CustomNPCs repair][cn-repair] · [Easy NPC state listener][easy-state]

**Exit gate.** Fixed and generated loadouts survive no-op reload and host reconstruction with stable choices/resources. Missing/duplicate owned goals converge correctly. Foreign targets/navigation and authored gear survive. Two consecutive integrated worlds work in the same process, and old-world state is absent.

### 6.5 Phase 3 — Recipient correctness, release safety and numeric boundaries

Implement **MN-007, MN-011, MN-012 and MN-017**. The geometry correction and numeric resolver fix can be developed independently, but their final tests must run against Phase 1's request contract.

For support, define current self-support eligibility before expanding to allied healing. Fix the wrong-recipient path even if that temporarily excludes an unverified target-raycast spell from generated self-support. Use precise diagnostics and minimally repair affected slots. Recheck relevant live safety at acceptance/emission without altering Iron's effective duration or continuous pulse cadence. Separate “future emission cancelled” from effects already released; do not imply the latter disappear unless an actual supported effect-specific cancellation exists.

Investigate **MN-R03 and MN-R07** for movement/aim ownership. Test actual projectile direction and actual prepared target data, not just head animation. Review cast-duration and cooldown arithmetic through deadline storage, not merely parser acceptance. [Protection][line-fire] · [Support defaults][config-support] · [Native target helper][up-target] · [Timing helpers][cast-time] [cooldown]

**Exit gate.** Wrong-target support and the geometric counterexample fail before the patch and pass after it. New blockers appearing during wind-up/charge prevent unauthorized future effects. Timing controls and ordinary cooldown behavior remain compatible. Positive numeric increases cannot wrap into faster casting.

### 6.6 Phase 4 — Exact integration verification and documentation closure

Run the matrix in §7 against the pinned binaries, including source-specific request paths, absence profiles, combined adapters and a second integrated world. Investigate remaining **MN-R04 through MN-R06** without assuming their outcome. Record which variants are unsupported, unverified or verified for particular behaviors.

Complete **MN-016** after behavior is settled. Mark historical plans as superseded where appropriate; update README, dependency tables, diagnostics, sample expectations and CustomNPCs development instructions together. Publish accepted-versus-tested version distinctions and precise persistence/friendly-fire limitations. Do not expand the CustomNPCs allowlist as a side effect of release cleanup.

**Exit gate.** Every P1 is corrected and verified in its affected positive profile, or the affected capability is deliberately gated off and its limitation disclosed. No integration is advertised as behaviorally verified from compilation, startup, registry presence, namespace trust or optional-test success alone.

### 6.7 Phase 5 — Small, opt-in enhancement increments

Implement **MN-E01/MN-E02** first because they improve evidence and authoring for subsequent features. Then consider **MN-E04/MN-E05**, followed by **MN-E03/MN-E06** where actual gameplay benefits justify the balance cost. **MN-E07** can follow stable events. **MN-E08** requires an explicit persistence policy decision and migration tests, not an opportunistic addition to a bug-fix patch.

A new host adapter or fallback driver belongs in a separately scoped increment with exact dependency evidence. Reject any proposal whose only justification is “the namespace is popular” or “the mob received a loadout successfully.”

### 6.8 Shared invariants for code review

| Invariant | Review question / required assertion |
|---|---|
| One authoritative session | Can any NPC be advanced simultaneously by a goal and detached/fallback driver? |
| One canonical identity | Do parser aliases, direct requests, cooldowns, events and diagnostics resolve to the same spell key? |
| One acceptance charge | Can refusal, exceptions, reentrant callbacks or duplicate requests charge twice or bypass a cooldown? |
| Safe preparation and cleanup | Can an external hook leave owned cast data or a terminal guard in an inconsistent state? |
| Correct recipient | Does the actual prepared/effected target still match the permitted intent, not just the requested target? |
| Owned mutations only | Is every removed goal, stopped path, modified item and cleared state demonstrably owned by Magic NPCs? |
| Stable no-op | Does unchanged reconciliation preserve goal/session identity, selection, mana, cooldowns and equipment? |
| Safe absence/unsupported build | Does rejection occur before touching unsupported typed classes or enabling a generic fallback? |
| Bounded lifecycle work | Can queued work, per-NPC maps, event listeners or references survive their entity/server lifetime? |
| Truthful evidence | Does every “passed” integration test prove its positive preconditions and actual expected effect? |

## 7. Test results, regression matrix, release acceptance and blockers

### 7.1 Checks actually executed in this audit

| Check | Result | What it proves / does not prove |
|---|---|---|
| Remote branch/tree and pinned source reads | Completed through GitHub connector | Establishes the remote baseline and inspected control paths; does not establish the user's local worktree. |
| Exact official Iron's compile-file identity | Verified as 1.20.1-3.15.0 | Establishes MN-001's artifact-label mismatch; the JAR was not executed. |
| Exact official Luminous profile file identity | Verified as V1.2.7, Forge 1.20.1 | Establishes profile artifact identity only. |
| Local Git/Java inspection | Git 2.47.3; OpenJDK/Javac 21.0.11 | Java 17 was not locally installed. |
| Repository clone | **Failed: GitHub DNS resolution unavailable** | No executable full checkout was created. This is an environment blocker, not a repository build failure. |
| Pure helper source integrity | **Both Git blob hashes matched** | Copied helper bytes match the pinned repository blobs. |
| Pure helper compilation | **Passed with `javac --release 17` on JDK 21** | Two dependency-free helpers compile for Java 17 APIs/bytecode. Not a Forge build or a Java 17 runtime test. |
| Ordinary helper assertions | **15 passed** | Nine cast-duration and six cooldown cases in an audit-only harness. Not the project's JUnit suite. |
| Cooldown overflow | **Reproduced using unchanged production `CooldownResolver`** | A huge positive multiplier returns 20 ticks rather than a valid long cooldown; see MN-017. |
| Line-of-fire counterexample | **Reproduced by independent arithmetic model** | The inspected center-projection formula misses an exact segment/AABB intersection; not a Minecraft-class or in-game execution. |
| Detached-list reentrancy | **JDK collection model reproduced `ConcurrentModificationException`** | Confirms the collection mechanism used in MN-013; the real Forge callback path remains a proposed regression. |
| Gradle task discovery / build / verification | **Not executed** | Missing full checkout, accessible dependency artifacts and project Java 17 environment. No build-pass claim. |
| Project JUnit tests | **Not executed** | The 15 audit assertions must not be substituted for the actual suite. |
| GameTests / dedicated server / integrated server | **Not executed** | No in-game integration pass is claimed anywhere in this report. |
| Offline JAR manifest audit / full sample validation / packaged JAR audit | **Not executed** | Required bytes and executable project setup were unavailable. |

The companion evidence archive contains the harnesses, integrity records and actual output. Its runner uses the two exact production helper sources supplied from the pinned repository and verifies their hashes; it does not download them or contain a reconstructed full project. No repository source or compiled classes are included in the archive.

**Integrity records for the executed production helpers:**

```text
CastTimeResolver.java
  Git blob SHA-1: a1b4408571a7a12905d3cc0f22c13ce76a85b45c
CooldownResolver.java
  Git blob SHA-1: 043a0a127762f6051a560a1d4da7b7bc983371e0
```

The cast-duration assertions cover native unchanged duration, INSTANT ignoring overrides, absolute precedence, rounding 15 × 0.5 to 8, doubling, zero absolute/multiplier floors, non-finite fallback and saturation. Cooldown assertions cover absolute precedence/floor, per-entry and global multiplier selection, rounding and the zero-multiplier floor. The overflow case is a separate reproduced defect, not counted as a passing ordinary behavior assertion. [Executed helper source][cast-time] · [Executed cooldown helper][cooldown]

### 7.2 How to obtain meaningful project results

The following commands are a **prospective execution checklist**, not a transcript of commands successfully run here. Run them in the actual existing checkout after preserving local edits and staging the project's exact required JARs. Use Java 17. Do not remove a build guard, provide fake API stubs or change dependency versions to get past missing prerequisites.

```bash
# Read-only baseline capture; do not reset, clean or change branches.
git status --porcelain=v1
git branch --show-current
git rev-parse HEAD
java -version

# Discover the actual wrapper and task graph before relying on task names.
./gradlew --version
./gradlew tasks --all

# These are configured/expected project paths, not audit-run results.
./gradlew checkVersionConsistency
./gradlew test
./gradlew build

# Base isolation/boot profile and the existing optional runtime profiles.
./gradlew runGameTestServer
./gradlew runGameTestServer -PeasyNpcRuntime
./gradlew runGameTestServer -PcustomNpcsRuntime
./gradlew runGameTestServer -PdevRuntime
./gradlew runGameTestServer -PdevRuntime -PluminousRuntime
```

The CustomNPCs-only profile intentionally differs from the full casting profile: loading the adapter without Iron's is a useful isolation test but not a casting test. Likewise, an all-mods profile is not a substitute for testing optional integrations separately. The current flags do not themselves establish a complete orthogonal matrix; introduce dedicated **test-only** runtime configurations where needed, clearly naming them as new infrastructure rather than pretending they already exist. [Configured build profiles][build] · [CustomNPCs profile instructions][cn-dev]

If `runData` or another generation task is used, execute it in an isolated worktree/copy with known inputs and review the generated diff. It can write generated resources and is not a harmless read-only audit command. Never overwrite the user's local generated content or treat a changed sample as an automatically approved correction.

For every run, retain commit SHA, full Java/Forge versions, JAR hashes, profile, relevant configs/datapacks, command, exit status, test inventory, actual assertions, skipped/optional counts and logs. For a positive runtime profile, missing entities, missing API hooks or expected casts that never start must be failures. Separately test that intentionally absent/unsupported dependencies are handled gracefully.

### 7.3 Dependency and deployment matrix

Run representative assertions in **both dedicated and integrated-server environments**. Not every long stress scenario needs every combination, but all hard gates and claimed integration behaviors need their relevant individual and combined positive controls.

| Profile | Dependencies / version condition | Essential assertion |
|---|---|---|
| ENV-01 | Magic NPCs without Iron's and without NPC frameworks | Clean startup/world load, neutral surfaces safe, no optional class-linkage error. |
| ENV-02 | Iron's absent; Recruits, Easy NPC Core and pinned CustomNPCs present individually | Host NPCs retain native behavior; inactive casting surfaces report truthfully; no hidden Iron's class load. |
| ENV-03 | Iron's only, actual compile-floor 3.15.0 with its own required dependencies | Base lifecycle, timing and generic mobs; exact binary API compatibility, not the inaccurate compile label. |
| ENV-04 | Iron's only, configured development version 3.16.1 with its own required dependencies | Same representative cohort; compare actual changes rather than assuming 3.15/3.16 are identical. |
| ENV-05 | Iron's 3.16.3 manifest reference with its own required dependencies | Manifest/registry reconciliation plus behavior cohort; no automatic certification of every manifest row. |
| ENV-06 | Iron's plus Recruits 1.15.2 | Four recruit types, diplomacy, rank, orders and combat coexistence. Earlier loader-accepted versions remain unverified unless tested. |
| ENV-07 | Iron's plus Easy NPC Core 7.11.0, **without configuration UI** | Core objective/action/conditions work independently of UI classes. |
| ENV-08 | ENV-07 plus exact compatible optional UI build | Client editing/presentation works without moving server casting authority into the UI. UI version must be identified first. |
| ENV-09 | Iron's plus CustomNPCs GBPort Unofficial **1.20.1.20260711**, hash-verified | Positive API, repair, script, mailbox, dialog, equipment and lifecycle tests actually execute. |
| ENV-10 | CustomNPCs deliberately unsupported build, or supported build with bridge disabled/probe failure fixture | Every assignment/reconcile/cast path refuses safely, including namespace toggle on and pre-existing school data. Do not add the negative-test build to the supported list. |
| ENV-11 | Iron's plus Recruits + Easy Core + pinned CustomNPCs together | Adapter/registry isolation, combined lifecycle cleanup, no duplicated listeners or cast accounting. |
| ENV-12 | ENV-11 plus generic owner/team/raid/sitting applicability | All relevant restrictions compose; adapter priority cannot erase generic protection. |
| ENV-13 | Full dev stack plus Luminous V1.2.7 | Real Phoenix/Witch Doctor heartbeat, native AI and actual effects; classify driver limitations without inventing a fallback. |
| ENV-14 | A specifically identified add-on Forge 1.20.1 JAR, with hash and required Iron's version | Per-spell capability/data/ownership behavior; manifest/trust provenance remains accurate. No exact add-on profile is approved by this audit. |
| ENV-15 | Iron's/Easy NPC outside loader-accepted ranges | Expected loader refusal is reported as a negative compatibility test, not an in-game test. No bypass to force loading. |
| ENV-16 | Integrated world A → menu → world B → menu → A in one JVM | Bridges, APIs and listeners reappear exactly once; old-world sessions, references and queues do not. |

A supported-range endpoint is not a representative stand-in for every intermediate release. Retain a version-specific evidence ledger. An unavailable negative-test binary is a blocker for that particular test, not permission to fabricate a compatible alternative or modify the production pin.

### 7.4 Entity and AI matrix

| Entity cohort | Why it is needed | Expected host boundary |
|---|---|---|
| Zombie / basic melee mob | Simple native attack plus explicit loadout | Coexist retains melee; suppress/yield honor configured policy. |
| Skeleton / pillager | Bow/crossbow scheduling, target movement and LOS | Casting is not permanently starved or aimed by a conflicting native controller without diagnosis. |
| Phantom or another identified flying mob | Flying navigation and non-ground positioning | No unsafe assumption that ground standoff navigation applies. |
| Wounded idle villager / profession change | No hostile target, support needs, school eligibility | Existing self-support works; job changes and chance outcomes retain intended persistence. |
| Tamed wolf / owned companion | Owner online/offline, sibling pets and sitting | Owner UUID, teams and sitting restrictions remain independent and composable. |
| Raider in same/different raid | Raid-specific collateral relationships | Same-raid protection follows actual shared raid state, not a generic “all raiders are allies” shortcut. |
| Recruits recruit/bowman/crossbowman/captain | Melee/ranged inheritance, rank and orders | Native owner/order/XP systems remain authoritative. |
| Easy NPC authored character | Immobility/home state, configured objectives/actions | Author-defined behavior survives reconstruction and configuration edits. |
| Pinned CustomNPCs scripted character | Dialog/script pause, custom factions/jobs, clone/respawn | Native target/navigation/inventory ownership; deferred bounded repair only. |
| Large and multipart custom-hitbox fixture | Geometry and scan coverage | Actual body/effect overlap is protected, including the MN-011 counterexample. |
| Iron's native spellcaster | Existing `MagicData`/native casting owner | Either explicit supported coexistence with one owner or a truthful refusal; never accidental double-management. |
| Luminous Phoenix/Witch Doctor | Repository-specific unverified expectations | Prove the actual driver and hook sequence before implementing integration-specific behavior. |

“Can be assigned a loadout” is not a pass criterion for any row. Observe actual spell effect, native movement/orders and resource state throughout the scenario.

### 7.5 Regression scenarios and acceptance assertions

| Test ID | Scenario / stimulus | Required assertion | Findings / areas |
|---|---|---|---|
| REG-01 | Submit canonical and bare-alias forms of the same spell in either order | One canonical cooldown identity; no alias bypass or duplicated event identity | MN-005 |
| REG-02 | Precast refusal, explicit event veto and insufficient mana | No acceptance cost or cooldown; documented refusal/terminal event semantics | MN-003, MN-004 |
| REG-03 | Simultaneous AI, Easy action, direct script and mailbox requests | At most one accepted active session; losers get precise outcomes | MN-003, MN-013 |
| REG-04 | Throw separately in each native hook and external event callback | No stuck cast/channel state, repeated effect, duplicate cleanup or starvation of other NPCs | MN-004 |
| REG-05 | Completion callback immediately requests another cast | No collection exception; correct event order and staged next request | MN-013 |
| REG-06 | Unsupported/disabled CustomNPCs with an enabled namespace loadout and existing school data | No owned goal/session/equipment mutation through fallback; precise diagnostic | MN-002 |
| REG-07 | Negative/huge spell levels and malformed external targets | Bounded normalized input or refusal before native hooks; no implicit unsafe recipient | MN-003 |
| REG-08 | Same generated school reconciled repeatedly; unchanged reload and manual validation | Stable chosen spell IDs, mana/cooldowns, gear and relevant goal/session identity | MN-006, MN-014 |
| REG-09 | Remove one selected spell, change rank and reload relevant metadata | Minimal valid repair, no refill or whole-selection reroll; bounded effective level | MN-006, MN-R05 |
| REG-10 | Remove/duplicate each owned goal independently, then request repeated repair | Complete desired owned structure; economics/foreign goals unchanged | MN-008 |
| REG-11 | Foreign retaliation goal present while school/self-defense is enabled then cleared | Foreign instance and priority survive; only owned self-defense is removed | MN-009 |
| REG-12 | Real CustomNPCs AI rebuild during wind-up, LONG and CONTINUOUS cast | Old session cleanup and exactly one new authority; no selector iteration mutation | MN-008, MN-R01 |
| REG-13 | Easy NPC configuration/AI objective reconstruction during casting | Author config survives, no duplicate goals or unsafe synchronous mutation | MN-R02 |
| REG-14 | Second integrated world in same JVM | Working bridges, one listener set, empty old state, truthful diagnostics | MN-010 |
| REG-15 | Wounded idle self-healer with support enabled/disabled; low mana | Existing support works without target when enabled, costs correctly, obeys toggle/cadence | Existing behavior; MN-007 |
| REG-16 | Target-dependent healing/buff entry while idle and while fighting | Correct authorized recipient or explicit unsupported strategy; never heals hostile fallback | MN-007 |
| REG-17 | Target dies, is removed, changes dimension, loses LOS or changes relationship at each cast phase | Applicable validation cancels/refuses future unauthorized effects; no cross-world target use | MN-003, MN-012 |
| REG-18 | Caster dies, unloads, is removed or transfers dimension while casting | One cleanup/terminal outcome; no old-world session continues; persistent choices obey policy | MN-004, MN-010, MN-L01 |
| REG-19 | Protected ally enters corridor/area after selection; required focus removed | Live release checks respond, without changing native duration/pulse cadence | MN-012 |
| REG-20 | MN-011 exact intersection plus rotated/slender/large/multipart bodies | No false-clear intersection; documented conservative tolerance near boundaries | MN-011 |
| REG-21 | LONG durations with modifiers, explicit override and extra wind-up | Correct acceptance/release tick; omitted override preserves native timing | Timing contract |
| REG-22 | CONTINUOUS normal/short/overridden durations, duplicate same-tick advance | Correct per-tick hooks/pulse count/cleanup; no double advancement | Timing contract |
| REG-23 | Positive cooldown boundary, non-finite multiplier and deadline wrap | No overflow into a shorter cooldown; actionable parser/resolver outcome | MN-017 |
| REG-24 | Native `coexist` → `suppress` → `yield` → removal; host rebuild between transitions | No obsolete foreign goals restored, target goals untouched, navigation ownership respected | MN-R03, native AI |
| REG-25 | Ownership/team/diplomacy/raid changes; owner logout; sit/passive/follow/hold/mount orders | Live policies respected for direct targets and protected collateral, including support permissions | All adapters |
| REG-26 | Rank crosses eligibility threshold for a non-caster without reload | Correct eligibility reevaluation without rerolling an established chance outcome | MN-R05 |
| REG-27 | Add loadout for already-loaded non-caster; disable/remove it; change config | New eligible state converges; obsolete owned state cleans up; no repeated grants | Reconciliation |
| REG-28 | Datapack same-path replacement, cross-file pooling, disabled stub, profession scopes | Actual precedence matches diagnostics; rejected data does not silently become working defaults | Loadout/parser |
| REG-29 | Invalid IDs, absent namespaces, malformed numbers, empty pools and manifest contradictions | Absent dependency is distinguishable from author error; precise file/entry/actionable message | Validation |
| REG-30 | Each sample pack alone, then selected documented overlaps | Real parser validates intended data; overlap outcomes explained rather than falsely all “active” | Samples |
| REG-31 | Script suspension, dialog pause, mailbox retry, clone and respawn | Single consumption; author state preserved; no duplicate requests/session identity or equipment grants | CustomNPCs |
| REG-32 | Low-permission player invokes commands, tome interactions and any registered client requests | Server validates authority and target; rejected requests cannot mutate NPC state | Permissions/networking, still unverified |
| REG-33 | No-op diagnostic/preview/readiness commands repeated | No RNG consumption, new assignments, resource use, goal replacement or gear changes | MN-E02, MN-E04 |
| REG-34 | Large reload and entity churn, followed by world stop | Bounded work/convergence; bookkeeping/reference cleanup; no stale next-world tasks | MN-R04, MN-E05 |
| REG-35 | Required positive fixture lacks dependency, entity, API or accepted cast | Positive profile fails rather than reporting success/optional pass | MN-015 |

For interruption tests, separate the **unaccepted wind-up** stage from an accepted native cast and from already released effects. Expected resource outcomes follow the explicit existing charge-at-acceptance policy, not a universal refund assumption. For “target loss,” distinguish a removed/dead target from a host choosing a different target while the original remains valid; define whether the current cast intentionally remains locked to its authorized recipient.

### 7.6 Friendly-fire and ownership verification by effect family

| Family | Observe directly | Limit of a passing result |
|---|---|---|
| Immediate targeted effect | Actual prepared target UUID and effect recipient; relationship at emission | Does not prove a raycast cannot be intercepted in another geometry; include interception fixture. |
| Straight projectile / beam | Actual initial vector, projectile owner, collision recipient and damage source | Launch clearance does not guarantee future movement of allies remains safe. |
| Target-area / caster-area / ground field | Actual center, extent, vertical placement, repeated hit targets and attribution | A scalar safety radius is not evidence of every effect's true volume. |
| Explosion / chained effect | Secondary targets, owner attribution at each stage, damage and non-damage effects | Preventing one damage event does not prove all knockback, status, terrain or chain effects are safe. |
| Summon | Summoner identity, summon allies/targets, secondary projectile owners, persistence and death cleanup | Successful summoning does not establish diplomacy parity with the caster's NPC framework. |
| Support | Intended and actual recipient, amount/effect usefulness, expiration and no hostile fallback | A lifecycle pass or particle is not proof of healing, cleansing or buff usefulness. |

Use these observations to label **verified protections**, **best-effort launch checks** and **unverified downstream behavior**. Do not promise universal friendly-fire immunity. Where exact effect ownership is unavailable, retain the limitation or decline the spell for that role rather than fabricate player attribution.

### 7.7 Performance and lifecycle workload

Use proposed cohorts of **0, 32, 128 and 512 loaded NPCs**, with a smaller subset actually casting and a separate all-casting stress profile. These are test sizes, not claims that any particular machine supports 512 active casters. Record hardware, Java flags, full mod list, world layout, active spell mix and NPC framework distribution.

Measure baseline and patched average/p95/max tick duration, allocation/heap trend, active and pending sessions, reconcile queue depth/oldest age, discovery cost, relevant map high-water marks, event counts, and log volume. Include repeated `/reload`, rapid entity churn, chunk unload/reload, two integrated worlds and cancellation under load. A fixed workload should converge; a newer generation should supersede obsolete work rather than multiply it. A map containing UUIDs is not automatically a strong-reference entity leak, but unbounded historical UUID growth is still worth measuring.

Set performance acceptance thresholds relative to the recorded baseline and intended server budget, not a fabricated universal timing number. Attribute regressions to particular work stages; do not disable safety or skip cleanup to meet a benchmark. Keep stress spells constrained to disposable worlds because summons, terrain effects and persistent fields can distort both gameplay and measurements.

### 7.8 Release acceptance criteria

A release claiming the affected integrations are verified should satisfy all of the following:

1. **Reproducible artifacts:** Actual checkout and JAR hashes recorded; Java 17 Forge build and project tests pass; dependency labels match resolved metadata. Audit-created helper results are not substituted for this evidence.
2. **Honest positive profiles:** Required integration fixtures actually spawn the host NPC, begin the expected cast and assert its effect. Missing prerequisites/refused expected-positive casts fail. Absence and unsupported-version tests remain separate and pass for the correct reason.
3. **Core correctness:** Every affected P1 regression in §4 passes through all relevant request sources. One authoritative session, canonical cooldowns, exception-safe cleanup, recipient safety and reentrant callbacks are demonstrated.
4. **State/host preservation:** No-op reconciliation and host reconstruction preserve stable choices, mana/cooldowns and authored equipment. Foreign goals/navigation, recruit orders/XP and CustomNPCs target ownership survive. Second-world activation is verified.
5. **Scope-specific gameplay evidence:** Representative timing, ranged/flying behavior, out-of-combat support, target/relationship changes and damage/summon ownership are observed in exact tested profiles. Untested families/builds remain explicitly unverified or gated.
6. **Data/permission/lifecycle closure:** Actual parser/sample and authorization tests run; malformed inputs are actionable; unsupported builds cannot use a generic fallback; queues/listeners/maps clean up; performance results show bounded convergence without disabling safety.
7. **Documentation agreement:** README, current ADR entry points, compatibility matrix, scripts, test instructions and player-facing claims describe the implemented behavior and its limitations. Historical plans are not presented as current architecture. No compatibility range is broadened solely because compilation succeeded.

A consciously disabled affected feature can be a safe interim outcome, but it must be reflected in runtime gating and documentation. An unverified P1 path left enabled is not equivalent to a completed fix merely because it did not crash in a short smoke test.

### 7.9 Remaining blockers and unverified areas

| Blocker / gap | What is needed to close it |
|---|---|
| No user checkout / inaccessible container clone | Execute against the actual checkout, record branch/SHA/local changes and revalidate any drift from the reviewed revision. |
| No local Java 17 or full resolved Gradle setup | Run the configured wrapper/toolchain and actual task graph in the project environment. |
| Missing local Recruits and pinned CustomNPCs bytes | Supply the already specified local dependency JARs to the build environment; verify hashes and inspect exact relevant APIs/rebuild/clone behavior. Do not replace them with stubs or another build. |
| Exact Easy NPC Core/UI binary behavior unverified | Identify the pinned Core artifact and an exact compatible optional UI build; trace callback timing and class isolation separately. |
| Iron's compile/runtime/manifest versions differ | Record actual resolved metadata and test each intended profile with its own required transitive dependencies. Source-labelled 3.16.3 is not proof of byte-for-byte JAR identity. |
| No live Minecraft execution | Run dedicated and integrated profiles, including second-world lifecycle, actual effects and state transitions. |
| Incomplete full-file/resource/command audit | Finish the specifically marked coverage gaps: all manifest-parser branches, every sample/data file, script-engine/global and inventory internals, commands/tome/network authorization, packaging and complete test inventory. |
| Add-on behavior and downstream effects unverified | Select exact add-on JARs and family-specific behavioral profiles; retain provenance and unsupported limits until tested. |
| Positive tests can currently hide skipped behavior | Complete MN-015 before accepting their green output as verification. |
| High-NPC-count cost not measured | Run the recorded cohort/baseline workload and capture stage-specific metrics, including discovery and cleanup. |

## Appendix A — Implementation handoff record

Use this record for each finding/task as it is implemented. It is a proposed tracking format, not a claim that fixes have been made.

```text
Finding/task ID:
Reviewed baseline SHA:
Implementation checkout SHA and preserved local changes:
Affected files and public/data surfaces:
Failing regression added:
Root cause confirmed or finding revised:
Correction and preserved invariants:
Save/config/script compatibility and migration:
Exact dependency JARs and hashes:
Commands/profile actually executed:
Assertions actually reached (including effect/recipient/ownership):
Skipped or blocked scenarios:
Before/after logs and relevant performance results:
Documentation changes:
Definition-of-done result:
```

For a source-confirmed finding that does not reproduce under the exact runtime fixture, preserve the evidence and explain why: an earlier gate, host callback order or version-specific implementation may make a path unreachable in that profile. Revise its scope rather than forcing a contrived gameplay claim. Conversely, an unverified hypothesis that reproduces should receive a new precise proof and regression before being promoted to a confirmed defect.

## Appendix B — Source navigation and evidence interpretation

Repository links below are pinned to `5e27810c3f65fd8bb64f7673c23c92f026b4b495`. Line-range links identify inspected review windows; the named symbols in each finding identify the implementation boundary within those windows. Upstream source links are pinned separately and explicitly version-qualified. Official distribution pages establish the identity of particular files, not their successful execution.

The most useful entry points for implementation are [baseline properties][baseline], [build][build], [session lifecycle][session], [session tick/cleanup][session-end], [automatic goal][goal], [detached driver][detached], [reconciliation][reconciler], [school pool][school-pool], [adapter composition][adapters], [CustomNPCs gate][cn-gate], [CustomNPCs repair][cn-repair], [Easy NPC listener][easy-state], [line-of-fire geometry][line-fire], and [positive CustomNPCs tests][cn-tests-casts]. Current architectural context is in [ADR 0009][adr9]; the root [historical plan][plan] must not override later accepted decisions.

**Final assessment:** Preserve the architecture, close the demonstrated correctness gaps, and make compatibility claims proportional to actual evidence. The project already has much of the machinery it needs; the priority is reliable boundaries and verifiable gameplay, not another broad integration rewrite.



[baseline]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/gradle.properties#L1-L61
[build]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/build.gradle
[mods]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/resources/META-INF/mods.toml
[readme]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/README.md#L1-L80
[readme-data]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/README.md#L108-L210
[changelog]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/CHANGELOG.md#L1-L100
[plan]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/Plan.md#L1-L85
[adr9]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/docs/decisions/0009-caster-movement-and-rank-scaling.md
[cn-dev]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/docs/customnpcs-development.md#L70-L162
[samples]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/datapack_samples/README.md#L1-L129
[main]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/MagicNpcs.java#L1-L204
[adapters]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/adapter/NpcAdapters.java
[state]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/caster/ManagedCasterState.java
[events]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/caster/MagicNpcEvents.java#L51-L144
[handler]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/IronsSpellcasterHandler.java#L1-L300
[handler-school]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/IronsSpellcasterHandler.java#L296-L480
[reconciler]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/CasterReconciler.java#L1-L300
[reconciler-mid]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/CasterReconciler.java#L298-L580
[reconciler-end]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/CasterReconciler.java#L580-L719
[session]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/MobCastSession.java#L1-L260
[session-end]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/MobCastSession.java#L261-L432
[goal]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/NpcSpellAttackGoal.java#L1-L310
[goal-tick]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/NpcSpellAttackGoal.java#L311-L650
[goal-resolvers]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/NpcSpellAttackGoal.java#L640-L900
[detached]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/DetachedCastDriver.java
[irons-bridge]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/IronsBridge.java
[spellcompat]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/SpellCompat.java
[manifest]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/SpellManifest.java
[manifest-reconcile]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/ManifestReconciler.java
[support-resolver]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/spell/SpellSupportResolver.java#L58-L107
[school-pool]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/SchoolSpellPool.java#L33-L159
[school-role]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/integration/irons/SchoolSpellPool.java#L161-L283
[loadout]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/loadout/LoadoutManager.java#L1-L250
[loadout-publish]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/loadout/LoadoutManager.java#L290-L475
[parser]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/loadout/LoadoutParser.java#L215-L380
[config-inventory]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/config/MagicNpcsConfig.java#L130-L174
[config-support]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/config/MagicNpcsConfig.java#L680-L742
[config-gates]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/config/MagicNpcsConfig.java#L1000-L1320
[attack-goals]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/util/AttackGoals.java
[movement]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/caster/CasterMovementGoal.java
[line-fire]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/util/LineOfFire.java
[cast-time]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/loadout/CastTimeResolver.java#L29-L57
[cooldown]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/core/loadout/CooldownResolver.java#L25-L44
[recruits]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/recruits/RecruitsAdapter.java
[easy-init]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/easynpc/EasyNpcIntegration.java
[easy-casting]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/easynpc/EasyNpcCastingIntegration.java
[easy-adapter]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/easynpc/EasyNpcAdapter.java
[easy-state]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/easynpc/EasyNpcStateListener.java
[easy-objective]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/easynpc/EasyNpcSpellObjective.java
[easy-action]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/easynpc/EasyNpcCastAction.java
[easy-conditions]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/easynpc/EasyNpcConditions.java#L26-L101
[cn-gate]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/CustomNpcsCompat.java
[cn-init]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/customnpcs/CustomNpcsIntegration.java
[cn-adapter]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/customnpcs/CustomNpcsAdapter.java
[cn-repair]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/customnpcs/CustomNpcsAiRepair.java
[cn-event]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/customnpcs/CustomNpcsEventBridge.java
[cn-script]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/customnpcs/CustomNpcsScriptBridge.java
[cn-api]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/customnpcs/CustomNpcsScriptApiIrons.java
[cn-tests]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/customnpcs/gametest/CustomNpcsCompatGameTests.java#L39-L174
[cn-tests-casts]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/customnpcs/gametest/CustomNpcsCompatGameTests.java#L231-L300
[owner]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/generic/OwnableTeamAdapter.java#L21-L72
[raid]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/generic/RaidAllyAdapter.java#L27-L64
[sitting]: https://github.com/otectus/magic-npcs/blob/5e27810c3f65fd8bb64f7673c23c92f026b4b495/src/main/java/com/otectus/magicnpcs/compat/generic/SittingPetAdapter.java#L27-L48
[up-version]: https://github.com/iron431/irons-spells-n-spellbooks/blob/cae63a6999e24ed3deaa012ecb8c262e0e377816/gradle.properties
[up-mob]: https://github.com/iron431/irons-spells-n-spellbooks/blob/cae63a6999e24ed3deaa012ecb8c262e0e377816/src/main/java/io/redspace/ironsspellbooks/entity/mobs/abstract_spell_casting_mob/AbstractSpellCastingMob.java#L220-L342
[up-abstract]: https://github.com/iron431/irons-spells-n-spellbooks/blob/cae63a6999e24ed3deaa012ecb8c262e0e377816/src/main/java/io/redspace/ironsspellbooks/api/spells/AbstractSpell.java#L244-L471
[up-heal]: https://github.com/iron431/irons-spells-n-spellbooks/blob/cae63a6999e24ed3deaa012ecb8c262e0e377816/src/main/java/io/redspace/ironsspellbooks/spells/holy/BlessingOfLifeSpell.java#L67-L91
[up-target]: https://github.com/iron431/irons-spells-n-spellbooks/blob/cae63a6999e24ed3deaa012ecb8c262e0e377816/src/main/java/io/redspace/ironsspellbooks/api/util/Utils.java#L655-L700
[iron-file]: https://www.curseforge.com/minecraft/mc-mods/irons-spells-n-spellbooks/files/7402504
[luminous-file]: https://www.curseforge.com/minecraft/mc-mods/luminous-beasts/files/8165506
[easy-core]: https://www.curseforge.com/minecraft/mc-mods/easy-npc-core
[recruits-release]: https://modrinth.com/mod/villager-recruits/version/1.15.2
[root-tree]: https://github.com/otectus/magic-npcs/tree/5e27810c3f65fd8bb64f7673c23c92f026b4b495
[pr1]: https://github.com/otectus/magic-npcs/pull/1
