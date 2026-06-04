package com.otectus.magicnpcs.compat.recruits;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;

/**
 * Registers the Villager Recruits adapter. Referenced ONLY inside the
 * {@code RecruitsCompat.isLoaded()} guard in {@link MagicNpcs}, so this class and
 * its Recruits imports classload only when Recruits is present.
 */
public final class RecruitsIntegration {
    private RecruitsIntegration() {}

    public static void init() {
        NpcAdapters.register(new RecruitsAdapter());
        MagicNpcs.LOGGER.info("Villager Recruits adapter registered (rank-scaled mana + diplomacy-aware targeting).");
    }
}
