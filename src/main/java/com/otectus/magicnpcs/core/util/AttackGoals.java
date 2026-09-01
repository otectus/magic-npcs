package com.otectus.magicnpcs.core.util;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recognises a mob's <em>own</em> attack goals by class name, so the {@code native_attack} loadout
 * policy (ADR 0002) can suppress or yield to them without importing any mod. Matching is on the goal's
 * simple class name against {@link MagicNpcsConfig#attackGoalClassNames()} — the vanilla list plus
 * {@code general.suppressibleAttackGoals} — which covers subclasses in mods we cannot compile against.
 *
 * <p>Vanilla-only (no Iron's, no mod imports), so it is usable from the core and from the diagnostic
 * command.
 */
public final class AttackGoals {
    private AttackGoals() {}

    /** @return true if {@code goal} looks like one of the mob's built-in attack goals. */
    public static boolean isNativeAttackGoal(Goal goal) {
        if (goal == null) {
            return false;
        }
        String simple = simpleName(goal.getClass());
        String nested = nestedName(goal.getClass());
        for (String candidate : MagicNpcsConfig.attackGoalClassNames()) {
            // Match either form. A *named* nested class reports only its own simple name from
            // Class#getSimpleName, so the shipped default "SpellcasterIllager$SpellcasterCastingSpellGoal"
            // could never match anything — native_attack "suppress" silently failed to remove an
            // evoker's or illusioner's casting goal, and "yield" never saw it running. (The sibling
            // default "AbstractSkeleton$1" worked only because anonymous classes fall through to the
            // binary-name path below.)
            if (simple.equalsIgnoreCase(candidate) || nested.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code Outer$Inner} for a nested class, else the same as {@link #simpleName}. Lets a config entry
     * disambiguate one mod's {@code AttackGoal} from another's by naming its enclosing class.
     */
    public static String nestedName(Class<?> type) {
        Class<?> enclosing = type.getEnclosingClass();
        return enclosing == null
                ? simpleName(type)
                : simpleName(enclosing) + "$" + simpleName(type);
    }

    /** @return true if any of the mob's native attack goals is currently running (the {@code yield} gate). */
    public static boolean anyNativeAttackRunning(Mob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            // A SuppressedGoal never runs, and isNativeAttackGoal would not match the wrapper anyway.
            if (wrapped.isRunning() && isNativeAttackGoal(wrapped.getGoal())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hold the mob's native attack goals inert (the {@code suppress} policy), <b>reversibly</b>.
     *
     * <p>Each matching goal is replaced, at the same priority, by a {@link SuppressedGoal} wrapper that
     * keeps a reference to the original. Releasing the lease with {@link #releaseNativeAttackGoals}
     * puts the exact original objects back. 0.6.1 removed the goals outright, so a mob that had ever
     * been a {@code suppress} caster could never get its own attack AI back — not by changing the
     * loadout to {@code coexist}, not by removing the loadout, not by disabling the mod (audit RCN-003).
     *
     * @return the simple class names of what was suppressed, so the caller can log exactly what it did
     *         rather than silently rewriting another mod's AI
     */
    public static List<String> suppressNativeAttackGoals(Mob mob) {
        List<WrappedGoal> targets = new ArrayList<>();
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (isNativeAttackGoal(wrapped.getGoal())) {
                targets.add(wrapped);
            }
        }
        List<String> suppressed = new ArrayList<>(targets.size());
        for (WrappedGoal wrapped : targets) {
            Goal goal = wrapped.getGoal();
            int priority = wrapped.getPriority();
            // removeGoal (not removeAllGoals) stops a goal that is currently running before dropping
            // it. removeAllGoals only unregisters, which would leave a running goal's control-flag
            // lock held forever — GoalSelector only releases a lock when its holder reports
            // !isRunning(), and an unregistered goal is never ticked again to report that.
            mob.goalSelector.removeGoal(goal);
            mob.goalSelector.addGoal(priority, new SuppressedGoal(goal, priority));
            suppressed.add(simpleName(goal.getClass()));
        }
        return suppressed;
    }

    /**
     * Undo {@link #suppressNativeAttackGoals}: put every wrapped goal back exactly as it was.
     *
     * @return the simple class names of what was restored
     */
    public static List<String> releaseNativeAttackGoals(Mob mob) {
        List<SuppressedGoal> wrappers = new ArrayList<>();
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof SuppressedGoal suppressed) {
                wrappers.add(suppressed);
            }
        }
        List<String> restored = new ArrayList<>(wrappers.size());
        for (SuppressedGoal wrapper : wrappers) {
            mob.goalSelector.removeGoal(wrapper);
            mob.goalSelector.addGoal(wrapper.originalPriority(), wrapper.delegate());
            restored.add(simpleName(wrapper.delegate().getClass()));
        }
        return restored;
    }

    /**
     * @return the running goal that currently holds the vanilla {@code MOVE} control lock, or
     *         {@code null} if nothing does.
     *
     *         <p>{@code GoalSelector.lockedFlags} is private, but the answer is derivable: a running
     *         {@link WrappedGoal} whose goal declares {@code MOVE} is the holder. Reading the lock
     *         rather than claiming it is what lets a flagless movement goal stand down for goals it
     *         has never heard of — Villager Recruits' {@code RecruitHoldPosGoal} declares {@code MOVE}
     *         and runs for as long as a recruit is held, so honouring the lock honours the player's
     *         hold-position order structurally, without matching on a class name.
     */
    public static Goal movementLockHolder(Mob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.isRunning() && wrapped.getGoal().getFlags().contains(Goal.Flag.MOVE)) {
                return wrapped.getGoal();
            }
        }
        return null;
    }

    /** @return true if any of this mob's native attack goals is currently held inert by us. */
    public static boolean hasSuppressedGoals(Mob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof SuppressedGoal) {
                return true;
            }
        }
        return false;
    }

    /**
     * A readable class name for an anonymous or inner goal class, which vanilla uses a lot
     * (e.g. {@code AbstractSkeleton$1}). {@code Class#getSimpleName} returns an empty string for
     * anonymous classes, so fall back to the trailing segment of the binary name.
     */
    public static String simpleName(Class<?> type) {
        String simple = type.getSimpleName();
        if (!simple.isEmpty()) {
            return simple;
        }
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /** Format a goal's control flags as {@code [MOVE, LOOK]} (or {@code []}) for a diagnostic dump. */
    public static String flags(Goal goal) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Goal.Flag flag : goal.getFlags()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(flag.name().toUpperCase(Locale.ROOT));
            first = false;
        }
        return sb.append(']').toString();
    }
}
