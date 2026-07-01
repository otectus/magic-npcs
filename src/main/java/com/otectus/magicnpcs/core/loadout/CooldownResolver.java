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
        if (explicitTicks != null) {
            return Math.max(explicitTicks, floor);
        }
        double mult = perSpellMult != null ? perSpellMult : globalMult;
        return Math.max((int) Math.round(baseCooldown * mult), floor);
    }
}
