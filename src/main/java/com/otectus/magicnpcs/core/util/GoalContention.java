package com.otectus.magicnpcs.core.util;

import net.minecraft.world.entity.ai.goal.Goal;

import java.util.Set;

/**
 * A faithful, testable restatement of the one vanilla rule that decides whether an injected goal can
 * ever start: {@code GoalSelector.tick()} starts a non-running goal only when, for <em>every</em>
 * {@link Goal.Flag} the candidate declares, the goal currently holding that flag satisfies
 * {@code WrappedGoal.canBeReplacedBy}:
 *
 * <pre>{@code
 * // net.minecraft.world.entity.ai.goal.WrappedGoal (1.20.1)
 * public boolean canBeReplacedBy(WrappedGoal other) {
 *    return this.isInterruptable() && other.getPriority() < this.getPriority();
 * }
 * }</pre>
 *
 * <p>Note {@code <}, not {@code <=}: an equal priority does <b>not</b> win. That single character is
 * why a {@code minecraft:witch}, whose {@code RangedAttackGoal} sits at priority 2 declaring
 * {@code MOVE, LOOK}, could never run a casting goal injected at priority 2 declaring {@code LOOK} —
 * and why a goal that declares <em>no</em> flags is never blocked at all (the loop over its flags is
 * empty). See ADR 0002.
 *
 * <p>Vanilla-only; used by the {@code /magicnpcs why} diagnostic to name the blocking goal.
 */
public final class GoalContention {
    private GoalContention() {}

    /**
     * @param ourPriority        the priority our casting goal is injected at
     * @param ourFlags           the control flags our casting goal declares
     * @param otherPriority      the other goal's priority
     * @param otherFlags         the other goal's control flags
     * @param otherInterruptable the other goal's {@code isInterruptable()}
     * @param otherRunning       whether the other goal is currently running (only a running goal holds
     *                           a flag lock)
     * @return true if the other goal currently prevents ours from starting
     */
    public static boolean blocks(int ourPriority, Set<Goal.Flag> ourFlags,
                                 int otherPriority, Set<Goal.Flag> otherFlags,
                                 boolean otherInterruptable, boolean otherRunning) {
        if (!otherRunning || ourFlags.isEmpty()) {
            return false; // nothing is locked, or we ask for no locks
        }
        boolean sharesFlag = false;
        for (Goal.Flag flag : ourFlags) {
            if (otherFlags.contains(flag)) {
                sharesFlag = true;
                break;
            }
        }
        if (!sharesFlag) {
            return false;
        }
        // canBeReplacedBy(ours): interruptable AND strictly better (lower) priority number.
        return !(otherInterruptable && ourPriority < otherPriority);
    }
}
