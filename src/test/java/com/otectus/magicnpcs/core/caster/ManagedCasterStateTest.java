package com.otectus.magicnpcs.core.caster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision cadence of {@link ManagedCasterState}: acquiring a target must pull an idle-cadence
 * deadline back in (ADR 0005), and pulling forward must never push a deadline further out. Uses the
 * package-private {@code forTest()} factory, so no world is needed.
 */
class ManagedCasterStateTest {

    @Test
    void scheduleDecisionRecordsTheIdleCadence() {
        ManagedCasterState state = ManagedCasterState.forTest();
        state.scheduleDecision(100, true);
        assertEquals(100, state.nextDecisionTick());
        assertTrue(state.idleScheduled(), "a deadline set from the idle cadence must be marked as such");
    }

    @Test
    void acquiringATargetPullsAnIdleDeadlineForward() {
        ManagedCasterState state = ManagedCasterState.forTest();
        state.scheduleDecision(100, true);
        state.pullDecisionForward(20);
        assertEquals(20, state.nextDecisionTick(), "entering combat must not wait out the idle window");
        assertFalse(state.idleScheduled(), "the deadline is no longer an idle one");
    }

    @Test
    void pullDecisionForwardNeverDelaysADeadline() {
        ManagedCasterState state = ManagedCasterState.forTest();
        state.scheduleDecision(40, false);
        state.pullDecisionForward(90);
        assertEquals(40, state.nextDecisionTick(), "pulling forward must never push a deadline later");
    }

    @Test
    void anUnstampedHeartbeatReadsAsNever() {
        ManagedCasterState state = ManagedCasterState.forTest();
        assertEquals(Integer.MAX_VALUE, state.goalHeartbeatAge(100),
                "a caster whose goal has never been evaluated must not read as fresh");
    }

    @Test
    void heartbeatAgeIsTheTicksSinceTheLastStamp() {
        ManagedCasterState state = ManagedCasterState.forTest();
        state.heartbeat(100);
        assertEquals(7, state.goalHeartbeatAge(107));
    }

    @Test
    void aHeartbeatStampedThisTickIsZeroTicksOld() {
        ManagedCasterState state = ManagedCasterState.forTest();
        state.heartbeat(100);
        assertEquals(0, state.goalHeartbeatAge(100));
    }
}
