package com.otectus.magicnpcs.core.util;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises a mob's <em>own</em> attack goals by class name, so the {@code native_attack} loadout
 * policy (ADR 0002) can suppress or yield to them without importing any mod. Matching is on the goal's
 * simple class name against {@link MagicNpcsConfig#attackGoalClassNames()} — the vanilla list plus
 * {@code general.suppressibleAttackGoals} — which covers subclasses in mods we cannot compile against.
 * Names nobody has listed are still reachable through {@code general.attackGoalNamePatterns}, applied
 * only to goals that are not target goals, not ours, and declare MOVE or LOOK.
 *
 * <p>Vanilla-only (no Iron's, no mod imports), so it is usable from the core and from the diagnostic
 * command.
 */
public final class AttackGoals {
    private AttackGoals() {}

    /**
     * Goals we install ourselves. They declare MOVE or LOOK and their names contain "Attack" and
     * "Cast", so pattern matching would otherwise have the mod suppress its own casting AI.
     */
    private static final Set<String> OWN_GOAL_NAMES =
            Set.of("NpcSpellAttackGoal", "CasterMovementGoal", "SuppressedGoal");

    /** @return true if {@code goal} looks like one of the mob's built-in attack goals. */
    public static boolean isNativeAttackGoal(Goal goal) {
        return matchedBy(goal).isPresent();
    }

    /**
     * Why {@code goal} counts as a native attack goal: {@code "exact"} for a
     * {@link MagicNpcsConfig#attackGoalClassNames()} hit, {@code "pattern:<regex>"} for a
     * {@link MagicNpcsConfig#attackGoalNamePatterns()} hit, empty when it is not one.
     *
     * <p>The exact list is checked first so a name a config author spelled out always wins, and so the
     * reason printed by {@code /magicnpcs why} names the setting that decided.
     */
    public static Optional<String> matchedBy(Goal goal) {
        if (goal == null) {
            return Optional.empty();
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
                return Optional.of("exact");
            }
        }
        if (!patternEligible(goal, simple)) {
            return Optional.empty();
        }
        for (Pattern pattern : MagicNpcsConfig.attackGoalNamePatterns()) {
            // Patterns see only the innermost simple name. The nested form is reserved for exact
            // entries: matching it here would let an enclosing class called, say, "BossAttackGoals"
            // pull every unrelated inner goal into a pattern hit.
            if (pattern.matcher(simple).find()) {
                return Optional.of("pattern:" + pattern.pattern());
            }
        }
        return Optional.empty();
    }

    /**
     * The guard rails on pattern matching: a mod's goal class names are not ours to interpret freely,
     * so a pattern may only ever look at a goal that could plausibly be an attack.
     *
     * <p>A {@link TargetGoal} only picks a target — suppressing one would leave the mob with no target
     * at all, and "yield" would treat mere target acquisition as an attack in progress. A goal that
     * declares neither MOVE nor LOOK does not contest anything the casting goal wants. And our own
     * goals are excluded by name, because {@code core} cannot import the packages they live in.
     */
    private static boolean patternEligible(Goal goal, String simple) {
        if (goal instanceof TargetGoal || OWN_GOAL_NAMES.contains(simple)) {
            return false;
        }
        return goal.getFlags().contains(Goal.Flag.MOVE) || goal.getFlags().contains(Goal.Flag.LOOK);
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
            Goal goal = wrapped.getGoal();
            // Never wrap a wrapper (suppressing twice would strand the original behind two layers that
            // releaseNativeAttackGoals only unwinds one of) and never wrap one of our own goals.
            if (goal instanceof SuppressedGoal || OWN_GOAL_NAMES.contains(simpleName(goal.getClass()))) {
                continue;
            }
            if (isNativeAttackGoal(goal)) {
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
