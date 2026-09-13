package com.otectus.magicnpcs.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.otectus.magicnpcs.compat.IronsCompat;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.loadout.LoadoutParser;
import com.otectus.magicnpcs.core.loadout.LoadoutProblem;
import com.otectus.magicnpcs.core.loadout.LoadoutRecord;
import com.otectus.magicnpcs.core.loadout.LoadoutSourceTier;
import com.otectus.magicnpcs.integration.irons.IronsCastingTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * GameTest harness. Run with {@code ./gradlew runGameTestServer}; the
 * {@code build.gradle} {@code gameTestServer} run target gates on the
 * {@code magicnpcs} namespace so unrelated mods' tests do not run.
 *
 * <p>Running {@code runGameTestServer} <b>offline</b> (no Iron's/Recruits) already
 * verifies the most important thing: the mod <b>boots</b> in a real server — the
 * log shows {@code magicnpcs … DONE} + "Started game test server", proving
 * {@code mods.toml} is valid, the mod boots with both optional dependencies absent,
 * the config registers, and the "Iron's absent → spellcasting disabled, no crash"
 * soft-dep path holds.
 *
 * <p>{@link #bootSanity} runs on the shipped {@code platform} structure template
 * ({@code data/magicnpcs/structures/platform.nbt}) and passes offline.
 *
 * <p>The casting scenarios below ({@link #skeletonCastsMagicMissile}, {@link #recruitCasts})
 * need the full runtime (Iron's + Curios + PlayerAnimator,
 * and Recruits for the recruit cases), which is not present in the dev environment — see
 * {@code docs/dev-runtime.md}. Each is gated through {@link PositiveProfile#require}, which decides
 * between the two runs this harness supports:
 *
 * <ul>
 *   <li><b>Absence smoke run</b> (no profile selected): an absent Iron's makes the test succeed
 *       immediately, so the offline run stays green and proves the soft-dependency path.</li>
 *   <li><b>Required-positive run</b> ({@code -PtestRuntimeProfile=<name>}): the profile names the mods
 *       that must be loaded, and a test whose host is missing <em>fails</em> naming it, instead of
 *       quietly reporting success. {@code verifyPositiveGameTests} then rejects a run in which no
 *       positive path executed at all — the MN-015 hole, where a green report and a report where
 *       nothing ran looked identical.</li>
 * </ul>
 */
@GameTestHolder("magicnpcs")
@PrefixGameTestTemplate(false)
public final class MagicNpcsGameTests {

    private MagicNpcsGameTests() {
    }

    /** Offline boot check: the mod loads and the gametest server starts cleanly. */
    @GameTest(template = "platform")
    public static void bootSanity(GameTestHelper helper) {
        helper.succeed();
    }

    /**
     * 0.9.0: a loadout for a mod that is not installed is SKIPPED, not REJECTED (I1).
     *
     * <p>Runs through {@link LoadoutManager#liveChecks()} rather than a stub, because the thing worth
     * proving is that the live {@code ModList} path answers "absent" for an absent namespace — the unit
     * tests already cover the parser's branches.
     */
    @GameTest(template = "platform")
    public static void absentModLoadoutIsSkippedNotRejected(GameTestHelper helper) {
        JsonElement json = JsonParser.parseString("""
                {
                  "entity_type": "magicnpcs_test_absent:thing",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        LoadoutRecord record = LoadoutParser.parse(new ResourceLocation("magicnpcs", "absent_mod_test"),
                json, "<gametest>", LoadoutSourceTier.DATAPACK, false, null, null,
                LoadoutManager.liveChecks());
        if (record.status() != LoadoutRecord.Status.INAPPLICABLE) {
            helper.fail("expected INAPPLICABLE for an absent mod, got " + record.status());
            return;
        }
        for (LoadoutProblem problem : record.problems()) {
            if (problem.severity() == LoadoutProblem.Severity.ERROR) {
                helper.fail("an absent mod must not produce an error: " + problem.describe());
                return;
            }
        }
        helper.succeed();
    }

    // --- Runtime casting checks (require Iron's; recruit cases also require Recruits) ---
    //
    // Each asks PositiveProfile whether its host is required and, only then, delegates to the
    // Iron's-side IronsCastingTests (lazily classloaded, so the offline run never touches Iron's).
    // Without Iron's and without a selected profile they succeed immediately (skip) and are counted
    // as skips; under a profile that requires Iron's they fail, naming it. Marked required=false so a
    // runtime-specific flake never fails the whole smoke suite; the offline boot gate (bootSanity)
    // stays required, and verifyPositiveGameTests is what makes the positive run's counts binding.

    /** Universal path: a skeleton with the shipped loadout spends mana casting at a target. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void skeletonCastsMagicMissile(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "skeletonCastsMagicMissile")) {
            return;
        }
        IronsCastingTests.skeletonCastsMagicMissile(helper);
    }

    /**
     * Target spells: Iron's pre-cast helpers raycast along the caster's facing, so a caster spawned
     * looking away from its target must still start the cast — the session snaps facing first.
     */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void targetSpellStartsWhenCasterFacesAway(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "targetSpellStartsWhenCasterFacesAway")) {
            return;
        }
        IronsCastingTests.targetSpellStartsWhenCasterFacesAway(helper);
    }

    /**
     * Cast-chance gate: with the global castChance forced to 0, a caster never spends mana. Runs in
     * its own batch — it mutates the shared {@code castChance} config, so isolating it keeps it from
     * starving the concurrently-running casting tests in the default batch (and vice-versa).
     */
    @GameTest(template = "platform", timeoutTicks = 200, required = false, batch = "globalCastChance")
    public static void castChanceZeroNeverCasts(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "castChanceZeroNeverCasts")) {
            return;
        }
        IronsCastingTests.castChanceZeroNeverCasts(helper);
    }

    /** Reactive condition: an execute spell fires only after the target drops below its HP threshold. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void executeConditionGatesCast(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "executeConditionGatesCast")) {
            return;
        }
        IronsCastingTests.executeConditionGatesCast(helper);
    }

    /** Aim fix: a caster facing away fires a projectile toward the target on the windup=0 path. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void windupAimsAtTarget(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "windupAimsAtTarget")) {
            return;
        }
        IronsCastingTests.windupAimsAtTarget(helper);
    }

    /**
     * Focus gate: empty-handed blocks casting; holding a tagged focus allows it. Runs in its own batch
     * — it toggles the shared {@code requireSpellFocus} config, which would otherwise block the
     * empty-handed casters in the default batch's casting tests.
     */
    @GameTest(template = "platform", timeoutTicks = 200, required = false, batch = "requireFocus")
    public static void focusGateRequiresHeldFocus(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "focusGateRequiresHeldFocus")) {
            return;
        }
        IronsCastingTests.focusGateRequiresHeldFocus(helper);
    }

    /** Bad-id convenience: a bare spell id auto-resolves under irons_spellbooks and casts. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void bareSpellIdAutoNamespaces(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "bareSpellIdAutoNamespaces")) {
            return;
        }
        IronsCastingTests.bareSpellIdAutoNamespaces(helper);
    }

    /** Per-spell cast_chance=0 blocks casting even when the global castChance is 1. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void perSpellCastChanceZeroNeverCasts(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "perSpellCastChanceZeroNeverCasts")) {
            return;
        }
        IronsCastingTests.perSpellCastChanceZeroNeverCasts(helper);
    }

    /** Target-data fix: devour (target-entity spell) casts once the bridge supplies TargetEntityCastData. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void devourCastsWithTargetData(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "devourCastsWithTargetData")) {
            return;
        }
        IronsCastingTests.devourCastsWithTargetData(helper);
    }

    /** Long-cast lifecycle: a LONG spell (root) fires after its cast time, not instantly. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void longCastCompletesAfterCastTime(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "longCastCompletesAfterCastTime")) {
            return;
        }
        IronsCastingTests.longCastCompletesAfterCastTime(helper);
    }

    /** Stomp handling: the forward ground-AoE spawns in front of the caster, toward the target. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void stompAoeFiresForward(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "stompAoeFiresForward")) {
            return;
        }
        IronsCastingTests.stompAoeFiresForward(helper);
    }

    // --- 0.6.0 behaviour changes (W1/W2/W3b) ---

    /**
     * W2: a witch casts even though its own {@code RangedAttackGoal} sits at the same priority holding
     * the LOOK flag — and keeps that goal.
     */
    @GameTest(template = "platform", timeoutTicks = 300, required = false)
    public static void witchCastsAlongsideItsRangedGoal(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "witchCastsAlongsideItsRangedGoal")) {
            return;
        }
        IronsCastingTests.witchCastsAlongsideItsRangedGoal(helper);
    }

    /** W2/W3: a bow skeleton both shoots arrows and casts, instead of casting in place of shooting. */
    @GameTest(template = "platform", timeoutTicks = 400, required = false)
    public static void skeletonShootsAndCasts(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "skeletonShootsAndCasts")) {
            return;
        }
        IronsCastingTests.skeletonShootsAndCasts(helper);
    }

    /** W1: a wounded caster with no target self-heals. */
    @GameTest(template = "platform", timeoutTicks = 400, required = false)
    public static void woundedCasterHealsOutOfCombat(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "woundedCasterHealsOutOfCombat")) {
            return;
        }
        IronsCastingTests.woundedCasterHealsOutOfCombat(helper);
    }

    /** W1 anti-loop guard: a full-health caster with no target never casts. */
    @GameTest(template = "platform", timeoutTicks = 400, required = false)
    public static void fullHealthCasterNeverCastsOutOfCombat(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "fullHealthCasterNeverCastsOutOfCombat")) {
            return;
        }
        IronsCastingTests.fullHealthCasterNeverCastsOutOfCombat(helper);
    }

    /** W1 acceptance criterion: ATTACK spells are never selected without a target. */
    @GameTest(template = "platform", timeoutTicks = 400, required = false)
    public static void attackSpellIsNeverCastWithoutATarget(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "attackSpellIsNeverCastWithoutATarget")) {
            return;
        }
        IronsCastingTests.attackSpellIsNeverCastWithoutATarget(helper);
    }

    /** W3(b): a configured cooldown corresponds to real game ticks, not roughly double. */
    @GameTest(template = "platform", timeoutTicks = 500, required = false)
    public static void cooldownIsMeasuredInRealGameTicks(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "cooldownIsMeasuredInRealGameTicks")) {
            return;
        }
        IronsCastingTests.cooldownIsMeasuredInRealGameTicks(helper);
    }

    /** Adapter path: a Villager Recruit casts (skips if Recruits is absent). */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void recruitCasts(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "recruitCasts")) {
            return;
        }
        IronsCastingTests.recruitCasts(helper);
    }

    /**
     * Luminous hypothesis stand-in: a foreign MOVE+LOOK ranged goal at a strictly better priority than
     * the casting goal must not starve it under the default coexist policy.
     */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void castingGoalStartsUnderStrictlyHigherPriorityLookGoal(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "castingGoalStartsUnderStrictlyHigherPriorityLookGoal")) {
            return;
        }
        IronsCastingTests.castingGoalStartsUnderStrictlyHigherPriorityLookGoal(helper);
    }

    /**
     * Luminous hypothesis stand-in: a goal class from an uncompilable mod is reachable by pattern
     * alone, gates "yield", and survives a suppress/release round trip unchanged.
     */
    @GameTest(template = "platform", timeoutTicks = 100, required = false)
    public static void patternRecognisedForeignGoalHonoursSuppressAndYield(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "patternRecognisedForeignGoalHonoursSuppressAndYield")) {
            return;
        }
        IronsCastingTests.patternRecognisedForeignGoalHonoursSuppressAndYield(helper);
    }

    /**
     * Luminous hypothesis stand-in: a wiped goal selector is detected as no longer intact and a
     * queued reconcile restores exactly one working casting goal.
     */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void goalWipeIsDetectedAndRepairedThroughReconcile(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "goalWipeIsDetectedAndRepairedThroughReconcile")) {
            return;
        }
        IronsCastingTests.goalWipeIsDetectedAndRepairedThroughReconcile(helper);
    }

    /**
     * Luminous hypothesis stand-in: a caster whose AI never runs the goal selector is reported by
     * {@code /magicnpcs why} as a stale heartbeat, not as a passing gate.
     */
    @GameTest(template = "platform", timeoutTicks = 150, required = false)
    public static void whyReportsStaleHeartbeatWhenGoalIsNeverEvaluated(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "whyReportsStaleHeartbeatWhenGoalIsNeverEvaluated")) {
            return;
        }
        IronsCastingTests.whyReportsStaleHeartbeatWhenGoalIsNeverEvaluated(helper);
    }

    /**
     * The 0.6.1 report: a mob that was already loaded when the datapack arrived must become a caster.
     *
     * <p>Marked {@code required = true}: this is the regression the corrective release exists for, and
     * a test that is allowed to skip is a test that cannot hold a fix in place. It still self-skips
     * when Iron's is absent, but in a dependency-present run it must pass.
     */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void existingMobBecomesCasterOnReconcile(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "existingMobBecomesCasterOnReconcile")) {
            return;
        }
        IronsCastingTests.existingMobBecomesCasterOnReconcile(helper);
    }

    /** A reload must not refill mana or clear cooldowns. */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void reconcilePreservesManaAndCooldowns(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "reconcilePreservesManaAndCooldowns")) {
            return;
        }
        IronsCastingTests.reconcilePreservesManaAndCooldowns(helper);
    }

    /** Turning the master switch off stops an already-installed goal, not just future ones. */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void masterSwitchBlocksInstalledGoal(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "masterSwitchBlocksInstalledGoal")) {
            return;
        }
        IronsCastingTests.masterSwitchBlocksInstalledGoal(helper);
    }

    /** native_attack "suppress" hands the mob's own attack goals back when it is released. */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void nativeAttackSuppressionIsReversible(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "nativeAttackSuppressionIsReversible")) {
            return;
        }
        IronsCastingTests.nativeAttackSuppressionIsReversible(helper);
    }

    /** A pure caster backs away from a target inside its minimum range. */
    @GameTest(template = "platform", timeoutTicks = 200)
    public static void casterKeepsItsDistance(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "casterKeepsItsDistance")) {
            return;
        }
        IronsCastingTests.casterKeepsItsDistance(helper);
    }

    /** With native_attack=coexist the movement goal stands down — no change for existing worlds. */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void movementStandsDownWhenNotSuppressed(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "movementStandsDownWhenNotSuppressed")) {
            return;
        }
        IronsCastingTests.movementStandsDownWhenNotSuppressed(helper);
    }

    /** A caster holds position while channelling, so it does not throw away its own aim. */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void casterDoesNotMoveWhileChannelling(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "casterDoesNotMoveWhileChannelling")) {
            return;
        }
        IronsCastingTests.casterDoesNotMoveWhileChannelling(helper);
    }

    /** A tamed companion ordered to sit does not cast. */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void sittingPetDoesNotCast(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "sittingPetDoesNotCast")) {
            return;
        }
        IronsCastingTests.sittingPetDoesNotCast(helper);
    }

    /** A spell with no verified mob-cast strategy is refused before anything is spent. */
    @GameTest(template = "platform", timeoutTicks = 100)
    public static void unsupportedSpellIsNotCastAndCostsNoMana(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "unsupportedSpellIsNotCastAndCostsNoMana")) {
            return;
        }
        IronsCastingTests.unsupportedSpellIsNotCastAndCostsNoMana(helper);
    }

    /** 0.6.1: an unmapped profession must not permanently disqualify a villager from ever casting. */
    @GameTest(template = "platform", timeoutTicks = 100, required = false)
    public static void unmappedProfessionVillagerStaysRecheckable(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "unmappedProfessionVillagerStaysRecheckable")) {
            return;
        }
        IronsCastingTests.unmappedProfessionVillagerStaysRecheckable(helper);
    }

    /** 0.6.1: a hand-set school outranks an explicit loadout and survives the chunk-reload round trip. */
    @GameTest(template = "platform", timeoutTicks = 100, required = false)
    public static void manualSchoolSurvivesReinjection(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "manualSchoolSurvivesReinjection")) {
            return;
        }
        IronsCastingTests.manualSchoolSurvivesReinjection(helper);
    }

    /** 0.6.1: "clear" must stay cleared across a reload, not just until the chunk unloads. */
    @GameTest(template = "platform", timeoutTicks = 100, required = false)
    public static void manualClearStaysCleared(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "manualClearStaysCleared")) {
            return;
        }
        IronsCastingTests.manualClearStaysCleared(helper);
    }

    /** 0.9.0: cast_time_multiplier 0.5 turns Gravity Fissure's 15-tick charge into 8 real ticks. */
    @GameTest(template = "platform", timeoutTicks = 300, required = false)
    public static void gravityFissureHalfMultiplierCastsInEightTicks(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "gravityFissureHalfMultiplierCastsInEightTicks")) {
            return;
        }
        IronsCastingTests.gravityFissureHalfMultiplierCastsInEightTicks(helper);
    }

    /** 0.9.0: windup and an absolute cast_time are separate delays, and absolute beats the multiplier. */
    @GameTest(template = "platform", timeoutTicks = 300, required = false)
    public static void gravityFissureAbsoluteSixWithWindupSix(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "gravityFissureAbsoluteSixWithWindupSix")) {
            return;
        }
        IronsCastingTests.gravityFissureAbsoluteSixWithWindupSix(helper);
    }

    /** 0.9.0: an entry with neither override charges for exactly Iron's effective cast time. */
    @GameTest(template = "platform", timeoutTicks = 300, required = false)
    public static void noOverrideKeepsIronsEffectiveDuration(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "noOverrideKeepsIronsEffectiveDuration")) {
            return;
        }
        IronsCastingTests.noOverrideKeepsIronsEffectiveDuration(helper);
    }

    /** 0.9.0: a scripted (SCRIPT-source) cast keeps Iron's timing, not the loadout's override. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void scriptedCastIgnoresLoadoutOverride(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "scriptedCastIgnoresLoadoutOverride")) {
            return;
        }
        IronsCastingTests.scriptedCastIgnoresLoadoutOverride(helper);
    }

    /** 0.9.0: the cast session advances at most once per game tick, however often the goal is ticked. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void sameTickDoubleTickIsIgnored(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "sameTickDoubleTickIsIgnored")) {
            return;
        }
        IronsCastingTests.sameTickDoubleTickIsIgnored(helper);
    }

    // --- 0.9.0: out-of-combat SUPPORT regression coverage (ADR 0005) ---

    /** 0.9.0: a wounded caster with no target completes a self-heal and gains health. */
    @GameTest(template = "platform", timeoutTicks = 400, required = false)
    public static void woundedIdleCasterSelfHeals(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "woundedIdleCasterSelfHeals")) {
            return;
        }
        IronsCastingTests.woundedIdleCasterSelfHeals(helper);
    }

    /**
     * 0.9.0: with supportOutOfCombat off, an idle wounded caster never starts a cast. Runs in its own
     * batch — it flips the shared config, which would otherwise stop the idle casters in the default
     * batch from ever casting.
     */
    @GameTest(template = "platform", timeoutTicks = 400, required = false, batch = "supportOutOfCombat")
    public static void supportOutOfCombatDisabledNeverCasts(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "supportOutOfCombatDisabledNeverCasts")) {
            return;
        }
        IronsCastingTests.supportOutOfCombatDisabledNeverCasts(helper);
    }

    /** 0.9.0: acquiring a target is served at the combat cadence, not the idle one. */
    @GameTest(template = "platform", timeoutTicks = 400, required = false)
    public static void targetAcquisitionUsesCombatCadence(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "targetAcquisitionUsesCombatCadence")) {
            return;
        }
        IronsCastingTests.targetAcquisitionUsesCombatCadence(helper);
    }

    /** 0.9.0: an out-of-combat self-heal plays no wind-up telegraph. */
    @GameTest(template = "platform", timeoutTicks = 400, required = false)
    public static void outOfCombatHealSkipsTelegraph(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "outOfCombatHealSkipsTelegraph")) {
            return;
        }
        IronsCastingTests.outOfCombatHealSkipsTelegraph(helper);
    }

    /** 0.9.0: an idle caster with no mana waits for it, then heals. */
    @GameTest(template = "platform", timeoutTicks = 400, required = false)
    public static void outOfCombatHealWaitsForMana(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "outOfCombatHealWaitsForMana")) {
            return;
        }
        IronsCastingTests.outOfCombatHealWaitsForMana(helper);
    }
    /**
     * 0.9.0: a {@code spells.capabilityOverrides} entry promotes an Iron's spell the built-in table
     * marks unsupported. Its own batch, because it mutates shared config while it runs.
     */
    @GameTest(template = "platform", timeoutTicks = 200, required = false, batch = "capabilityOverrides")
    public static void capabilityOverrideEnablesUnsupportedIronsSpell(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID, "capabilityOverrideEnablesUnsupportedIronsSpell")) {
            return;
        }
        IronsCastingTests.capabilityOverrideEnablesUnsupportedIronsSpell(helper);
    }

    // --- 0.9.1 regression wrappers (roadmap REG-01, REG-03, REG-05) -----------------------------

    /** REG-05: a cast started from inside a completion callback must not corrupt the driver. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void callbackCreatedCastDoesNotCorruptTheDetachedDriver(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID,
                "callbackCreatedCastDoesNotCorruptTheDetachedDriver")) {
            return;
        }
        IronsCastingTests.callbackCreatedCastDoesNotCorruptTheDetachedDriver(helper);
    }

    /** REG-03: competing requests for one caster accept at most one session and charge it once. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void competingRequestsProduceAtMostOneAcceptedSession(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID,
                "competingRequestsProduceAtMostOneAcceptedSession")) {
            return;
        }
        IronsCastingTests.competingRequestsProduceAtMostOneAcceptedSession(helper);
    }

    /** REG-01: an alias spelling and the canonical id share one cooldown. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void anAliasAndItsCanonicalFormShareOneCooldown(GameTestHelper helper) {
        if (!PositiveProfile.require(helper, IronsCompat.MODID,
                "anAliasAndItsCanonicalFormShareOneCooldown")) {
            return;
        }
        IronsCastingTests.anAliasAndItsCanonicalFormShareOneCooldown(helper);
    }
}
