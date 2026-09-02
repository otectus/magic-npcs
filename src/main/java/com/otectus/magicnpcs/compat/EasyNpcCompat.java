package com.otectus.magicnpcs.compat;

import net.minecraftforge.fml.ModList;

/**
 * Single source of truth for whether Easy NPC is loaded. Mirrors {@link RecruitsCompat}: any
 * reference to Easy NPC APIs MUST be guarded by {@link #isLoaded()} or live only inside a class this
 * guard reaches, so an install without Easy NPC never crashes at classload.
 *
 * <p>Easy NPC 7.x is published as three separate mods — core ({@code easy_npc}, which owns every
 * class Magic NPCs compiles against), the config UI ({@link EasyNpcConfigUiCompat}), and a
 * dependency-only bundle. This guard names <em>core</em>, because that is what the integration needs;
 * an install running core without the config UI is fully supported.
 */
public final class EasyNpcCompat {
    public static final String MODID = "easy_npc";

    private static volatile Boolean cached;

    private EasyNpcCompat() {}

    public static boolean isLoaded() {
        Boolean c = cached;
        if (c != null) {
            return c;
        }
        synchronized (EasyNpcCompat.class) {
            if (cached == null) {
                cached = ModList.get().isLoaded(MODID);
            }
            return cached;
        }
    }
}
