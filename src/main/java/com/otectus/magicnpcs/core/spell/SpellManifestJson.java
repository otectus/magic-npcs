package com.otectus.magicnpcs.core.spell;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.otectus.magicnpcs.core.loadout.LoadoutProblem;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for one spell manifest file, {@code data/<ns>/spell_manifests/<name>.json}:
 *
 * <pre>{@code
 * { "format": 1,
 *   "verified_against": "traveloptics 1.2.0",
 *   "spells": { "traveloptics:tidal_lance": "DIRECT" } }
 * }</pre>
 *
 * <p>A manifest is how an add-on author (or an operator with a datapack) states what a mob can
 * actually do with their spells, without depending on Magic NPCs. Pure and Iron's-free: it never
 * touches the spell registry, so an id for a mod that is not installed simply never matches.
 *
 * <p>Problems are reported with the same {@link LoadoutProblem} vocabulary the loadout parser uses, so
 * the codes are machine-stable and the messages are printed the same way.
 */
public final class SpellManifestJson {

    /** The only manifest format version this build understands. */
    public static final int FORMAT = 1;

    private SpellManifestJson() {}

    /**
     * @param entries  the accepted rows, keyed by spell id, in file order
     * @param problems everything wrong with the file; a {@code MANIFEST_FORMAT} error means no rows
     */
    public record Parsed(Map<ResourceLocation, SpellSupportResolver.ManifestEntry> entries,
                         List<LoadoutProblem> problems) {}

    /**
     * @param fileId the resource id of the manifest, used as the row source in diagnostics
     * @param root   the parsed JSON
     * @return the accepted rows plus every problem found; never {@code null}
     */
    public static Parsed parse(ResourceLocation fileId, JsonElement root) {
        List<LoadoutProblem> problems = new ArrayList<>();
        if (root == null || !root.isJsonObject()) {
            problems.add(LoadoutProblem.error("MANIFEST_FORMAT", "",
                    "spell manifest " + fileId + " is not a JSON object; the whole file is skipped"));
            return new Parsed(Map.of(), List.copyOf(problems));
        }
        JsonObject obj = root.getAsJsonObject();
        int format = obj.has("format") && obj.get("format").isJsonPrimitive()
                ? obj.get("format").getAsJsonPrimitive().getAsInt() : -1;
        if (format != FORMAT) {
            problems.add(LoadoutProblem.error("MANIFEST_FORMAT", "/format",
                    "spell manifest " + fileId + " declares format " + (format < 0 ? "<missing>" : format)
                            + "; this build only understands " + FORMAT + ", so the whole file is skipped",
                    "set \"format\": " + FORMAT));
            return new Parsed(Map.of(), List.copyOf(problems));
        }
        String verifiedAgainst = obj.has("verified_against") && obj.get("verified_against").isJsonPrimitive()
                ? obj.get("verified_against").getAsString() : null;
        if (!obj.has("spells") || !obj.get("spells").isJsonObject()) {
            problems.add(LoadoutProblem.error("MANIFEST_NO_SPELLS", "/spells",
                    "spell manifest " + fileId + " has no \"spells\" object, so it declares nothing",
                    "add \"spells\": { \"<namespace>:<path>\": \"DIRECT\" }"));
            return new Parsed(Map.of(), List.copyOf(problems));
        }

        Map<ResourceLocation, SpellSupportResolver.ManifestEntry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> row : obj.getAsJsonObject("spells").entrySet()) {
            String rawId = row.getKey();
            String pointer = "/spells/" + rawId;
            if (rawId.indexOf(':') < 0) {
                problems.add(LoadoutProblem.error("MANIFEST_BAD_ID", pointer,
                        "'" + rawId + "' has no namespace, so it cannot name a spell",
                        "write it in full, e.g. irons_spellbooks:" + rawId));
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(rawId);
            if (id == null) {
                problems.add(LoadoutProblem.error("MANIFEST_BAD_ID", pointer,
                        "'" + rawId + "' is not a valid resource location"));
                continue;
            }
            String rawCapability = row.getValue() != null && row.getValue().isJsonPrimitive()
                    ? row.getValue().getAsString() : String.valueOf(row.getValue());
            SpellCapability capability = capabilityOf(rawCapability);
            if (capability == null) {
                problems.add(LoadoutProblem.error("MANIFEST_BAD_CAPABILITY", pointer,
                        "'" + rawCapability + "' is not a known capability, so " + id + " is skipped",
                        "use one of " + names()));
                continue;
            }
            entries.put(id, new SpellSupportResolver.ManifestEntry(capability, fileId, verifiedAgainst));
        }
        if (entries.isEmpty() && problems.isEmpty()) {
            problems.add(LoadoutProblem.info("MANIFEST_NO_SPELLS", "/spells",
                    "spell manifest " + fileId + " declares no spells"));
        }
        return new Parsed(Map.copyOf(entries), List.copyOf(problems));
    }

    private static SpellCapability capabilityOf(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return SpellCapability.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String names() {
        StringBuilder sb = new StringBuilder();
        for (SpellCapability c : SpellCapability.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(c.name());
        }
        return sb.toString();
    }
}
