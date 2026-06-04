package com.otectus.magicnpcs.compat;

import net.minecraftforge.fml.ModList;

/**
 * Single source of truth for whether Iron's Spellbooks is loaded.
 * Result is cached after the first invocation to keep the check cheap on the
 * cast hot-path.
 *
 * <p>Any common-side reference to Iron's APIs MUST be guarded by
 * {@link #isLoaded()} or live only inside a class that this guard reaches —
 * otherwise an Iron's-less install crashes at classload with
 * {@code NoClassDefFoundError}.
 *
 * <p>Ported verbatim from the sibling mod {@code ars-n-spells}
 * ({@code com.otectus.arsnspells.compat.IronsCompat}).
 */
public final class IronsCompat {
    public static final String MODID = "irons_spellbooks";

    private static volatile Boolean cached;

    private IronsCompat() {}

    public static boolean isLoaded() {
        Boolean c = cached;
        if (c != null) {
            return c;
        }
        synchronized (IronsCompat.class) {
            if (cached == null) {
                cached = ModList.get().isLoaded(MODID);
            }
            return cached;
        }
    }
}
