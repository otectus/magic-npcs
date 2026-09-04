# Magic NPCs Casting Behavior and Luminous Compatibility Specification

## Research baseline and scope

**Target repository:** `otectus/magic-npcs`, current `main` as researched on September 3, 2026. The checked-in build identifies itself as Magic NPCs **0.8.0** for **Minecraft 1.20.1 / Forge 47.4.16**, compiling against **Iron's Spells 'n Spellbooks 1.20.1-3.16.3**. fileciteturn15file0L2-L2

This specification addresses the three player-facing requests:

| Player concern | Current `main` status | Required coding work |
|---|---|---|
| SUPPORT/heal spells should work when the NPC is not fighting | **Already implemented generically since 0.6.0** | Preserve behavior, close regression gaps, improve validation/documentation |
| Phoenix and Witch Doctor from Luminous do not cast when they already have ranged AI | **Generic ranged-AI contention fix already exists; Luminous itself was never runtime-verified** | Reproduce against current Luminous: Beasts 1.2.7 and either certify the generic fix or add a generic fallback driver |
| Reduce Gravity Fissure's actual charge/cast duration | **Not currently supported**; `windup` only changes Magic NPCs' additional pre-cast delay | Add per-spell native cast-time overrides, diagnostics, tests, and documentation |

The first two reports were already investigated by this repository during the 0.6.0 work. The repository explicitly confirmed that out-of-combat SUPPORT was blocked by the old null-target gate and that native ranged AI could starve the casting goal because of `GoalSelector` flag/priority contention. It also explicitly recorded that **Luminous Phoenix/Witch Doctor had not been runtime-tested**, so those mobs were handled only structurally rather than empirically. fileciteturn5file0L2-L2

For Luminous testing, pin the compatibility target to **LUMINOUS: BEASTS V1.2.7, Forge 1.20.1, CurseForge file 8165506**, released May 29, 2026. The official project page categorizes the mod as MCreator-generated and All Rights Reserved. The same changelog describes newer Luminous flying mobs as projectile-based similarly to Phoenix, while Witch Doctor entered the 1.20.1 line in v1.2.5. citeturn9search3turn9search4

**Non-goals for this change:**

- Do not redesign SUPPORT into ally healing. Current SUPPORT semantics are deliberately **self-cast**; ally-targeted support requires a separate target model and ally-selection policy and was explicitly deferred in the existing architecture decision. fileciteturn4file0L2-L2
- Do not add Luminous-specific entity classes, mixins, hard-coded AI class names, or a mandatory Luminous dependency unless runtime investigation proves that no generic solution is possible.
- Do not mutate Iron's global `AbstractSpell` singleton objects to shorten casts.
- Do not change the existing meaning of `"windup"`.
- Do not make ATTACK spells fire while an NPC has no hostile target.
- Do not change player spell cast times or casts performed by unrelated Iron's entities.

## Required player-visible behavior

The implementation must present one coherent model to datapack authors:

**SUPPORT without combat.** A wounded NPC that owns a SUPPORT spell such as `irons_spellbooks:heal` must be able to self-cast it while it has no attack target. A healthy idle NPC must not continuously cast SUPPORT spells merely because it has no target. ATTACK spells must remain ineligible while targetless. The current configuration already exposes `balance.supportOutOfCombat = true` and an idle reevaluation interval of `supportOutOfCombatIntervalTicks = 100` by default. fileciteturn27file0L2-L2

**Native ranged AI coexistence.** A mob may keep throwing, shooting, or otherwise performing its own native ranged attacks while Magic NPCs independently selects spells. This is already the intended `"native_attack": "coexist"` default. `"suppress"` remains the opt-in pure-caster conversion, and `"yield"` remains the opt-in policy in which Magic NPCs backs off while native attack AI is running. fileciteturn6file0L2-L2

**Cast pacing has two independently configurable stages.** The player must be able to distinguish:

```text
Magic NPC windup
      ↓
Iron's native spell cast/channel
      ↓
spell effect
```

`"windup"` controls only the first stage. The new `"cast_time"` / `"cast_time_multiplier"` controls the second stage. Current Magic NPCs intentionally treats those as separate phases: its default wind-up is six ticks, while `MobCastSession` subsequently starts Iron's real spell lifecycle using the spell's effective cast time. fileciteturn28file0L2-L2 fileciteturn11file0L2-L2

The desired datapack syntax after this change is:

```json
{
  "entity_type": "example:caster",
  "max_mana": 300,
  "mana_regen": 10,
  "spells": [
    {
      "spell": "irons_spellbooks:gravity_fissure",
      "level": 1,
      "weight": 1,
      "role": "attack",

      "windup": 0,
      "cast_time_multiplier": 0.5,

      "min_range": 0.0,
      "max_range": 24.0
    },
    {
      "spell": "irons_spellbooks:heal",
      "level": 1,
      "weight": 1,
      "role": "support"
    }
  ]
}
```

For deterministic timing, the absolute form must also work:

```json
{
  "spell": "irons_spellbooks:gravity_fissure",
  "level": 1,
  "role": "attack",
  "windup": 0,
  "cast_time": 6
}
```

The upstream 1.20.1 Gravity Fissure implementation is a `LONG` spell whose class assigns a native `castTime` of **15 ticks**. Its effect is spawned only in `onCast`, using the caster's forward direction at release. Therefore a moving player can legitimately leave the intended position during that native charge period. fileciteturn25file0L2-L2

With the proposed resolver:

```text
base/effective Gravity Fissure cast = 15 ticks
cast_time_multiplier = 0.5
15 × 0.5 = 7.5
Math.round(...) = 8 ticks
```

Thus:

```json
"windup": 0,
"cast_time_multiplier": 0.5
```

means “remove Magic NPCs' extra pre-cast wind-up and shorten the Iron's portion to approximately half its effective duration.” It does **not** mean “shorten the lifetime of the black hole.” Gravity Fissure computes its spawned black-hole duration separately from spell power in `getDurationTicks`; that effect lifetime is not the cast time. fileciteturn25file0L2-L2

Before this feature is implemented, the best existing syntax is only:

```json
{
  "spell": "irons_spellbooks:gravity_fissure",
  "role": "attack",
  "windup": 0
}
```

That removes Magic NPCs' extra six-tick default wind-up, but **does not shorten Iron's own native Gravity Fissure cast**. The current loadout schema contains `cast_chance`, `cooldown`, `cooldown_multiplier`, and `windup`, but no native cast-time field. fileciteturn19file0L2-L2 fileciteturn31file0L2-L2

## Out-of-combat SUPPORT behavior

This requirement is substantially implemented on current `main`; the coding task here is to **retain and harden it rather than replace it**.

The original defect was straightforward: `NpcSpellAttackGoal.canUse()` required `mob.getTarget() != null`, which meant the SUPPORT selection logic could not even execute until the mob entered combat. The accepted 0.6.0 design removed that prerequisite and deliberately allows a null combat target only on the SUPPORT path. fileciteturn4file0L2-L2

Current `NpcSpellAttackGoal` already implements that model. It determines `outOfCombat` from a null/live target, checks the slower SUPPORT cadence when there is no target, and permits that branch only when the loadout actually has a SUPPORT entry and `SUPPORT_OUT_OF_COMBAT` is enabled. fileciteturn9file0L2-L2

The selector then treats roles differently: SUPPORT entries can survive the null-target path when the caster is sufficiently hurt, while ATTACK entries explicitly fail when the caster is out of combat. Mana, cooldown, per-spell held-item rules, conditions, and castability checks still apply. fileciteturn11file0L2-L2

**Required preserved semantics**

```text
target == null
    |
    +-- ATTACK  -> never eligible
    |
    +-- SUPPORT
          |
          +-- feature disabled -> never eligible
          |
          +-- health/condition gate fails -> never eligible
          |
          +-- insufficient mana / cooldown / equipment gate -> ineligible
          |
          +-- eligible -> self-cast using idle cadence
```

The health guard is important. A SUPPORT spell without an explicit reactive condition uses the configured support-health threshold. If a datapack supplies a condition that replaces that ordinary gate, current code still imposes an out-of-combat anti-loop floor when the condition contains no self-health/recent-hurt component, preventing a condition such as “enemies nearby” from causing an idle full-health NPC to cast forever. fileciteturn4file0L2-L2

Do **not** reintroduce any of these patterns:

```java
// Wrong: restores the original bug.
if (mob.getTarget() == null) {
    return false;
}
```

```java
// Wrong: makes targetless ATTACK spells fire into empty space.
if (target == null) {
    chooseFromAllRoles();
}
```

```java
// Wrong: duplicate support evaluation outside the goal/controller.
@SubscribeEvent
public void livingTick(...) {
    maybeHealAgain(...);
}
```

The current architecture intentionally keeps spell eligibility, mana, conditions, cooldowns, cast chance, and state checks in the same decision pipeline instead of inventing a second support-casting implementation. fileciteturn4file0L2-L2

**Required regression coverage**

Add or retain automated tests covering all of the following observable cases:

| Scenario | Expected result |
|---|---|
| Wounded caster, `heal` SUPPORT, no combat target | Heals without first entering combat |
| Full-health caster, `heal` SUPPORT, no target | No cast |
| Wounded caster, `supportOutOfCombat=false` | No targetless support cast |
| Targetless caster with ATTACK spell only | No cast |
| Mixed ATTACK + SUPPORT loadout, no target | Only SUPPORT may be selected |
| SUPPORT entry with condition containing no health/recent-hurt term, full health | Must not idle-loop |
| Same caster after acquiring a target | Must switch back to combat decision cadence rather than remain delayed by the long idle interval |
| Insufficient mana / cooldown active | OOC support waits normally |
| Successful OOC self-heal | No combat wind-up telegraph should be emitted |

The existing decision record specifically requires a slower idle cadence and suppresses the combat telegraph for an idle self-heal. It also notes that acquiring a combat target must not result in ATTACK behavior being governed by the five-second idle cadence. fileciteturn4file0L2-L2

No new loadout syntax is required for this part. The normal existing entry remains:

```json
{
  "spell": "irons_spellbooks:heal",
  "level": 1,
  "weight": 1,
  "role": "support"
}
```

A pack author may control how injured the NPC must be globally through `balance.supportHealthThreshold`; the current default is `0.5`, meaning the ordinary SUPPORT gate activates below half health. fileciteturn27file0L2-L2

## Native ranged AI and Luminous compatibility

The second complaint has a known generic root cause in older Magic NPCs builds, but it needs an actual **Luminous 1.2.7 compatibility run** before it can be considered fully closed.

Earlier Magic NPCs injected its casting goal at priority 2 while declaring the `LOOK` control flag. A vanilla Witch has a native `RangedAttackGoal` at priority 2 using `MOVE` and `LOOK`; because an equal-priority goal cannot replace that owner of `LOOK`, the Magic NPC goal could remain permanently starved. A bow skeleton showed the reverse symptom because its lower-priority bow goal could be preempted by Magic NPCs. The repository's accepted solution was therefore to make the casting goal declare **no control flags by default**, allowing it to coexist with native attack behavior. fileciteturn6file0L2-L2

Current code implements that decision:

```java
setFlags(
    MagicNpcsConfig.castingGoalUsesLookFlag()
        ? EnumSet.of(Flag.LOOK)
        : EnumSet.noneOf(Flag.class)
);
```

The legacy flag behavior remains opt-in through `general.castingGoalUsesLookFlag`, whose default is false. fileciteturn9file0L2-L2 fileciteturn27file0L2-L2

Therefore, **do not solve Luminous initially by changing the casting priority to 0 or 1**. That would merely turn starvation into aggressive preemption of other AI, the failure mode the existing architecture decision was designed to eliminate. fileciteturn6file0L2-L2

### Luminous reproduction gate

Use the official Forge 1.20.1 **Luminous: Beasts 1.2.7** release, file ID `8165506`, for the compatibility run. Pin that artifact in an opt-in development profile and never bundle it with Magic NPCs. The official package is All Rights Reserved, so integration should remain black-box/runtime-oriented rather than copying Luminous implementation code into this project. citeturn9search3turn9search6

Suggested dev-only Gradle shape:

```gradle
// Example only; keep opt-in and runtime-only.
if (project.hasProperty("luminousRuntime")) {
    runtimeOnly fg.deobf(
        "curse.maven:luminous-beasts-1089627:8165506"
    )
}
```

Do not hard-code an entity registry ID from an old forum post, old datapack, or old Luminous build. Luminous's own v1.2.6 changelog states that IDs were changed for compatibility and specifically says the **Phoenix registry name was changed** to match the main LUMINOUS mod. Confirm the exact registry IDs from the pinned v1.2.7 runtime before creating test loadouts. citeturn10view0

For **Phoenix** and **Witch Doctor**, construct a minimal reproduction loadout using one obvious, already-supported ATTACK spell and no probabilistic noise:

```json
{
  "entity_type": "<confirmed-v1.2.7-entity-id>",
  "max_mana": 500,
  "mana_regen": 50,
  "native_attack": "coexist",
  "spells": [
    {
      "spell": "irons_spellbooks:magic_missile",
      "level": 1,
      "weight": 1,
      "role": "attack",
      "min_range": 0.0,
      "max_range": 24.0,
      "cast_chance": 1.0,
      "cooldown": 20,
      "windup": 0
    }
  ]
}
```

Before changing code, run all three diagnostics for each mob:

```text
/magicnpcs validate
/magicnpcs loadout entity <target>
/magicnpcs why <target>
```

Current Magic NPC documentation says `/magicnpcs why` exposes the installed goals as `priority | class | flags | running` and identifies a blocking goal. It also explicitly documents that a mob which does not use the ordinary goal system may show an empty or unrelated list. fileciteturn16file0L2-L2

Classify the result into one of these paths:

| Observation | Interpretation | Implementation response |
|---|---|---|
| Magic NPC goal exists, starts, and casts | Current 0.6+ generic fix solved the report | Add pinned regression documentation/test; **no Luminous-specific code** |
| Goal exists but `why` reports a state/mana/range/LOS/spell blocker | Not a ranged-AI scheduling problem | Fix the actual generic gate only if erroneous |
| Goal exists but is somehow still blocked by control contention with `castingGoalUsesLookFlag=false` | Regression in goal setup or reconciliation | Repair generic goal injection; no mob-specific priority table |
| Goal is installed but its execution/tick path never advances | Mob's custom AI lifecycle bypasses the assumption on which `Goal` integration relies | Implement the generic fallback driver below |
| Goal gets deleted/replaced after spawn or AI state changes | Luminous rebuilds goals dynamically | Add generic goal-presence repair/reconciliation, modeled on existing integration repair seams rather than class-name special cases |
| Loadout never resolves onto the mob | Registry-id or resource/loadout problem | Correct the datapack test fixture; do not alter AI |

The repository's prior investigation explicitly warned that Luminous had not been available in its dev environment and that, if those entities did not actually tick `goalSelector`, priority and flag changes could not solve the problem. That unresolved branch is the main purpose of this compatibility gate. fileciteturn5file0L2-L2

### Conditional generic fallback driver

Implement this **only when the pinned Luminous runtime demonstrates that ordinary goal injection is insufficient**.

Do not solve it by calling Luminous's ranged-attack procedure, intercepting a Phoenix projectile method, or making Magic NPCs depend on MCreator-generated classes. Instead, refactor the existing cast state machine so the `Goal` becomes one driver of a reusable caster controller.

Target architecture:

```text
                      ┌─────────────────────────┐
                      │ NpcCastingController    │
                      │                         │
loadout/state ------> │ choose / start / tick   │
Iron's bridge ------> │ cancel / cooldown       │
                      └────────────┬────────────┘
                                   │
                    ┌──────────────┴──────────────┐
                    │                             │
          NpcSpellAttackGoal             FallbackTickDriver
          normal/primary path            conditional path only
                    │                             │
                    └──────────────┬──────────────┘
                                   │
                              MobCastSession
```

The controller, not either driver, must own:

```java
boolean canStartDecision();
boolean startDecision();
boolean canContinue();
void tickCast();
void cancel(CancelReason reason);
```

It must also own or consistently access the same:

- SUPPORT/ATTACK role selection;
- mana checks;
- `ManagedCasterState`;
- cast chance;
- cooldowns;
- held-item requirements;
- reactive conditions;
- targeting and line-of-sight checks;
- friendly-fire checks;
- native-attack policy;
- wind-up;
- cast session;
- cast event publication.

This requirement prevents the tick fallback from becoming a second source of truth. The old architecture specifically rejected an independent tick-handler implementation because it would duplicate all of those gates. A shared controller removes that objection while preserving one casting engine. fileciteturn4file0L2-L2

The fallback driver must use positive detection rather than entity IDs. For example:

```java
if (managedCaster
        && castingGoalInstalled
        && controller.lastGoalHeartbeatAge(mob.tickCount) > 40
        && controller.isOtherwiseEligible()) {
    fallbackDriver.tick(mob);
}
```

The goal path updates a heartbeat whenever the injected casting goal is actually evaluated/ticked. The tick fallback becomes active only after the goal has demonstrably stopped participating. It must immediately back off when goal execution resumes so **one cast session can never be ticked twice in the same game tick**.

Expose the selected driver through `/magicnpcs why`, for example:

```text
Casting driver: goal
Goal heartbeat: 1 tick ago
```

or:

```text
Casting driver: fallback_tick
Reason: injected casting goal has not been evaluated for 47 ticks
```

This makes future modded-mob failures observable instead of forcing pack authors to infer them from “nothing happened.”

### Luminous acceptance test

For both the confirmed v1.2.7 Phoenix and Witch Doctor entity IDs:

1. Spawn the mob with a deterministic Magic NPC loadout.
2. Give it a valid hostile target.
3. Verify `/magicnpcs why` reports an installed, viable casting path.
4. Observe at least one `MagicNpcCastEvent.Started` and `Completed`.
5. Verify the configured Iron's spell effect occurs.
6. Under `"native_attack": "coexist"`, verify the mob's own ranged behavior still occurs as well.
7. Repeat after target loss/reacquisition.
8. Repeat after chunk unload/reload.
9. Repeat after `/reload` / caster reconciliation.
10. Confirm no duplicated spells, doubled mana costs, doubled cooldown starts, or two session ticks per game tick.

The Luminous-specific test artifact may remain a manual/optional runtime profile if its licensing or distribution model makes CI installation unsuitable; the generic mechanics it exposes must still receive automated unit/GameTest coverage inside Magic NPCs. citeturn9search3turn9search6

## Native cast-time override design

This is the substantive new datapack feature needed for the Gravity Fissure request.

Today, `LoadoutEntry` has per-spell overrides for cast chance, cooldown, cooldown multiplier, and Magic NPC wind-up, but it carries no native cast-time value. fileciteturn7file0L2-L2

The parser likewise reads:

```text
cast_chance
cooldown
cooldown_multiplier
windup
condition
...
```

and has no cast-time field. fileciteturn30file0L2-L2

At execution time, `MobCastSession.begin` asks `SpellCompat.effectiveCastTime(...)` for the duration and passes that duration directly to Iron's `MagicData.initiateCast(...)`. The session then advances the real Iron's lifecycle with `handleCastDuration`, `onServerCastTick`, `onCast`, and `onServerCastComplete`. This is the correct seam for changing an NPC's individual cast duration without modifying the shared spell object. fileciteturn12file0L2-L2

`SpellCompat.effectiveCastTime` currently returns zero for `INSTANT`/`NONE`, and otherwise delegates to Iron's `spell.getEffectiveCastTime(level, caster)`. Importantly, that is the **effective** cast time, not merely the raw class field, so caster-side Iron's modifiers are already respected. fileciteturn13file0L2-L2

### Schema contract

Add these canonical spell-entry fields:

```json
"cast_time": 6,
"cast_time_multiplier": 0.5
```

Semantics:

| Field | Type | Meaning | Precedence |
|---|---:|---|---:|
| `cast_time` | integer ≥ 0 | Absolute final native cast duration in game ticks | Highest |
| `cast_time_multiplier` | finite number ≥ 0 | Multiplies Iron's effective native cast time | Second |
| omitted | — | Preserve Iron's effective cast duration unchanged | Default |

`cast_time` mirrors the existing usability pattern of `cooldown`, while `cast_time_multiplier` lets a pack say “make this NPC cast this spell 50% faster” without hard-coding the upstream duration.

Do not introduce a global cast-time multiplier in this ticket. The player's request is spell-specific, and per-spell tuning avoids unexpectedly altering every LONG/CONTINUOUS spell in existing packs.

Add constants:

```java
public static final String CAST_TIME = "cast_time";
public static final String CAST_TIME_MULTIPLIER = "cast_time_multiplier";
```

to `LoadoutJson`.

Add both to:

```java
LoadoutSchema.SPELL_KEYS
```

so strict-schema users may use them legitimately. Current unknown-key validation warns by default and can reject unknown keys under strict mode, so failing to update this set would make the newly documented feature unusable for strict pack authors. fileciteturn31file0L2-L2

Add typo/suggestion mappings, at minimum:

```java
"cast_duration"            -> "cast_time"
"casttime"                 -> "cast_time"
"cast_duration_multiplier" -> "cast_time_multiplier"
"casttime_multiplier"      -> "cast_time_multiplier"
```

Do not silently accept those aliases. The canonical serialization remains `cast_time` / `cast_time_multiplier`; typo handling should point authors at the documented names.

### Data model

Extend `LoadoutEntry`:

```java
public record LoadoutEntry(
        ResourceLocation spell,
        int level,
        int weight,
        double minRange,
        double maxRange,
        double safetyRadius,
        Role role,
        Double castChance,
        Integer cooldownTicks,
        Double cooldownMultiplier,

        Integer castTimeTicks,
        Double castTimeMultiplier,

        Integer windupTicks,
        CastCondition condition,
        boolean requireHeldItem,
        List<String> requiredItems,
        HandRequirement requiredHand
) {
}
```

Place native cast timing next to the other pacing fields. Update every compatibility constructor so omitted fields remain `null`; this guarantees existing source call sites and generated loadouts retain today's behavior.

Serializer:

```java
if (entry.castTimeTicks() != null) {
    o.addProperty(CAST_TIME, entry.castTimeTicks());
}
if (entry.castTimeMultiplier() != null) {
    o.addProperty(CAST_TIME_MULTIPLIER, entry.castTimeMultiplier());
}
```

The existing serializer deliberately emits optional pacing fields only when explicitly set; preserve that pattern so old loadout JSON does not gain new noise. fileciteturn20file0L2-L2

### Parser validation

Do not silently coerce malformed values.

Recommended parser behavior:

```java
Integer castTime = null;
if (o.has(LoadoutJson.CAST_TIME)) {
    castTime = getInt(...);
    if (castTime < 0) {
        problems.add(error(
            "CAST_TIME_NEGATIVE",
            pointer + "/cast_time",
            "cast_time must be zero or greater"
        ));
        return null;
    }
}

Double castTimeMultiplier = null;
if (o.has(LoadoutJson.CAST_TIME_MULTIPLIER)) {
    double value = GsonHelper.getAsDouble(...);

    if (!Double.isFinite(value) || value < 0.0) {
        problems.add(error(
            "CAST_TIME_MULTIPLIER_INVALID",
            pointer + "/cast_time_multiplier",
            "cast_time_multiplier must be a finite number zero or greater"
        ));
        return null;
    }

    castTimeMultiplier = value;
}
```

When both fields exist, accept the loadout but emit a clear informational diagnostic:

```text
CAST_TIME_ABSOLUTE_WINS:
cast_time overrides cast_time_multiplier for this spell.
```

This follows the same mental model already documented for explicit `cooldown` versus `cooldown_multiplier`. fileciteturn16file0L2-L2

### Resolver

Do not overwrite:

```java
spell.castTime
```

and do not use reflection to alter it. Iron's spell instances are registry objects used outside this NPC's cast, while `MagicData.initiateCast` already receives a duration for the individual cast session. `MobCastSession` is therefore the correct isolation boundary. fileciteturn12file0L2-L2

Add an overload or helper in `SpellCompat`:

```java
public static int effectiveCastTime(
        AbstractSpell spell,
        int level,
        LivingEntity caster,
        Integer absoluteTicks,
        Double multiplier
) {
    CastType type = spell.getCastType();

    // Preserve existing semantics for non-duration spells.
    if (type == CastType.INSTANT || type == CastType.NONE) {
        return 0;
    }

    int base = Math.max(
        0,
        spell.getEffectiveCastTime(level, caster)
    );

    // No override = exact backward compatibility.
    if (absoluteTicks == null && multiplier == null) {
        return base;
    }

    if (absoluteTicks != null) {
        // A LONG/CONTINUOUS cast still needs a real session tick.
        return Math.max(1, absoluteTicks);
    }

    double scaled = base * multiplier;

    if (!Double.isFinite(scaled)) {
        // Should have been prevented at parse time; fail safely.
        return base;
    }

    long rounded = Math.round(scaled);

    return (int) Math.min(
        Integer.MAX_VALUE,
        Math.max(1L, rounded)
    );
}
```

The rule “no override returns `base` exactly” is deliberate. Do not normalize an odd upstream zero-duration LONG spell to one tick unless the datapack actually opts into the new field.

Precedence is therefore:

```text
INSTANT / NONE
      -> 0

LONG / CONTINUOUS
      |
      +-- cast_time present
      |      -> max(1, cast_time)
      |
      +-- cast_time_multiplier present
      |      -> max(1, round(effective * multiplier))
      |
      +-- neither
             -> effective cast time unchanged
```

A multiplier is applied to **Iron's effective cast time**, not the raw `castTime` member. That preserves any cast-speed attributes or other caster-dependent modifications Iron's applies through `getEffectiveCastTime`. fileciteturn13file0L2-L2

### Session API

Keep `MobCastSession` independent of datapack records.

Preferred API:

```java
public static Start begin(
        Mob caster,
        LivingEntity target,
        AbstractSpell spell,
        int level,
        int resolvedCastTime,
        MagicNpcCastEvent.CastSource eventSource
)
```

The existing overload should remain:

```java
public static Start begin(
        Mob caster,
        LivingEntity target,
        AbstractSpell spell,
        int level,
        MagicNpcCastEvent.CastSource eventSource
)
```

and delegate using the ordinary Iron's effective cast time.

This is important for scripted/detached casting. A dialog action or API call that merely says “cast Gravity Fissure” should not unexpectedly inherit a random loadout entry's timing override unless that API explicitly elects to do so.

`NpcSpellAttackGoal.beginCast()` should resolve the selected entry's value and pass it into the explicit-duration overload:

```java
int castTime = SpellCompat.effectiveCastTime(
    chosen.spell(),
    effectiveLevel(chosen),
    mob,
    chosen.entry().castTimeTicks(),
    chosen.entry().castTimeMultiplier()
);

MobCastSession.Start start = MobCastSession.begin(
    mob,
    target,
    chosen.spell(),
    effectiveLevel(chosen),
    castTime,
    MagicNpcCastEvent.CastSource.AI
);
```

Everything after `MagicData.initiateCast(...)` must remain the normal session. Do not “make a fast cast” by calling `spell.onCast(...)` early. The current session exists precisely because LONG/CONTINUOUS spells need Iron's pre-cast, cast-tick, effect, completion, cancellation, and cast-data lifecycle to run correctly. fileciteturn12file0L2-L2

### Continuous-spell semantics

Document one important consequence: for a `LONG` spell such as Gravity Fissure, reducing cast time primarily shortens the wait before `onCast` commits the effect. For a `CONTINUOUS` spell, cast duration is also the length of the channel, so reducing it can reduce the number of channel/effect opportunities. `MobCastSession` advances continuous casts over their duration and invokes their effects on Iron's cadence, so shortening that duration is intentionally a gameplay/balance change rather than merely an animation speed-up. fileciteturn12file0L2-L2

### Diagnostics

Current caster diagnostics already surface each entry's cooldown, mana cost, and planned wind-up. fileciteturn32file0L1-L18

Extend them with both the base/effective and resolved durations. Example:

```text
irons_spellbooks:gravity_fissure
  role: attack
  level: 1
  windup: 0t
  cast: 8t
  iron_effective_cast: 15t
  cast_time_multiplier: 0.5x
  cooldown: 900t
  mana: ...
```

Absolute form:

```text
cast: 6t
iron_effective_cast: 15t
cast_time: 6t (absolute override)
```

No override:

```text
cast: 15t (Iron's effective)
```

Instant spell with a useless override:

```text
cast: 0t (instant)
note: cast_time is ignored because this spell is INSTANT
```

Also add a validator warning or informational record when `cast_time` or `cast_time_multiplier` is supplied to an `INSTANT`/`NONE` spell. This catches a common pack-author mistake without rejecting an otherwise harmless file.

## Verification, documentation, and acceptance

The implementation is complete only when all three player concerns are demonstrably closed at the configuration, runtime, and documentation layers.

**Core parser/unit coverage**

Extend `LoadoutParseTest`, `LoadoutSchemaTest`, and serialization/round-trip coverage with:

```text
cast_time parses
cast_time_multiplier parses
both survive JSON round-trip
omitted values remain null
negative cast_time rejected
negative multiplier rejected
non-finite multiplier rejected
absolute field wins when both are present
strict schema recognizes both fields
cast_duration typo suggests cast_time
```

The project already maintains parser/schema tests alongside the core loadout implementation, so these additions should live in the existing test structure rather than a separate test framework. fileciteturn1file0L2-L2

**Cast-time resolver coverage**

At minimum:

```text
effective=15, no override             -> 15
effective=15, multiplier=0.5          -> 8
effective=15, multiplier=2.0          -> 30
effective=15, absolute=6              -> 6
effective=15, absolute=6, mult=0.5    -> 6
LONG, absolute=0                      -> 1
CONTINUOUS, multiplier=0              -> 1
INSTANT, absolute=10                  -> 0
NONE, multiplier=2                    -> 0
```

**Iron's lifecycle GameTest**

Use a known LONG spell and observe actual tick timing through the cast session/events. The following must remain true:

```text
Pre/Started event
    ↓
MagicData.initiateCast(resolved duration)
    ↓
normal per-tick session processing
    ↓
onCast at completion
    ↓
onServerCastComplete
    ↓
Completed event
```

Verify that:

- mana is deducted once;
- cooldown begins once;
- `onServerPreCast` remains called;
- cast ticks continue normally;
- cancellation still invokes Iron's completion cleanup;
- cast data is cleaned up;
- no-override behavior has the same duration as before this feature;
- scripted/detached casts continue using ordinary Iron's timing unless explicitly extended later.

Those lifecycle requirements follow the session design already present in current code. fileciteturn12file0L2-L2

**Gravity Fissure regression**

Create a test or controlled runtime fixture using:

```json
{
  "spell": "irons_spellbooks:gravity_fissure",
  "level": 1,
  "role": "attack",
  "windup": 0,
  "cast_time_multiplier": 0.5
}
```

With an unmodified effective base of 15 ticks, the resolved native duration must report and execute as eight ticks. The upstream spell is LONG and performs its actual black-hole spawn at cast completion, so the test should measure the release/effect point rather than merely seeing the cast animation start. fileciteturn25file0L2-L2

Separately verify:

```json
{
  "spell": "irons_spellbooks:gravity_fissure",
  "role": "attack",
  "windup": 6,
  "cast_time": 6
}
```

The test should demonstrate that the six Magic NPC wind-up ticks and six Iron's cast ticks are distinct phases, not one field overriding the other. Current code already separates wind-up from the session's effective Iron's duration; retain that architecture. fileciteturn11file0L2-L2

**SUPPORT acceptance**

A release candidate passes only when a wounded caster can heal itself with `target == null` without first being attacked, while a full-health caster does not loop SUPPORT casts and an ATTACK-only caster remains inert without a target. This is established current behavior and must be protected from regression. fileciteturn4file0L2-L2

**Native-AI acceptance**

Vanilla Witch and ranged skeleton tests must continue to demonstrate coexistence because they are the reference examples of the original equal/lower-priority conflict. The goal must remain flagless by default, and setting `castingGoalUsesLookFlag=true` may intentionally reproduce legacy contention for operators who explicitly request it. fileciteturn6file0L2-L2

The release gate for the player's actual report additionally requires a **pinned Luminous: Beasts 1.2.7 Forge 1.20.1 run** for Phoenix and Witch Doctor. It is insufficient to conclude compatibility solely from the vanilla Witch test because the repository's own investigation explicitly left these two Luminous entities unverified. fileciteturn5file0L2-L2 citeturn9search3

The acceptable result is one of:

```text
A. Current generic Goal solution works on both Luminous mobs:
   certify it, add regression evidence, ship no Luminous-specific code.

B. One/both mobs bypass normal Goal execution:
   ship the shared-controller + fallback-driver architecture,
   prove it fixes them without special-casing their entity IDs.
```

**Documentation changes**

Update `docs/loadouts/README.md`, the main README/help where pacing fields are summarized, `/magicnpcs validate` guidance, and `CHANGELOG.md`.

The loadout documentation should contain this exact conceptual explanation:

```text
windup
  Magic NPCs-specific delay before Iron's starts casting.
  Set to 0 to remove the extra telegraph/aim delay.

cast_time
  Absolute duration, in ticks, of Iron's own LONG/CONTINUOUS cast
  for this NPC spell entry.

cast_time_multiplier
  Scales Iron's effective native cast duration.
  0.5 = roughly twice as fast.
  2.0 = twice as long.

For LONG spells, this usually changes time-to-release.
For CONTINUOUS spells, this also changes channel length.
Neither field changes the lifetime of an effect spawned after casting.
```

Include the Gravity Fissure answer prominently:

```json
{
  "spell": "irons_spellbooks:gravity_fissure",
  "role": "attack",
  "windup": 0,
  "cast_time_multiplier": 0.5
}
```

and an absolute alternative:

```json
{
  "spell": "irons_spellbooks:gravity_fissure",
  "role": "attack",
  "windup": 0,
  "cast_time": 6
}
```

The documentation must also make clear that **current releases before this change have no native cast-duration syntax**: `"windup": 0` only removes Magic NPCs' pre-cast delay. The present schema and parser substantiate that distinction. fileciteturn19file0L2-L2 fileciteturn30file0L2-L2

**Final definition of done**

| Requirement | Release gate |
|---|---|
| OOC heal | Injured SUPPORT caster self-heals with no combat target |
| OOC safety | Full-health idle caster does not spam; ATTACK never casts targetless |
| Existing config | `supportOutOfCombat` and idle cadence retain current semantics |
| Vanilla ranged AI | Witch and skeleton can cast under `"coexist"` without their native attacks being suppressed |
| Luminous Phoenix | Verified on Luminous: Beasts 1.2.7 Forge 1.20.1 |
| Luminous Witch Doctor | Verified on the same pinned build |
| Non-Goal AI, if encountered | Generic fallback driver implemented with no entity-ID special case and no double ticking |
| New schema | `cast_time` and `cast_time_multiplier` parse, serialize, validate, and diagnose correctly |
| Backward compatibility | Omitting both new fields produces exactly the previous Iron's effective duration |
| Gravity Fissure | `0.5` multiplier resolves an effective 15-tick cast to 8 ticks; absolute form works |
| Iron's lifecycle | Pre-cast, tick, effect, completion/cancel cleanup, mana, and cooldown behavior remain intact |
| Isolation | No mutation of shared Iron's spell definitions; no change to player/unrelated mob cast times |
| Documentation | Clearly distinguishes `windup`, native cast time, cooldown, and post-cast effect duration |