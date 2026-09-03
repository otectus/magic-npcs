package com.otectus.magicnpcs.compat.customnpcs;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.compat.CustomNpcsCompat;
import com.otectus.magicnpcs.compat.IronsCompat;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.diag.DiagnosticContributors;
import com.otectus.magicnpcs.integration.irons.CasterReconciler;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import noppes.npcs.api.NpcAPI;
import noppes.npcs.api.entity.ICustomNpc;
import noppes.npcs.api.entity.data.INPCAi;
import noppes.npcs.api.entity.data.INPCInventory;
import noppes.npcs.api.event.DialogEvent;
import noppes.npcs.api.event.NpcEvent;
import noppes.npcs.api.handler.data.IFaction;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Starts the CustomNPCs bridge. Reached only reflectively, from {@link CustomNpcsCompat}, after that
 * class has confirmed CustomNPCs is present <em>and</em> on the supported-version list — so this class
 * and every CustomNPCs import in this package classload only then.
 *
 * <p>Before anything is registered, the API surface is probed. "The mod is present and its version
 * string matches" is a weaker claim than it looks for a community port: a rebuild can keep the version
 * and move a class. The probe touches every type and entry point the bridge depends on, so a shape
 * mismatch becomes one reported {@code PROBE_FAILED} at startup instead of a {@code NoSuchMethodError}
 * thrown out of somebody else's event dispatch an hour into a session.
 *
 * <p>Three separate buses are in play and they are not interchangeable: NPC lifecycle events exist
 * only on CustomNPCs' API bus, entity-leave is a Forge-bus event, and creative tab contents are built
 * on the mod bus.
 */
public final class CustomNpcsIntegration {

    private static CustomNpcsEventBridge bridge;
    private static IEventBus apiBus;
    private static boolean busRegistered;
    private static String probeResult = "not run";

    /**
     * Iron's-side counter formatting and teardown, or {@code null} when Iron's Spellbooks is absent.
     * Held as functional references created inside the {@link IronsCompat#isLoaded()} branch below,
     * which is what keeps {@link CustomNpcsAiRepair} — and the {@code integration.irons} package it
     * imports — off this class's resolution path on an install without Iron's.
     */
    private static Supplier<String> repairCounters;
    private static Runnable repairReset;

    private CustomNpcsIntegration() {}

    public static void init(IEventBus modBus) {
        if (!probe()) {
            CustomNpcsCompat.markProbeFailed(probeResult);
            MagicNpcs.LOGGER.error("[magicnpcs] The CustomNPCs API is not the shape this build expects "
                    + "({}). The bridge is off; nothing has been registered.", probeResult);
            return;
        }

        NpcAdapters.register(new CustomNpcsAdapter());

        Consumer<Mob> repairHook = null;
        Supplier<CustomNpcsScriptApi> scriptApi = CustomNpcsScriptApi::inactive;
        if (IronsCompat.isLoaded()) {
            repairHook = CustomNpcsAiRepair::check;
            repairCounters = CustomNpcsAiRepair::countersLine;
            repairReset = CustomNpcsAiRepair::reset;
            // Same guarded-constructor-reference trick as the hooks above: CustomNpcsScriptApiIrons
            // imports integration.irons, and a constructor reference made inside this branch is the
            // only thing that ever resolves it.
            scriptApi = CustomNpcsScriptApiIrons::new;
            // Same guarded-method-reference trick: an NPC that starts a conversation stops casting,
            // but only Iron's knows what "stop casting" means, and this class must link without it.
            CustomNpcsActivityState.setCancelCastHook(CasterReconciler::cancelCastForDialog);
        }

        apiBus = NpcAPI.Instance().events();
        bridge = new CustomNpcsEventBridge(apiBus, repairHook);
        apiBus.register(bridge);
        busRegistered = true;

        MinecraftForge.EVENT_BUS.register(CustomNpcsActivityState.class);
        DiagnosticContributors.register(new CustomNpcsDiagnostics());

        if (IronsCompat.isLoaded()) {
            modBus.addListener(CustomNpcsCreativeTab::onBuildContents);
        }

        CustomNpcsCompat.markActivePublicApi();

        // The script surface goes up last, and in this order: the API first, then the bridge that
        // executes against it, then the global that hands it to scripts. A script can run on the very
        // tick the world finishes loading, so the global must not exist before what it calls does.
        CustomNpcsScriptBridge.setApi(scriptApi.get());
        CustomNpcsScriptGlobal.install();

        MagicNpcs.LOGGER.info("[magicnpcs] CustomNPCs bridge active on the CustomNPCs API event bus "
                        + "(version {}). Goal repair after an AI rebuild: {}.",
                CustomNpcsCompat.detectedVersion(),
                repairHook != null ? "active" : "inactive (Iron's Spellbooks not detected)");
    }

    /** Take the bridge off CustomNPCs' bus and drop everything it accumulated. */
    public static void shutdown() {
        if (busRegistered && apiBus != null && bridge != null) {
            apiBus.unregister(bridge);
        }
        busRegistered = false;
        bridge = null;
        apiBus = null;
        CustomNpcsActivityState.clear();
        CustomNpcsEventBridge.resetHeartbeat();
        // Order matters here too, backwards: take the global away before the bridge it calls into is
        // reset, so a script cannot reach a bridge that has already dropped its API.
        CustomNpcsScriptGlobal.uninstall();
        CustomNpcsScriptBridge.reset();
        if (repairReset != null) {
            repairReset.run();
        }
    }

    /**
     * Touch every CustomNPCs type and entry point the bridge needs, so a missing one is found here.
     *
     * <p>The class literals are not decoration: naming a type in a field or a call site defers its
     * resolution until that code runs, which for an event handler means "inside CustomNPCs' dispatch".
     * Forcing resolution now is the difference between a startup message and a mid-session crash.
     */
    private static boolean probe() {
        try {
            Class<?>[] required = {
                    NpcAPI.class, ICustomNpc.class, INPCAi.class, INPCInventory.class, IFaction.class,
                    NpcEvent.InitEvent.class, NpcEvent.UpdateEvent.class, DialogEvent.class,
            };
            if (required.length == 0) {
                return false; // unreachable; the array exists to force the resolution above
            }
            if (!NpcAPI.IsAvailable()) {
                probeResult = "NpcAPI reports itself unavailable";
                return false;
            }
            NpcAPI api = NpcAPI.Instance();
            if (api == null) {
                probeResult = "NpcAPI.Instance() is null";
                return false;
            }
            if (api.events() == null) {
                probeResult = "NpcAPI.Instance().events() is null — there is no API event bus to listen on";
                return false;
            }
            probeResult = "ok";
            return true;
        } catch (LinkageError | RuntimeException ex) {
            probeResult = ex.toString();
            return false;
        }
    }

    /** @return what the API probe concluded, for the diagnostics summary. */
    static String probeResult() {
        return probeResult;
    }

    /** @return whether the bridge is currently listening on the CustomNPCs API bus. */
    static boolean busRegistered() {
        return busRegistered;
    }

    /** @return the repair counters, or a note that repair is not wired at all. */
    static String repairCounters() {
        return repairCounters == null ? "n/a (Iron's Spellbooks absent)" : repairCounters.get();
    }

    /** @return how many faults the event bridge has absorbed this session. */
    static int bridgeFaults() {
        return bridge == null ? 0 : bridge.faults();
    }
}
