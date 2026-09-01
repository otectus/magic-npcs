package com.otectus.magicnpcs.core.util;

import net.minecraft.world.entity.ai.goal.Goal;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the W2 root cause as a rule rather than an anecdote: {@link GoalContention} restates
 * vanilla's {@code WrappedGoal.canBeReplacedBy} (strictly-lower priority number, and only for flags
 * the candidate actually declares), and these cases are the two mobs from the bug reports.
 */
class GoalContentionTest {

    private static final Set<Goal.Flag> LOOK = EnumSet.of(Goal.Flag.LOOK);
    private static final Set<Goal.Flag> MOVE_LOOK = EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK);
    private static final Set<Goal.Flag> NONE = EnumSet.noneOf(Goal.Flag.class);

    @Test
    void witchAtEqualPriorityStarvesALookClaimingCastGoal() {
        // minecraft:witch registers RangedAttackGoal at priority 2 with {MOVE, LOOK}; the pre-0.6.0
        // casting goal was injected at priority 2 with {LOOK}. 2 < 2 is false ⇒ never starts.
        assertTrue(GoalContention.blocks(2, LOOK, 2, MOVE_LOOK, true, true));
    }

    @Test
    void declaringNoFlagsIsNeverBlocked() {
        // The 0.6.0 default. With an empty flag set the vanilla loop has nothing to check.
        assertFalse(GoalContention.blocks(2, NONE, 2, MOVE_LOOK, true, true));
        assertFalse(GoalContention.blocks(9, NONE, 0, MOVE_LOOK, false, true));
    }

    @Test
    void aStrictlyBetterPriorityWins() {
        assertFalse(GoalContention.blocks(1, LOOK, 2, MOVE_LOOK, true, true));
    }

    @Test
    void aWorsePriorityLoses() {
        assertTrue(GoalContention.blocks(3, LOOK, 2, MOVE_LOOK, true, true));
    }

    @Test
    void skeletonBowGoalIsPreemptedByALookClaimingCastGoal() {
        // AbstractSkeleton adds its bow goal at priority 4. 2 < 4 ⇒ our goal stops it every cast —
        // the mechanism behind "skeletons shoot magic missile instead of arrows".
        assertFalse(GoalContention.blocks(2, LOOK, 4, MOVE_LOOK, true, true));
    }

    @Test
    void anIdleGoalHoldsNoLock() {
        assertFalse(GoalContention.blocks(2, LOOK, 2, MOVE_LOOK, true, false));
    }

    @Test
    void disjointFlagsDoNotContend() {
        assertFalse(GoalContention.blocks(2, LOOK, 1, EnumSet.of(Goal.Flag.JUMP), true, true));
    }

    @Test
    void anUninterruptableGoalBlocksEvenABetterPriority() {
        assertTrue(GoalContention.blocks(0, LOOK, 5, MOVE_LOOK, false, true));
    }
}
