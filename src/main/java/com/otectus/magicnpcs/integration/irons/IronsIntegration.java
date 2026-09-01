package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * Wires every Iron's-touching handler. Referenced ONLY from inside the
 * {@code IronsCompat.isLoaded()} guard in {@link MagicNpcs}, so this class — and
 * the entire {@code integration.irons} package's Iron's imports — classload only
 * when Iron's Spellbooks is present.
 */
public final class IronsIntegration {
    private IronsIntegration() {}

    public static void init(IEventBus modEventBus) {
        // EntityAttributeModificationEvent is a MOD-bus event.
        modEventBus.addListener(IronsAttributeHandler::onAttributeModify);
        // EntityJoinLevelEvent + LivingTickEvent are FORGE-bus events.
        MinecraftForge.EVENT_BUS.register(new IronsSpellcasterHandler());
        // School Tome. Must be an EntityInteract handler, not an Item callback — see
        // SchoolTomeInteraction for why the item callback never fired on villagers or recruits.
        MinecraftForge.EVENT_BUS.register(SchoolTomeInteraction.class);
        MagicNpcs.LOGGER.info("Iron's mob-casting integration wired.");
    }
}
