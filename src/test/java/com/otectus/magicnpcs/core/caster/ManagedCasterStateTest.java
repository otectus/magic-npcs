package com.otectus.magicnpcs.core.caster;

import net.minecraft.resources.ResourceLocation;
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

    // --- cooldown deadlines: long-valued, canonical, merged on the longest (MN-005, MN-017) -------

    private static final ResourceLocation CANONICAL =
            new ResourceLocation("irons_spellbooks", "magic_missile");
    private static final ResourceLocation ALIAS = new ResourceLocation("minecraft", "magic_missile");

    /**
     * REG-23: a deadline near the top of the long range counts down rather than wrapping.
     *
     * <p>{@code CooldownResolver} can now answer {@link Integer#MAX_VALUE}; adding that to a tick
     * count in an int put the deadline in the past, which is the same defect one step further on. The
     * deadline is a long precisely so saturating the resolver is not merely relocating the overflow.
     */
    @Test
    void aSaturatedCooldownStaysInTheFuture() {
        ManagedCasterState state = ManagedCasterState.forTest();
        long now = 1_000L;
        state.startCooldownAt(CANONICAL, now + Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, state.cooldownRemaining(CANONICAL, now));
        assertTrue(state.cooldownRemaining(CANONICAL, now + 1_000_000L) > 0,
                "an effectively-never cooldown must still be resting a million ticks later");
    }

    /** A deadline that has passed reads as ready, and never as a negative remainder. */
    @Test
    void anExpiredCooldownReadsAsReady() {
        ManagedCasterState state = ManagedCasterState.forTest();
        state.startCooldownAt(CANONICAL, 500L);
        assertEquals(100L, state.cooldownRemaining(CANONICAL, 400L));
        assertEquals(0L, state.cooldownRemaining(CANONICAL, 500L));
        assertEquals(0L, state.cooldownRemaining(CANONICAL, 900L));
    }

    /** An unknown spell is ready, not resting: a missing entry must never read as a live cooldown. */
    @Test
    void anUnknownSpellIsReady() {
        assertEquals(0L, ManagedCasterState.forTest().cooldownRemaining(CANONICAL, 0L));
    }

    /** A deadline is only ever pushed out. A second, shorter cooldown cannot cut a longer one short. */
    @Test
    void aShorterDeadlineNeverShortensALongerOne() {
        ManagedCasterState state = ManagedCasterState.forTest();
        state.startCooldownAt(CANONICAL, 1_000L);
        state.startCooldownAt(CANONICAL, 200L);
        assertEquals(1_000L, state.cooldownRemaining(CANONICAL, 0L));
    }

    /**
     * REG-01: an alias and its canonical form are one spell, and normalising merges them onto the
     * <b>longest</b> remaining deadline.
     *
     * <p>Taking the shorter would hand an author a way to cut a cooldown in half by spelling the id a
     * second way, which is the selection-weight half of MN-005 turned into an economy exploit.
     */
    @Test
    void normalisingMergesEquivalentKeysOntoTheLongestDeadline() {
        ManagedCasterState state = ManagedCasterState.forTest();
        state.startCooldownAt(ALIAS, 900L);
        state.startCooldownAt(CANONICAL, 300L);

        state.normalizeCooldownKeys(id -> ALIAS.equals(id) ? CANONICAL : id);

        assertEquals(900L, state.cooldownRemaining(CANONICAL, 0L),
                "the merged key must keep the longer of the two rests");
        assertEquals(0L, state.cooldownRemaining(ALIAS, 0L),
                "the alias key must be gone once it has been merged");
    }

    /** Normalising is safe to run twice and leaves an already-canonical map alone. */
    @Test
    void normalisingIsIdempotent() {
        ManagedCasterState state = ManagedCasterState.forTest();
        state.startCooldownAt(CANONICAL, 400L);
        state.normalizeCooldownKeys(id -> id);
        state.normalizeCooldownKeys(id -> id);
        assertEquals(400L, state.cooldownRemaining(CANONICAL, 0L));
    }

    /**
     * A loadout change keeps the cooldowns of spells it still contains and drops the rest — and the
     * retained set is compared canonically, so an alias entry does not look like a removed spell.
     */
    @Test
    void retainingKeepsStillPresentSpellsAndDropsTheRest() {
        ManagedCasterState state = ManagedCasterState.forTest();
        ResourceLocation other = new ResourceLocation("irons_spellbooks", "fireball");
        state.startCooldownAt(CANONICAL, 800L);
        state.startCooldownAt(other, 800L);

        state.retainCooldownsFor(java.util.Set.of(CANONICAL));

        assertEquals(800L, state.cooldownRemaining(CANONICAL, 0L));
        assertEquals(0L, state.cooldownRemaining(other, 0L));
    }

    /** Clearing one spell's cooldown leaves the others alone. */
    @Test
    void clearingOneCooldownLeavesTheOthersAlone() {
        ManagedCasterState state = ManagedCasterState.forTest();
        ResourceLocation other = new ResourceLocation("irons_spellbooks", "fireball");
        state.startCooldownAt(CANONICAL, 800L);
        state.startCooldownAt(other, 800L);

        state.clearCooldown(CANONICAL);

        assertEquals(0L, state.cooldownRemaining(CANONICAL, 0L));
        assertEquals(800L, state.cooldownRemaining(other, 0L));
    }
}
