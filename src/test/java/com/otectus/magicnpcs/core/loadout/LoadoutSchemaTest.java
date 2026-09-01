package com.otectus.magicnpcs.core.loadout;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the known-key contract (audit "Unknown keys").
 *
 * <p>Through 0.6.1 an unrecognised key was simply not read, so {@code max_manna} left the loadout on
 * its 100-mana default and told nobody. The value of reporting it is entirely in the message, so these
 * tests assert the code, the JSON pointer, and the suggestion — not just that "something" was flagged.
 */
class LoadoutSchemaTest {

    private static List<LoadoutProblem> check(String json, java.util.Set<String> known, boolean strict) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        List<LoadoutProblem> problems = new ArrayList<>();
        LoadoutSchema.checkKeys(object, known, "", strict, problems);
        return problems;
    }

    @Test
    void aKnownKeyIsNotReported() {
        assertTrue(check("{\"entity_type\": \"minecraft:skeleton\"}", LoadoutSchema.ROOT_KEYS, false).isEmpty());
    }

    @Test
    void anUnknownKeyIsAWarningByDefaultAndNamesItsPointer() {
        List<LoadoutProblem> problems = check("{\"max_manna\": 50}", LoadoutSchema.ROOT_KEYS, false);
        assertEquals(1, problems.size());
        LoadoutProblem problem = problems.get(0);
        assertEquals(LoadoutProblem.Severity.WARNING, problem.severity());
        assertEquals("UNKNOWN_KEY", problem.code());
        assertEquals("/max_manna", problem.pointer());
    }

    @Test
    void strictModeMakesAnUnknownKeyFatal() {
        List<LoadoutProblem> problems = check("{\"max_manna\": 50}", LoadoutSchema.ROOT_KEYS, true);
        assertEquals(LoadoutProblem.Severity.ERROR, problems.get(0).severity());
    }

    @Test
    void aNearMissSuggestsTheKeyThatWasMeant() {
        // The three misspellings the audit names, plus one plain transposition.
        assertEquals("max_mana", LoadoutSchema.suggestFor("max_manna", LoadoutSchema.ROOT_KEYS));
        assertEquals("spell", LoadoutSchema.suggestFor("spell_id", LoadoutSchema.SPELL_KEYS));
        assertEquals("cast_chance", LoadoutSchema.suggestFor("castchange", LoadoutSchema.SPELL_KEYS));
        assertEquals("cooldown", LoadoutSchema.suggestFor("cooldwon", LoadoutSchema.SPELL_KEYS));
    }

    @Test
    void anUnrelatedKeyGetsNoSuggestionRatherThanAMisleadingOne() {
        assertNull(LoadoutSchema.suggestFor("completely_unrelated_thing", LoadoutSchema.ROOT_KEYS));
    }

    @Test
    void commentKeysAreAllowedEverywhere() {
        assertTrue(check("{\"_comment\": \"note\", \"__comment\": \"note\"}",
                LoadoutSchema.ROOT_KEYS, true).isEmpty());
    }

    @Test
    void theSuggestionListsValidKeysWhenNothingIsClose() {
        LoadoutProblem problem =
                check("{\"completely_unrelated_thing\": 1}", LoadoutSchema.ROOT_KEYS, false).get(0);
        assertTrue(problem.suggestion().contains("entity_type"),
                "with no near miss the message should list the valid keys: " + problem.suggestion());
    }
}
