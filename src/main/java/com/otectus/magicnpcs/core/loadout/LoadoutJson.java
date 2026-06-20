package com.otectus.magicnpcs.core.loadout;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * The JSON field names and serialization for spellcaster loadouts, shared by the
 * datapack loader ({@link LoadoutManager}) and the data generator so the read and
 * write sides can never drift. Vanilla-only (Gson + core records — no Iron's).
 */
public final class LoadoutJson {
    public static final String ENTITY_TYPE = "entity_type";
    public static final String PROFESSION = "profession";
    public static final String MAX_MANA = "max_mana";
    public static final String MANA_REGEN = "mana_regen";
    public static final String SPELLS = "spells";
    public static final String SPELL = "spell";
    public static final String LEVEL = "level";
    public static final String WEIGHT = "weight";
    public static final String MIN_RANGE = "min_range";
    public static final String MAX_RANGE = "max_range";
    public static final String SAFETY_RADIUS = "safety_radius";
    public static final String ROLE = "role";

    private LoadoutJson() {}

    public static JsonObject toJson(SpellcasterLoadout loadout) {
        JsonObject o = new JsonObject();
        o.addProperty(ENTITY_TYPE, loadout.entityType().toString());
        if (loadout.profession() != null) {
            o.addProperty(PROFESSION, loadout.profession().toString());
        }
        o.addProperty(MAX_MANA, loadout.maxMana());
        o.addProperty(MANA_REGEN, loadout.manaRegen());
        JsonArray spells = new JsonArray();
        for (LoadoutEntry entry : loadout.spells()) {
            spells.add(toJson(entry));
        }
        o.add(SPELLS, spells);
        return o;
    }

    public static JsonObject toJson(LoadoutEntry entry) {
        JsonObject o = new JsonObject();
        o.addProperty(SPELL, entry.spell().toString());
        o.addProperty(LEVEL, entry.level());
        o.addProperty(WEIGHT, entry.weight());
        o.addProperty(MIN_RANGE, entry.minRange());
        o.addProperty(MAX_RANGE, entry.maxRange());
        o.addProperty(SAFETY_RADIUS, entry.safetyRadius());
        o.addProperty(ROLE, entry.role().name().toLowerCase(Locale.ROOT));
        return o;
    }
}
