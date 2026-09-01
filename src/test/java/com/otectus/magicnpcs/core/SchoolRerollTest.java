package com.otectus.magicnpcs.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SchoolReroll}, the fix for "re-rolled schools for 0 NPC(s)" (W4).
 *
 * <p>The 0.5.0 behaviour these pin down as wrong: pick exactly one random school, and if it happens to
 * have no castable spells, report a bare zero. The policy must instead try the whole allowed pool and,
 * on total failure, hand back every school it tried and why.
 */
@SuppressWarnings("deprecation")
class SchoolRerollTest {

    private static final ResourceLocation FIRE = new ResourceLocation("irons_spellbooks", "fire");
    private static final ResourceLocation ICE = new ResourceLocation("irons_spellbooks", "ice");
    private static final ResourceLocation HOLY = new ResourceLocation("irons_spellbooks", "holy");
    private static final List<ResourceLocation> ALLOWED = List.of(FIRE, ICE, HOLY);

    private static RandomSource rng() {
        return RandomSource.create(1234L);
    }

    @Test
    void succeedsOnTheFirstSchoolThatYieldsALoadout() {
        // Only HOLY works — 0.5.0 would have reported failure two times out of three.
        SchoolReroll.Outcome outcome = SchoolReroll.reroll(ALLOWED, null, rng(),
                school -> school.equals(HOLY) ? SchoolAssignResult.OK : SchoolAssignResult.NO_CASTABLE_SPELLS);
        assertTrue(outcome.ok());
        assertEquals(HOLY, outcome.assigned());
    }

    @Test
    void reportsEverySchoolItTriedWhenNoneWork() {
        SchoolReroll.Outcome outcome = SchoolReroll.reroll(ALLOWED, null, rng(),
                school -> SchoolAssignResult.NO_CASTABLE_SPELLS);
        assertFalse(outcome.ok());
        assertNull(outcome.assigned());
        assertEquals(3, outcome.failures().size(), "every allowed school must be tried before giving up");
        assertTrue(outcome.failures().keySet().containsAll(ALLOWED));
        // The message names the schools and points at the diagnostic command, not just "0 NPCs".
        assertTrue(outcome.describeFailures().contains("irons_spellbooks:fire"));
        assertTrue(outcome.describeFailures().contains("/magicnpcs school pool"));
    }

    @Test
    void excludesTheCurrentSchoolSoARerollActuallyRerolls() {
        List<ResourceLocation> tried = new ArrayList<>();
        SchoolReroll.Outcome outcome = SchoolReroll.reroll(ALLOWED, FIRE, rng(), school -> {
            tried.add(school);
            return SchoolAssignResult.OK;
        });
        assertTrue(outcome.ok());
        assertFalse(tried.contains(FIRE), "a reroll must not hand back the school the mob already had");
    }

    @Test
    void keepsTheOnlyAllowedSchoolEvenIfItIsTheCurrentOne() {
        // With one school configured, "exclude the current one" would leave nothing to try.
        SchoolReroll.Outcome outcome = SchoolReroll.reroll(List.of(FIRE), FIRE, rng(),
                school -> SchoolAssignResult.OK);
        assertTrue(outcome.ok());
        assertEquals(FIRE, outcome.assigned());
    }

    @Test
    void stopsImmediatelyWhenTheWholeSchoolSystemIsDisabled() {
        List<ResourceLocation> tried = new ArrayList<>();
        SchoolReroll.Outcome outcome = SchoolReroll.reroll(ALLOWED, null, rng(), school -> {
            tried.add(school);
            return SchoolAssignResult.SCHOOLS_DISABLED;
        });
        assertFalse(outcome.ok());
        assertEquals(1, tried.size(), "a global switch should not be re-tested once per school");
    }

    @Test
    void emptyAllowedPoolFailsWithoutTryingAnything() {
        SchoolReroll.Outcome outcome = SchoolReroll.reroll(List.of(), null, rng(), school -> {
            throw new AssertionError("must not attempt any school");
        });
        assertFalse(outcome.ok());
        assertTrue(outcome.failures().isEmpty());
    }

    @Test
    void failuresAreReportedInTheOrderTried() {
        // describeFailures() documents "in the order tried", but Map.copyOf returns a hash-ordered
        // map, so the reported order was arbitrary.
        List<ResourceLocation> tried = new ArrayList<>();
        SchoolReroll.Outcome outcome = SchoolReroll.reroll(ALLOWED, null, rng(), school -> {
            tried.add(school);
            return SchoolAssignResult.NO_CASTABLE_SPELLS;
        });
        assertFalse(outcome.ok());
        assertEquals(tried, new ArrayList<>(outcome.failures().keySet()));
    }

    // --- Tome cycling ---------------------------------------------------------------------------

    @Test
    void cycleAdvancesToTheNextSchoolInOrder() {
        SchoolReroll.CycleOutcome outcome =
                SchoolReroll.cycle(ALLOWED, FIRE, school -> SchoolAssignResult.OK);
        assertEquals(ICE, outcome.assigned());
        assertFalse(outcome.cleared());
    }

    @Test
    void cycleStartsAtTheFirstSchoolWhenNothingIsAssigned() {
        SchoolReroll.CycleOutcome outcome =
                SchoolReroll.cycle(ALLOWED, null, school -> SchoolAssignResult.OK);
        assertEquals(FIRE, outcome.assigned());
    }

    @Test
    void cycleSkipsSchoolsWithNoCastableSpells() {
        // The old (index + 1) % size Tome logic asked for ICE, failed, and — because a failed apply
        // leaves the stored school untouched — asked for ICE again on the next click, forever.
        SchoolReroll.CycleOutcome outcome = SchoolReroll.cycle(ALLOWED, FIRE,
                school -> school.equals(ICE) ? SchoolAssignResult.NO_CASTABLE_SPELLS : SchoolAssignResult.OK);
        assertEquals(HOLY, outcome.assigned());
        assertTrue(outcome.failures().containsKey(ICE));
    }

    @Test
    void cyclingPastTheLastSchoolClears() {
        // This is what keeps "stop casting" reachable from the item once sneak means "cycle".
        SchoolReroll.CycleOutcome outcome =
                SchoolReroll.cycle(ALLOWED, HOLY, school -> {
                    throw new AssertionError("nothing left to try after the last school");
                });
        assertTrue(outcome.cleared());
        assertNull(outcome.assigned());
        assertTrue(outcome.ok());
    }

    @Test
    void cycleReportsFailureWhenEveryRemainingSchoolIsEmpty() {
        SchoolReroll.CycleOutcome outcome = SchoolReroll.cycle(ALLOWED, null,
                school -> SchoolAssignResult.NO_CASTABLE_SPELLS);
        assertTrue(outcome.cleared(), "running out of schools still ends at cleared");
        assertEquals(ALLOWED.size(), outcome.failures().size());
    }
}
