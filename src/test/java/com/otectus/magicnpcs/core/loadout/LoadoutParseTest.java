package com.otectus.magicnpcs.core.loadout;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LoadoutManager#parse}: the 0.6.0 datapack fields and the clamps that keep a
 * malformed or over-optimistic pack from becoming a silent trap.
 *
 * <p>Every new field is optional with a documented default, so the first test here is the
 * backward-compatibility one: a 0.5.0 loadout must parse to exactly the 0.5.0 behaviour.
 */
@SuppressWarnings("deprecation")
class LoadoutParseTest {

    private static final ResourceLocation SOURCE = new ResourceLocation("mypack", "guard");

    private static SpellcasterLoadout parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return LoadoutManager.parse(object, SOURCE);
    }

    @Test
    void a050LoadoutParsesWithEvery060FieldAtItsDefault() {
        SpellcasterLoadout loadout = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "max_mana": 100, "mana_regen": 10,
                  "spells": [ { "spell": "irons_spellbooks:magic_missile", "role": "attack" } ]
                }""");
        assertTrue(loadout.enabled());
        assertNull(loadout.goalPriority());
        assertEquals(NativeAttackPolicy.COEXIST, loadout.nativeAttack());
        assertEquals(LoadoutSourceTier.DATAPACK, loadout.tier());
        assertFalse(loadout.replace());
        assertEquals(1, loadout.spells().size());
    }

    @Test
    void aDisabledLoadoutMayOmitSpellsEntirely() {
        // The whole point of the off switch is "stop this type casting"; demanding a dummy spell list
        // for that would be the trap 0.5.0 had (LoadoutManager threw "loadout has no spells").
        SpellcasterLoadout loadout = parse("""
                { "entity_type": "minecraft:skeleton", "enabled": false, "replace": true }""");
        assertFalse(loadout.enabled());
        assertTrue(loadout.replace());
        assertTrue(loadout.spells().isEmpty());
    }

    @Test
    void anEnabledLoadoutWithNoSpellsIsStillAnError() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse("""
                { "entity_type": "minecraft:skeleton", "spells": [] }"""));
        assertTrue(ex.getMessage().contains("enabled"),
                "the error should point the author at the off switch: " + ex.getMessage());
    }

    @Test
    void anEnabledLoadoutMissingTheSpellsKeyGetsTheSameHint() {
        // Only the "spells": [] spelling reached the friendly message; omitting the key entirely threw
        // Gson's "Missing spells, expected to find a JsonArray" first, which says nothing about the
        // off switch the author probably wanted.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse("""
                { "entity_type": "minecraft:skeleton" }"""));
        assertTrue(ex.getMessage().contains("enabled"),
                "the error should point the author at the off switch: " + ex.getMessage());
    }

    @Test
    void nativeAttackPolicyParsesAndRejectsNonsense() {
        assertEquals(NativeAttackPolicy.SUPPRESS, parse("""
                { "entity_type": "minecraft:witch", "native_attack": "suppress",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ] }""").nativeAttack());
        assertEquals(NativeAttackPolicy.YIELD, parse("""
                { "entity_type": "minecraft:witch", "native_attack": "YIELD",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ] }""").nativeAttack());
        assertThrows(IllegalArgumentException.class, () -> parse("""
                { "entity_type": "minecraft:witch", "native_attack": "ignore",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ] }"""));
    }

    @Test
    void goalPriorityIsClampedToTheGoalSelectorRange() {
        assertEquals(0, parse("""
                { "entity_type": "minecraft:witch", "goal_priority": -5,
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ] }""").goalPriority());
        assertEquals(99, parse("""
                { "entity_type": "minecraft:witch", "goal_priority": 5000,
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ] }""").goalPriority());
    }

    @Test
    void recentDamageWindowIsClampedToTheVanillaLimit() {
        // Vanilla clears lastHurtByMob after 100 ticks, so a larger window silently behaved as 100
        // (backlog B17). Clamp it at parse time instead of leaving it as a trap.
        SpellcasterLoadout loadout = parse("""
                { "entity_type": "minecraft:skeleton", "spells": [ {
                    "spell": "irons_spellbooks:magic_missile",
                    "condition": { "when_recently_hurt": true, "recent_damage_window": 600 } } ] }""");
        assertEquals(LoadoutManager.MAX_RECENT_DAMAGE_WINDOW,
                loadout.spells().get(0).condition().recentDamageWindow());
    }

    @Test
    void roleMustBeAttackOrSupport() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> parse("""
                { "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:heal", "role": "healer" } ] }"""));
        assertTrue(ex.getMessage().contains("role"));
    }
}
