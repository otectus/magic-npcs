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

    // --- 0.9.1: the roadmap's timing cases (§6.4 REG-23's cast-time half) ------------------------

    /**
     * Rounding is half-up at the midpoint, not truncation. 15 × 0.7 is 10.5; truncating would give 10
     * and quietly make every fractional multiplier slightly faster than the author asked for.
     */
    @Test
    void aFractionalResultIsRoundedRatherThanTruncated() {
        assertEquals(11, CastTimeResolver.resolve(true, EFFECTIVE, null, 0.7));
        assertEquals(2, CastTimeResolver.resolve(true, 3, null, 0.5));
    }

    /**
     * A non-finite multiplier falls back to Iron's own timing rather than producing a garbage cast
     * duration. NaN in particular survives every comparison-based clamp, so it has to be named.
     */
    @Test
    void aNonFiniteMultiplierFallsBackToIronsOwnTiming() {
        assertEquals(EFFECTIVE, CastTimeResolver.resolve(true, EFFECTIVE, null, Double.NaN));
        assertEquals(EFFECTIVE, CastTimeResolver.resolve(true, EFFECTIVE, null, Double.POSITIVE_INFINITY));
        assertEquals(EFFECTIVE, CastTimeResolver.resolve(true, EFFECTIVE, null, Double.NEGATIVE_INFINITY));
    }

    /**
     * A huge finite multiplier saturates instead of wrapping. This is the cast-time twin of MN-017:
     * a product that does not fit in an int must not come back as a negative number, because a
     * negative duration is an instant cast — the opposite of what was asked for.
     */
    @Test
    void aHugeFiniteMultiplierSaturatesInsteadOfWrapping() {
        assertEquals(Integer.MAX_VALUE, CastTimeResolver.resolve(true, 30_000_000, null, 100.0));
        assertEquals(Integer.MAX_VALUE, CastTimeResolver.resolve(true, EFFECTIVE, null, 1e18));
    }

    /** A negative multiplier is still floored at one tick, never turned into an instant cast. */
    @Test
    void aNegativeMultiplierStillLeavesAChargingSpellCharging() {
        assertEquals(1, CastTimeResolver.resolve(true, EFFECTIVE, null, -5.0));
    }

    /** Positive control: the ordinary values in the shipped loadouts are unchanged by all of this. */
    @Test
    void ordinaryValuesAreUnaffected() {
        assertEquals(EFFECTIVE, CastTimeResolver.resolve(true, EFFECTIVE, null, 1.0));
        assertEquals(40, CastTimeResolver.resolve(true, 20, null, 2.0));
        assertEquals(12, CastTimeResolver.resolve(true, 100, 12, 0.25));
    }
}
