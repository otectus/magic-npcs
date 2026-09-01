package com.otectus.magicnpcs.core.util;

import net.minecraft.util.RandomSource;

/**
 * Overflow-safe arithmetic for the mod's weighted picks.
 *
 * <p>Every weighted pick here sums datapack-supplied weights and then rolls
 * {@code random.nextInt(total)}. Those sums were plain {@code int}s clamped only at the lower bound,
 * so a weight near {@link Integer#MAX_VALUE} — or a moderate one multiplied by
 * {@code reactive.matchedConditionWeightBonus}, which allows up to 100× — wrapped the total negative.
 * {@code RandomSource.nextInt} throws {@link IllegalArgumentException} on a non-positive bound, and in
 * the casting goal that exception escaped {@code Goal#canUse()} into {@code Mob#serverAiStep} and the
 * level tick: a hard "Ticking entity" server crash caused by nothing worse than an over-enthusiastic
 * number in a JSON file.
 *
 * <p>Totals are accumulated as {@code long} and saturate rather than wrap, so an absurd weight skews
 * selection — which is what the pack author asked for — instead of taking the server down.
 */
public final class Weights {
    private Weights() {}

    /** A weight of at least 1, widened so callers can accumulate without overflowing. */
    public static long normalize(int weight) {
        return Math.max(1, weight);
    }

    /** {@code a + b}, clamped to {@link Long#MAX_VALUE} instead of wrapping. */
    public static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /**
     * A uniform roll in {@code [0, total)} for a total that may exceed {@code int} range.
     *
     * @param total must be positive; every caller guarantees at least one entry of weight ≥ 1
     */
    public static long roll(long total, RandomSource random) {
        return Math.floorMod(random.nextLong(), total);
    }
}
