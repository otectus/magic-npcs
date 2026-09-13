package com.otectus.magicnpcs.gametest;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REG-35: the harness's own honesty rule (roadmap MN-015).
 *
 * <p>Three cases, and the distinction between the middle two is the whole finding. Through 0.9.0 a
 * missing host and a present one produced the same green result, so a report saying "all integration
 * tests passed" was compatible with no integration test having run. A required host that is absent now
 * has to be a failure; an unrequired one is still a skip, because the offline boot run genuinely does
 * prove something and must stay green.
 */
class PositiveProfileTest {

    private static final String IRONS = "irons_spellbooks";
    private static final String CUSTOMNPCS = "customnpcs";

    /** A present host always runs, profile or no profile. */
    @Test
    void aPresentHostAlwaysRuns() {
        assertEquals(PositiveProfile.Verdict.RUN,
                PositiveProfile.verdictFor(IRONS, true, Set.of()));
        assertEquals(PositiveProfile.Verdict.RUN,
                PositiveProfile.verdictFor(IRONS, true, Set.of(IRONS)));
    }

    /** No profile selected: an absent host is the offline smoke run, and skipping is correct. */
    @Test
    void anAbsentHostSkipsWhenNoProfileRequiresIt() {
        assertEquals(PositiveProfile.Verdict.SKIP,
                PositiveProfile.verdictFor(IRONS, false, Set.of()));
    }

    /** The MN-015 case: a profile that requires the host must fail when it is not there. */
    @Test
    void anAbsentRequiredHostFailsRatherThanSkipping() {
        assertEquals(PositiveProfile.Verdict.MISSING_REQUIRED,
                PositiveProfile.verdictFor(IRONS, false, Set.of(IRONS)));
        assertEquals(PositiveProfile.Verdict.MISSING_REQUIRED,
                PositiveProfile.verdictFor(CUSTOMNPCS, false, Set.of(IRONS, CUSTOMNPCS)));
    }

    /**
     * A profile only makes the hosts it names binding. Under {@code irons-floor} an absent CustomNPCs
     * is still a skip — claiming otherwise would fail tests the selected profile never promised.
     */
    @Test
    void aProfileOnlyBindsTheHostsItNames() {
        assertEquals(PositiveProfile.Verdict.SKIP,
                PositiveProfile.verdictFor(CUSTOMNPCS, false, Set.of(IRONS)));
    }

    /** A null required-set behaves as "nothing is required" rather than throwing. */
    @Test
    void aMissingRequiredSetIsTreatedAsNoProfile() {
        assertEquals(PositiveProfile.Verdict.SKIP,
                PositiveProfile.verdictFor(IRONS, false, null));
    }
}
