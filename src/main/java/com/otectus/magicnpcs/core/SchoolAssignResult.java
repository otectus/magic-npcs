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
    NO_CASTABLE_SPELLS,
    /**
     * The NPC belongs to a framework at a build this mod is not pinned to. Deliberately left
     * unmanaged: the bridge declines to speak for an unrecognised build, and through 0.9.0 a manual
     * assignment wrote a school onto it anyway, which is the bypass MN-002 records.
     */
    UNSUPPORTED_NPC_FRAMEWORK,
    /** The NPC's framework is installed but its bridge is not running, so it cannot be managed. */
    NPC_FRAMEWORK_UNAVAILABLE,
    /** The integration for this NPC's framework is switched off in the config. */
    NPC_FRAMEWORK_DISABLED;

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
            case UNSUPPORTED_NPC_FRAMEWORK -> "this NPC's own mod is at a build Magic NPCs is not "
                    + "pinned to, so it is left unmanaged — nothing was written to it";
            case NPC_FRAMEWORK_UNAVAILABLE -> "this NPC's own mod is installed but its Magic NPCs "
                    + "bridge is not running — nothing was written to it";
            case NPC_FRAMEWORK_DISABLED -> "the integration for this NPC's own mod is switched off — "
                    + "nothing was written to it";
        };
    }
}
