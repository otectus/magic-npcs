package com.otectus.magicnpcs.core.caster;

import com.otectus.magicnpcs.compat.CustomNpcsCompat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-06: the neutral framework gate (roadmap MN-002).
 *
 * <p>Asserted through {@link FrameworkEligibility#decide}, which takes the three facts the decision
 * actually rests on rather than a live mob. That is deliberate: a gate whose refusals can only be
 * reached by staging an unsupported CustomNPCs build is a gate nobody ever checks, and the bypass it
 * is meant to close went unnoticed for three releases.
 *
 * <p>The positive control matters as much as the refusals. Every non-CustomNPCs mob, and every
 * CustomNPC on a supported running build, must still be managed — a gate that refuses too much would
 * silently stop vanilla mobs casting.
 */
class FrameworkEligibilityTest {

    private static final String CUSTOMNPCS = "customnpcs";
    private static final boolean ENABLED = true;

    // --- positive controls ------------------------------------------------------------------------

    @Test
    void aVanillaMobIsNotGatedOnAnyFrameworkBuild() {
        // Even with the bridge in its worst state: a skeleton is nobody's NPC.
        assertTrue(FrameworkEligibility.decide("minecraft",
                CustomNpcsCompat.Status.DISABLED_ERROR, false).allowed());
    }

    @Test
    void anotherModsNpcIsNotGatedEither() {
        assertTrue(FrameworkEligibility.decide("recruits",
                CustomNpcsCompat.Status.PRESENT_UNSUPPORTED, ENABLED).allowed());
        assertTrue(FrameworkEligibility.decide("easy_npc",
                CustomNpcsCompat.Status.ABSENT, ENABLED).allowed());
    }

    @Test
    void aCustomNpcOnAnActiveBridgeIsManaged() {
        assertTrue(FrameworkEligibility.decide(CUSTOMNPCS,
                CustomNpcsCompat.Status.ACTIVE_PUBLIC_API, ENABLED).allowed());
        assertTrue(FrameworkEligibility.decide(CUSTOMNPCS,
                CustomNpcsCompat.Status.ACTIVE_FULL, ENABLED).allowed());
    }

    /**
     * A degraded bridge is still allowed, and that is a decision rather than an oversight: the NPC is
     * managed, its goals are merely at risk of being stripped by an AI rebuild, and refusing every
     * request would take casting away from NPCs that are working.
     */
    @Test
    void aDegradedAiRepairBridgeStillManagesItsNpcs() {
        assertTrue(FrameworkEligibility.decide(CUSTOMNPCS,
                CustomNpcsCompat.Status.DEGRADED_AI_REPAIR, ENABLED).allowed());
        assertTrue(FrameworkEligibility.isDegradedButUsable(CustomNpcsCompat.Status.DEGRADED_AI_REPAIR));
    }

    // --- refusals ---------------------------------------------------------------------------------

    /** The MN-002 case itself: an unrecognised CustomNPCs build must not be managed at all. */
    @Test
    void anUnsupportedCustomNpcsBuildIsRefused() {
        FrameworkEligibility.Verdict verdict = FrameworkEligibility.decide(CUSTOMNPCS,
                CustomNpcsCompat.Status.PRESENT_UNSUPPORTED, ENABLED);
        assertFalse(verdict.allowed());
        assertEquals(FrameworkEligibility.Decision.UNSUPPORTED_FRAMEWORK, verdict.decision());
        assertTrue(verdict.detail() != null && !verdict.detail().isBlank(),
                "a refusal has to say which build it saw, or nobody can act on it");
    }

    @Test
    void aFailedApiProbeIsRefusedAsUnavailableRatherThanUnsupported() {
        FrameworkEligibility.Verdict verdict = FrameworkEligibility.decide(CUSTOMNPCS,
                CustomNpcsCompat.Status.PROBE_FAILED, ENABLED);
        assertFalse(verdict.allowed());
        assertEquals(FrameworkEligibility.Decision.BRIDGE_UNAVAILABLE, verdict.decision());
    }

    @Test
    void aBridgeShutDownAfterAnErrorIsRefused() {
        assertEquals(FrameworkEligibility.Decision.BRIDGE_UNAVAILABLE,
                FrameworkEligibility.decide(CUSTOMNPCS,
                        CustomNpcsCompat.Status.DISABLED_ERROR, ENABLED).decision());
    }

    /**
     * A CustomNPC entity exists while the facade reports the mod absent. That combination should be
     * impossible, which is exactly why it is refused rather than allowed: whatever produced it, this
     * mod has no working bridge to manage the NPC through.
     */
    @Test
    void anImpossibleAbsentStateIsRefusedRatherThanAssumedFine() {
        assertEquals(FrameworkEligibility.Decision.BRIDGE_UNAVAILABLE,
                FrameworkEligibility.decide(CUSTOMNPCS,
                        CustomNpcsCompat.Status.ABSENT, ENABLED).decision());
    }

    /** The config switch refuses ahead of the build check, so the reported reason is the real one. */
    @Test
    void theDisabledSwitchRefusesWithItsOwnReason() {
        FrameworkEligibility.Verdict verdict = FrameworkEligibility.decide(CUSTOMNPCS,
                CustomNpcsCompat.Status.ACTIVE_FULL, false);
        assertFalse(verdict.allowed());
        assertEquals(FrameworkEligibility.Decision.FRAMEWORK_DISABLED, verdict.decision());
    }

    /** A null namespace (an unregistered entity type) is not treated as a CustomNPC. */
    @Test
    void anUnknownNamespaceIsNotGated() {
        assertTrue(FrameworkEligibility.decide(null,
                CustomNpcsCompat.Status.PRESENT_UNSUPPORTED, ENABLED).allowed());
    }
}
