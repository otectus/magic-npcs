package com.otectus.magicnpcs.core;

import net.minecraft.resources.ResourceLocation;

/**
 * Why a school assignment succeeded or failed. Replaces the 0.5.0 {@code boolean}, which made
 * {@code /magicnpcs school reroll} report "Re-rolled schools for 0 NPC(s)" with no indication of which
 * school it tried or why nothing happened — the exact complaint behind W4.
 *
 * <p>Iron's-free so {@code command/} and the School Tome can consume it.
 */
public enum SchoolAssignResult {
    /** The school was assigned and a casting goal was (re)injected. */
    OK,
    /** The whole school system is switched off (`schools.enableSchools`). */
    SCHOOLS_DISABLED,
    /** No such school is registered in this Iron's build. */
    UNKNOWN_SCHOOL,
    /** The school is registered but not listed in {@code schools.allowedSchools}. */
    SCHOOL_NOT_ALLOWED,
    /**
     * The school exists and is allowed, but every spell in it was filtered out by the current caps —
     * {@code maxRarity}, {@code maxSpellLevel}, {@code allowedCastTypes}, the spell blacklist/whitelist,
     * or Iron's own per-spell enable flags. Run {@code /magicnpcs school pool <school>} for the
     * per-filter breakdown.
     */
    NO_CASTABLE_SPELLS;

    public boolean ok() {
        return this == OK;
    }

    /** A one-line explanation naming the school, for command output. */
    public String describe(ResourceLocation school) {
        return switch (this) {
            case OK -> school + ": assigned";
            case SCHOOLS_DISABLED -> "magic schools are disabled (schools.enableSchools = false)";
            case UNKNOWN_SCHOOL -> school + ": not a registered Iron's school in this install";
            case SCHOOL_NOT_ALLOWED -> school + ": not listed in schools.allowedSchools";
            case NO_CASTABLE_SPELLS -> school + ": no castable spells under the current filters "
                    + "(see /magicnpcs school pool " + school + ")";
        };
    }
}
