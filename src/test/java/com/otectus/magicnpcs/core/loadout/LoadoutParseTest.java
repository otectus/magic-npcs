package com.otectus.magicnpcs.core.loadout;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    /** Everything resolves, except that "recruits" is not installed — the shipped-loadout case (I1). */
    private static final RegistryChecks RECRUITS_ABSENT =
            RegistryChecks.of(id -> true, id -> true, id -> true, ns -> !ns.equals("recruits"));

    private static SpellcasterLoadout parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return LoadoutManager.parse(object, SOURCE);
    }

    private static LoadoutRecord parse(String json, RegistryChecks checks) {
        return LoadoutParser.parse(SOURCE, JsonParser.parseString(json), "mypack",
                LoadoutSourceTier.DATAPACK, false, null, null, checks);
    }

    private static List<LoadoutProblem> withCode(LoadoutRecord record, String code) {
        List<LoadoutProblem> out = new ArrayList<>();
        for (LoadoutProblem p : record.problems()) {
            if (p.code().equals(code)) {
                out.add(p);
            }
        }
        return out;
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

    @Test
    void theCastTimeOverridesParseAndDefaultToNull() {
        SpellcasterLoadout loadout = parse("""
                { "entity_type": "minecraft:skeleton", "spells": [
                    { "spell": "irons_spellbooks:gravity_fissure", "cast_time": 6 },
                    { "spell": "irons_spellbooks:blood_slash", "cast_time_multiplier": 0.5 },
                    { "spell": "irons_spellbooks:magic_missile" } ] }""");
        assertEquals(6, loadout.spells().get(0).castTimeTicks());
        assertNull(loadout.spells().get(0).castTimeMultiplier());
        assertEquals(0.5, loadout.spells().get(1).castTimeMultiplier());
        assertNull(loadout.spells().get(1).castTimeTicks());
        assertNull(loadout.spells().get(2).castTimeTicks());
        assertNull(loadout.spells().get(2).castTimeMultiplier());
    }

    @Test
    void bothCastTimeFieldsMayBeSetTogether() {
        // Precedence is resolved at cast time, so the parser keeps both values.
        SpellcasterLoadout loadout = parse("""
                { "entity_type": "minecraft:skeleton", "spells": [
                    { "spell": "irons_spellbooks:gravity_fissure",
                      "cast_time": 6, "cast_time_multiplier": 0.5 } ] }""");
        assertEquals(1, loadout.spells().size());
        assertEquals(6, loadout.spells().get(0).castTimeTicks());
        assertEquals(0.5, loadout.spells().get(0).castTimeMultiplier());
    }

    @Test
    void anInvalidCastTimeIsRejectedRatherThanClamped() {
        // Unlike cooldown/windup, a bad cast time is an error: a silent "-1 means 0" would read as a
        // working instant cast. As with every other entry error, the file is rejected.
        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class, () -> parse("""
                { "entity_type": "minecraft:skeleton", "spells": [
                    { "spell": "irons_spellbooks:gravity_fissure", "cast_time": -1 },
                    { "spell": "irons_spellbooks:magic_missile" } ] }"""));
        assertTrue(negative.getMessage().contains("cast_time"));

        IllegalArgumentException negativeMult = assertThrows(IllegalArgumentException.class, () -> parse("""
                { "entity_type": "minecraft:skeleton", "spells": [
                    { "spell": "irons_spellbooks:gravity_fissure", "cast_time_multiplier": -0.5 },
                    { "spell": "irons_spellbooks:magic_missile" } ] }"""));
        assertTrue(negativeMult.getMessage().contains("cast_time_multiplier"));

        assertThrows(IllegalArgumentException.class, () -> parse("""
                { "entity_type": "minecraft:skeleton", "spells": [
                    { "spell": "irons_spellbooks:gravity_fissure", "cast_time_multiplier": "fast" },
                    { "spell": "irons_spellbooks:magic_missile" } ] }"""));
    }

    @Test
    void aSupportConditionWithNoHealthTermHasNoSelfHealthGate() {
        // ADR 0005 anti-loop floor: only a condition that constrains the caster's own condition may
        // stand in for the "when hurt" gate out of combat.
        SpellcasterLoadout loadout = parse("""
                { "entity_type": "minecraft:skeleton", "spells": [ {
                    "spell": "irons_spellbooks:heal", "role": "support",
                    "condition": { "enemies_within": 3, "enemies_radius": 8.0 } } ] }""");
        assertFalse(loadout.spells().get(0).condition().hasSelfHealthGate(),
                "enemies_within says nothing about the caster's health");
    }

    @Test
    void aSelfHpConditionHasASelfHealthGate() {
        SpellcasterLoadout loadout = parse("""
                { "entity_type": "minecraft:skeleton", "spells": [ {
                    "spell": "irons_spellbooks:heal", "role": "support",
                    "condition": { "self_hp_below": 0.5 } } ] }""");
        assertTrue(loadout.spells().get(0).condition().hasSelfHealthGate());
    }

    @Test
    void recentlyHurtCountsAsASelfHealthGate() {
        SpellcasterLoadout loadout = parse("""
                { "entity_type": "minecraft:skeleton", "spells": [ {
                    "spell": "irons_spellbooks:heal", "role": "support",
                    "condition": { "when_recently_hurt": true } } ] }""");
        assertTrue(loadout.spells().get(0).condition().hasSelfHealthGate());
    }

    // --- absent mods (0.9.0, I1) ----------------------------------------------------------------
    //
    // Every shipped loadout targets an optional mod, so "that mod is not installed" is the normal
    // case, not an author error. Through 0.8.0 it was indistinguishable from a typo: the parser only
    // asked the registry, so all six shipped files were REJECTED, every reload logged six ERROR
    // blocks, and /magicnpcs validate said FAILED on a correctly installed pack.

    @Test
    void anEntityTypeFromAnAbsentModSkipsTheFileInsteadOfRejectingIt() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "recruits:recruit",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""", RECRUITS_ABSENT);
        assertEquals(LoadoutRecord.Status.INAPPLICABLE, record.status());
        assertFalse(record.hasErrors());
        assertNull(record.loadout());
        assertEquals(1, withCode(record, "MOD_ABSENT").size());
        assertEquals(LoadoutProblem.Severity.INFO, withCode(record, "MOD_ABSENT").get(0).severity());
        assertEquals("recruits", record.absentNamespace().orElse(null));
    }

    @Test
    void aProfessionFromAnAbsentModSkipsTheFile() {
        // Dropping the key instead would silently widen "this profession only" to "every villager".
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:villager",
                  "profession": "recruits:mercenary",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""", RECRUITS_ABSENT);
        assertEquals(LoadoutRecord.Status.INAPPLICABLE, record.status());
        assertFalse(record.hasErrors());
        assertEquals("recruits", record.absentNamespace().orElse(null));
    }

    @Test
    void aSpellEntryFromAnAbsentModIsDroppedWhileTheRestSurvive() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "recruits:war_cry" },
                              { "spell": "irons_spellbooks:magic_missile" } ]
                }""", RECRUITS_ABSENT);
        assertEquals(LoadoutRecord.Status.ACTIVE, record.status());
        assertFalse(record.hasErrors());
        assertEquals(1, record.loadout().spells().size());
        assertEquals("irons_spellbooks:magic_missile",
                record.loadout().spells().get(0).spell().toString());
        assertEquals(1, withCode(record, "SPELL_MOD_ABSENT").size());
    }

    @Test
    void aLoadoutWhoseEverySpellComesFromAnAbsentModIsSkippedNotBlamed() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "recruits:war_cry" }, { "spell": "recruits:rally" } ]
                }""", RECRUITS_ABSENT);
        assertEquals(LoadoutRecord.Status.INAPPLICABLE, record.status());
        assertFalse(record.hasErrors());
        assertTrue(withCode(record, "NO_CASTABLE_SPELLS").isEmpty());
        assertEquals(2, withCode(record, "SPELL_MOD_ABSENT").size());
    }

    @Test
    void anItemFromAnAbsentModIsDroppedAndTheLoadoutStillLoads() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "equipment": { "mainhand": [ "recruits:war_horn", "minecraft:stick" ] },
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""", RECRUITS_ABSENT);
        assertEquals(LoadoutRecord.Status.ACTIVE, record.status());
        assertFalse(record.hasErrors());
        assertEquals(1, record.loadout().equipment().mainhand().size());
        assertEquals(1, withCode(record, "MOD_ABSENT").size());
    }

    @Test
    void anUnknownIdFromAnInstalledModIsStillAnError() {
        // The distinction the whole change rests on: absent mod = skipped, typo = rejected.
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skelton",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""", RegistryChecks.of(id -> false, id -> true, id -> true, ns -> true));
        assertEquals(LoadoutRecord.Status.REJECTED, record.status());
        assertEquals(1, withCode(record, "UNKNOWN_ENTITY_TYPE").size());
    }
}
