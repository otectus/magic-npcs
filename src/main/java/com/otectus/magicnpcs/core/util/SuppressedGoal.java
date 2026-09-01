package com.otectus.magicnpcs.core.util;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * A reversible wrapper that holds one of a mob's own goals inert without destroying it.
 *
 * <p>{@code "native_attack": "suppress"} used to call {@code GoalSelector#removeGoal} on every matching
 * goal, which is irreversible: the goal object is dropped and nothing records how to rebuild it.
 * Switching the loadout to {@code coexist}, removing the loadout, disabling the mod, or simply failing
 * to re-inject therefore left the mob permanently unable to swing its sword or fire its bow, with no
 * way back short of killing it (audit RCN-003).
 *
 * <p>The wrapper takes the original goal's place at the same priority and answers {@code canUse()} with
 * false, so the {@code GoalSelector} never starts it — and keeps a reference to the real goal, so
 * releasing the lease puts the exact original object back. It claims the same control flags, so flag
 * arbitration behaves as it did before suppression.
 *
 * <p>Vanilla-only (no Iron's, no mod imports), so the core and the diagnostics can both see it.
 */
public final class SuppressedGoal extends Goal {

    private final Goal delegate;
    private final int originalPriority;

    public SuppressedGoal(Goal delegate, int originalPriority) {
        this.delegate = delegate;
        this.originalPriority = originalPriority;
        // Same flags as the goal it stands in for: a suppressed goal that silently released its flags
        // would change which other goals can run, which is a behaviour change of its own.
        setFlags(delegate.getFlags());
    }

    /** The goal this wrapper is holding inert — restored verbatim when the lease is released. */
    public Goal delegate() {
        return delegate;
    }

    /** The {@code GoalSelector} priority the original goal was registered at. */
    public int originalPriority() {
        return originalPriority;
    }

    @Override
    public boolean canUse() {
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public boolean isInterruptable() {
        return true;
    }

    @Override
    public String toString() {
        return "Suppressed(" + AttackGoals.simpleName(delegate.getClass()) + ")";
    }
}
