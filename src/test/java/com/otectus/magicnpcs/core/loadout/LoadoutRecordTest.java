package com.otectus.magicnpcs.core.loadout;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the outcome record every discovered loadout file now keeps (audit VAL-001).
 *
 * <p>The property under test throughout is that a <em>bad</em> file still produces a record. Through
 * 0.6.1 it produced a log line and nothing else, which is why {@code /magicnpcs validate} could report
 * "no issues" over a rejected skeleton loadout: it was reading a map the file had never entered.
 *
 * <p>Registry checks are off here ({@code checkRegistries = false}) because a plain JUnit run has no
 * bootstrapped Minecraft; every other rule is the production one.
 */
class LoadoutRecordTest {

    private static final ResourceLocation ID = new ResourceLocation("mypack", "skeleton");

    private static LoadoutRecord parse(String json) {
        JsonElement element = JsonParser.parseString(json);
        return LoadoutParser.parse(ID, element, "mypack", LoadoutSourceTier.DATAPACK,
                false, null, null, false);
    }

    private static LoadoutProblem problem(LoadoutRecord record, String code) {
        for (LoadoutProblem p : record.problems()) {
            if (p.code().equals(code)) {
                return p;
            }
        }
        return null;
    }

    @Test
    void aValidLoadoutIsActiveAndCarriesItsProvenance() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        assertEquals(LoadoutRecord.Status.ACTIVE, record.status());
        assertEquals("mypack", record.packId());
        assertNotNull(record.loadout());
        assertFalse(record.hasErrors());
        assertFalse(record.contentHash().isEmpty());
    }

    @Test
    void aBrokenLoadoutIsRejectedButStillProducesARecord() {
        LoadoutRecord record = parse("""
                { "entity_type": "minecraft:skeleton", "spells": [ { "level": 1 } ] }""");
        assertEquals(LoadoutRecord.Status.REJECTED, record.status());
        assertNull(record.loadout(), "a rejected record must not hand a broken loadout to the runtime");
        assertEquals(ID, record.resourceId(), "…but it must still name the file so validation can report it");
        assertNotNull(problem(record, "MISSING_SPELL"));
    }

    @Test
    void aDisabledLoadoutIsSuppressedRatherThanRejected() {
        LoadoutRecord record = parse("""
                { "entity_type": "minecraft:skeleton", "enabled": false }""");
        assertEquals(LoadoutRecord.Status.SUPPRESSED, record.status());
        assertFalse(record.hasErrors(), "omitting spells is legal precisely because it is disabled");
    }

    @Test
    void aBareDisabledStubInheritsTheKeyOfTheLoadoutItShadows() {
        // The documented way to switch off a shipped loadout: drop { "enabled": false } at the same
        // data path. 0.6.1 read the mandatory entity_type first and rejected exactly that file.
        LoadoutRecord record = LoadoutParser.parse(ID, JsonParser.parseString("{ \"enabled\": false }"),
                "mypack", LoadoutSourceTier.DATAPACK, false,
                new ResourceLocation("minecraft", "skeleton"), null, false);
        assertEquals(LoadoutRecord.Status.SUPPRESSED, record.status());
        assertEquals(new ResourceLocation("minecraft", "skeleton"), record.entityType());
        assertNotNull(problem(record, "INFERRED_ENTITY_TYPE"),
                "the inference must be recorded, not silent");
    }

    @Test
    void aBareDisabledStubWithNothingToShadowIsAnActionableError() {
        LoadoutRecord record = parse("{ \"enabled\": false }");
        assertEquals(LoadoutRecord.Status.REJECTED, record.status());
        LoadoutProblem missing = problem(record, "MISSING_ENTITY_TYPE");
        assertNotNull(missing);
        assertTrue(missing.suggestion().contains("shadows"),
                "the message should explain when a bare stub does work: " + missing.suggestion());
    }

    @Test
    void anInvertedRangeIsAnErrorBecauseTheSpellCanNeverBeSelected() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile",
                                "min_range": 20, "max_range": 5 } ]
                }""");
        assertEquals(LoadoutRecord.Status.REJECTED, record.status());
        assertEquals("/spells/0", problem(record, "RANGE_INVERTED").pointer());
    }

    @Test
    void aRestrictionListThatEmptiesItselfIsAnErrorRatherThanASilentWidening() {
        // Every value is unusable. 0.6.1 dropped them and returned null, and a null restriction list
        // means "allow anywhere" — so a typo widened the condition instead of narrowing it.
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "conditions": { "difficulties": ["nightmare", "brutal"] },
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        assertEquals(LoadoutRecord.Status.REJECTED, record.status());
        assertNotNull(problem(record, "RESTRICTION_EMPTIED"));
        assertNotNull(problem(record, "BAD_DIFFICULTY"),
                "each dropped value should be named, not just the outcome");
    }

    @Test
    void aPartlyValidRestrictionKeepsWhatResolvedAndReportsWhatDidNot() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "conditions": { "difficulties": ["hard", "nightmare"] },
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        assertEquals(LoadoutRecord.Status.REJECTED, record.status(),
                "an unusable value is still an error — the author asked for something impossible");
        assertNotNull(problem(record, "BAD_DIFFICULTY"));
        assertNull(problem(record, "RESTRICTION_EMPTIED"), "'hard' survived, so the list is not empty");
    }

    @Test
    void moonPhasesOutsideZeroToSevenAreRejected() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "conditions": { "moon_phases": [0, 9] },
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        assertNotNull(problem(record, "BAD_MOON_PHASE"));
    }

    @Test
    void anInvertedYWindowIsAnError() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "conditions": { "min_y": 60, "max_y": 10 },
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        assertNotNull(problem(record, "Y_RANGE_INVERTED"));
    }

    @Test
    void aFractionAboveOneIsClampedWithAPercentageHint() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "caster_chance": 50,
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        LoadoutProblem clamped = problem(record, "FRACTION_CLAMPED");
        assertNotNull(clamped);
        assertTrue(clamped.suggestion().contains("percentage"),
                "50 almost certainly meant 0.5: " + clamped.suggestion());
        assertEquals(1.0, record.loadout().casterChance());
    }

    @Test
    void requiredItemsWithoutTheFlagIsReportedAsInert() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile",
                                "required_items": ["#magicnpcs:spell_focuses"] } ]
                }""");
        assertEquals(LoadoutRecord.Status.ACTIVE, record.status());
        assertNotNull(problem(record, "REQUIRED_ITEMS_INERT"));
    }

    @Test
    void theHeldItemRequirementRoundTripsThroughTheRecord() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile",
                                "require_held_item": true,
                                "required_items": ["#magicnpcs:spell_focuses"],
                                "required_hand": "main" } ]
                }""");
        assertEquals(LoadoutRecord.Status.ACTIVE, record.status());
        LoadoutEntry entry = record.loadout().spells().get(0);
        assertTrue(entry.requireHeldItem());
        assertEquals(LoadoutEntry.HandRequirement.MAIN, entry.requiredHand());
        assertEquals(1, entry.requiredItems().size());
    }

    @Test
    void theContentHashChangesWhenBehaviourChangesAndNotWhenItDoesNot() {
        String base = """
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile", "max_range": 16 } ]
                }""";
        String reordered = """
                {
                  "spells": [ { "max_range": 16, "spell": "irons_spellbooks:magic_missile" } ],
                  "entity_type": "minecraft:skeleton"
                }""";
        String changed = base.replace("16", "24");
        assertEquals(parse(base).loadout().contentHash(), parse(reordered).loadout().contentHash(),
                "key order is not behaviour, so a reformatted file must not read as an edit");
        assertFalse(parse(base).loadout().contentHash().equals(parse(changed).loadout().contentHash()),
                "a changed range must change the hash, or reconciliation would skip the update");
    }

    @Test
    void npcTraitsParseIntoTheThreeConditionSets() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "conditions": {
                    "npc_traits": {
                      "all_of": ["customnpcs:role/none"],
                      "any_of": ["customnpcs:job/guard", "customnpcs:job/bard"],
                      "none_of": ["customnpcs:job/farmer"]
                    }
                  },
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        assertEquals(LoadoutRecord.Status.ACTIVE, record.status());
        LoadoutConditions conditions = record.loadout().conditions();
        assertEquals(java.util.Set.of(new ResourceLocation("customnpcs", "role/none")),
                conditions.traitsAllOf());
        assertEquals(2, conditions.traitsAnyOf().size());
        assertEquals(java.util.Set.of(new ResourceLocation("customnpcs", "job/farmer")),
                conditions.traitsNoneOf());
    }

    @Test
    void aMalformedTraitIsRejectedAndNamesItsOwnIndex() {
        // The value of the report is the pointer: in a ten-entry list "one of these is wrong" is not
        // actionable, so the element index is part of the contract.
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "conditions": {
                    "npc_traits": { "any_of": ["customnpcs:job/guard", "Not An Id"] }
                  },
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        assertEquals(LoadoutRecord.Status.REJECTED, record.status());
        LoadoutProblem bad = problem(record, "BAD_NPC_TRAIT");
        assertNotNull(bad);
        assertEquals("/conditions/npc_traits/any_of/1", bad.pointer());
    }

    @Test
    void omittedNpcTraitsAreNoConstraintAtAll() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "conditions": { "min_y": 0 },
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        assertEquals(LoadoutRecord.Status.ACTIVE, record.status());
        LoadoutConditions conditions = record.loadout().conditions();
        assertTrue(conditions.traitsAllOf().isEmpty());
        assertTrue(conditions.traitsAnyOf().isEmpty());
        assertTrue(conditions.traitsNoneOf().isEmpty());
    }

    @Test
    void aNegativeCastTimeIsReportedAndRejectsTheFile() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:gravity_fissure", "cast_time": -1 },
                              { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        LoadoutProblem bad = problem(record, "CAST_TIME_NEGATIVE");
        assertNotNull(bad);
        assertEquals("/spells/0/cast_time", bad.pointer());
        assertEquals(LoadoutRecord.Status.REJECTED, record.status());
    }

    @Test
    void anInvalidCastTimeMultiplierIsReported() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:gravity_fissure",
                                "cast_time_multiplier": -0.5 },
                              { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        LoadoutProblem bad = problem(record, "CAST_TIME_MULTIPLIER_INVALID");
        assertNotNull(bad);
        assertEquals("/spells/0/cast_time_multiplier", bad.pointer());
    }

    @Test
    void settingBothCastTimeFieldsSaysWhichOneWins() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:gravity_fissure",
                                "cast_time": 6, "cast_time_multiplier": 0.5 } ]
                }""");
        assertEquals(LoadoutRecord.Status.ACTIVE, record.status());
        LoadoutProblem info = problem(record, "CAST_TIME_ABSOLUTE_WINS");
        assertNotNull(info);
        assertEquals(LoadoutProblem.Severity.INFO, info.severity());
    }

    @Test
    void theCastTimeOverridesRoundTripThroughTheRecord() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:gravity_fissure",
                                "cast_time": 6, "cast_time_multiplier": 0.5 } ]
                }""");
        LoadoutEntry entry = record.loadout().spells().get(0);
        com.google.gson.JsonObject written = LoadoutJson.toJson(entry);
        assertTrue(written.has(LoadoutJson.CAST_TIME));
        assertTrue(written.has(LoadoutJson.CAST_TIME_MULTIPLIER));

        LoadoutRecord reparsed = parse("""
                { "entity_type": "minecraft:skeleton", "spells": [ %s ] }"""
                .formatted(written.toString()));
        LoadoutEntry back = reparsed.loadout().spells().get(0);
        assertEquals(entry.castTimeTicks(), back.castTimeTicks());
        assertEquals(entry.castTimeMultiplier(), back.castTimeMultiplier());
    }

    @Test
    void anEntryWithNoCastTimeOverrideWritesNeitherKey() {
        LoadoutRecord record = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");
        com.google.gson.JsonObject written = LoadoutJson.toJson(record.loadout().spells().get(0));
        assertFalse(written.has(LoadoutJson.CAST_TIME));
        assertFalse(written.has(LoadoutJson.CAST_TIME_MULTIPLIER));
    }

    @Test
    void anInapplicableRecordCountsApartFromTheRejectedOnes() {
        // "parsed" is what the author is told loaded; a file skipped for an absent mod did not load
        // and did not fail, so it belongs in neither the parsed nor the rejected column.
        LoadoutRecord rejected = parse("""
                { "entity_type": "minecraft:skeleton", "spells": [ { "level": 1 } ] }""");
        LoadoutRecord skipped = LoadoutParser.parse(ID, JsonParser.parseString("""
                {
                  "entity_type": "recruits:recruit",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }"""), "mypack", LoadoutSourceTier.DATAPACK, false, null, null,
                RegistryChecks.of(id -> true, id -> true, id -> true, ns -> !ns.equals("recruits")));
        LoadoutRecord active = parse("""
                {
                  "entity_type": "minecraft:skeleton",
                  "spells": [ { "spell": "irons_spellbooks:magic_missile" } ]
                }""");

        LoadoutCatalog catalog = new LoadoutCatalog(1, java.util.List.of(rejected, skipped, active),
                java.util.Map.of(), java.util.Set.of(), java.util.Map.of(), java.util.Set.of());
        LoadoutCatalog.Counts counts = catalog.counts();
        assertEquals(3, counts.discovered());
        assertEquals(1, counts.rejected());
        assertEquals(1, counts.inapplicable());
        assertEquals(1, counts.parsed());
        assertEquals(LoadoutRecord.Status.INAPPLICABLE, skipped.status());
        assertEquals("recruits", skipped.absentNamespace().orElse(null));
    }
}
