package com.otectus.magicnpcs.core.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link AuditCursor}: the spacing gap, the per-spell budget, completion, and the
 * progress text. The runner that uses it needs a live server, so this is the only part of
 * {@code /magicnpcs audit spells} that can be tested without one — and it is the part that decides
 * whether a 378-spell run finishes at all.
 */
class AuditCursorTest {

    private static AuditCursor cursor(int startedTick) {
        return new AuditCursor(List.of("irons_spellbooks:magic_missile", "irons_spellbooks:fireball",
                "irons_spellbooks:heal"), 40, 5, startedTick);
    }

    @Test
    void theFirstSpellStartsWithoutWaitingForTheSpacingGap() {
        AuditCursor cursor = cursor(100);
        assertTrue(cursor.shouldStep(100));
        assertEquals("irons_spellbooks:magic_missile", cursor.current());
    }

    @Test
    void theNextSpellWaitsForTheSpacingGap() {
        AuditCursor cursor = cursor(100);
        cursor.advance(100);
        assertFalse(cursor.shouldStep(104));
        assertTrue(cursor.shouldStep(105));
        assertEquals("irons_spellbooks:fireball", cursor.current());
    }

    @Test
    void theBudgetIsExceededOnlyAfterItHasFullyElapsed() {
        AuditCursor cursor = cursor(0);
        cursor.advance(10);
        assertFalse(cursor.budgetExceeded(50));
        assertTrue(cursor.budgetExceeded(51));
    }

    @Test
    void walkingTheWholeListCompletesIt() {
        AuditCursor cursor = cursor(0);
        for (int i = 0; i < 3; i++) {
            assertFalse(cursor.isDone());
            cursor.advance(i * 5);
        }
        assertTrue(cursor.isDone());
        assertNull(cursor.current());
    }

    @Test
    void advancingPastTheEndIsHarmless() {
        AuditCursor cursor = new AuditCursor(List.of("irons_spellbooks:heal"), 40, 5, 0);
        cursor.advance(0);
        cursor.advance(5);
        assertTrue(cursor.isDone());
        assertEquals("1/1", cursor.progress());
    }

    @Test
    void progressCountsSpellsAlreadyFinished() {
        AuditCursor cursor = cursor(0);
        assertEquals("0/3", cursor.progress());
        cursor.advance(0);
        assertEquals("1/3", cursor.progress());
        cursor.advance(5);
        cursor.advance(10);
        assertEquals("3/3", cursor.progress());
    }

    @Test
    void anEmptyListIsDoneImmediately() {
        AuditCursor cursor = new AuditCursor(List.of(), 40, 5, 0);
        assertTrue(cursor.isDone());
        assertNull(cursor.current());
        assertEquals("0/0", cursor.progress());
    }
}
