package com.otectus.magicnpcs.core.adapter;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Per-NPC-mod hook consulted by the universal casting path, so mod-specific
 * concepts (ownership, diplomacy, rank) can feed targeting and mana scaling
 * WITHOUT the Iron's-side code importing that mod. Implementations live under
 * {@code compat/<mod>/} and are registered via {@link NpcAdapters} only when the
 * backing mod is present.
 *
 * <p>Pure: no Iron's, no mod-specific imports here — those stay in the concrete
 * implementations.
 */
public interface NpcAdapter {

    /** @return true if this adapter governs the given mob. */
    boolean appliesTo(Mob mob);

    /** Multiplier on the mob's max-mana pool (e.g. scale by rank/level). Default: no scaling. */
    default double manaScale(Mob mob) {
        return 1.0;
    }

    /** @return false to forbid casting an attack spell at {@code target} (friendly-fire gate). */
    default boolean canCastAt(Mob caster, LivingEntity target) {
        return true;
    }

    /**
     * @return true if this adapter has a notion of allies worth a line-of-fire
     *         check. When false, the universal goal skips the ally scan entirely.
     */
    default boolean tracksAllies() {
        return false;
    }

    /** @return true if {@code other} is an ally that must not be caught in the line of fire. */
    default boolean isAlly(Mob caster, LivingEntity other) {
        return false;
    }
}
