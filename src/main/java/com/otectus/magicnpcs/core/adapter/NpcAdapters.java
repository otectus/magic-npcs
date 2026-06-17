package com.otectus.magicnpcs.core.adapter;

import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of {@link NpcAdapter}s. The universal casting path resolves the
 * adapter for a mob here; the {@link #DEFAULT} is a no-op (mana scale 1, no
 * allies, any target allowed), so mobs from mods with no adapter behave exactly
 * as the plain mod-agnostic core does.
 *
 * <p>Pure — adapters are registered behind their own mod guards (e.g.
 * {@code RecruitsCompat.isLoaded()}), so this class never imports those mods.
 */
public final class NpcAdapters {
    private static final NpcAdapter DEFAULT = new NpcAdapter() {
        @Override
        public boolean appliesTo(Mob mob) {
            return true;
        }
    };

    private static final List<NpcAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    private NpcAdapters() {}

    public static void register(NpcAdapter adapter) {
        ADAPTERS.add(adapter);
    }

    /**
     * @return the applicable adapter with the highest {@link NpcAdapter#priority()},
     *         or a no-op default. Highest priority wins so a specific mod adapter
     *         (e.g. Recruits) beats a broad generic one (e.g. owner/team).
     */
    public static NpcAdapter resolve(Mob mob) {
        NpcAdapter best = DEFAULT;
        int bestPriority = Integer.MIN_VALUE;
        for (NpcAdapter adapter : ADAPTERS) {
            if (adapter.appliesTo(mob) && adapter.priority() > bestPriority) {
                best = adapter;
                bestPriority = adapter.priority();
            }
        }
        return best;
    }
}
