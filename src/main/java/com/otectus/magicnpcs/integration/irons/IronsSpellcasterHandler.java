package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import io.redspace.ironsspellbooks.api.entity.IMagicEntity;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.entity.mobs.goals.WizardAttackGoal;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * FORGE-bus handler driving the universal casting path: registers the loadout
 * datapack listener, primes mana + injects the casting goal for any mob whose
 * type has a loadout, and ticks our own mana regen (Iron's does not regen foreign
 * mobs — ADR 0001). Max-mana is scaled by the per-mob {@link NpcAdapters adapter}
 * (e.g. Recruits rank) and re-scaled on the regen cadence so it tracks level-ups.
 *
 * <p>Goal choice: when {@code recruits.useIronsAI} is on and the mob is
 * {@link IMagicEntity} (the Recruits mixin applied), inject Iron's own
 * {@code WizardAttackGoal}; otherwise the built-in {@link NpcSpellAttackGoal}.
 * Server-side only; gated by {@link MagicNpcsConfig}.
 */
public class IronsSpellcasterHandler {

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new LoadoutManager());
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !MagicNpcsConfig.ENABLE_SPELLCASTING.get()
                || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        SpellcasterLoadout loadout = LoadoutManager.get(EntityType.getKey(mob.getType()));
        if (loadout == null || hasSpellGoal(mob)) {
            return; // not a spellcaster, or already injected (e.g. chunk reload)
        }

        AttributeInstance maxMana = mob.getAttribute(AttributeRegistry.MAX_MANA.get());
        AttributeInstance manaRegen = mob.getAttribute(AttributeRegistry.MANA_REGEN.get());
        if (maxMana == null || manaRegen == null) {
            return; // type lacks mana attributes (shouldn't happen — added in IronsAttributeHandler)
        }
        maxMana.setBaseValue(desiredMaxMana(mob, loadout));
        manaRegen.setBaseValue(loadout.manaRegen());
        IronsBridge.initMana(mob);

        if (useIronsGoal(mob)) {
            mob.goalSelector.addGoal(2, IronsGoalFactory.wizardGoal(
                    mob, loadout,
                    MagicNpcsConfig.RECRUITS_IRONS_AI_SPEED.get(),
                    MagicNpcsConfig.RECRUITS_IRONS_AI_INTERVAL.get(),
                    spellQuality(mob)));
        } else {
            mob.goalSelector.addGoal(2, new NpcSpellAttackGoal(mob, loadout));
        }
    }

    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level().isClientSide()
                || !MagicNpcsConfig.ENABLE_SPELLCASTING.get()
                || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        SpellcasterLoadout loadout = LoadoutManager.get(EntityType.getKey(mob.getType()));
        if (loadout == null) {
            return;
        }
        if (mob.tickCount % MagicManager.MANA_REGEN_TICKS == 0) {
            rescaleMaxMana(mob, loadout); // track adapter changes (e.g. recruit level-ups)
            IronsBridge.tickRegen(mob);
        }
    }

    /** Use Iron's own goal only when opted-in AND the mixin actually made this mob {@link IMagicEntity}. */
    private static boolean useIronsGoal(Mob mob) {
        return MagicNpcsConfig.RECRUITS_USE_IRONS_AI.get() && mob instanceof IMagicEntity;
    }

    /** Iron's spell quality (0..1) driven by the mob's adapter (recruit rank → manaScale). */
    private static float spellQuality(Mob mob) {
        return (float) Math.min(1.0, 0.25 * NpcAdapters.resolve(mob).manaScale(mob));
    }

    private static double desiredMaxMana(Mob mob, SpellcasterLoadout loadout) {
        return loadout.maxMana() * MagicNpcsConfig.MANA_MULTIPLIER.get() * NpcAdapters.resolve(mob).manaScale(mob);
    }

    private static void rescaleMaxMana(Mob mob, SpellcasterLoadout loadout) {
        AttributeInstance maxMana = mob.getAttribute(AttributeRegistry.MAX_MANA.get());
        if (maxMana == null) {
            return;
        }
        double desired = desiredMaxMana(mob, loadout);
        if (Math.abs(maxMana.getBaseValue() - desired) > 0.5) {
            maxMana.setBaseValue(desired);
        }
    }

    private static boolean hasSpellGoal(Mob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof NpcSpellAttackGoal || wrapped.getGoal() instanceof WizardAttackGoal) {
                return true;
            }
        }
        return false;
    }
}
