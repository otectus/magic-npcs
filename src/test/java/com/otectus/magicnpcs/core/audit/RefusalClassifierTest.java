package com.otectus.magicnpcs.core.audit;

import com.otectus.magicnpcs.core.spell.SpellCapability;
import com.otectus.magicnpcs.core.spell.SpellSupportResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The table that decides what a pre-cast refusal means. Every rule gets a case, plus the two spells
 * the 0.9.0 instance run turned on: {@code recall}, which is right to refuse a mob, and {@code root},
 * which is not.
 */
class RefusalClassifierTest {

    @Test
    void acceptedCastIsOk() {
        RefusalClassifier.Classified c = RefusalClassifier.classify(SpellCapability.DIRECT,
                SpellSupportResolver.Provenance.VERIFIED, false, "");
        assertEquals(RefusalClassifier.Outcome.OK, c.outcome());
        assertEquals("", c.detail());
    }

    @Test
    void playerOnlyRefusalIsExpected() {
        RefusalClassifier.Classified c = RefusalClassifier.classify(SpellCapability.PLAYER_ONLY,
                SpellSupportResolver.Provenance.MANIFEST, true, "Iron's refused the cast");
        assertEquals(RefusalClassifier.Outcome.EXPECTED_PLAYER_ONLY, c.outcome());
        assertEquals("player-only spell; expected for a mob caster", c.detail());
    }

    @Test
    void verifiedSupportedCapabilityRefusalIsSuspect() {
        RefusalClassifier.Classified c = RefusalClassifier.classify(SpellCapability.SUMMON,
                SpellSupportResolver.Provenance.VERIFIED, true, "refused");
        assertEquals(RefusalClassifier.Outcome.PRECAST_REFUSED_SUSPECT, c.outcome());
        assertEquals("refused [MANIFEST_SUSPECT]", c.detail());
    }

    /**
     * A verified row that already says a mob cannot supply what the spell needs (sacrifice wants
     * the caster's own summon, wololo a sheep, multi-target spells a selection) is expected to refuse.
     */
    @Test
    void unsupportedVerifiedCapabilityRefusalIsExpected() {
        RefusalClassifier.Classified c = RefusalClassifier.classify(SpellCapability.MULTI_TARGET,
                SpellSupportResolver.Provenance.VERIFIED, true, "refused");
        assertEquals(RefusalClassifier.Outcome.EXPECTED_UNSUPPORTED, c.outcome());
        assertEquals("manifest marks this MULTI_TARGET; a mob cannot supply it", c.detail());
    }

    /** The same capability from a datapack manifest is not our claim, so it stays a plain refusal. */
    @Test
    void unsupportedManifestCapabilityRefusalIsPlainRefusal() {
        RefusalClassifier.Classified c = RefusalClassifier.classify(SpellCapability.SPECIAL_PREPARATION,
                SpellSupportResolver.Provenance.MANIFEST, true, "refused");
        assertEquals(RefusalClassifier.Outcome.PRECAST_REFUSED, c.outcome());
        assertEquals("refused", c.detail());
    }

    /** {@code recall} hard-checks {@code instanceof ServerPlayer}: the manifest is right about it. */
    @Test
    void recallRefusalNeverContradictsTheManifest() {
        RefusalClassifier.Classified c = RefusalClassifier.classify(SpellCapability.PLAYER_ONLY,
                SpellSupportResolver.Provenance.VERIFIED, true, "refused");
        assertEquals(RefusalClassifier.Outcome.EXPECTED_PLAYER_ONLY, c.outcome());
    }

    /** {@code root} is verified TARGET_ENTITY, so a refusal means one of the two is wrong. */
    @Test
    void rootRefusalIsFlaggedForFollowUp() {
        RefusalClassifier.Classified c = RefusalClassifier.classify(SpellCapability.TARGET_ENTITY,
                SpellSupportResolver.Provenance.VERIFIED, true,
                "target raycast missed (manifest: TARGET_ENTITY)");
        assertEquals(RefusalClassifier.Outcome.PRECAST_REFUSED_SUSPECT, c.outcome());
        assertTrue(c.detail().endsWith(" [MANIFEST_SUSPECT]"));
    }

    /** An add-on spell nothing verified refusing is just a refusal; there is no claim to contradict. */
    @Test
    void unverifiedAddonRefusalIsNotSuspect() {
        RefusalClassifier.Classified c = RefusalClassifier.classify(SpellCapability.UNVERIFIED,
                SpellSupportResolver.Provenance.UNVERIFIED, true, "refused");
        assertEquals(RefusalClassifier.Outcome.PRECAST_REFUSED, c.outcome());
        assertEquals("refused", c.detail());
    }
}
