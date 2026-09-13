package com.otectus.magicnpcs.core.loadout;

/**
 * Pure resolver for an NPC spell's effective cooldown, in ticks. Centralizes the
 * precedence rules so they are documented in one place and unit-testable without a
 * running game (the casting goal delegates here). Iron's-free.
 *
 * <p><b>Precedence</b> (highest first):
 * <ol>
 *   <li>an explicit per-spell {@code cooldown} (ticks) — used verbatim, only ever raised by the floor;</li>
 *   <li>a per-spell {@code cooldown_multiplier} — scales the spell's Iron's default cooldown;</li>
 *   <li>the global {@code balance.cooldownMultiplier} — the same scaling, applied when neither
 *       per-spell field is set.</li>
 * </ol>
 *
 * <p><b>Multiplier direction</b>: the multiplier scales the Iron's default, so a value
 * <em>above</em> {@code 1.0} makes the spell <em>slower</em> (longer cooldown) and a value
 * <em>below</em> {@code 1.0} makes it <em>faster</em> (shorter cooldown). e.g. a 1200-tick
 * (60 s) spell at {@code 0.0833} ≈ 100 ticks (5 s).
 *
 * <p>Every result is floored by {@code minCooldownTicks} — including an explicit
 * {@code cooldown} — so {@code minCooldownTicks} can prevent very low cooldowns.
 * 20 ticks = 1 second.
 *
 * <p><b>Large values saturate; they never wrap.</b> Through 0.9.0 the scaled product was narrowed to
 * an {@code int} before the floor was applied, so a large enough base × multiplier overflowed into a
 * negative number and was replaced by {@code minCooldownTicks}: raising a cooldown made the spell cast
 * <em>more</em> often (roadmap MN-017). A value that cannot be represented is now
 * {@link Integer#MAX_VALUE}, and a non-finite or negative multiplier is refused rather than silently
 * producing the shortest cooldown the config allows.
 */
public final class CooldownResolver {
    private CooldownResolver() {}

    /**
     * @param explicitTicks per-spell {@code cooldown} in ticks, or {@code null} to fall through
     * @param perSpellMult  per-spell {@code cooldown_multiplier}, or {@code null} to use {@code globalMult}
     * @param globalMult    global {@code balance.cooldownMultiplier}
     * @param baseCooldown  the spell's Iron's default cooldown in ticks ({@code AbstractSpell#getSpellCooldown})
     * @param floor         {@code balance.minCooldownTicks}, a hard floor applied to every result
     * @return the effective cooldown in ticks, never below {@code floor}
     */
    public static int resolve(Integer explicitTicks, Double perSpellMult, double globalMult,
                              int baseCooldown, int floor) {
        int safeFloor = Math.max(0, floor);
        if (explicitTicks != null) {
            return Math.max(explicitTicks, safeFloor);
        }
        double mult = perSpellMult != null ? perSpellMult : globalMult;
        if (!Double.isFinite(mult) || mult < 0.0) {
            // NaN, ±infinity and a negative multiplier have no cooldown they could mean. Answering
            // the floor is the conservative reading — a spell nobody can describe the rest of does
            // not get to be the fastest spell in the game — and the parser has already complained
            // about the value, so this is a second line of defence rather than the only report.
            return safeFloor;
        }
        // Saturating, and BEFORE the narrowing cast. This is the MN-017 defect: a 30,000,000-tick
        // base at multiplier 100 is 3e9, which does not fit in an int, and (int) Math.round wrapped
        // it to a large negative number that Math.max then replaced with the floor. A deliberately
        // enormous cooldown became the shortest one allowed - a positive number made the spell
        // faster. Computing in double and clamping to Integer.MAX_VALUE keeps "effectively never"
        // meaning effectively never, and ManagedCasterState holds the resulting deadline as a long
        // so the saturation is not simply relocated into the deadline arithmetic.
        double scaled = Math.round((double) baseCooldown * mult);
        if (scaled >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max((int) scaled, safeFloor);
    }
}
