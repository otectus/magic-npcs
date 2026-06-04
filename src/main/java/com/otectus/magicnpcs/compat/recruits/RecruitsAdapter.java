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

    @Override
    public boolean appliesTo(Mob mob) {
        return mob instanceof AbstractRecruitEntity && MagicNpcsConfig.RECRUITS_INTEGRATION_ENABLED.get();
    }

    @Override
    public double manaScale(Mob mob) {
        int level = Math.max(0, ((AbstractRecruitEntity) mob).getXpLevel());
        return 1.0 + level * MagicNpcsConfig.RECRUITS_MANA_PER_LEVEL.get();
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
