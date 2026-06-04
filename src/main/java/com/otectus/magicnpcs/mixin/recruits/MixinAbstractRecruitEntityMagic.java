package com.otectus.magicnpcs.mixin.recruits;

import com.otectus.magicnpcs.integration.irons.IronsBridge;
import io.redspace.ironsspellbooks.api.entity.IMagicEntity;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.capabilities.magic.SyncedSpellData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Makes a Villager Recruit implement Iron's {@link IMagicEntity} so Iron's own
 * mob-AI goals (e.g. {@code WizardAttackGoal}) can drive it.
 *
 * <p>Casting is routed through our proven {@link IronsBridge} ({@code onCast} via
 * {@code CastSource.MOB}) rather than Iron's cast-time/{@code SyncedSpellData}
 * machinery — recruits are not GeckoLib, so the cast-time animation channel is
 * moot and we already scope to INSTANT spells. {@code getItemBySlot} is inherited
 * from {@code LivingEntity}; everything else is a trivial flag or no-op.
 *
 * <p>Applied only when BOTH Iron's and Recruits are present (see
 * {@code MagicNpcsMixinPlugin}); the config is {@code required=false}, so if it is
 * skipped the entity simply is not {@code IMagicEntity} and the handler falls back
 * to the built-in {@code NpcSpellAttackGoal}. {@code remap = false}: no Minecraft
 * member references, so no refmap is needed.
 */
@Mixin(targets = "com.talhanation.recruits.entities.AbstractRecruitEntity", remap = false)
public abstract class MixinAbstractRecruitEntityMagic implements IMagicEntity {

    @Unique
    private boolean magicnpcs$hasUsedSingleAttack;

    @Unique
    private boolean magicnpcs$drinkingPotion;

    @Override
    public MagicData getMagicData() {
        return MagicData.getPlayerMagicData((LivingEntity) (Object) this);
    }

    @Override
    public void initiateCastSpell(AbstractSpell spell, int spellLevel) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (IronsBridge.canAfford(self, spell, spellLevel)) {
            IronsBridge.cast(self, spell, spellLevel);
        }
    }

    @Override
    public boolean isCasting() {
        // Instant casts: the goal's own attack interval is the cadence.
        return false;
    }

    @Override
    public void cancelCast() {
    }

    @Override
    public void castComplete() {
    }

    @Override
    public boolean getHasUsedSingleAttack() {
        return magicnpcs$hasUsedSingleAttack;
    }

    @Override
    public void setHasUsedSingleAttack(boolean used) {
        this.magicnpcs$hasUsedSingleAttack = used;
    }

    @Override
    public boolean isDrinkingPotion() {
        return magicnpcs$drinkingPotion;
    }

    @Override
    public void startDrinkingPotion() {
    }

    @Override
    public void setSyncedSpellData(SyncedSpellData syncedSpellData) {
    }

    @Override
    public void notifyDangerousProjectile(Projectile projectile) {
    }

    @Override
    public void setBurningDashDirectionData() {
    }

    @Override
    public boolean setTeleportLocationBehindTarget(int distance) {
        return false;
    }
}
