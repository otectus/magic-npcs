package com.otectus.magicnpcs.compat.recruits;

import com.talhanation.recruits.entities.AbstractRecruitEntity;
import net.minecraft.world.entity.Mob;

/**
 * Recruits-side helpers for the runtime GameTests, kept in the {@code compat.recruits}
 * package so Recruits types never leak into the Iron's-side test bodies (the import-isolation
 * invariant). Only referenced behind a {@code RecruitsCompat.isLoaded()} guard.
 */
public final class RecruitsTestSupport {
    /**
     * Recruits combat state in which a recruit attacks hostile monsters on sight. Per
     * {@code AbstractRecruitEntity.shouldAttack}, the aggressive state (1) routes to
     * {@code shouldAttackOnAggressive}, which accepts any monster; the passive state (3) never
     * attacks (see {@link RecruitsAdapter#canCastNow}).
     */
    private static final int STATE_AGGRESSIVE = 1;

    private RecruitsTestSupport() {}

    /** Put a recruit into the aggressive combat state so it will engage a hostile target in a GameTest. */
    public static void makeAggressive(Mob mob) {
        if (mob instanceof AbstractRecruitEntity recruit) {
            recruit.setAggroState(STATE_AGGRESSIVE);
        }
    }
}
