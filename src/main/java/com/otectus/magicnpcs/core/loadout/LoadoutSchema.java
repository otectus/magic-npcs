package com.otectus.magicnpcs.core.loadout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The known-key contract for a spellcaster loadout file, and the schema checks that need no Iron's
 * registry access.
 *
 * <p>0.6.1's parser read the keys it knew and ignored everything else, so {@code max_manna},
 * {@code spell_id} or {@code castchange} silently became a default or a missing field and the author
 * was told nothing (audit "Unknown keys"). Every object level now declares its key set: an unknown key
 * is a warning by default and an error under {@code general.strictLoadoutSchema}, and a near-miss is
 * matched against the real keys so the message can say what was probably meant.
 *
 * <p>Vanilla-only (Gson + core records), so the datapack loader, the data generator and the unit tests
 * all share one definition of "a valid key".
 */
public final class LoadoutSchema {

    /** Comment keys a pack author may use anywhere. Anything else unknown is reported. */
    public static final Set<String> COMMENT_KEYS = Set.of("_comment", "__comment", "$comment");

    public static final Set<String> ROOT_KEYS = Set.of(
            LoadoutJson.ENTITY_TYPE, LoadoutJson.PROFESSION, LoadoutJson.MAX_MANA, LoadoutJson.MANA_REGEN,
            LoadoutJson.SPELLS, LoadoutJson.EQUIPMENT, LoadoutJson.CONDITIONS, LoadoutJson.POOL_WEIGHT,
            LoadoutJson.REPLACE, LoadoutJson.ENABLED, LoadoutJson.GOAL_PRIORITY, LoadoutJson.NATIVE_ATTACK,
            LoadoutJson.CASTER_CHANCE);

    public static final Set<String> SPELL_KEYS = Set.of(
            LoadoutJson.SPELL, LoadoutJson.LEVEL, LoadoutJson.WEIGHT, LoadoutJson.MIN_RANGE,
            LoadoutJson.MAX_RANGE, LoadoutJson.SAFETY_RADIUS, LoadoutJson.ROLE, LoadoutJson.CAST_CHANCE,
            LoadoutJson.COOLDOWN, LoadoutJson.COOLDOWN_MULTIPLIER, LoadoutJson.WINDUP, LoadoutJson.CONDITION,
            LoadoutJson.REQUIRE_HELD_ITEM, LoadoutJson.REQUIRED_ITEMS, LoadoutJson.REQUIRED_HAND);

    public static final Set<String> EQUIPMENT_KEYS = Set.of(
            LoadoutJson.MAINHAND, LoadoutJson.OFFHAND, LoadoutJson.CHANCE, LoadoutJson.ONLY_IF_EMPTY);

    public static final Set<String> CONDITIONS_KEYS = Set.of(
            LoadoutJson.COND_DIMENSIONS, LoadoutJson.COND_BIOMES, LoadoutJson.COND_DIFFICULTIES,
            LoadoutJson.COND_TIME, LoadoutJson.COND_MIN_Y, LoadoutJson.COND_MAX_Y,
            LoadoutJson.COND_REQUIRE_RAID, LoadoutJson.COND_REQUIRE_STORM, LoadoutJson.COND_MOON_PHASES);

    public static final Set<String> CONDITION_KEYS = Set.of(
            LoadoutJson.CON_SELF_HP_BELOW, LoadoutJson.CON_TARGET_HP_BELOW, LoadoutJson.CON_ENEMIES_WITHIN,
            LoadoutJson.CON_ENEMIES_RADIUS, LoadoutJson.CON_WHEN_RECENTLY_HURT,
            LoadoutJson.CON_RECENT_DAMAGE_WINDOW);

    /**
     * Misspellings seen in the wild or named in the 0.6.1 audit, mapped to the key that was meant.
     * Consulted before the generic edit-distance guess so the common cases get an exact suggestion.
     */
    private static final Map<String, String> KNOWN_TYPOS = Map.ofEntries(
            Map.entry("max_manna", LoadoutJson.MAX_MANA),
            Map.entry("maxmana", LoadoutJson.MAX_MANA),
            Map.entry("mana", LoadoutJson.MAX_MANA),
            Map.entry("spell_id", LoadoutJson.SPELL),
            Map.entry("spellid", LoadoutJson.SPELL),
            Map.entry("id", LoadoutJson.SPELL),
            Map.entry("castchange", LoadoutJson.CAST_CHANCE),
            Map.entry("cast_change", LoadoutJson.CAST_CHANCE),
            Map.entry("chance", LoadoutJson.CAST_CHANCE),
            Map.entry("mana_regeneration", LoadoutJson.MANA_REGEN),
            Map.entry("entity", LoadoutJson.ENTITY_TYPE),
            Map.entry("entitytype", LoadoutJson.ENTITY_TYPE),
            Map.entry("type", LoadoutJson.ENTITY_TYPE),
            Map.entry("range", LoadoutJson.MAX_RANGE),
            Map.entry("cool_down", LoadoutJson.COOLDOWN),
            Map.entry("wind_up", LoadoutJson.WINDUP),
            Map.entry("spell_list", LoadoutJson.SPELLS));

    private LoadoutSchema() {}

    /**
     * Report every key in {@code object} that is not in {@code known} and is not a comment key.
     *
     * @param pointer JSON pointer of the object itself ({@code ""} for the root)
     * @param strict  when true the problems are errors (the file is rejected); otherwise warnings
     */
    public static void checkKeys(JsonObject object, Set<String> known, String pointer,
                                 boolean strict, List<LoadoutProblem> out) {
        for (Map.Entry<String, JsonElement> e : object.entrySet()) {
            String key = e.getKey();
            if (known.contains(key) || COMMENT_KEYS.contains(key)) {
                continue;
            }
            String guess = suggestFor(key, known);
            String message = "unknown key '" + key + "'"
                    + (strict ? "" : " — it is ignored, so the value you set here has no effect");
            String suggestion = guess == null
                    ? "valid keys here: " + String.join(", ", sorted(known))
                    : "did you mean \"" + guess + "\"?";
            out.add(new LoadoutProblem(
                    strict ? LoadoutProblem.Severity.ERROR : LoadoutProblem.Severity.WARNING,
                    "UNKNOWN_KEY", pointer + "/" + key, message, suggestion));
        }
    }

    /** @return the known key {@code raw} most likely meant, or {@code null} if nothing is close. */
    public static String suggestFor(String raw, Set<String> known) {
        String lower = raw.toLowerCase(Locale.ROOT);
        String typo = KNOWN_TYPOS.get(lower);
        if (typo != null && known.contains(typo)) {
            return typo;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : known) {
            int d = editDistance(lower, candidate);
            if (d < bestDistance) {
                bestDistance = d;
                best = candidate;
            }
        }
        // Two edits on a short key is still a plausible typo; more is a different word entirely.
        return best != null && bestDistance <= Math.max(2, best.length() / 4) ? best : null;
    }

    private static List<String> sorted(Set<String> keys) {
        List<String> out = new java.util.ArrayList<>(keys);
        java.util.Collections.sort(out);
        return out;
    }

    /** Plain Levenshtein distance — small key sets, so the O(n·m) table is irrelevant. */
    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return prev[b.length()];
    }
}
