package com.otectus.magicnpcs.core.loadout;

/**
 * Pure resolver for the native cast duration of one NPC cast, in ticks. Centralizes the
 * precedence rules so they are documented in one place and unit-testable without a running
 * game (the Iron's adapter delegates here). Iron's-free.
 *
 * <p><b>Precedence</b> (highest first):
 * <ol>
 *   <li>an explicit per-spell {@code cast_time} (ticks) — used verbatim, only ever raised by the floor;</li>
 *   <li>a per-spell {@code cast_time_multiplier} — scales the spell's Iron's effective cast time;</li>
 *   <li>neither — Iron's effective cast time is returned unchanged.</li>
 * </ol>
 *
 * <p><b>Backward compatibility</b>: with both overrides {@code null} the input is returned
 * verbatim, with no normalisation, so a loadout written before these fields existed casts on
 * exactly Iron's timing.
 *
 * <p><b>Multiplier direction</b>: the multiplier scales Iron's effective cast time, so a value
 * <em>above</em> {@code 1.0} makes the charge <em>longer</em> and a value <em>below</em>
 * {@code 1.0} makes it <em>shorter</em>. e.g. a 15-tick charge at {@code 0.5} is 8 ticks.
 *
 * <p><b>Why 1 is the floor</b>: a spell with a cast duration (Iron's {@code LONG} or
 * {@code CONTINUOUS}) has to occupy at least one tick to be charged at all, so an override of
 * {@code 0} — or a multiplier that rounds to {@code 0} — resolves to 1 rather than silently
 * turning the spell into an instant cast. Spells with no cast duration ({@code INSTANT} /
 * {@code NONE}) always resolve to 0 and ignore both overrides entirely.
 */
public final class CastTimeResolver {
    private CastTimeResolver() {}

    /**
     * @param hasDuration   whether the spell actually charges (Iron's {@code LONG} / {@code CONTINUOUS})
     * @param ironsEffective Iron's own effective cast time in ticks for this spell, level and caster
     * @param absoluteTicks per-spell {@code cast_time} in ticks, or {@code null} to fall through
     * @param multiplier    per-spell {@code cast_time_multiplier}, or {@code null} for no scaling
     * @return the cast duration in ticks: 0 when the spell has no duration, otherwise never below 1
     *         once an override is set
     */
    public static int resolve(boolean hasDuration, int ironsEffective, Integer absoluteTicks, Double multiplier) {
        if (!hasDuration) {
            return 0;
        }
        if (absoluteTicks == null && multiplier == null) {
            return ironsEffective;
        }
        if (absoluteTicks != null) {
            return Math.max(1, absoluteTicks);
        }
        double scaled = ironsEffective * multiplier;
        if (!Double.isFinite(scaled)) {
            return ironsEffective;
        }
        long rounded = Math.round(scaled);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, rounded));
    }
}
