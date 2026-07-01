package com.otectus.magicnpcs.core.loadout;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

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
    public static final String CAST_CHANCE = "cast_chance";
    public static final String COOLDOWN = "cooldown";
    public static final String COOLDOWN_MULTIPLIER = "cooldown_multiplier";
    public static final String WINDUP = "windup";

    // Per-loadout weighted starting equipment (0.5.0). All optional.
    public static final String EQUIPMENT = "equipment";
    public static final String MAINHAND = "mainhand";
    public static final String OFFHAND = "offhand";
    public static final String ITEM = "item";
    public static final String CHANCE = "chance";
    public static final String ONLY_IF_EMPTY = "only_if_empty";

    // Loadout-level context gate + pooling (0.4.0).
    public static final String CONDITIONS = "conditions";
    public static final String POOL_WEIGHT = "pool_weight";
    // Explicit override (0.5.0): clears lower-priority loadouts for the same entity_type+profession key.
    public static final String REPLACE = "replace";
    public static final String COND_DIMENSIONS = "dimensions";
    public static final String COND_BIOMES = "biomes";
    public static final String COND_DIFFICULTIES = "difficulties";
    public static final String COND_TIME = "time";
    public static final String COND_MIN_Y = "min_y";
    public static final String COND_MAX_Y = "max_y";
    public static final String COND_REQUIRE_RAID = "require_raid";
    public static final String COND_REQUIRE_STORM = "require_storm";
    public static final String COND_MOON_PHASES = "moon_phases";

    // Per-spell reactive condition (0.4.0).
    public static final String CONDITION = "condition";
    public static final String CON_SELF_HP_BELOW = "self_hp_below";
    public static final String CON_TARGET_HP_BELOW = "target_hp_below";
    public static final String CON_ENEMIES_WITHIN = "enemies_within";
    public static final String CON_ENEMIES_RADIUS = "enemies_radius";
    public static final String CON_WHEN_RECENTLY_HURT = "when_recently_hurt";
    public static final String CON_RECENT_DAMAGE_WINDOW = "recent_damage_window";

    private LoadoutJson() {}

    public static JsonObject toJson(SpellcasterLoadout loadout) {
        JsonObject o = new JsonObject();
        o.addProperty(ENTITY_TYPE, loadout.entityType().toString());
        if (loadout.profession() != null) {
            o.addProperty(PROFESSION, loadout.profession().toString());
        }
        o.addProperty(MAX_MANA, loadout.maxMana());
        o.addProperty(MANA_REGEN, loadout.manaRegen());
        // Optional pooling/context fields: written only when non-default so existing loadouts round-trip unchanged.
        if (loadout.poolWeight() != 1) {
            o.addProperty(POOL_WEIGHT, loadout.poolWeight());
        }
        if (loadout.replace()) {
            o.addProperty(REPLACE, true);
        }
        if (loadout.equipment() != null) {
            o.add(EQUIPMENT, toJson(loadout.equipment()));
        }
        if (loadout.conditions() != null) {
            o.add(CONDITIONS, toJson(loadout.conditions()));
        }
        JsonArray spells = new JsonArray();
        for (LoadoutEntry entry : loadout.spells()) {
            spells.add(toJson(entry));
        }
        o.add(SPELLS, spells);
        return o;
    }

    public static JsonObject toJson(LoadoutConditions c) {
        JsonObject o = new JsonObject();
        if (c.dimensions() != null && !c.dimensions().isEmpty()) {
            o.add(COND_DIMENSIONS, stringArray(c.dimensions().stream().map(Object::toString).toList()));
        }
        if (c.biomes() != null && !c.biomes().isEmpty()) {
            o.add(COND_BIOMES, stringArray(c.biomes()));
        }
        if (c.difficulties() != null && !c.difficulties().isEmpty()) {
            o.add(COND_DIFFICULTIES, stringArray(c.difficulties().stream()
                    .map(d -> d.getKey()).toList()));
        }
        if (c.time() != null && c.time() != LoadoutConditions.TimeOfDay.ANY) {
            o.addProperty(COND_TIME, c.time().name().toLowerCase(Locale.ROOT));
        }
        if (c.minY() != null) {
            o.addProperty(COND_MIN_Y, c.minY());
        }
        if (c.maxY() != null) {
            o.addProperty(COND_MAX_Y, c.maxY());
        }
        if (c.requireRaid() != null) {
            o.addProperty(COND_REQUIRE_RAID, c.requireRaid());
        }
        if (c.requireStorm() != null) {
            o.addProperty(COND_REQUIRE_STORM, c.requireStorm());
        }
        if (c.moonPhases() != null && !c.moonPhases().isEmpty()) {
            JsonArray phases = new JsonArray();
            c.moonPhases().forEach(phases::add);
            o.add(COND_MOON_PHASES, phases);
        }
        return o;
    }

    public static JsonObject toJson(LoadoutEquipment e) {
        JsonObject o = new JsonObject();
        if (e.mainhand() != null && !e.mainhand().isEmpty()) {
            o.add(MAINHAND, weightedItems(e.mainhand()));
        }
        if (e.offhand() != null && !e.offhand().isEmpty()) {
            o.add(OFFHAND, weightedItems(e.offhand()));
        }
        o.addProperty(CHANCE, e.chance());
        o.addProperty(ONLY_IF_EMPTY, e.onlyIfEmpty());
        return o;
    }

    private static JsonArray weightedItems(java.util.List<LoadoutEquipment.WeightedItem> items) {
        JsonArray arr = new JsonArray();
        for (LoadoutEquipment.WeightedItem w : items) {
            // Weight 1 round-trips as the bare-string shorthand; anything else as {item, weight}.
            if (w.weight() == 1) {
                arr.add(new JsonPrimitive(w.item().toString()));
            } else {
                JsonObject io = new JsonObject();
                io.addProperty(ITEM, w.item().toString());
                io.addProperty(WEIGHT, w.weight());
                arr.add(io);
            }
        }
        return arr;
    }

    private static JsonArray stringArray(java.util.List<String> values) {
        JsonArray arr = new JsonArray();
        values.forEach(v -> arr.add(new JsonPrimitive(v)));
        return arr;
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
        // Optional tuning fields: written only when set, so loadouts that rely on global
        // defaults round-trip unchanged.
        if (entry.castChance() != null) {
            o.addProperty(CAST_CHANCE, entry.castChance());
        }
        if (entry.cooldownTicks() != null) {
            o.addProperty(COOLDOWN, entry.cooldownTicks());
        }
        if (entry.cooldownMultiplier() != null) {
            o.addProperty(COOLDOWN_MULTIPLIER, entry.cooldownMultiplier());
        }
        if (entry.windupTicks() != null) {
            o.addProperty(WINDUP, entry.windupTicks());
        }
        if (entry.condition() != null) {
            o.add(CONDITION, toJson(entry.condition()));
        }
        return o;
    }

    public static JsonObject toJson(CastCondition c) {
        JsonObject o = new JsonObject();
        if (c.selfHpBelow() != null) {
            o.addProperty(CON_SELF_HP_BELOW, c.selfHpBelow());
        }
        if (c.targetHpBelow() != null) {
            o.addProperty(CON_TARGET_HP_BELOW, c.targetHpBelow());
        }
        if (c.enemiesWithin() != null) {
            o.addProperty(CON_ENEMIES_WITHIN, c.enemiesWithin());
        }
        if (c.enemiesRadius() != null) {
            o.addProperty(CON_ENEMIES_RADIUS, c.enemiesRadius());
        }
        if (c.whenRecentlyHurt() != null) {
            o.addProperty(CON_WHEN_RECENTLY_HURT, c.whenRecentlyHurt());
        }
        if (c.recentDamageWindow() != null) {
            o.addProperty(CON_RECENT_DAMAGE_WINDOW, c.recentDamageWindow());
        }
        return o;
    }
}
