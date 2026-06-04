package com.otectus.magicnpcs.compat;

import net.minecraftforge.fml.ModList;

/**
 * Single source of truth for whether Villager Recruits is loaded. Mirrors
 * {@link IronsCompat}: any reference to Recruits APIs MUST be guarded by
 * {@link #isLoaded()} or live only inside a class this guard reaches, so an
 * install without Recruits never crashes at classload.
 */
public final class RecruitsCompat {
    public static final String MODID = "recruits";

    private static volatile Boolean cached;

    private RecruitsCompat() {}

    public static boolean isLoaded() {
        Boolean c = cached;
        if (c != null) {
            return c;
        }
        synchronized (RecruitsCompat.class) {
            if (cached == null) {
                cached = ModList.get().isLoaded(MODID);
            }
            return cached;
        }
    }
}
