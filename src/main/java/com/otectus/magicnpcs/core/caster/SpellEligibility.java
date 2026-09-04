package com.otectus.magicnpcs.core.caster;

import com.otectus.magicnpcs.core.loadout.CastCondition;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;

import java.util.function.BooleanSupplier;

/**
 * The pure role/condition/range/line-of-sight gate behind spell selection, extracted from
 * {@code NpcSpellAttackGoal.choose()} so the out-of-combat SUPPORT rules of ADR 0005 can be tested
 * without a {@code Mob}, a level, or Iron's on the classpath.
 *
 * <p>Vanilla-free as well as Iron's-free: everything the gate needs (is the caster out of combat, is
 * it hurt, may it attack its target, is the target in range and visible) is decided by the caller and
 * passed in as a boolean, and the reactive condition is evaluated through a {@link BooleanSupplier}
 * so the (potentially expensive) entity scan still happens only where the goal would have run it.
 */
public final class SpellEligibility {

    private SpellEligibility() {}

    /** What the role gate decided about one loadout entry. */
    public enum Verdict {
        /** Not eligible this decision — the caller skips the entry. */
        SKIP,
        /** Eligible, with no reactive condition involved. */
        ELIGIBLE,
        /** Eligible <em>because</em> its reactive condition is satisfied (earns the weight bonus). */
        ELIGIBLE_CONDITION_MATCHED
    }

    /**
     * Decide whether one entry survives the role gate, in exactly the order the casting goal applies
     * it. SUPPORT is eligible in combat or out of it, but out of combat only when the caster is hurt
     * (below {@code supportHealthThreshold}) or when a reactive condition matches — and a condition
     * with no self-health term ({@link CastCondition#hasSelfHealthGate()}) still needs the hurt gate,
     * the anti-loop floor of ADR 0005 that stops an idle caster self-buffing forever. ATTACK is never
     * eligible without a target, and additionally needs the target to be attackable, in range, and in
     * line of sight.
     *
     * @param role              the entry's role
     * @param outOfCombat       true when the caster has no live target
     * @param hurt              true when the caster is below the support health threshold
     * @param condition         the entry's reactive condition, or {@code null} when reactive casting is
     *                          off or the entry has none (an empty condition imposes nothing)
     * @param conditionMatches  evaluates {@code condition} against the world; called at most once, and
     *                          only where the goal itself would evaluate it
     * @param canAttackTarget   the adapter allows this caster to cast at its target (never an ally)
     * @param inRange           the target is within the entry's min/max range band
     * @param lineOfSightOk     line of sight is not required, or it is clear
     * @return the verdict for this entry
     */
    public static Verdict roleGate(LoadoutEntry.Role role, boolean outOfCombat, boolean hurt,
                                   CastCondition condition, BooleanSupplier conditionMatches,
                                   boolean canAttackTarget, boolean inRange, boolean lineOfSightOk) {
        boolean hasCond = condition != null && !condition.isEmpty();
        if (role == LoadoutEntry.Role.SUPPORT) {
            if (hasCond) {
                if (!conditionMatches.getAsBoolean()) {
                    return Verdict.SKIP; // reactive condition replaces the default "when hurt" gate
                }
                if (outOfCombat && !condition.hasSelfHealthGate() && !hurt) {
                    return Verdict.SKIP; // anti-loop floor: idle support still requires being hurt
                }
                return Verdict.ELIGIBLE_CONDITION_MATCHED;
            }
            return hurt ? Verdict.ELIGIBLE : Verdict.SKIP; // self-cast support only when threatened
        }
        // ATTACK — never selectable without a target.
        if (outOfCombat || !canAttackTarget) {
            return Verdict.SKIP;
        }
        if (!inRange) {
            return Verdict.SKIP; // target out of range
        }
        if (!lineOfSightOk) {
            return Verdict.SKIP; // can't see the target through blocks
        }
        if (hasCond) {
            if (!conditionMatches.getAsBoolean()) {
                return Verdict.SKIP; // reactive condition (e.g. execute below target HP, AoE when swarmed)
            }
            return Verdict.ELIGIBLE_CONDITION_MATCHED;
        }
        return Verdict.ELIGIBLE;
    }

    /**
     * The gate a targetless caster must pass before it may make a decision at all: out of combat only
     * SUPPORT is eligible (ADR 0005), so a loadout with no SUPPORT entry has nothing to decide, and the
     * whole out-of-combat path is opt-out through {@code supportOutOfCombat}.
     *
     * @param hasSupportSpell true when the resolved loadout contains at least one SUPPORT entry
     * @param featureEnabled  the {@code supportOutOfCombat} config value
     * @return true when the caster may enter the out-of-combat SUPPORT decision path
     */
    public static boolean outOfCombatPathOpen(boolean hasSupportSpell, boolean featureEnabled) {
        return hasSupportSpell && featureEnabled;
    }
}
