package com.otectus.magicnpcs.integration.irons;

import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;

/**
 * Ensures every living entity type carries Iron's mana attributes, so any type a
 * datapack loadout targets is guaranteed to have a {@code MAX_MANA}/{@code MANA_REGEN}
 * {@code AttributeInstance}. This is a MOD-bus event that fires during setup,
 * before datapacks load, so it cannot consult loadouts and must add broadly;
 * adding is harmless for non-casters (the base value stays at default and is only
 * set authoritatively at join time for entities that actually have a loadout).
 */
public final class IronsAttributeHandler {
    private IronsAttributeHandler() {}

    public static void onAttributeModify(EntityAttributeModificationEvent event) {
        for (EntityType<? extends LivingEntity> type : event.getTypes()) {
            if (!event.has(type, AttributeRegistry.MAX_MANA.get())) {
                event.add(type, AttributeRegistry.MAX_MANA.get());
            }
            if (!event.has(type, AttributeRegistry.MANA_REGEN.get())) {
                event.add(type, AttributeRegistry.MANA_REGEN.get());
            }
        }
    }
}
