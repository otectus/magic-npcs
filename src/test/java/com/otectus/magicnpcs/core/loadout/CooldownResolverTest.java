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
}
