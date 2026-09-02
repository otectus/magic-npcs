package com.otectus.magicnpcs.compat.easynpc;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.compat.EasyNpcConfigUiCompat;
import com.otectus.magicnpcs.compat.IronsCompat;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;

/**
 * Registers the Easy NPC integration. Referenced ONLY inside the {@code EasyNpcCompat.isLoaded()}
 * guard in {@link MagicNpcs}, so this class and its Easy NPC imports classload only when Easy NPC is
 * present.
 *
 * <p>The adapter is registered unconditionally; the casting hooks are held behind a second guard for
 * Iron's, because they are the part that touches the spell layer. An install with Easy NPC but no
 * Iron's still gets correct owner and faction protection — there is simply nothing to protect anyone
 * from yet.
 */
public final class EasyNpcIntegration {

    private static boolean castingHooksInstalled;

    private EasyNpcIntegration() {}

    public static void init() {
        NpcAdapters.register(new EasyNpcAdapter());
        EasyNpcDiagnostics.register();
        if (IronsCompat.isLoaded()) {
            EasyNpcCastingIntegration.init();
            castingHooksInstalled = true;
        }
        // Say which Easy NPC modules are actually installed. Core and the configuration UI are
        // separate mods since Easy NPC 6.1.0, and "I cannot configure my NPC" almost always means the
        // UI module is missing — an answer that should not require reading a mod list.
        MagicNpcs.LOGGER.info("Easy NPC adapter registered (level-scaled mana + owner/faction-aware "
                        + "targeting). Configuration UI module: {}. Casting hooks: {}.",
                EasyNpcConfigUiCompat.isLoaded() ? "present" : "absent (core-only install)",
                castingHooksInstalled ? "active" : "inactive (Iron's Spellbooks not detected)");
    }

    /** Release everything held in Easy NPC's static registries when the server stops. */
    public static void shutdown() {
        if (castingHooksInstalled) {
            EasyNpcCastingIntegration.shutdown();
        }
    }
}
