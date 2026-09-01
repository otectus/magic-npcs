package com.otectus.magicnpcs.compat.recruits;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Villager Recruits adapter. Scales mana by recruit rank ({@code getXpLevel()})
 * and routes targeting/ally decisions through Recruits' own diplomacy/team/owner
 * -aware {@code shouldAttack(LivingEntity)} predicate — so no reimplementation of
 * {@code RecruitsDiplomacyManager} is needed.
 *
 * <p>Imports Recruits; classloaded only behind {@code RecruitsCompat.isLoaded()}
 * via {@link RecruitsIntegration}.
 */
public final class RecruitsAdapter implements NpcAdapter {

    /** Recruits command state for "passive" (flee / do not fight) — recruits in it should not cast. */
    private static final int STATE_PASSIVE = 3;

    @Override
    public int priority() {
        return 100; // beat the generic owner/team adapter when a recruit is also Ownable
    }

    /**
     * Applies to every recruit, <b>regardless of the {@code recruits.enabled} toggle</b>.
     *
     * <p>Before 0.6.1 the toggle was checked here, which made turning it off actively dangerous: the
     * bundled recruit loadouts still applied, so recruits kept casting, but this adapter was no longer
     * resolved — and the fallback adapter's defaults are {@code canCastAt = true} / {@code isAlly =
     * false}. The documented way to "disable the Recruits integration" therefore removed owner and
     * ally protection while leaving the spells switched on, so recruits would blast their own player.
     * The toggle now suppresses <em>casting</em> ({@link #canCastNow}) and rank scaling instead, and
     * the safety logic is never removed.
     */
    @Override
    public boolean appliesTo(Mob mob) {
        return mob instanceof AbstractRecruitEntity;
    }

    @Override
    public boolean canCastNow(Mob mob) {
        if (!MagicNpcsConfig.RECRUITS_INTEGRATION_ENABLED.get()) {
            return false; // integration off: recruits do not cast at all (but stay protected)
        }
        // Respect the Recruits command system: a recruit ordered to a passive/flee
        // state must not spell-spam. Aggressive/neutral/raid states may cast.
        return ((AbstractRecruitEntity) mob).getState() != STATE_PASSIVE;
    }

    /**
     * A passive/fleeing recruit may still heal itself — that is precisely when it needs to. Only the
     * integration toggle suppresses support casting outright.
     */
    @Override
    public boolean canSupportCastNow(Mob mob) {
        return MagicNpcsConfig.RECRUITS_INTEGRATION_ENABLED.get();
    }

    @Override
    public double manaScale(Mob mob) {
        if (!MagicNpcsConfig.RECRUITS_INTEGRATION_ENABLED.get()) {
            return 1.0; // no rank scaling when the integration is off
        }
        return 1.0 + level(mob) * MagicNpcsConfig.RECRUITS_MANA_PER_LEVEL.get();
    }

    @Override
    public int level(Mob mob) {
        return Math.max(0, ((AbstractRecruitEntity) mob).getXpLevel());
    }

    @Override
    public boolean schoolAssignable(Mob mob) {
        return true; // recruits are progression NPCs — eligible for the recruit school branch
    }

    @Override
    public boolean canCastAt(Mob caster, LivingEntity target) {
        return ((AbstractRecruitEntity) caster).shouldAttack(target);
    }

    @Override
    public boolean tracksAllies() {
        return true;
    }

    @Override
    public boolean isAlly(Mob caster, LivingEntity other) {
        AbstractRecruitEntity recruit = (AbstractRecruitEntity) caster;
        if (other == recruit.getOwner()) {
            return true; // the owning player
        }
        // A fellow recruit the caster would not attack: protect it from collateral.
        return other instanceof AbstractRecruitEntity && !recruit.shouldAttack(other);
    }

    /**
     * Translate the Recruits command system into the movement latitude the caster-movement goal may
     * use, so a casting recruit repositions like a ranged unit <em>without</em> walking out of the
     * orders its owner gave it.
     *
     * <p>Recruits' own {@code RecruitRangedBowAttackGoal} solves the same problem with private
     * {@code handleFollow} / {@code handleHoldPos} / {@code handleWander} branches over exactly these
     * accessors; this maps them onto the mod-agnostic policy so every caster benefits from the same
     * rules, not only recruits.
     *
     * <p>A march order or a formation slot is {@link MovementPolicy.Freedom#PINNED} rather than a
     * tight anchor: those are positions the player is actively managing, and a caster that drifts
     * "only a little" out of a shield wall has still broken the shield wall.
     */
    @Override
    public MovementPolicy movementPolicy(Mob mob) {
        AbstractRecruitEntity recruit = (AbstractRecruitEntity) mob;
        if (recruit.getShouldMovePos() || recruit.isInFormation || recruit.holdFormation) {
            return MovementPolicy.PINNED;
        }
        if (recruit.getShouldHoldPos()) {
            // Held position: a little slack to find a firing angle, not enough to leave the post.
            return MovementPolicy.anchored(recruit.getHoldPos(), HOLD_LEASH);
        }
        if (recruit.getShouldFollow() || recruit.isFollowing()) {
            LivingEntity owner = recruit.getOwner();
            return owner == null ? MovementPolicy.FREE
                    : MovementPolicy.anchored(owner.position(), FOLLOW_LEASH);
        }
        return MovementPolicy.FREE;
    }

    /** Slack around a held position: enough to sidestep for line of sight, not to abandon the post. */
    private static final double HOLD_LEASH = 4.0;

    /**
     * Slack around the owner while following. Wider than {@link #HOLD_LEASH} because a following
     * recruit is already expected to move, and a caster that will not back off from a charging
     * target is the behaviour this whole feature exists to fix.
     */
    private static final double FOLLOW_LEASH = 12.0;
}
