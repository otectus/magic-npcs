package com.otectus.magicnpcs;

import com.otectus.magicnpcs.compat.CustomNpcsCompat;
import com.otectus.magicnpcs.compat.EasyNpcCompat;
import com.otectus.magicnpcs.compat.IronsCompat;
import com.otectus.magicnpcs.compat.RecruitsCompat;
import com.otectus.magicnpcs.compat.generic.OwnableTeamAdapter;
import com.otectus.magicnpcs.compat.generic.RaidAllyAdapter;
import com.otectus.magicnpcs.compat.generic.SittingPetAdapter;
import com.otectus.magicnpcs.compat.easynpc.EasyNpcIntegration;
import com.otectus.magicnpcs.compat.recruits.RecruitsIntegration;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import com.otectus.magicnpcs.core.caster.ReconcileReason;
import com.otectus.magicnpcs.integration.irons.IronsIntegration;
import com.otectus.magicnpcs.registry.MagicNpcsItems;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Magic NPCs — lets NPC mobs cast Iron's Spells 'n Spellbooks spells.
 *
 * <p>Registers the mod-event-bus lifecycle listeners and the Forge event bus, and reports what it found
 * at startup. All spellcasting features are gated behind {@link IronsCompat#isLoaded()} so the mod
 * loads cleanly when Iron's is absent.
 */
@Mod(MagicNpcs.MODID)
public class MagicNpcs {
    public static final String MODID = "magicnpcs";
    public static final Logger LOGGER = LoggerFactory.getLogger(MagicNpcs.class);

    /**
     * The Iron's versions this build's mob-cast manifest was actually verified against. A version
     * outside this range still loads — {@code mods.toml} admits a wider range so a point release does
     * not lock people out — but it is reported at startup, because "we have not checked this build"
     * needs to be visible somewhere other than a changelog (audit "Version contract").
     */
    public static final String IRONS_VERIFIED_RANGE = "1.20.1-3.15.x … 1.20.1-3.16.x";

    public MagicNpcs() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        // Server config — per-world gameplay balance; auto-syncs to clients on login.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MagicNpcsConfig.SPEC, "magicnpcs-server.toml");
        // Common config — installation-level facts ([compat] toggles, debugLogging) in config/, so a
        // modpack author sets them once instead of per world (ADR 0004).
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MagicNpcsConfig.COMMON_SPEC, "magicnpcs-common.toml");
        modEventBus.addListener(this::onConfigLoad);
        modEventBus.addListener(this::onConfigReload);

        // Items (School Tome) — vanilla-only registration; the item's effect is Iron's-gated at use time.
        MagicNpcsItems.init(modEventBus);

        MinecraftForge.EVENT_BUS.register(this);

        // Generic safety adapters — vanilla-only (no mod imports), so they are safe to register always.
        // Since 0.6.2 every applicable adapter contributes rather than the highest-priority one winning
        // outright, so a mod-specific adapter can no longer displace any of these (audit TGT-001).
        NpcAdapters.register(new OwnableTeamAdapter());
        NpcAdapters.register(new RaidAllyAdapter());
        NpcAdapters.register(new SittingPetAdapter());

        // Iron's-touching code is referenced ONLY inside this guard so it never classloads when Iron's
        // Spellbooks is absent.
        if (IronsCompat.isLoaded()) {
            IronsIntegration.init(modEventBus);
        }

        // Recruits adapter registers independently of Iron's: harmless if Iron's is absent (nothing
        // consults it), active when both are present.
        if (RecruitsCompat.isLoaded()) {
            RecruitsIntegration.init();
        }

        // Easy NPC likewise registers independently of Iron's. Its adapter is always active once the
        // mod is present, because owner/faction protection must not depend on a feature toggle; the
        // [easynpc] toggle governs casting, not safety.
        if (EasyNpcCompat.isLoaded()) {
            EasyNpcIntegration.init();
        }

        // CustomNPCs is reached only through a neutral facade: unlike the other two, an unsupported
        // build of it is a link error rather than an absent class, so the guard has to survive being
        // wrong about the API shape. Everything typed lives behind a reflective hop inside init().
        CustomNpcsCompat.init(modEventBus);
    }

    /**
     * Warn about config keys still set in their pre-0.6.0 (server-side) location once the specs are
     * loaded. Fires for both specs; the check itself is guarded, so an early call is harmless.
     */
    private void onConfigLoad(final ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == MagicNpcsConfig.SPEC) {
            MagicNpcsConfig.invalidateCaches();
            MagicNpcsConfig.warnOnLegacyKeys();
        }
    }

    /**
     * Reconcile every loaded mob when the server config is reloaded.
     *
     * <p>0.6.1 read the master switch when injecting a goal and in the mana tick, but an
     * already-installed goal never consulted it again — so turning {@code enableSpellcasting} off left
     * every existing caster casting until its chunk reloaded (audit CFG-001). The goal now checks the
     * switch on every decision <em>and</em> a config reload reconciles the world, which is the defence
     * in depth the audit asks for: the delayed queue must not be the only thing stopping a cast.
     */
    private void onConfigReload(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != MagicNpcsConfig.SPEC) {
            return;
        }
        // The parsed views of the list settings (trusted namespaces, capability overrides) are cached
        // per config load, so they have to be dropped here or a corrected override never takes effect.
        MagicNpcsConfig.invalidateCaches();
        if (!IronsCompat.isLoaded()) {
            return;
        }
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> com.otectus.magicnpcs.integration.irons.IronsSpellcasterHandler
                    .queueAllLoadedMobs(server, ReconcileReason.CONFIG_RELOAD));
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        logProvenance();
        if (!IronsCompat.isLoaded()) {
            LOGGER.warn("Iron's Spellbooks not detected — Magic NPCs spellcasting is disabled.");
            return;
        }
        LOGGER.info("Magic NPCs common setup complete; Iron's Spellbooks integration active.");
    }

    /**
     * One startup line naming this build and everything it depends on.
     *
     * <p>The 0.6.1 investigation could not tell which source produced the shipped binary, because the
     * jar said nothing about itself beyond a version string that disagreed with the repository (audit
     * REL-001). The build stamps the git commit and build time into the manifest; this prints them
     * alongside the detected dependency versions, so a log excerpt is enough to identify a build.
     */
    private void logProvenance() {
        Package pkg = MagicNpcs.class.getPackage();
        String version = ModList.get().getModContainerById(MODID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
        String build = pkg == null || pkg.getImplementationVersion() == null
                ? "unknown build" : pkg.getImplementationVersion();
        LOGGER.info("Magic NPCs {} ({}) | Iron's Spellbooks: {} | Villager Recruits: {} | "
                        + "Easy NPC: {} (config UI: {}) | verified against {}",
                version, build, dependencyVersion("irons_spellbooks"), dependencyVersion("recruits"),
                dependencyVersion("easy_npc"), dependencyVersion("easy_npc_config_ui"),
                IRONS_VERIFIED_RANGE);
        LOGGER.info("Config: <world>/serverconfig/magicnpcs-server.toml (per world) and "
                + "config/magicnpcs-common.toml (all worlds). Run /magicnpcs config in game.");
    }

    private static String dependencyVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("absent");
    }

    /** Drop every managed caster's in-memory state when the server stops, so a restart starts clean. */
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        ManagedCasterState.clearAll();
        // Open-cast bookkeeping is keyed by entity UUID; a stale one from this world must not decide
        // whether a cast in the next one may announce its ending.
        com.otectus.magicnpcs.core.caster.MagicNpcEvents.clear();
        if (IronsCompat.isLoaded()) {
            com.otectus.magicnpcs.integration.irons.DetachedCastDriver.clearAll();
            // An audit that outlives the server would leave its two persistent dummies saved in the
            // world it was auditing; cancelling writes the rows it already has and discards them.
            com.otectus.magicnpcs.integration.irons.SpellAuditRun.cancelActive();
        }
        if (EasyNpcCompat.isLoaded()) {
            EasyNpcIntegration.shutdown();
        }
        CustomNpcsCompat.shutdown();
    }
}
