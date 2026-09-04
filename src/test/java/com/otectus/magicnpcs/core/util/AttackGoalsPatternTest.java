package com.otectus.magicnpcs.core.util;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pattern arm of {@link AttackGoals#matchedBy}: the default
 * {@code general.attackGoalNamePatterns} must recognise a modded ranged goal nobody has listed by
 * name, without touching target goals, flagless goals, or a goal whose name merely reads like one.
 */
class AttackGoalsPatternTest {

    /** A modded ranged goal of a kind the exact-name list has never heard of. */
    private static final class LaserAttackGoal extends Goal {
        /** @param flag the single control flag it declares, or {@code null} for none. */
        LaserAttackGoal(Goal.Flag flag) {
            if (flag != null) {
                setFlags(EnumSet.of(flag));
            }
        }

        @Override
        public boolean canUse() {
            return false;
        }
    }

    /**
     * Held inside a neutral outer class on purpose: nested-name matching sees {@code Outer$Inner}, and
     * every other fixture here would lend its own enclosing name to the match.
     */
    private static final class Prism {
        static final class RainbowGoal extends Goal {
            RainbowGoal() {
                setFlags(EnumSet.of(Goal.Flag.MOVE));
            }

            @Override
            public boolean canUse() {
                return false;
            }
        }
    }

    /** Same simple name as vanilla's melee goal: the exact list must still claim it. */
    private static final class MeleeAttackGoal extends Goal {
        MeleeAttackGoal() {
            setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return false;
        }
    }

    /** Stands in for {@code NearestAttackableTargetGoal}: the name says "Attack", the job is targeting. */
    private static final class NearestAttackableTargetGoal extends TargetGoal {
        NearestAttackableTargetGoal() {
            super(null, false);
            setFlags(EnumSet.of(Goal.Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            return false;
        }
    }

    /** A named nested goal, matchable only through its {@code Outer$Inner} name. */
    private static final class Boss {
        static final class ChargeGoal extends Goal {
            ChargeGoal() {
                setFlags(EnumSet.of(Goal.Flag.LOOK));
            }

            @Override
            public boolean canUse() {
                return false;
            }
        }
    }

    @Test
    void aModdedAttackGoalDeclaringMoveIsMatchedByPattern() {
        Optional<String> match = AttackGoals.matchedBy(new LaserAttackGoal(Goal.Flag.MOVE));
        assertEquals(Optional.of("pattern:Attack"), match);
        assertTrue(AttackGoals.isNativeAttackGoal(new LaserAttackGoal(Goal.Flag.MOVE)));
    }

    @Test
    void aFlaglessGoalIsNeverMatchedByPattern() {
        // Nothing that contests neither movement nor looking can be starving the casting goal, and
        // suppressing it would be rewriting another mod's AI for no benefit.
        assertEquals(Optional.empty(), AttackGoals.matchedBy(new LaserAttackGoal(null)));
    }

    @Test
    void targetGoalsAreExcludedEvenWhenTheirNameSaysAttack() {
        // Suppressing NearestAttackableTargetGoal would leave the mob with no target at all, and
        // "yield" would read target acquisition as an attack already in progress.
        assertEquals(Optional.empty(), AttackGoals.matchedBy(new NearestAttackableTargetGoal()));
    }

    @Test
    void anUnrelatedNameIsNotMatched() {
        // Patterns are case sensitive precisely so "Bow" does not claim "Rainbow".
        assertFalse(AttackGoals.isNativeAttackGoal(new Prism.RainbowGoal()));
    }

    @Test
    void aNestedGoalIsMatchedThroughItsOuterQualifiedName() {
        assertEquals(Optional.of("pattern:Charge"), AttackGoals.matchedBy(new Boss.ChargeGoal()));
    }

    @Test
    void theExactListStillWinsOverThePatterns() {
        assertEquals(Optional.of("exact"), AttackGoals.matchedBy(new MeleeAttackGoal()));
    }

    @Test
    void ourOwnGoalsAreNeverPatternMatched() {
        // SuppressedGoal claims the flags of what it wraps, so a second suppression pass would
        // otherwise wrap the wrapper and strand the original goal.
        Goal wrapped = new SuppressedGoal(new LaserAttackGoal(Goal.Flag.MOVE), 3);
        assertEquals(Optional.empty(), AttackGoals.matchedBy(wrapped));
    }
}
