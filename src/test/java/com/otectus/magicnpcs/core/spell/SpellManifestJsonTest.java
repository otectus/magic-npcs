package com.otectus.magicnpcs.core.spell;

import com.google.gson.JsonParser;
import com.otectus.magicnpcs.core.loadout.LoadoutProblem;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The datapack manifest parser: what it accepts, and — more importantly — that everything it rejects
 * is reported with a stable code rather than dropped. A manifest that silently loses a row is the
 * failure the loadout catalog was rebuilt to stop happening.
 */
class SpellManifestJsonTest {

    private static final ResourceLocation FILE = new ResourceLocation("traveloptics", "spells");

    private static SpellManifestJson.Parsed parse(String json) {
        return SpellManifestJson.parse(FILE, JsonParser.parseString(json));
    }

    private static boolean hasCode(SpellManifestJson.Parsed parsed, String code) {
        for (LoadoutProblem p : parsed.problems()) {
            if (p.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aWellFormedManifestParses() {
        SpellManifestJson.Parsed parsed = parse("{\"format\":1,\"verified_against\":\"traveloptics 1.2.0\","
                + "\"spells\":{\"traveloptics:tidal_lance\":\"TARGET_ENTITY\","
                + "\"traveloptics:sea_wall\":\"ADDON_DEFAULT\"}}");
        assertEquals(2, parsed.entries().size());
        assertTrue(parsed.problems().isEmpty());
        SpellSupportResolver.ManifestEntry entry =
                parsed.entries().get(new ResourceLocation("traveloptics", "tidal_lance"));
        assertEquals(SpellCapability.TARGET_ENTITY, entry.capability());
        assertEquals(FILE, entry.fileId());
        assertEquals("traveloptics 1.2.0", entry.verifiedAgainst());
    }

    @Test
    void anUnknownCapabilitySkipsOnlyThatRow() {
        SpellManifestJson.Parsed parsed = parse("{\"format\":1,\"spells\":{"
                + "\"traveloptics:tidal_lance\":\"SPLASHY\","
                + "\"traveloptics:sea_wall\":\"DIRECT\"}}");
        assertEquals(1, parsed.entries().size());
        assertTrue(hasCode(parsed, "MANIFEST_BAD_CAPABILITY"));
    }

    @Test
    void aMissingSpellsObjectIsAnError() {
        SpellManifestJson.Parsed parsed = parse("{\"format\":1}");
        assertTrue(parsed.entries().isEmpty());
        assertTrue(hasCode(parsed, "MANIFEST_NO_SPELLS"));
    }

    @Test
    void aBareIdIsRejected() {
        SpellManifestJson.Parsed parsed = parse("{\"format\":1,\"spells\":{\"tidal_lance\":\"DIRECT\"}}");
        assertTrue(parsed.entries().isEmpty());
        assertTrue(hasCode(parsed, "MANIFEST_BAD_ID"));
    }

    @Test
    void theWrongFormatSkipsTheWholeFile() {
        SpellManifestJson.Parsed parsed = parse("{\"format\":2,\"spells\":{"
                + "\"traveloptics:tidal_lance\":\"DIRECT\"}}");
        assertTrue(parsed.entries().isEmpty());
        assertTrue(hasCode(parsed, "MANIFEST_FORMAT"));
    }
}
