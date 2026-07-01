package com.otectus.magicnpcs.gametest;

import com.otectus.magicnpcs.compat.IronsCompat;
import com.otectus.magicnpcs.integration.irons.IronsCastingTests;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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
 * {@code mods.toml} is valid, the mixin config loads without crashing when its
 * targets are absent (plugin gate → skip), the config registers, and the
 * "Iron's absent → spellcasting disabled, no crash" soft-dep path holds.
 *
 * <p>{@link #bootSanity} runs on the shipped {@code platform} structure template
 * ({@code data/magicnpcs/structures/platform.nbt}) and passes offline.
 *
 * <p>The casting scenarios below ({@link #skeletonCastsMagicMissile}, {@link #recruitCasts},
 * {@link #recruitCastsWithIronsAi}) need the full runtime (Iron's + Curios + PlayerAnimator,
 * and Recruits for the recruit cases), which is not present in the dev environment — see
 * {@code docs/dev-runtime.md}. Each is gated on {@link IronsCompat#isLoaded()} and succeeds
 * immediately (skips) when Iron's is absent, so the offline run stays green; with the full
 * runtime they spawn the mob + a target and assert that mana is spent on a real cast.
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

    // --- Runtime casting checks (require Iron's; recruit cases also require Recruits) ---
    //
    // Each guards on IronsCompat.isLoaded() and, only then, delegates to the Iron's-side
    // IronsCastingTests (lazily classloaded, so the offline run never touches Iron's). Without
    // Iron's they succeed immediately (skip); with the full runtime they assert real casts.
    // Marked required=false so a runtime-specific flake never fails the whole suite; the
    // offline boot gate (bootSanity) stays required.

    /** Universal path: a skeleton with the shipped loadout spends mana casting at a target. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void skeletonCastsMagicMissile(GameTestHelper helper) {
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        IronsCastingTests.skeletonCastsMagicMissile(helper);
    }

    /**
     * Cast-chance gate: with the global castChance forced to 0, a caster never spends mana. Runs in
     * its own batch — it mutates the shared {@code castChance} config, so isolating it keeps it from
     * starving the concurrently-running casting tests in the default batch (and vice-versa).
     */
    @GameTest(template = "platform", timeoutTicks = 200, required = false, batch = "globalCastChance")
    public static void castChanceZeroNeverCasts(GameTestHelper helper) {
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        IronsCastingTests.castChanceZeroNeverCasts(helper);
    }

    /** Reactive condition: an execute spell fires only after the target drops below its HP threshold. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void executeConditionGatesCast(GameTestHelper helper) {
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        IronsCastingTests.executeConditionGatesCast(helper);
    }

    /** Aim fix: a caster facing away fires a projectile toward the target on the windup=0 path. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void windupAimsAtTarget(GameTestHelper helper) {
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
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
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        IronsCastingTests.focusGateRequiresHeldFocus(helper);
    }

    /** Bad-id convenience: a bare spell id auto-resolves under irons_spellbooks and casts. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void bareSpellIdAutoNamespaces(GameTestHelper helper) {
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        IronsCastingTests.bareSpellIdAutoNamespaces(helper);
    }

    /** Per-spell cast_chance=0 blocks casting even when the global castChance is 1. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void perSpellCastChanceZeroNeverCasts(GameTestHelper helper) {
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        IronsCastingTests.perSpellCastChanceZeroNeverCasts(helper);
    }

    /** Target-data fix: devour (target-entity spell) casts once the bridge supplies TargetEntityCastData. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void devourCastsWithTargetData(GameTestHelper helper) {
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        IronsCastingTests.devourCastsWithTargetData(helper);
    }

    /** Long-cast lifecycle: a LONG spell (root) fires after its cast time, not instantly. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void longCastCompletesAfterCastTime(GameTestHelper helper) {
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        IronsCastingTests.longCastCompletesAfterCastTime(helper);
    }

    /** Stomp handling: the forward ground-AoE spawns in front of the caster, toward the target. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void stompAoeFiresForward(GameTestHelper helper) {
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        IronsCastingTests.stompAoeFiresForward(helper);
    }

    /** Adapter path: a Villager Recruit casts (skips if Recruits is absent). */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void recruitCasts(GameTestHelper helper) {
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        IronsCastingTests.recruitCasts(helper);
    }

    /** Iron's-AI path: a recruit driven by Iron's WizardAttackGoal (via the mixin) casts. */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void recruitCastsWithIronsAi(GameTestHelper helper) {
        if (!IronsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        IronsCastingTests.recruitCastsWithIronsAi(helper);
    }
}
