package com.otectus.magicnpcs.core.caster;

/**
 * What one reconcile call actually did, and why.
 *
 * <p>0.6.1's {@code tryInject} returned a plain {@code boolean}, and returned {@code true} even when
 * {@code applyLoadout} bailed out early because the mob had no mana attributes — so a {@code /reload}
 * could report "rebuilt 40 live casters" for forty mobs that had not been given a goal (audit RCN-005).
 * Every path now says which of these happened, and the counts printed to the log and to commands are
 * built from these outcomes rather than from "we called the method".
 */
public record ReconcileResult(Outcome outcome, ReasonCode reason, String detail) {

    public enum Outcome {
        /** A casting goal was installed where there was none. */
        INSTALLED,
        /** An existing caster's goal was replaced because its loadout changed. */
        UPDATED,
        /** Already running exactly this loadout: nothing was touched. */
        UNCHANGED,
        /** A casting goal was removed because the mob should no longer cast. */
        REMOVED,
        /** The mob is not a caster and was not one before: nothing to do. */
        NOT_APPLICABLE,
        /** Reconciliation was attempted and could not complete. */
        FAILED;

        /** @return true when this outcome changed the mob's behaviour. */
        public boolean changed() {
            return this == INSTALLED || this == UPDATED || this == REMOVED;
        }
    }

    /**
     * Stable machine-readable reasons. {@code /magicnpcs why} and the GameTests refer to these rather
     * than to English text, so a message can be reworded without breaking a test or a support runbook.
     */
    public enum ReasonCode {
        OK("reconciled"),
        NO_LOADOUT("no loadout applies to this mob"),
        SUPPRESSED("a loadout exists but is deliberately switched off"),
        NOT_A_CASTER("this individual lost its one-time caster_chance roll"),
        MASTER_SWITCH_OFF("general.enableSpellcasting is false"),
        MANUAL_CLEARED("a player cleared this mob's school by hand"),
        MANUAL_SCHOOL_UNUSABLE("the hand-assigned school yields no castable spells right now"),
        NO_MANA_ATTRIBUTES("the mob has no Iron's mana attributes, so it cannot be given a mana pool"),
        NO_CASTABLE_SPELLS("every spell in the loadout was filtered out"),
        CLIENT_SIDE("reconciliation was attempted on the client"),
        UNCHANGED("the installed loadout already matches the catalog");

        private final String description;

        ReasonCode(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public static ReconcileResult of(Outcome outcome, ReasonCode reason) {
        return new ReconcileResult(outcome, reason, null);
    }

    public static ReconcileResult of(Outcome outcome, ReasonCode reason, String detail) {
        return new ReconcileResult(outcome, reason, detail);
    }

    /** @return true if a casting goal is installed as a result of this reconcile. */
    public boolean casterInstalled() {
        return outcome == Outcome.INSTALLED || outcome == Outcome.UPDATED || outcome == Outcome.UNCHANGED;
    }

    public String describe() {
        return outcome.name().toLowerCase(java.util.Locale.ROOT) + " — " + reason.description()
                + (detail == null ? "" : " (" + detail + ")");
    }
}
