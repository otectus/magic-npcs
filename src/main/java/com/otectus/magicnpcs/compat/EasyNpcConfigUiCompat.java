package com.otectus.magicnpcs.compat;

import net.minecraftforge.fml.ModList;

/**
 * Whether Easy NPC's optional configuration-UI module is installed.
 *
 * <p>Kept separate from {@link EasyNpcCompat} because the two are genuinely separate mods since Easy
 * NPC 6.1.0: a server or a lightweight pack may ship core alone. Magic NPCs compiles against
 * <em>neither</em> UI class and needs nothing from this module — the flag exists so
 * {@code /magicnpcs why} and the startup line can report what is actually installed. A player asking
 * "why can't I configure this NPC" is usually running core-only, and that answer should not require
 * reading a mod list.
 */
public final class EasyNpcConfigUiCompat {
    public static final String MODID = "easy_npc_config_ui";

    private static volatile Boolean cached;

    private EasyNpcConfigUiCompat() {}

    public static boolean isLoaded() {
        Boolean c = cached;
        if (c != null) {
            return c;
        }
        synchronized (EasyNpcConfigUiCompat.class) {
            if (cached == null) {
                cached = ModList.get().isLoaded(MODID);
            }
            return cached;
        }
    }
}
