package com.otectus.magicnpcs.core.caster;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.util.AttackGoals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Keeps a pure caster at a range its own spells are actually eligible at, instead of leaving it
 * standing where it happens to be.
 *
 * <p>A mob with a casting loadout but no ranged AI of its own has nothing telling it where to stand.
 * A Villager Recruit is the clearest case: {@code AbstractRecruitEntity.registerGoals()} gives every
 * recruit a {@code RecruitMeleeAttackGoal} and only Bowmen and Crossbowmen get a ranged goal on top,
 * so a plain recruit handed a magic-missile loadout closes to sword range and casts on the way in.
 * It is infantry that occasionally throws a spell.
 *
 * <p><b>This goal only runs where the mob's own attack AI has been suppressed.</b> Stopping the
 * charge is not its job — {@code "native_attack": "suppress"} already does that, reversibly, via
 * {@link com.otectus.magicnpcs.core.util.SuppressedGoal}. This supplies the missing half: where to
 * stand instead. Gating on suppression also means it can never end up in a tug-of-war with a melee
 * goal that is actively pathing inward, and that a pack which has not opted in sees no change at all.
 *
 * <p><b>No control flags.</b> Like {@link com.otectus.magicnpcs.integration.irons.NpcSpellAttackGoal}
 * (ADR 0002), and like Recruits' own {@code RecruitRangedBowAttackGoal}, which declares {@code LOOK}
 * but not {@code MOVE}: flags govern goal <em>scheduling</em>, not access to the navigation API, so a
 * goal can steer without claiming the lock. Claiming {@code MOVE} at the default casting priority of
 * 2 would be starved by {@code RecruitMeleeAttackGoal} (also priority 2 — {@code canBeReplacedBy}
 * needs a strictly lower number) and would preempt {@code RecruitHoldPosGoal} at priority 3, kiting a
 * recruit off a hold-position order. Both failure modes at once.
 *
 * <p>Instead it <em>reads</em> the lock: {@link AttackGoals#movementLockHolder} stands the goal down
 * whenever any running goal holds {@code MOVE}, which honours a command system from a mod this code
 * has never heard of without matching on a class name.
 *
 * <p>Vanilla-only: {@code core/} imports no Iron's types, so the "am I mid-cast?" signal is read from
 * {@link ManagedCasterState#channelling()} rather than from the cast session itself.
 */
public class CasterMovementGoal extends Goal {

    /**
     * How often a new destination is computed. Re-pathing every tick fights the navigator more than
     * it helps, and a caster that recomputes its retreat twenty times a second visibly jitters.
     */
    private static final int REPATH_INTERVAL_TICKS = 10;

    /** Keep a little inside the outer edge of the band, so ordinary drift does not fall out of it. */
    private static final double BAND_MARGIN = 1.5;

    /** How far a retreat step looks for open ground, horizontally and vertically. */
    private static final int RETREAT_HORIZONTAL = 12;
    private static final int RETREAT_VERTICAL = 5;

    private final Mob mob;
    private final List<LoadoutEntry> entries;
    private final double speedModifier;

    private int repathAt;

    /**
     * @param entries the loadout entries that survived the casting goal's construction — blacklisted,
     *                unknown and unverified spells already filtered out. Sizing the band from the raw
     *                loadout instead would have the mob hold a range for a spell it can never cast.
     */
    public CasterMovementGoal(Mob mob, List<LoadoutEntry> entries, double speedModifier) {
        this.mob = mob;
        this.entries = List.copyOf(entries);
        this.speedModifier = speedModifier;
        // Deliberately no setFlags(...): see the class javadoc.
    }

    @Override
    public boolean canUse() {
        if (!MagicNpcsConfig.casterMovementEnabled()) {
            return false;
        }
        ManagedCasterState state = ManagedCasterState.peek(mob);
        if (state == null || !state.nativeAttackSuppressed()) {
            // The mob still has its own attack AI. Stand down rather than fight it for the navigator.
            return false;
        }
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive() || mob.isNoAi() || !mob.isAlive()) {
            return false;
        }
        if (!(mob instanceof PathfinderMob) || mob.isPassenger() || mob.isLeashed()) {
            // Claiming no control flags means opting out of the protection GoalSelector normally
            // gives: Mob#updateControlFlags disables MOVE while a mob is ridden or leashed, and the
            // selector enforces that by flag — which is vacuously true for a flagless goal. Recruits
            // mount horses, so this has to be checked here instead.
            return false;
        }
        if (AttackGoals.movementLockHolder(mob) != null) {
            // Something else is steering. Honouring the MOVE lock rather than claiming it means a
            // hold-position or follow goal from a mod we have never heard of still wins, with no
            // class name to match on. Recruits' own RecruitHoldPosGoal declares MOVE and runs for as
            // long as a recruit is held.
            return false;
        }
        return !NpcAdapters.resolve(mob).movementPolicy(mob).pinned();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true; // the hold-still-while-channelling check has to be per-tick to be worth anything
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        repathAt = 0;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        ManagedCasterState state = ManagedCasterState.peek(mob);
        if (state != null && state.channelling()) {
            // Hold position through a cast. The casting goal re-aims at the target every tick of a
            // channel; walking at the same time throws that aim away, and a channelled spell that
            // reads the caster's look angle (a breath, a ray, a cone) would spray.
            mob.getNavigation().stop();
            return;
        }
        if (mob.tickCount < repathAt) {
            return;
        }
        repathAt = mob.tickCount + REPATH_INTERVAL_TICKS;

        Band band = preferredBand();
        if (band == null) {
            return; // nothing with a range to hold — a support-only caster has nowhere to be
        }
        double distance = mob.distanceTo(target);
        if (distance >= band.min() && distance <= band.max()) {
            mob.getNavigation().stop(); // already standing somewhere its spells work
            return;
        }
        Vec3 destination = destinationFor(target, distance, band);
        if (destination != null) {
            mob.getNavigation().moveTo(destination.x, destination.y, destination.z, speedModifier);
        }
    }

    /**
     * Where to stand: away from the target when too close, toward it when too far, clamped into
     * whatever leash the mob's own mod allows.
     *
     * <p>Retreat asks vanilla's {@link DefaultRandomPos#getPosAway} for somewhere reachable rather
     * than projecting a straight line backwards, because a straight line walks a caster into the wall
     * it is standing against and it then stops retreating for no visible reason.
     */
    private Vec3 destinationFor(LivingEntity target, double distance, Band band) {
        if (distance > band.max()) {
            return clampToPolicy(target.position());
        }
        Vec3 away = DefaultRandomPos.getPosAway((PathfinderMob) mob, RETREAT_HORIZONTAL,
                RETREAT_VERTICAL, target.position());
        if (away == null) {
            return null; // cornered — stand and cast rather than shuffle into the wall
        }
        Vec3 clamped = clampToPolicy(away);
        if (mob instanceof PathfinderMob pathfinder && pathfinder.hasRestriction()
                && !pathfinder.isWithinRestriction(BlockPos.containing(clamped))) {
            return null; // a home-bound mob (a villager, a golem) does not retreat out of its home
        }
        return clamped;
    }

    /**
     * Pull a destination back inside the mod's movement leash.
     *
     * <p>This is what stops the goal walking a recruit out of the position its owner told it to hold.
     * A {@code PINNED} policy never reaches here — {@link #canUse()} refuses first.
     */
    private Vec3 clampToPolicy(Vec3 ideal) {
        NpcAdapter.MovementPolicy policy = NpcAdapters.resolve(mob).movementPolicy(mob);
        if (policy.freedom() != NpcAdapter.MovementPolicy.Freedom.ANCHORED || policy.anchor() == null) {
            return ideal;
        }
        Vec3 offset = ideal.subtract(policy.anchor());
        double leash = policy.leash();
        if (offset.lengthSqr() <= leash * leash) {
            return ideal;
        }
        return policy.anchor().add(offset.normalize().scale(leash));
    }

    /**
     * The distance window this caster's ATTACK spells share.
     *
     * <p>Taken from the loadout's own {@code min_range}/{@code max_range} rather than from a separate
     * "preferred range" setting, so there is one place a pack author states where a mob fights and no
     * way for the two to disagree. The widest floor and the narrowest ceiling give the band where the
     * most spells are eligible at once; a loadout whose entries do not overlap falls back to the
     * widest entry, because an empty band would make the mob pace between two unreachable bounds.
     */
    private Band preferredBand() {
        double min = Double.NEGATIVE_INFINITY;
        double max = Double.POSITIVE_INFINITY;
        double widestMin = 0.0;
        double widestMax = 0.0;
        boolean any = false;
        for (LoadoutEntry entry : entries) {
            if (entry.role() != LoadoutEntry.Role.ATTACK) {
                continue;
            }
            any = true;
            min = Math.max(min, entry.minRange());
            max = Math.min(max, entry.maxRange());
            if (entry.maxRange() - entry.minRange() > widestMax - widestMin) {
                widestMin = entry.minRange();
                widestMax = entry.maxRange();
            }
        }
        if (!any) {
            return null;
        }
        if (min + BAND_MARGIN >= max) {
            return new Band(widestMin, widestMax); // the entries do not overlap; use the roomiest one
        }
        return new Band(min, max);
    }

    /** The distance window the caster tries to stand in, in blocks. */
    private record Band(double min, double max) {}
}
