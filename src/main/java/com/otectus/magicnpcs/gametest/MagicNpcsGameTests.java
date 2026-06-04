package com.otectus.magicnpcs.gametest;

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
 * <p>{@link #bootSanity} itself needs the {@code platform} structure template
 * ({@code data/magicnpcs/structures/platform.nbt}, authored in-game via a
 * structure block) before it can assert; until that lands it is a scaffold and the
 * run reports a missing structure after the (successful) boot.
 *
 * <p>The casting scenarios below need the full runtime (Iron's + Curios +
 * PlayerAnimator, and Recruits for the recruit cases) — see {@code docs/dev-runtime.md}.
 * Each is a documented stub today; flesh out by spawning the mob + a target on a
 * structure template and asserting the observed effect (projectile spawned, mana
 * dropped, ally spared) via {@link GameTestHelper}.
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

    // --- Runtime-only scenarios (enable when Iron's + companions are present) ---

    // TODO(runtime): spawn a tagged skeleton + a target; tick; assert a Magic Missile
    //   projectile spawns and the skeleton's MAX_MANA pool drops, then refills.
    // TODO(runtime): spawn a recruit, its owner, and a hostile; assert the recruit casts
    //   at the hostile, never at the owner/ally (shouldAttack gate + line-of-fire).
    // TODO(runtime): with recruits.useIronsAI=true, assert a recruit drives Iron's
    //   WizardAttackGoal (varies spell by distance); with it false, the built-in goal.
}
