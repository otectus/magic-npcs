package com.otectus.magicnpcs.core.caster;

/**
 * Why a reconcile was requested. Recorded in the debug log and surfaced by {@code /magicnpcs why}, so
 * "this mob has the wrong loadout" can be traced to the event that should have corrected it.
 *
 * <p>The list is also the checklist the audit asks for (RCN-001): every one of these must run the same
 * idempotent reconcile, rather than each caller inventing its own "remove the goal and re-add it".
 */
public enum ReconcileReason {
    /** The entity joined a level — spawn, chunk load, or dimension change. */
    ENTITY_JOIN,
    /** A datapack reload published a new catalog. Applies to <b>every</b> loaded mob, not just casters. */
    DATAPACK_RELOAD,
    /** A config file was reloaded, so the master switch or a balance knob may have changed. */
    CONFIG_RELOAD,
    /** A player set, rerolled, or cleared a school by command or School Tome. */
    MANUAL_SCHOOL,
    /** A villager took or changed a job, so a profession-scoped loadout may now apply. */
    PROFESSION_CHANGE,
    /** An operator asked for it explicitly with {@code /magicnpcs reconcile}. */
    ADMIN_COMMAND,
    /** A GameTest drove it directly. */
    TEST
}
