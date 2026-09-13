package com.otectus.magicnpcs.core.loadout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link CooldownResolver}: the precedence and direction rules that
 * datapack authors most often get wrong (does a bigger multiplier mean a longer or shorter
 * cooldown?). No Minecraft/Iron's classes touched, so these run under plain {@code ./gradlew test}.
 */
class CooldownResolverTest {

    private static final int FLOOR = 20; // default balance.minCooldownTicks
    private static final double GLOBAL = 1.0;

    @Test
    void explicitTicksTakePrecedenceOverMultiplier() {
        // Explicit cooldown=100 wins even though a multiplier is also present.
        int cd = CooldownResolver.resolve(100, 0.5, GLOBAL, 1200, FLOOR);
        assertEquals(100, cd, "explicit cooldown ticks must override the multiplier path");
    }

    @Test
    void echoingStrikesExampleFiveSeconds() {
        // The user's case: force a 5-second (100-tick) cooldown regardless of the Iron's default.
        assertEquals(100, CooldownResolver.resolve(100, null, GLOBAL, 1200, FLOOR));
    }

    @Test
    void multiplierBelowOneShortensCooldown() {
        // 60 s (1200 t) × 0.0833 ≈ 100 t (5 s): a multiplier < 1 makes the spell FASTER.
        int cd = CooldownResolver.resolve(null, 0.0833, GLOBAL, 1200, FLOOR);
        assertTrue(cd < 1200, "a multiplier below 1.0 must shorten the cooldown");
        assertEquals(100, cd, "1200 × 0.0833 should round to ~100 ticks");
    }

    @Test
    void multiplierAboveOneLengthensCooldown() {
        // A multiplier > 1 makes the spell SLOWER (longer cooldown) — the common misconception.
        int cd = CooldownResolver.resolve(null, 2.0, GLOBAL, 200, FLOOR);
        assertEquals(400, cd, "a multiplier above 1.0 must lengthen the cooldown");
    }

    @Test
    void globalMultiplierUsedWhenNoPerSpellFields() {
        int cd = CooldownResolver.resolve(null, null, 1.5, 200, FLOOR);
        assertEquals(300, cd, "with no per-spell fields, the global multiplier scales the base");
    }

    @Test
    void floorClampsExplicitTicks() {
        // minCooldownTicks can prevent a very low explicit cooldown.
        int cd = CooldownResolver.resolve(5, null, GLOBAL, 1200, 40);
        assertEquals(40, cd, "minCooldownTicks must floor even an explicit cooldown");
    }

    @Test
    void floorClampsMultiplierPath() {
        int cd = CooldownResolver.resolve(null, 0.0, GLOBAL, 1200, FLOOR);
        assertEquals(FLOOR, cd, "a 0 multiplier still cannot go below the floor");
    }

    // --- REG-23: a positive cooldown value must never make a spell faster (roadmap MN-017) --------

    /**
     * The roadmap's exact witness. 30,000,000 ticks at ×100 is 3×10^9, which does not fit in an int;
     * through 0.9.0 the narrowing cast happened before the floor, so the product wrapped negative and
     * {@code Math.max} handed back {@code minCooldownTicks}. An author asking for "effectively never"
     * got the fastest cooldown the server allowed.
     */
    @Test
    void theRoadmapWitnessSaturatesInsteadOfWrappingToTheFloor() {
        int cd = CooldownResolver.resolve(null, 100.0, GLOBAL, 30_000_000, FLOOR);
        assertTrue(cd > FLOOR,
                "a huge positive cooldown must not resolve to the minimum; got " + cd);
        assertEquals(Integer.MAX_VALUE, cd,
                "an unrepresentable cooldown saturates rather than wrapping");
    }

    /** The boundary either side of {@link Integer#MAX_VALUE}, so saturation starts where it should. */
    @Test
    void integerBoundariesAreHandledExactly() {
        assertEquals(Integer.MAX_VALUE - 1,
                CooldownResolver.resolve(null, 1.0, GLOBAL, Integer.MAX_VALUE - 1, FLOOR));
        assertEquals(Integer.MAX_VALUE,
                CooldownResolver.resolve(null, 1.0, GLOBAL, Integer.MAX_VALUE, FLOOR));
        assertEquals(Integer.MAX_VALUE,
                CooldownResolver.resolve(null, 2.0, GLOBAL, Integer.MAX_VALUE, FLOOR));
    }

    /** An explicit tick count at the top of the range is used verbatim, not wrapped. */
    @Test
    void anExplicitMaximumCooldownIsUsedVerbatim() {
        assertEquals(Integer.MAX_VALUE,
                CooldownResolver.resolve(Integer.MAX_VALUE, null, GLOBAL, 200, FLOOR));
    }

    /**
     * NaN and infinity are refused rather than silently producing a number. NaN is the dangerous one:
     * it survives {@code Math.max(0.0, x)}, so the old clamp let it straight through into the
     * arithmetic.
     */
    @Test
    void nonFiniteMultipliersResolveToTheFloorRatherThanGarbage() {
        assertEquals(FLOOR, CooldownResolver.resolve(null, Double.NaN, GLOBAL, 1200, FLOOR));
        assertEquals(FLOOR, CooldownResolver.resolve(null, Double.POSITIVE_INFINITY, GLOBAL, 1200, FLOOR));
        assertEquals(FLOOR, CooldownResolver.resolve(null, Double.NEGATIVE_INFINITY, GLOBAL, 1200, FLOOR));
        assertEquals(FLOOR, CooldownResolver.resolve(null, null, Double.NaN, 1200, FLOOR));
    }

    /** A negative multiplier cannot produce a negative cooldown, which would read as "ready now". */
    @Test
    void aNegativeMultiplierCannotProduceANegativeCooldown() {
        assertEquals(FLOOR, CooldownResolver.resolve(null, -1.0, GLOBAL, 1200, FLOOR));
    }

    /** A negative configured floor does not become a negative cooldown either. */
    @Test
    void aNegativeFloorIsTreatedAsZero() {
        assertEquals(200, CooldownResolver.resolve(null, 1.0, GLOBAL, 200, -100));
        assertEquals(0, CooldownResolver.resolve(0, null, GLOBAL, 200, -100));
    }

    /**
     * Positive control: every ordinary value in the shipped loadouts resolves exactly as it did
     * before this change. The overflow fix must be invisible in the normal range.
     */
    @Test
    void ordinaryRangesAreUnchanged() {
        assertEquals(1200, CooldownResolver.resolve(null, 1.0, GLOBAL, 1200, FLOOR));
        assertEquals(600, CooldownResolver.resolve(null, 0.5, GLOBAL, 1200, FLOOR));
        assertEquals(2400, CooldownResolver.resolve(null, 2.0, GLOBAL, 1200, FLOOR));
        assertEquals(100, CooldownResolver.resolve(100, null, GLOBAL, 1200, FLOOR));
    }
}
