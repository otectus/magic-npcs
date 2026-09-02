package com.otectus.magicnpcs.core.caster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The goal-change notification exists so an integration holding a reference to our casting goal —
 * Easy NPC caches the {@code Goal} its objective factory returned — is told when that object is
 * replaced, instead of resurrecting a dead one later.
 *
 * <p>The property that matters most is isolation: {@code fireGoalChanged} runs inside
 * {@code CasterReconciler}, which a datapack reload calls for every loaded mob in the world. One
 * misbehaving listener must not abort that pass and leave the world half-reconciled.
 */
class CasterGoalListenersTest {

    @BeforeEach
    @AfterEach
    void reset() {
        CasterGoalListeners.clearForTest();
    }

    @Test
    void firesEveryRegisteredListener() {
        List<String> called = new ArrayList<>();
        CasterGoalListeners.register(mob -> called.add("first"));
        CasterGoalListeners.register(mob -> called.add("second"));

        CasterGoalListeners.fireGoalChanged(null);

        assertEquals(List.of("first", "second"), called);
    }

    @Test
    void aThrowingListenerDoesNotStopTheOthers() {
        List<String> called = new ArrayList<>();
        CasterGoalListeners.register(mob -> {
            throw new IllegalStateException("integration blew up");
        });
        CasterGoalListeners.register(mob -> called.add("still ran"));

        assertDoesNotThrow(() -> CasterGoalListeners.fireGoalChanged(null));
        assertEquals(List.of("still ran"), called,
                "a reload reconciles every mob in the world; one bad listener must not take it down");
    }

    @Test
    void firingWithNoListenersIsHarmless() {
        assertDoesNotThrow(() -> CasterGoalListeners.fireGoalChanged(null));
    }

    @Test
    void clearForTestRemovesEverything() {
        CasterGoalListeners.register(mob -> {
            throw new AssertionError("cleared listener was still called");
        });
        CasterGoalListeners.clearForTest();

        assertDoesNotThrow(() -> CasterGoalListeners.fireGoalChanged(null));
        assertTrue(true);
    }
}
