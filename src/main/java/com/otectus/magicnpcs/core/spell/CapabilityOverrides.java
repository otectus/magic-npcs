package com.otectus.magicnpcs.core.spell;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for {@code spells.capabilityOverrides}, the one-off operator mechanism for stating what a
 * single spell needs: {@code "namespace:path=CAPABILITY"}, e.g.
 * {@code "traveloptics:tidal_lance=TARGET_ENTITY"}.
 *
 * <p>It outranks every other layer, including the built-in reviewed table, because the operator is
 * the one who can actually watch the spell fire. A datapack manifest is the better answer for a whole
 * pack; this is for the single spell someone needs working today. Pure and Iron's-free.
 */
public final class CapabilityOverrides {

    private CapabilityOverrides() {}

    /**
     * @param overrides the accepted entries, keyed by spell id
     * @param problems  one plain-English line per rejected entry
     */
    public record Parsed(Map<ResourceLocation, SpellCapability> overrides, List<String> problems) {}

    /** Parse the raw config list; a bad entry is reported and skipped, never fatal. */
    public static Parsed parse(List<? extends String> entries) {
        Map<ResourceLocation, SpellCapability> overrides = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        if (entries == null) {
            return new Parsed(Map.of(), List.of());
        }
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                problems.add("'" + entry + "' is not <namespace:path>=<CAPABILITY>");
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(entry.substring(0, eq).trim());
            if (id == null || !entry.substring(0, eq).contains(":")) {
                problems.add("'" + entry + "' does not start with a full namespace:path spell id");
                continue;
            }
            SpellCapability capability = capabilityOf(entry.substring(eq + 1));
            if (capability == null) {
                problems.add("'" + entry + "' names a capability this build does not know");
                continue;
            }
            overrides.put(id, capability);
        }
        return new Parsed(Map.copyOf(overrides), List.copyOf(problems));
    }

    /** The config-spec validator: true when {@code o} is a single well-formed override entry. */
    public static boolean isValidEntry(Object o) {
        if (!(o instanceof String s)) {
            return false;
        }
        Parsed parsed = parse(List.of(s));
        return parsed.problems().isEmpty() && !parsed.overrides().isEmpty();
    }

    private static SpellCapability capabilityOf(String raw) {
        try {
            return SpellCapability.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
