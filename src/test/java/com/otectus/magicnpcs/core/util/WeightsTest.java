package com.otectus.magicnpcs.core.util;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link Weights}, the overflow-safe arithmetic behind every weighted pick.
 *
 * <p>The failure this guards against was a server crash, not a mis-pick: totals were {@code int} sums
 * of datapack-supplied weights, so a large weight (or a moderate one scaled by
 * {@code matchedConditionWeightBonus}, which allows 100×) wrapped negative, and
 * {@code RandomSource.nextInt} throws on a non-positive bound — from inside {@code Goal#canUse()},
 * which lands in the level tick as a "Ticking entity" crash.
 */
class WeightsTest {

    @Test
    void hugeWeightsSaturateInsteadOfWrappingNegative() {
        long total = 0L;
        for (int i = 0; i < 4; i++) {
            total = Weights.saturatingAdd(total, Weights.normalize(Integer.MAX_VALUE));
        }
        assertTrue(total > 0, "a total of four near-max weights must stay positive");
        assertEquals(4L * Integer.MAX_VALUE, total);
    }

    @Test
    void saturationClampsRatherThanOverflowing() {
        assertEquals(Long.MAX_VALUE, Weights.saturatingAdd(Long.MAX_VALUE, 1L));
        assertEquals(Long.MAX_VALUE, Weights.saturatingAdd(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    void weightsAreAtLeastOne() {
        assertEquals(1L, Weights.normalize(0));
        assertEquals(1L, Weights.normalize(-17));
        assertEquals(9L, Weights.normalize(9));
    }

    @Test
    void rollStaysWithinRangeForTotalsBeyondIntRange() {
        RandomSource random = RandomSource.create(20260821L);
        long total = 4L * Integer.MAX_VALUE;
        for (int i = 0; i < 500; i++) {
            long roll = Weights.roll(total, random);
            assertTrue(roll >= 0 && roll < total, "roll out of range: " + roll);
        }
    }

    @Test
    void rollCoversASmallTotalEvenly() {
        RandomSource random = RandomSource.create(7L);
        boolean[] seen = new boolean[3];
        for (int i = 0; i < 200; i++) {
            seen[(int) Weights.roll(3L, random)] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            assertTrue(seen[i], "never rolled " + i);
        }
    }
}
