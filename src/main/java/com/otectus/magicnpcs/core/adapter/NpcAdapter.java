package com.otectus.magicnpcs.core.adapter;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
     * How this NPC's owning mod wants automatic school assignment done, or {@code null} when it has no
     * opinion and the built-in rules apply.
     *
     * <p>Exists so the assignment code does not have to know which progression mod it is looking at.
     * Before this, the recruit branch read {@code [schools.recruits]} directly, so any adapter that
     * answered {@link #schoolAssignable} true — Easy NPC being the first — was silently governed by
     * Villager Recruits' caster chance and rank threshold, and its own settings did nothing.
     *
     * <p>Returned by the same single provider as {@link #manaScale} and {@link #level}: combining two
     * mods' assignment rules is not meaningful, and a progression NPC belongs to one mod.
     */
    default SchoolRollPolicy schoolRollPolicy(Mob mob) {
        return null;
    }

    /**
     * One mod's automatic school-assignment settings.
     *
     * @param enabled      whether this mod's NPCs are assigned schools at all
     * @param casterChance chance [0..1] that an eligible NPC becomes a caster, rolled once and persisted
     * @param minLevel     the {@link #level(Mob)} an NPC must reach to be eligible
     * @param mode         {@code RANDOM}, {@code BY_TYPE} or {@code BY_RANK}
     * @param typeSchools  the {@code BY_TYPE} map, as {@code "entityType=school[,school]"} entries
     */
    record SchoolRollPolicy(boolean enabled, double casterChance, int minLevel, String mode,
                            java.util.List<? extends String> typeSchools) {}

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
     * The NPC framework this adapter speaks for, e.g. {@code customnpcs:npc}, or empty when the
     * adapter is generic (owner/team) and names no framework of its own. Diagnostics and script
     * surfaces use it to say <em>which</em> mod is governing a mob without a class-name guess.
     *
     * <p>Default: empty.
     */
    default Optional<ResourceLocation> frameworkId() {
        return Optional.empty();
    }

    /**
     * The player who owns this NPC, when the backing mod has a live notion of ownership. Read from
     * the mod every time rather than persisted: an owner that has been cleared in the other mod's UI
     * must stop being an owner here in the same tick.
     *
     * <p>Default: empty — no ownership concept.
     */
    default Optional<UUID> ownerId(Mob mob) {
        return Optional.empty();
    }

    /**
     * Mod-specific facts about this NPC as namespaced ids — its role, its job, its faction, its AI
     * modes. Exists so diagnostics and data-driven rules can describe an NPC's mod-side
     * configuration without any of them importing that mod or learning its integer enums.
     *
     * <p>Default: the empty set.
     */
    default Set<ResourceLocation> traits(Mob mob) {
        return Set.of();
    }

    /**
     * Put {@code stack} in {@code hand} the way the backing mod wants it done. Some NPC mods hold
     * their equipment in their own inventory object and copy it onto the entity, so a plain
     * {@link Mob#setItemInHand} is overwritten on the next sync and the grant silently disappears.
     *
     * @return true if this adapter handled the placement; false — the default — to say it has no
     *         opinion, and the caller should fall back to {@link Mob#setItemInHand}.
     */
    default boolean setHeldItem(Mob mob, InteractionHand hand, ItemStack stack) {
        return false;
    }

    /**
     * Tell this NPC's own mod that something happened to its casting — a cast about to start, one
     * started, finished, cancelled, or a school change. The one way a signal reaches a framework:
     * {@code core.caster.MagicNpcEvents} posts the Forge event and then calls this, so a leaf never
     * listens on the Forge bus and a signal has exactly one emission path.
     *
     * <p>Called on the server thread, inside the casting path. An implementation that runs a script
     * here is running it synchronously, and must guard against a script that casts back.
     *
     * @return true to request that the cast be vetoed. Honoured only for
     *         {@link MagicNpcSignal#CAST_PRE}; the answer is ignored for every other signal, because
     *         nothing after the transaction point can be un-spent. Default: no opinion.
     */
    default boolean publish(Mob mob, MagicNpcSignal signal) {
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
