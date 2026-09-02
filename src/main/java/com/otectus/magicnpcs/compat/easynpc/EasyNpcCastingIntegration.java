package com.otectus.magicnpcs.compat.easynpc;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.caster.CasterGoalListeners;
import de.markusbordihn.easynpc.api.action.ActionRegistry;
import de.markusbordihn.easynpc.api.event.EasyNPCEventRegistry;
import de.markusbordihn.easynpc.data.objective.ObjectiveRegistry;

/**
 * The half of the Easy NPC integration that needs Iron's Spellbooks as well as Easy NPC — the casting
 * objective, the {@code magicnpcs:cast} action, the spell-state dialog conditions, and the listeners
 * that keep them in step with the reconciler.
 *
 * <p>Split from {@link EasyNpcIntegration} because the two have different preconditions. The adapter
 * is about who an NPC may point a spell at and where it may stand, and must be registered whenever
 * Easy NPC is present — those rules protect owners and factions even when nothing can cast. Everything
 * here reaches into {@code integration.irons} and therefore classloads Iron's, so it is referenced
 * only behind {@code IronsCompat.isLoaded()}.
 */
final class EasyNpcCastingIntegration {

    private static EasyNpcStateListener stateListener;

    private EasyNpcCastingIntegration() {}

    static void init() {
        if (MagicNpcsConfig.EASYNPC_USE_OBJECTIVE.get()) {
            ObjectiveRegistry.register(EasyNpcSpellObjective.ID, new EasyNpcSpellObjective());
            // Easy NPC caches the Goal object its factory returned; without this the cache would
            // outlive the goal on any loadout change. See CasterGoalListeners.
            CasterGoalListeners.register(EasyNpcSpellObjective::onCastingGoalChanged);
        }
        ActionRegistry.register(EasyNpcCastAction.ID, new EasyNpcCastAction());
        EasyNpcConditions.register();

        stateListener = new EasyNpcStateListener();
        EasyNPCEventRegistry.registerStateEventListener(stateListener);

        MagicNpcs.LOGGER.info("Easy NPC casting hooks registered: objective {}, action {}, "
                        + "dialog conditions {}, {}, {}.",
                MagicNpcsConfig.EASYNPC_USE_OBJECTIVE.get()
                        ? EasyNpcSpellObjective.ID : "(disabled by easynpc.useObjective)",
                EasyNpcCastAction.ID, EasyNpcConditions.HAS_SCHOOL, EasyNpcConditions.CAN_CAST,
                EasyNpcConditions.HAS_MANA);
    }

    /**
     * Unhook from Easy NPC's event registry when the server stops.
     *
     * <p>The registry holds listeners in a static list for the lifetime of the JVM. In a single-player
     * session the client outlives the integrated server, so a listener left behind would survive into
     * the next world with stale expectations — and every subsequent world load would add another copy.
     */
    static void shutdown() {
        if (stateListener != null) {
            EasyNPCEventRegistry.unregisterStateEventListener(stateListener);
            stateListener = null;
        }
    }
}
