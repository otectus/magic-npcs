package com.otectus.magicnpcs.core.adapter;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Per-NPC-mod hook consulted by the universal casting path, so mod-specific
 * concepts (ownership, diplomacy, rank) can feed targeting and mana scaling
 * WITHOUT the Iron's-side code importing that mod. Implementations live under
 * {@code compat/<mod>/} and are registered via {@link NpcAdapters} only when the
 * backing mod is present.
 *
 * <p>Pure: no Iron's, no mod-specific imports here — those stay in the concrete
 * implementations.
 */
public interface NpcAdapter {

    /** @return true if this adapter governs the given mob. */
    boolean appliesTo(Mob mob);

    /**
     * Resolution priority. The universal path picks the highest-priority adapter
     * whose {@link #appliesTo(Mob)} is true, so a specific mod adapter (e.g.
     * Recruits at 100) always beats a broad generic one (e.g. owner/team at -100).
     */
    default int priority() {
        return 0;
    }

    /** Multiplier on the mob's max-mana pool (e.g. scale by rank/level). Default: no scaling. */
    default double manaScale(Mob mob) {
        return 1.0;
    }

    /**
     * The mob's progression level/rank, if the backing mod has one (e.g. a recruit's
     * XP rank). Used for level-scaled spell selection and rank-gated school assignment.
     * Default: 0 (no progression).
     */
    default int level(Mob mob) {
        return 0;
    }

    /**
     * @return true if this NPC is a progression/recruit-style mob eligible for the
     *         recruit branch of school assignment. Keeps mod-specific type checks out
     *         of the Iron's-side assignment code. Default: false.
     */
    default boolean schoolAssignable(Mob mob) {
        return false;
    }

    /**
     * @return false to suppress all casting right now for a mod-specific reason
     *         (e.g. a recruit commanded to a passive/hold state, an NPC trading or
     *         working). Vanilla invalid states (dead, sleeping, removed, no-AI) are
     *         handled by the goal itself, not here. Default: always allowed.
     */
    default boolean canCastNow(Mob mob) {
        return true;
    }

    /** @return false to forbid casting an attack spell at {@code target} (friendly-fire gate). */
    default boolean canCastAt(Mob caster, LivingEntity target) {
        return true;
    }

    /**
     * @return true if this adapter has a notion of allies worth a line-of-fire
     *         check. When false, the universal goal skips the ally scan entirely.
     */
    default boolean tracksAllies() {
        return false;
    }

    /** @return true if {@code other} is an ally that must not be caught in the line of fire. */
    default boolean isAlly(Mob caster, LivingEntity other) {
        return false;
    }
}
