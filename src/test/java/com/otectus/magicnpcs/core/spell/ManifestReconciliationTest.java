package com.otectus.magicnpcs.core.spell;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The manifest-versus-registry diff: the check that catches a checked-in table drifting away from the
 * Iron's version actually installed.
 */
class ManifestReconciliationTest {

    @Test
    void manifestRowWithNoRegisteredSpellIsUnregistered() {
        ManifestReconciliation.Result result = ManifestReconciliation.diff(
                Set.of("root", "thunder_step"), Set.of("root"));
        assertEquals(List.of("thunder_step"), result.unregistered());
        assertEquals(List.of(), result.unlisted());
        assertEquals(2, result.rows());
    }

    @Test
    void registeredSpellWithNoManifestRowIsUnlisted() {
        ManifestReconciliation.Result result = ManifestReconciliation.diff(
                Set.of("root"), Set.of("root", "tidal_lance", "acupuncture"));
        assertEquals(List.of(), result.unregistered());
        assertEquals(List.of("acupuncture", "tidal_lance"), result.unlisted());
    }

    /** {@code none} is Iron's placeholder, not a spell: it is never counted or reported. */
    @Test
    void placeholderIsExcludedFromBothSides() {
        ManifestReconciliation.Result result = ManifestReconciliation.diff(
                Set.of("none", "root"), Set.of("none", "root"));
        assertEquals(1, result.rows());
        assertEquals(List.of(), result.unregistered());
        assertEquals(List.of(), result.unlisted());
    }

    @Test
    void emptyInputsDiffToNothing() {
        ManifestReconciliation.Result result = ManifestReconciliation.diff(Set.of(), Set.of());
        assertEquals(0, result.rows());
        assertEquals(List.of(), result.unregistered());
        assertEquals(List.of(), result.unlisted());
        assertEquals("manifest: 0 rows, 0 unregistered, 0 unlisted", result.summary());
    }

    @Test
    void summaryNamesBothDirections() {
        ManifestReconciliation.Result result = ManifestReconciliation.diff(
                Set.of("root", "thunder_step"), Set.of("root", "tidal_lance"));
        assertEquals("manifest: 2 rows, 1 unregistered, 1 unlisted", result.summary());
    }
}
