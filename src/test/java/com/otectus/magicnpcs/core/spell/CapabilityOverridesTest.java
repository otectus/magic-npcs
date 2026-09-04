package com.otectus.magicnpcs.core.spell;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code "namespace:path=CAPABILITY"} config parser, and the validator the config spec uses. */
class CapabilityOverridesTest {

    @Test
    void aWellFormedEntryParses() {
        CapabilityOverrides.Parsed parsed =
                CapabilityOverrides.parse(List.of("traveloptics:tidal_lance=TARGET_ENTITY"));
        assertTrue(parsed.problems().isEmpty());
        assertEquals(SpellCapability.TARGET_ENTITY,
                parsed.overrides().get(new ResourceLocation("traveloptics", "tidal_lance")));
    }

    @Test
    void theCapabilityIsCaseInsensitive() {
        CapabilityOverrides.Parsed parsed =
                CapabilityOverrides.parse(List.of("traveloptics:tidal_lance=direct"));
        assertEquals(SpellCapability.DIRECT,
                parsed.overrides().get(new ResourceLocation("traveloptics", "tidal_lance")));
    }

    @Test
    void aBadEntryIsReportedAndSkippedWithoutLosingTheGoodOnes() {
        CapabilityOverrides.Parsed parsed = CapabilityOverrides.parse(List.of(
                "traveloptics:tidal_lance=SPLASHY",
                "no_equals_sign",
                "tidal_lance=DIRECT",
                "traveloptics:sea_wall=DIRECT"));
        assertEquals(1, parsed.overrides().size());
        assertEquals(3, parsed.problems().size());
    }

    @Test
    void theValidatorAcceptsOnlyWellFormedEntries() {
        assertTrue(CapabilityOverrides.isValidEntry("traveloptics:tidal_lance=ADDON_DEFAULT"));
        assertFalse(CapabilityOverrides.isValidEntry("traveloptics:tidal_lance"));
        assertFalse(CapabilityOverrides.isValidEntry("tidal_lance=DIRECT"));
        assertFalse(CapabilityOverrides.isValidEntry(42));
    }
}
