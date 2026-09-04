package com.otectus.magicnpcs.core.loadout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for {@link CastTimeResolver}: the precedence table for the per-spell native
 * cast-time overrides, and the two rules an author is most likely to be surprised by — a spell with
 * no cast duration ignores both fields, and a charging spell never drops below one tick. No
 * Minecraft/Iron's classes touched, so these run under plain {@code ./gradlew test}.
 */
class CastTimeResolverTest {

    /** Gravity Fissure: LONG, castTime 15. */
    private static final int EFFECTIVE = 15;

    @Test
    void noOverrideKeepsIronsEffectiveTimeVerbatim() {
        assertEquals(EFFECTIVE, CastTimeResolver.resolve(true, EFFECTIVE, null, null));
    }

    @Test
    void multiplierBelowOneShortensTheCharge() {
        assertEquals(8, CastTimeResolver.resolve(true, EFFECTIVE, null, 0.5));
    }

    @Test
    void multiplierAboveOneLengthensTheCharge() {
        assertEquals(30, CastTimeResolver.resolve(true, EFFECTIVE, null, 2.0));
    }

    @Test
    void absoluteTicksAreUsedVerbatim() {
        assertEquals(6, CastTimeResolver.resolve(true, EFFECTIVE, 6, null));
    }

    @Test
    void absoluteTicksWinOverTheMultiplier() {
        assertEquals(6, CastTimeResolver.resolve(true, EFFECTIVE, 6, 0.5));
    }

    @Test
    void aChargingSpellNeverDropsBelowOneTick() {
        // Zero would turn a LONG/CONTINUOUS spell into an instant cast, which is not what either
        // override is for.
        assertEquals(1, CastTimeResolver.resolve(true, EFFECTIVE, 0, null));
        assertEquals(1, CastTimeResolver.resolve(true, EFFECTIVE, null, 0.0));
    }

    @Test
    void aSpellWithNoDurationIgnoresBothOverrides() {
        assertEquals(0, CastTimeResolver.resolve(false, 0, 10, null));
        assertEquals(0, CastTimeResolver.resolve(false, 0, null, 2.0));
    }

    @Test
    void zeroEffectiveWithNoOverrideStaysZero() {
        assertEquals(0, CastTimeResolver.resolve(true, 0, null, null));
    }
}
