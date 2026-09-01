package com.otectus.magicnpcs.core.loadout;

import java.util.Locale;

/**
 * How a loadout's casting goal should coexist with the mob's own built-in attack AI — the
 * per-loadout half of ADR 0002. Set with the root-level {@code "native_attack"} field.
 *
 * <p>Vanilla-only (the goal-class matching that implements {@link #SUPPRESS} and {@link #YIELD}
 * lives in {@code core.util.AttackGoals}), so this enum is safe to parse without Iron's present.
 */
public enum NativeAttackPolicy {
    /** Default: the casting goal runs alongside the mob's own attack goals. */
    COEXIST,
    /** Remove the mob's native ranged/melee attack goals at injection — a "pure caster" conversion. */
    SUPPRESS,
    /** Only cast while none of the mob's own attack goals is currently running. */
    YIELD;

    /**
     * @param raw the JSON value
     * @return the matching policy
     * @throws IllegalArgumentException with a readable message when {@code raw} is not a policy
     */
    public static NativeAttackPolicy parse(String raw) {
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "native_attack must be 'coexist', 'suppress', or 'yield', got '" + raw + "'");
        }
    }

    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
