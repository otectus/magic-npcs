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

    /**
     * @return false to suppress <em>self-cast SUPPORT</em> casting right now. Separate from
     *         {@link #canCastNow(Mob)} because a "do not fight" command state should stop an NPC
     *         attacking, not stop it healing itself — a recruit ordered to retreat and recover was
     *         otherwise unable to do the recovering. Default: follow {@link #canCastNow(Mob)}.
     */
    default boolean canSupportCastNow(Mob mob) {
        return canCastNow(mob);
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

    /**
     * Where this NPC's owning mod says it is allowed to stand.
     *
     * <p>Consulted by the caster-movement goal, which repositions a pure caster to a range its own
     * spells are eligible at. Without this, that goal would happily walk a Villager Recruit out of
     * the formation line its owner put it in, or off the position it was told to hold — a caster
     * that ignores the player's orders is worse than one that stands still.
     *
     * <p>Default {@link MovementPolicy#FREE}: a mob whose mod has no command system (a skeleton, a
     * witch) may reposition anywhere.
     */
    default MovementPolicy movementPolicy(Mob mob) {
        return MovementPolicy.FREE;
    }

    /**
     * A mod command system's answer to "may this NPC move, and how far".
     *
     * @param freedom how much latitude the NPC has
     * @param anchor  the point it must stay near when {@link Freedom#ANCHORED}; {@code null} otherwise
     * @param leash   how far from {@code anchor} it may stray, in blocks; ignored unless ANCHORED
     */
    record MovementPolicy(Freedom freedom, net.minecraft.world.phys.Vec3 anchor, double leash) {

        /** No mod opinion: reposition anywhere. */
        public static final MovementPolicy FREE = new MovementPolicy(Freedom.FREE, null, 0.0);

        /** The NPC has been told to stay exactly where it is. */
        public static final MovementPolicy PINNED = new MovementPolicy(Freedom.PINNED, null, 0.0);

        public enum Freedom {
            /** Reposition anywhere. */
            FREE,
            /** Reposition, but stay within {@link MovementPolicy#leash()} of the anchor. */
            ANCHORED,
            /** Do not reposition at all — a hold-position order, a formation slot, an active march. */
            PINNED
        }

        /** An NPC that may move but must stay within {@code leash} blocks of {@code anchor}. */
        public static MovementPolicy anchored(net.minecraft.world.phys.Vec3 anchor, double leash) {
            return anchor == null ? FREE : new MovementPolicy(Freedom.ANCHORED, anchor, Math.max(0.0, leash));
        }

        /**
         * Combine two policies, taking the <b>more restrictive</b>.
         *
         * <p>Same direction as every other composition rule in {@link NpcAdapters}: registering an
         * extra adapter may only ever make behaviour more conservative. PINNED beats ANCHORED beats
         * FREE, and two anchors resolve to the shorter leash — so a recruit that is both following
         * its owner and holding a formation slot ends up honouring the tighter of the two.
         */
        public MovementPolicy and(MovementPolicy other) {
            if (freedom == Freedom.PINNED || other.freedom == Freedom.PINNED) {
                return PINNED;
            }
            if (freedom == Freedom.ANCHORED && other.freedom == Freedom.ANCHORED) {
                return leash <= other.leash ? this : other;
            }
            return freedom == Freedom.ANCHORED ? this : other;
        }

        /** @return true if the NPC may not be repositioned at all. */
        public boolean pinned() {
            return freedom == Freedom.PINNED;
        }
    }
}
