package com.otectus.magicnpcs.core.spell;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Did you mean …?" for an id that did not resolve. Resolution itself never changes — a bare id is
 * still only ever {@code irons_spellbooks:} — so a foreign namespace can only be suggested.
 */
class SpellIdSuggestionsTest {

    private static final Map<String, List<String>> INDEX = Map.of(
            "fireball", List.of("traveloptics", "irons_spellbooks"),
            "tidal_lance", List.of("traveloptics"));

    @Test
    void aBareIdSuggestsIronsFirst() {
        Optional<String> suggestion = SpellIdSuggestions.suggest(INDEX, "fireball");
        assertEquals(Optional.of("did you mean irons_spellbooks:fireball? "
                + "(also registered as traveloptics:fireball)"), suggestion);
    }

    @Test
    void aMinecraftNamespacedIdIsTreatedAsBare() {
        assertTrue(SpellIdSuggestions.suggest(INDEX, "minecraft:tidal_lance").isPresent());
    }

    @Test
    void aWrongNamespaceSuggestsTheOnesThatDoRegisterThePath() {
        assertEquals(Optional.of("did you mean traveloptics:tidal_lance?"),
                SpellIdSuggestions.suggest(INDEX, "travelopticz:tidal_lance"));
    }

    @Test
    void anIdThatIsAlreadyTheOnlyRegistrationSuggestsNothing() {
        assertTrue(SpellIdSuggestions.suggest(INDEX, "traveloptics:tidal_lance").isEmpty());
    }

    @Test
    void anUnknownPathSuggestsNothing() {
        assertTrue(SpellIdSuggestions.suggest(INDEX, "irons_spellbooks:not_a_spell").isEmpty());
    }
}
