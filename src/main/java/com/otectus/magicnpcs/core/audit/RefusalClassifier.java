package com.otectus.magicnpcs.core.audit;

import com.otectus.magicnpcs.core.spell.SpellCapability;
import com.otectus.magicnpcs.core.spell.SpellSupportResolver;

/**
 * What a pre-cast refusal <em>means</em>, given what this build claims about the spell.
 *
 * <p>The audit could only ever say "Iron's said no", which is useless on its own: a player-only spell
 * refusing a mob caster is the manifest working, while a verified {@code TARGET_ENTITY} spell refusing
 * one is the manifest being wrong (or the probe being wrong — that is exactly what the 0.9.0 instance
 * run found). Splitting the two is a table over capability, provenance and the refused flag, so it
 * lives in {@code core}, free of Iron's and testable without a server.
 */
public final class RefusalClassifier {

    private RefusalClassifier() {}

    /** How a single audited spell's pre-cast result should be reported. */
    public enum Outcome {
        /** Iron's accepted the cast. */
        OK,
        /** Refused, and this build already said a mob cannot cast it. */
        EXPECTED_PLAYER_ONLY,
        /**
         * Refused, and this build's verified manifest already marks the spell as something a mob
         * cannot supply (a summon of its own, a sheep, a multi-target selection…).
         */
        EXPECTED_UNSUPPORTED,
        /** Refused, and nothing this build claims contradicts that. */
        PRECAST_REFUSED,
        /** Refused although this build verified a mob-castable capability: one of the two is wrong. */
        PRECAST_REFUSED_SUSPECT
    }

    /** An outcome plus the detail text the audit report prints beside it. */
    public record Classified(Outcome outcome, String detail) {}

    /**
     * @param capability    what this build claims a mob must supply for the spell
     * @param provenance    which layer made that claim
     * @param refused       true if Iron's pre-cast check said no
     * @param refusalDetail the raw reason, carried into the detail of a genuine refusal
     */
    public static Classified classify(SpellCapability capability,
                                      SpellSupportResolver.Provenance provenance,
                                      boolean refused, String refusalDetail) {
        String detail = refusalDetail == null ? "" : refusalDetail;
        if (!refused) {
            return new Classified(Outcome.OK, "");
        }
        if (capability == SpellCapability.PLAYER_ONLY) {
            return new Classified(Outcome.EXPECTED_PLAYER_ONLY,
                    "player-only spell; expected for a mob caster");
        }
        if (provenance == SpellSupportResolver.Provenance.VERIFIED && capability != null) {
            if (capability.supported()) {
                return new Classified(Outcome.PRECAST_REFUSED_SUSPECT, detail + " [MANIFEST_SUSPECT]");
            }
            return new Classified(Outcome.EXPECTED_UNSUPPORTED,
                    "manifest marks this " + capability.name() + "; a mob cannot supply it");
        }
        return new Classified(Outcome.PRECAST_REFUSED, detail);
    }
}
