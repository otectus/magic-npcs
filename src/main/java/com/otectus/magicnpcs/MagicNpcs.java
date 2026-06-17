package com.otectus.magicnpcs;

import com.otectus.magicnpcs.compat.IronsCompat;
import com.otectus.magicnpcs.compat.RecruitsCompat;
import com.otectus.magicnpcs.compat.generic.OwnableTeamAdapter;
import com.otectus.magicnpcs.compat.recruits.RecruitsIntegration;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.integration.irons.IronsIntegration;
import com.otectus.magicnpcs.registry.MagicNpcsItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Magic NPCs — lets NPC mobs cast Iron's Spells 'n Spellbooks spells.
 *
 * <p>Phase 0 bootstrap: registers the mod-event-bus lifecycle listeners and the
 * Forge event bus, and reports whether Iron's Spellbooks is present. All
 * spellcasting features are added in later phases and are gated behind
 * {@link IronsCompat#isLoaded()} so the mod loads cleanly when Iron's is absent.
 */
@Mod(MagicNpcs.MODID)
public class MagicNpcs {
    public static final String MODID = "magicnpcs";
    public static final Logger LOGGER = LoggerFactory.getLogger(MagicNpcs.class);

    public MagicNpcs() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        // Server config — registered unconditionally (Iron's-free); auto-syncs to clients on login.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MagicNpcsConfig.SPEC, "magicnpcs-server.toml");

        // Items (School Tome) — vanilla-only registration; the item's effect is Iron's-gated at use time.
        MagicNpcsItems.init(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);

        // Generic owner/team friendly-fire protection — vanilla-only (no mod imports),
        // so it is safe to register always. It is the lowest-priority adapter, so a
        // specific mod adapter (e.g. Recruits) always wins when both apply.
        NpcAdapters.register(new OwnableTeamAdapter());

        // Iron's-touching code is referenced ONLY inside this guard so it never
        // classloads when Iron's Spellbooks is absent.
        if (IronsCompat.isLoaded()) {
            IronsIntegration.init(modEventBus);
        }

        // Recruits adapter registers independently of Iron's: harmless if Iron's is
        // absent (nothing consults it), active when both are present. Referenced only
        // inside the guard so Recruits classes classload only when Recruits is present.
        if (RecruitsCompat.isLoaded()) {
            RecruitsIntegration.init();
        }

        LOGGER.info("Magic NPCs loading (Iron's: {}, Recruits: {})", IronsCompat.isLoaded(), RecruitsCompat.isLoaded());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        if (!IronsCompat.isLoaded()) {
            LOGGER.warn("Iron's Spellbooks not detected — Magic NPCs spellcasting is disabled.");
            return;
        }
        LOGGER.info("Magic NPCs common setup complete; Iron's Spellbooks integration active.");
    }
}
