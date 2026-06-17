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

    @Override
    public boolean appliesTo(Mob mob) {
        return mob instanceof AbstractRecruitEntity && MagicNpcsConfig.RECRUITS_INTEGRATION_ENABLED.get();
    }

    @Override
    public boolean canCastNow(Mob mob) {
        // Respect the Recruits command system: a recruit ordered to a passive/flee
        // state must not spell-spam. Aggressive/neutral/raid states may cast.
        return ((AbstractRecruitEntity) mob).getState() != STATE_PASSIVE;
    }

    @Override
    public double manaScale(Mob mob) {
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
}
