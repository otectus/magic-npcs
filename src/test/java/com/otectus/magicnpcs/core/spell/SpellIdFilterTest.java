package com.otectus.magicnpcs.core.spell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whitelist/blacklist matcher. The wildcard is the whole point: without it, allowing a
 * seventy-seven-spell add-on pack meant seventy-seven lines, and a whitelist plus a trusted namespace
 * enabled nothing at all.
 */
class SpellIdFilterTest {

    @Test
    void anExactIdMatches() {
        assertTrue(SpellIdFilter.matches(List.of("irons_spellbooks:fireball"), "irons_spellbooks:fireball"));
    }

    @Test
    void aDifferentIdDoesNotMatch() {
        assertFalse(SpellIdFilter.matches(List.of("irons_spellbooks:fireball"), "irons_spellbooks:firebolt"));
    }

    @Test
    void aNamespaceWildcardMatchesEveryPathInIt() {
        assertTrue(SpellIdFilter.matches(List.of("traveloptics:*"), "traveloptics:tidal_lance"));
        assertTrue(SpellIdFilter.matches(List.of("traveloptics:*"), "traveloptics:sea_wall"));
    }

    @Test
    void aNamespaceWildcardDoesNotLeakIntoOtherNamespaces() {
        assertFalse(SpellIdFilter.matches(List.of("traveloptics:*"), "irons_spellbooks:fireball"));
    }

    @Test
    void anEmptyOrNullPatternListMatchesNothing() {
        assertFalse(SpellIdFilter.matches(List.of(), "irons_spellbooks:fireball"));
        assertFalse(SpellIdFilter.matches(null, "irons_spellbooks:fireball"));
    }
}
