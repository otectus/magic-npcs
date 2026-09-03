package com.otectus.magicnpcs.core.loadout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Difficulty;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns one spellcaster JSON file into a {@link LoadoutRecord}, collecting <em>every</em> problem it
 * finds instead of throwing on the first one.
 *
 * <p>This is the half of audit VAL-001 the loader could not do. 0.6.1 parsed inside a {@code try},
 * logged {@code ex.getMessage()} and dropped the file, so an author saw one complaint at a time (if
 * they read the log at all) and {@code /magicnpcs validate} never saw the file. Parsing now always
 * produces a record — a rejected one still carries the entity type where that could be read, its
 * problems, and its provenance, so validation can name the file, the pack, and the exact JSON pointer.
 *
 * <p>Registry-aware but Iron's-free: entity types, professions, items and difficulties are checked
 * here; spell ids need Iron's and are checked on the integration side.
 */
public final class LoadoutParser {

    /**
     * Vanilla clears {@code lastHurtByMob} after 100 ticks, so a longer {@code recent_damage_window}
     * silently behaves as 100 (backlog B17). Clamped with a warning rather than left as a trap.
     */
    public static final int MAX_RECENT_DAMAGE_WINDOW = 100;

    /** Above this a cooldown is almost certainly a seconds value nobody converted to ticks. */
    private static final int SUSPICIOUS_COOLDOWN_TICKS = 12000;

    private LoadoutParser() {}

    /**
     * Parse one file into a record.
     *
     * @param inferredEntityType the entity type read from a lower resource in the same stack; used
     *                           only to give a bare {@code {"enabled": false}} suppression stub a key
     * @param inferredProfession likewise for the optional villager profession
     */
    public static LoadoutRecord parse(ResourceLocation resourceId, JsonElement raw, String packId,
                                      LoadoutSourceTier tier, boolean strict,
                                      ResourceLocation inferredEntityType,
                                      ResourceLocation inferredProfession) {
        return parse(resourceId, raw, packId, tier, strict, inferredEntityType, inferredProfession, true);
    }

    /**
     * As above, with registry existence checks made optional.
     *
     * @param checkRegistries true inside a game runtime, where entity types, professions and items can
     *                        and must be resolved. False for the schema unit tests, which run without a
     *                        bootstrapped Minecraft: {@code BuiltInRegistries} cannot even be
     *                        classloaded there, and every id would otherwise read as "not registered".
     *                        Never false in production - the datapack loader always passes true.
     */
    public static LoadoutRecord parse(ResourceLocation resourceId, JsonElement raw, String packId,
                                      LoadoutSourceTier tier, boolean strict,
                                      ResourceLocation inferredEntityType,
                                      ResourceLocation inferredProfession, boolean checkRegistries) {
        List<LoadoutProblem> problems = new ArrayList<>();
        JsonObject json;
        try {
            json = GsonHelper.convertToJsonObject(raw, "loadout");
        } catch (Exception ex) {
            problems.add(LoadoutProblem.error("NOT_AN_OBJECT", "",
                    "the file's top level is not a JSON object (" + ex.getMessage() + ")",
                    "a loadout file is one object: { \"entity_type\": …, \"spells\": [ … ] }"));
            return rejected(resourceId, packId, tier, null, null, problems, hashOf(raw));
        }
        String hash = hashOf(json);
        LoadoutSchema.checkKeys(json, LoadoutSchema.ROOT_KEYS, "", strict, problems);

        boolean enabled = getBoolean(json, LoadoutJson.ENABLED, true, "", problems);
        boolean replace = getBoolean(json, LoadoutJson.REPLACE, false, "", problems);

        ResourceLocation entityType =
                readEntityType(json, enabled, inferredEntityType, checkRegistries, problems);
        ResourceLocation profession =
                readProfession(json, inferredProfession, checkRegistries, problems);
        if (entityType == null) {
            return rejected(resourceId, packId, tier, null, profession, problems, hash);
        }

        double maxMana = readNonNegative(json, LoadoutJson.MAX_MANA, 100.0, "", problems);
        double manaRegen = readNonNegative(json, LoadoutJson.MANA_REGEN, 10.0, "", problems);
        int poolWeight = Math.max(1, getInt(json, LoadoutJson.POOL_WEIGHT, 1, "", problems));
        Double casterChance = json.has(LoadoutJson.CASTER_CHANCE)
                ? readFraction(json, LoadoutJson.CASTER_CHANCE, "", problems) : null;
        Integer goalPriority = null;
        if (json.has(LoadoutJson.GOAL_PRIORITY)) {
            int p = getInt(json, LoadoutJson.GOAL_PRIORITY, 2, "", problems);
            if (p < 0 || p > 99) {
                problems.add(LoadoutProblem.warning("GOAL_PRIORITY_RANGE", "/" + LoadoutJson.GOAL_PRIORITY,
                        "goal_priority=" + p + " is outside the 0..99 GoalSelector range",
                        "clamped to " + Math.max(0, Math.min(99, p))));
            }
            goalPriority = Math.max(0, Math.min(99, p));
        }
        NativeAttackPolicy nativeAttack = NativeAttackPolicy.COEXIST;
        if (json.has(LoadoutJson.NATIVE_ATTACK)) {
            try {
                nativeAttack = NativeAttackPolicy.parse(GsonHelper.getAsString(json, LoadoutJson.NATIVE_ATTACK));
            } catch (Exception ex) {
                problems.add(LoadoutProblem.error("BAD_NATIVE_ATTACK", "/" + LoadoutJson.NATIVE_ATTACK,
                        String.valueOf(ex.getMessage()), "one of: coexist, suppress, yield"));
            }
        }

        LoadoutEquipment equipment = null;
        if (json.has(LoadoutJson.EQUIPMENT)) {
            equipment = parseEquipment(GsonHelper.getAsJsonObject(json, LoadoutJson.EQUIPMENT),
                    strict, checkRegistries, problems);
        }
        LoadoutConditions conditions = null;
        if (json.has(LoadoutJson.CONDITIONS)) {
            conditions = parseConditions(GsonHelper.getAsJsonObject(json, LoadoutJson.CONDITIONS), strict, problems);
        }

        List<LoadoutEntry> spells = parseSpells(json, enabled, strict, checkRegistries, problems);

        SpellcasterLoadout loadout = new SpellcasterLoadout(entityType, profession, maxMana, manaRegen,
                List.copyOf(spells), equipment, conditions, poolWeight, resourceId, replace, enabled,
                tier, goalPriority, nativeAttack, casterChance);

        boolean fatal = false;
        for (LoadoutProblem p : problems) {
            fatal |= p.severity() == LoadoutProblem.Severity.ERROR;
        }
        LoadoutRecord.Status status = fatal ? LoadoutRecord.Status.REJECTED
                : (enabled ? LoadoutRecord.Status.ACTIVE : LoadoutRecord.Status.SUPPRESSED);
        return new LoadoutRecord(resourceId, packId, tier, status, entityType, profession,
                fatal ? null : loadout, problems, hash);
    }

    private static List<LoadoutEntry> parseSpells(JsonObject json, boolean enabled, boolean strict,
                                                  boolean checkRegistries, List<LoadoutProblem> problems) {
        List<LoadoutEntry> spells = new ArrayList<>();
        if (!json.has(LoadoutJson.SPELLS)) {
            // A disabled loadout may omit "spells" entirely — switching a type off is the whole point,
            // and demanding a dummy spell list for that would be a trap (backlog W3c).
            if (enabled) {
                problems.add(LoadoutProblem.error("NO_SPELLS", "",
                        "an enabled loadout must declare \"spells\"",
                        "add \"spells\": [ … ], or set \"enabled\": false to switch this type off"));
            }
            return spells;
        }
        JsonElement arr = json.get(LoadoutJson.SPELLS);
        if (!arr.isJsonArray()) {
            problems.add(LoadoutProblem.error("SPELLS_NOT_ARRAY", "/" + LoadoutJson.SPELLS,
                    "\"spells\" must be a JSON array",
                    "\"spells\": [ { \"spell\": \"irons_spellbooks:magic_missile\" } ]"));
            return spells;
        }
        int i = 0;
        for (JsonElement element : arr.getAsJsonArray()) {
            String pointer = "/" + LoadoutJson.SPELLS + "/" + i++;
            if (!element.isJsonObject()) {
                problems.add(LoadoutProblem.error("SPELL_NOT_OBJECT", pointer,
                        "each \"spells\" entry must be an object"));
                continue;
            }
            LoadoutEntry entry =
                    parseEntry(element.getAsJsonObject(), pointer, strict, checkRegistries, problems);
            if (entry != null) {
                spells.add(entry);
            }
        }
        if (enabled && spells.isEmpty()) {
            problems.add(LoadoutProblem.error("NO_CASTABLE_SPELLS", "/" + LoadoutJson.SPELLS,
                    "no spell entry could be read, so this mob would have nothing to cast",
                    "fix the entries reported above, or set \"enabled\": false"));
        }
        return spells;
    }

    // --- root fields -------------------------------------------------------------------------

    private static ResourceLocation readEntityType(JsonObject json, boolean enabled,
                                                   ResourceLocation inferred, boolean checkRegistries,
                                                   List<LoadoutProblem> problems) {
        if (!json.has(LoadoutJson.ENTITY_TYPE)) {
            // The bare `{ "enabled": false }` suppression stub the docs have always shown. 0.6.1 read
            // the mandatory entity_type first and rejected it outright (audit "enabled: false
            // incompatibility"). Infer the key from the resource this file shadows instead, and record
            // the inference so the author can see it happened.
            if (!enabled && inferred != null) {
                problems.add(LoadoutProblem.info("INFERRED_ENTITY_TYPE", "",
                        "no \"entity_type\"; inferred " + inferred
                                + " from the loadout this file shadows at the same data path"));
                return inferred;
            }
            problems.add(LoadoutProblem.error("MISSING_ENTITY_TYPE", "",
                    "\"entity_type\" is required",
                    enabled ? "e.g. \"entity_type\": \"minecraft:skeleton\""
                            : "a bare { \"enabled\": false } stub only works when it shadows a loadout at "
                                    + "the same data path; otherwise name the entity type explicitly"));
            return null;
        }
        String raw;
        try {
            raw = GsonHelper.getAsString(json, LoadoutJson.ENTITY_TYPE);
        } catch (Exception ex) {
            problems.add(LoadoutProblem.error("BAD_ENTITY_TYPE", "/" + LoadoutJson.ENTITY_TYPE,
                    "\"entity_type\" must be a string"));
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) {
            problems.add(LoadoutProblem.error("BAD_ENTITY_TYPE", "/" + LoadoutJson.ENTITY_TYPE,
                    "'" + raw + "' is not a valid resource id",
                    "ids look like namespace:path, e.g. minecraft:skeleton"));
            return null;
        }
        if (checkRegistries && !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            problems.add(LoadoutProblem.error("UNKNOWN_ENTITY_TYPE", "/" + LoadoutJson.ENTITY_TYPE,
                    "no entity type '" + id + "' is registered",
                    "check the spelling, and that the mod owning '" + id.getNamespace() + "' is installed"));
            return null;
        }
        return id;
    }

    private static ResourceLocation readProfession(JsonObject json, ResourceLocation inferred,
                                                   boolean checkRegistries, List<LoadoutProblem> problems) {
        if (!json.has(LoadoutJson.PROFESSION)) {
            return inferred;
        }
        String raw;
        try {
            raw = GsonHelper.getAsString(json, LoadoutJson.PROFESSION);
        } catch (Exception ex) {
            problems.add(LoadoutProblem.error("BAD_PROFESSION", "/" + LoadoutJson.PROFESSION,
                    "\"profession\" must be a string"));
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) {
            problems.add(LoadoutProblem.error("BAD_PROFESSION", "/" + LoadoutJson.PROFESSION,
                    "'" + raw + "' is not a valid resource id"));
            return null;
        }
        if (checkRegistries && !BuiltInRegistries.VILLAGER_PROFESSION.containsKey(id)) {
            problems.add(LoadoutProblem.error("UNKNOWN_PROFESSION", "/" + LoadoutJson.PROFESSION,
                    "no villager profession '" + id + "' is registered",
                    "e.g. minecraft:cleric, minecraft:librarian"));
            return null;
        }
        return id;
    }

    // --- spell entries -----------------------------------------------------------------------

    private static LoadoutEntry parseEntry(JsonObject o, String pointer, boolean strict,
                                           boolean checkRegistries, List<LoadoutProblem> problems) {
        LoadoutSchema.checkKeys(o, LoadoutSchema.SPELL_KEYS, pointer, strict, problems);
        if (!o.has(LoadoutJson.SPELL)) {
            problems.add(LoadoutProblem.error("MISSING_SPELL", pointer,
                    "each \"spells\" entry needs a \"spell\" id",
                    "e.g. \"spell\": \"irons_spellbooks:magic_missile\" — run /magicnpcs spells for the list"));
            return null;
        }
        String rawSpell;
        try {
            rawSpell = GsonHelper.getAsString(o, LoadoutJson.SPELL);
        } catch (Exception ex) {
            problems.add(LoadoutProblem.error("BAD_SPELL_ID", pointer + "/" + LoadoutJson.SPELL,
                    "\"spell\" must be a string"));
            return null;
        }
        ResourceLocation spellId = ResourceLocation.tryParse(rawSpell);
        if (spellId == null) {
            problems.add(LoadoutProblem.error("BAD_SPELL_ID", pointer + "/" + LoadoutJson.SPELL,
                    "'" + rawSpell + "' is not a valid resource id",
                    "ids look like irons_spellbooks:magic_missile"));
            return null;
        }
        LoadoutEntry.Role role = LoadoutEntry.Role.ATTACK;
        if (o.has(LoadoutJson.ROLE)) {
            String raw = GsonHelper.getAsString(o, LoadoutJson.ROLE);
            try {
                role = LoadoutEntry.Role.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                problems.add(LoadoutProblem.error("BAD_ROLE", pointer + "/" + LoadoutJson.ROLE,
                        "role must be 'attack' or 'support', got '" + raw + "'"));
                return null;
            }
        }

        int level = Math.max(1, getInt(o, LoadoutJson.LEVEL, 1, pointer, problems));
        int weight = Math.max(1, getInt(o, LoadoutJson.WEIGHT, 1, pointer, problems));
        double minRange = readNonNegative(o, LoadoutJson.MIN_RANGE, 0.0, pointer, problems);
        double maxRange = readNonNegative(o, LoadoutJson.MAX_RANGE, 20.0, pointer, problems);
        double safety = readNonNegative(o, LoadoutJson.SAFETY_RADIUS, 1.5, pointer, problems);
        if (minRange > maxRange) {
            problems.add(LoadoutProblem.error("RANGE_INVERTED", pointer,
                    String.format(Locale.ROOT, "min_range=%.1f is greater than max_range=%.1f, so no "
                                    + "distance can ever satisfy both and this spell is never selectable",
                            minRange, maxRange),
                    "swap them, or widen max_range"));
        }

        Double castChance = o.has(LoadoutJson.CAST_CHANCE)
                ? readFraction(o, LoadoutJson.CAST_CHANCE, pointer, problems) : null;
        Integer cooldown = null;
        if (o.has(LoadoutJson.COOLDOWN)) {
            cooldown = Math.max(0, getInt(o, LoadoutJson.COOLDOWN, 0, pointer, problems));
            if (cooldown > SUSPICIOUS_COOLDOWN_TICKS) {
                problems.add(LoadoutProblem.warning("COOLDOWN_SUSPICIOUS", pointer + "/" + LoadoutJson.COOLDOWN,
                        "cooldown=" + cooldown + " ticks is over ten minutes",
                        "cooldown is in ticks, not seconds — 20 ticks is one second"));
            }
        }
        Double cooldownMult = null;
        if (o.has(LoadoutJson.COOLDOWN_MULTIPLIER)) {
            cooldownMult = Math.max(0.0, GsonHelper.getAsDouble(o, LoadoutJson.COOLDOWN_MULTIPLIER));
        }
        Integer windup = null;
        if (o.has(LoadoutJson.WINDUP)) {
            windup = Math.max(0, getInt(o, LoadoutJson.WINDUP, 0, pointer, problems));
        }
        CastCondition condition = o.has(LoadoutJson.CONDITION)
                ? parseCondition(GsonHelper.getAsJsonObject(o, LoadoutJson.CONDITION),
                        pointer + "/" + LoadoutJson.CONDITION, strict, problems)
                : null;

        boolean requireHeld = getBoolean(o, LoadoutJson.REQUIRE_HELD_ITEM, false, pointer, problems);
        List<String> requiredItems = readItemRefs(o, pointer, checkRegistries, problems);
        LoadoutEntry.HandRequirement hand = LoadoutEntry.HandRequirement.EITHER;
        if (o.has(LoadoutJson.REQUIRED_HAND)) {
            try {
                hand = LoadoutEntry.HandRequirement.parse(GsonHelper.getAsString(o, LoadoutJson.REQUIRED_HAND));
            } catch (IllegalArgumentException ex) {
                problems.add(LoadoutProblem.error("BAD_REQUIRED_HAND", pointer + "/" + LoadoutJson.REQUIRED_HAND,
                        String.valueOf(ex.getMessage())));
            }
        }
        if (!requiredItems.isEmpty() && !requireHeld) {
            problems.add(LoadoutProblem.warning("REQUIRED_ITEMS_INERT", pointer + "/" + LoadoutJson.REQUIRED_ITEMS,
                    "\"required_items\" is listed but \"require_held_item\" is not true, so the list is ignored",
                    "add \"" + LoadoutJson.REQUIRE_HELD_ITEM + "\": true"));
        }
        // Only worth saying when the author actually chose a value: a SUPPORT entry left on the
        // defaults (which is what the data generator writes for every one of ours) has not been
        // configured wrongly, and flagging it would put a line of noise under every shipped loadout.
        if (role == LoadoutEntry.Role.SUPPORT && (minRange != 0.0 || maxRange != 20.0)) {
            problems.add(LoadoutProblem.info("SUPPORT_RANGE_IGNORED", pointer,
                    "SUPPORT spells are self-cast, so min_range/max_range have no effect on this entry"));
        }

        return new LoadoutEntry(spellId, level, weight, minRange, maxRange, safety, role,
                castChance, cooldown, cooldownMult, windup, condition, requireHeld, requiredItems, hand);
    }

    /**
     * Read {@code required_items}: item ids and/or {@code #tag} references, each checked against the
     * registry. An unresolvable entry is an error, never a silent drop — a list that quietly empties
     * itself would turn "only while holding a staff" into "always".
     */
    private static List<String> readItemRefs(JsonObject o, String pointer, boolean checkRegistries,
                                             List<LoadoutProblem> problems) {
        if (!o.has(LoadoutJson.REQUIRED_ITEMS)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String base = pointer + "/" + LoadoutJson.REQUIRED_ITEMS;
        int i = 0;
        for (JsonElement e : GsonHelper.getAsJsonArray(o, LoadoutJson.REQUIRED_ITEMS)) {
            String raw = e.getAsString();
            String at = base + "/" + i++;
            String body = raw.startsWith("#") ? raw.substring(1) : raw;
            ResourceLocation id = ResourceLocation.tryParse(body);
            if (id == null) {
                problems.add(LoadoutProblem.error("BAD_ITEM_REF", at,
                        "'" + raw + "' is not a valid item id or #tag reference"));
                continue;
            }
            if (!raw.startsWith("#") && checkRegistries && !BuiltInRegistries.ITEM.containsKey(id)) {
                problems.add(LoadoutProblem.error("UNKNOWN_ITEM", at,
                        "no item '" + id + "' is registered",
                        "use #namespace:tag for a tag, e.g. #magicnpcs:spell_focuses"));
                continue;
            }
            out.add(raw);
        }
        if (out.isEmpty()) {
            problems.add(LoadoutProblem.error("REQUIRED_ITEMS_EMPTIED", base,
                    "every entry in \"required_items\" was unusable, which would leave the held-item "
                            + "requirement with nothing to match",
                    "fix the values reported above, or drop the key to fall back to "
                            + "#magicnpcs:spell_focuses"));
        }
        return out;
    }

    // --- nested blocks -----------------------------------------------------------------------

    private static LoadoutEquipment parseEquipment(JsonObject o, boolean strict, boolean checkRegistries,
                                                   List<LoadoutProblem> problems) {
        String pointer = "/" + LoadoutJson.EQUIPMENT;
        LoadoutSchema.checkKeys(o, LoadoutSchema.EQUIPMENT_KEYS, pointer, strict, problems);
        List<LoadoutEquipment.WeightedItem> mainhand =
                parseWeightedItems(o, LoadoutJson.MAINHAND, pointer, checkRegistries, problems);
        List<LoadoutEquipment.WeightedItem> offhand =
                parseWeightedItems(o, LoadoutJson.OFFHAND, pointer, checkRegistries, problems);
        double chance = o.has(LoadoutJson.CHANCE) ? readFraction(o, LoadoutJson.CHANCE, pointer, problems) : 1.0;
        boolean onlyIfEmpty = getBoolean(o, LoadoutJson.ONLY_IF_EMPTY, true, pointer, problems);
        if (mainhand.isEmpty() && offhand.isEmpty()) {
            problems.add(LoadoutProblem.warning("EMPTY_EQUIPMENT", pointer,
                    "the \"equipment\" block grants nothing",
                    "list items under \"mainhand\"/\"offhand\", or remove the block to fall back to "
                            + "equipment.spawnWithGearChance"));
        }
        return new LoadoutEquipment(mainhand, offhand, chance, onlyIfEmpty);
    }

    private static List<LoadoutEquipment.WeightedItem> parseWeightedItems(
            JsonObject o, String key, String parentPointer, boolean checkRegistries,
            List<LoadoutProblem> problems) {
        if (!o.has(key)) {
            return List.of();
        }
        List<LoadoutEquipment.WeightedItem> out = new ArrayList<>();
        String base = parentPointer + "/" + key;
        int i = 0;
        for (JsonElement e : GsonHelper.getAsJsonArray(o, key)) {
            String at = base + "/" + i++;
            String rawId;
            int weight = 1;
            if (e.isJsonObject()) {
                JsonObject io = e.getAsJsonObject();
                rawId = GsonHelper.getAsString(io, LoadoutJson.ITEM);
                weight = Math.max(1, GsonHelper.getAsInt(io, LoadoutJson.WEIGHT, 1));
            } else {
                rawId = e.getAsString();
            }
            ResourceLocation id = ResourceLocation.tryParse(rawId);
            if (id == null) {
                problems.add(LoadoutProblem.error("BAD_ITEM_REF", at,
                        "'" + rawId + "' is not a valid item id"));
                continue;
            }
            if (checkRegistries && !BuiltInRegistries.ITEM.containsKey(id)) {
                problems.add(LoadoutProblem.error("UNKNOWN_ITEM", at,
                        "no item '" + id + "' is registered"));
                continue;
            }
            out.add(new LoadoutEquipment.WeightedItem(id, weight));
        }
        return out;
    }

    private static CastCondition parseCondition(JsonObject o, String pointer, boolean strict,
                                                List<LoadoutProblem> problems) {
        LoadoutSchema.checkKeys(o, LoadoutSchema.CONDITION_KEYS, pointer, strict, problems);
        Double selfHp = o.has(LoadoutJson.CON_SELF_HP_BELOW)
                ? readFraction(o, LoadoutJson.CON_SELF_HP_BELOW, pointer, problems) : null;
        Double targetHp = o.has(LoadoutJson.CON_TARGET_HP_BELOW)
                ? readFraction(o, LoadoutJson.CON_TARGET_HP_BELOW, pointer, problems) : null;
        Integer enemies = o.has(LoadoutJson.CON_ENEMIES_WITHIN)
                ? Math.max(0, getInt(o, LoadoutJson.CON_ENEMIES_WITHIN, 0, pointer, problems)) : null;
        Double radius = o.has(LoadoutJson.CON_ENEMIES_RADIUS)
                ? Math.max(0.0, GsonHelper.getAsDouble(o, LoadoutJson.CON_ENEMIES_RADIUS)) : null;
        if (radius != null && enemies == null) {
            problems.add(LoadoutProblem.warning("ENEMIES_RADIUS_WITHOUT_COUNT",
                    pointer + "/" + LoadoutJson.CON_ENEMIES_RADIUS,
                    "\"enemies_radius\" is set but \"enemies_within\" is not, so the radius is never used",
                    "add \"" + LoadoutJson.CON_ENEMIES_WITHIN + "\": <count>"));
        }
        Boolean hurt = o.has(LoadoutJson.CON_WHEN_RECENTLY_HURT)
                ? GsonHelper.getAsBoolean(o, LoadoutJson.CON_WHEN_RECENTLY_HURT) : null;
        Integer window = null;
        if (o.has(LoadoutJson.CON_RECENT_DAMAGE_WINDOW)) {
            int raw = Math.max(0, getInt(o, LoadoutJson.CON_RECENT_DAMAGE_WINDOW, 0, pointer, problems));
            if (raw > MAX_RECENT_DAMAGE_WINDOW) {
                problems.add(LoadoutProblem.warning("RECENT_WINDOW_CLAMPED",
                        pointer + "/" + LoadoutJson.CON_RECENT_DAMAGE_WINDOW,
                        "recent_damage_window=" + raw + " exceeds the " + MAX_RECENT_DAMAGE_WINDOW
                                + "-tick vanilla limit (lastHurtByMob is cleared then)",
                        "clamped to " + MAX_RECENT_DAMAGE_WINDOW));
            }
            window = Math.min(raw, MAX_RECENT_DAMAGE_WINDOW);
        }
        return new CastCondition(selfHp, targetHp, enemies, radius, hurt, window);
    }

    private static LoadoutConditions parseConditions(JsonObject o, boolean strict, List<LoadoutProblem> problems) {
        String pointer = "/" + LoadoutJson.CONDITIONS;
        LoadoutSchema.checkKeys(o, LoadoutSchema.CONDITIONS_KEYS, pointer, strict, problems);
        List<ResourceLocation> dims = parseIdList(o, LoadoutJson.COND_DIMENSIONS, pointer, problems);
        List<String> biomes = parseStringList(o, LoadoutJson.COND_BIOMES, pointer, problems);
        Set<Difficulty> diffs = parseDifficulties(o, pointer, problems);
        LoadoutConditions.TimeOfDay time = null;
        if (o.has(LoadoutJson.COND_TIME)) {
            String raw = GsonHelper.getAsString(o, LoadoutJson.COND_TIME);
            try {
                time = LoadoutConditions.TimeOfDay.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                problems.add(LoadoutProblem.error("BAD_TIME", pointer + "/" + LoadoutJson.COND_TIME,
                        "time must be 'day', 'night', or 'any', got '" + raw + "'"));
            }
        }
        Integer minY = o.has(LoadoutJson.COND_MIN_Y)
                ? getInt(o, LoadoutJson.COND_MIN_Y, 0, pointer, problems) : null;
        Integer maxY = o.has(LoadoutJson.COND_MAX_Y)
                ? getInt(o, LoadoutJson.COND_MAX_Y, 0, pointer, problems) : null;
        if (minY != null && maxY != null && minY > maxY) {
            problems.add(LoadoutProblem.error("Y_RANGE_INVERTED", pointer,
                    "min_y=" + minY + " is above max_y=" + maxY + ", so no height can satisfy both"));
        }
        Boolean raid = o.has(LoadoutJson.COND_REQUIRE_RAID)
                ? GsonHelper.getAsBoolean(o, LoadoutJson.COND_REQUIRE_RAID) : null;
        Boolean storm = o.has(LoadoutJson.COND_REQUIRE_STORM)
                ? GsonHelper.getAsBoolean(o, LoadoutJson.COND_REQUIRE_STORM) : null;
        List<Integer> moon = parseMoonPhases(o, pointer, problems);
        Set<ResourceLocation> allOf = Set.of();
        Set<ResourceLocation> anyOf = Set.of();
        Set<ResourceLocation> noneOf = Set.of();
        if (o.has(LoadoutJson.COND_NPC_TRAITS)) {
            JsonObject traits = GsonHelper.getAsJsonObject(o, LoadoutJson.COND_NPC_TRAITS);
            String traitsPointer = pointer + "/" + LoadoutJson.COND_NPC_TRAITS;
            LoadoutSchema.checkKeys(traits, LoadoutSchema.NPC_TRAITS_KEYS, traitsPointer, strict, problems);
            allOf = parseTraitSet(traits, LoadoutJson.TRAITS_ALL_OF, traitsPointer, problems);
            anyOf = parseTraitSet(traits, LoadoutJson.TRAITS_ANY_OF, traitsPointer, problems);
            noneOf = parseTraitSet(traits, LoadoutJson.TRAITS_NONE_OF, traitsPointer, problems);
        }
        return new LoadoutConditions(dims, biomes, diffs, time, minY, maxY, raid, storm, moon,
                allOf, anyOf, noneOf);
    }

    /**
     * Read one {@code npc_traits} list. Unlike the world restrictions above there is no registry to
     * check against — an NPC mod's traits exist only while that mod is installed — so a well-formed id
     * naming a trait nobody reports is legal and simply never matches. Only a malformed id is an error,
     * and it names its own index so the author can find it in a long list.
     *
     * <p>A missing or empty list means "no constraint", which is the same thing a missing
     * {@code npc_traits} block means; there is nothing here to widen, so it is not reported.
     */
    private static Set<ResourceLocation> parseTraitSet(JsonObject o, String key, String parentPointer,
                                                       List<LoadoutProblem> problems) {
        if (!o.has(key)) {
            return Set.of();
        }
        Set<ResourceLocation> out = new LinkedHashSet<>();
        String base = parentPointer + "/" + key;
        int i = 0;
        for (JsonElement e : GsonHelper.getAsJsonArray(o, key)) {
            String at = base + "/" + i++;
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                problems.add(LoadoutProblem.error("BAD_NPC_TRAIT", at,
                        "each \"" + key + "\" entry must be a string",
                        "traits look like namespace:path, e.g. customnpcs:job/guard"));
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
            if (id == null) {
                problems.add(LoadoutProblem.error("BAD_NPC_TRAIT", at,
                        "'" + e.getAsString() + "' is not a valid resource id",
                        "traits look like namespace:path, e.g. customnpcs:job/guard"));
                continue;
            }
            out.add(id);
        }
        return Set.copyOf(out);
    }

    /**
     * Read a restriction list of resource ids.
     *
     * <p>0.6.1 dropped unparseable entries silently and returned {@code null} when nothing survived —
     * and a {@code null} restriction list means "allow anywhere". A typo therefore <em>widened</em> the
     * condition instead of narrowing it (audit "Silent invalid-list widening"). Every dropped value is
     * now reported, and a list that empties itself entirely is an error.
     */
    private static List<ResourceLocation> parseIdList(JsonObject o, String key, String parentPointer,
                                                      List<LoadoutProblem> problems) {
        if (!o.has(key)) {
            return null;
        }
        List<ResourceLocation> out = new ArrayList<>();
        String base = parentPointer + "/" + key;
        int i = 0;
        int declared = 0;
        for (JsonElement e : GsonHelper.getAsJsonArray(o, key)) {
            declared++;
            String at = base + "/" + i++;
            ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
            if (id == null) {
                problems.add(LoadoutProblem.error("BAD_CONDITION_ID", at,
                        "'" + e.getAsString() + "' is not a valid resource id"));
                continue;
            }
            out.add(id);
        }
        return finishRestriction(out, declared, key, base, problems);
    }

    private static List<String> parseStringList(JsonObject o, String key, String parentPointer,
                                                List<LoadoutProblem> problems) {
        if (!o.has(key)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        int declared = 0;
        String base = parentPointer + "/" + key;
        for (JsonElement e : GsonHelper.getAsJsonArray(o, key)) {
            declared++;
            out.add(e.getAsString());
        }
        return finishRestriction(out, declared, key, base, problems);
    }

    private static <T> List<T> finishRestriction(List<T> out, int declared, String key, String pointer,
                                                 List<LoadoutProblem> problems) {
        if (declared == 0) {
            problems.add(LoadoutProblem.warning("EMPTY_RESTRICTION", pointer,
                    "\"" + key + "\" is an empty list, which restricts nothing",
                    "remove the key, or list the values you meant to allow"));
            return null;
        }
        if (out.isEmpty()) {
            problems.add(LoadoutProblem.error("RESTRICTION_EMPTIED", pointer,
                    "every value in \"" + key + "\" was unusable, which would turn this restriction into "
                            + "\"allow anywhere\" — refusing to widen it silently",
                    "fix the values reported above"));
            return null;
        }
        return out;
    }

    private static Set<Difficulty> parseDifficulties(JsonObject o, String parentPointer,
                                                     List<LoadoutProblem> problems) {
        if (!o.has(LoadoutJson.COND_DIFFICULTIES)) {
            return null;
        }
        EnumSet<Difficulty> set = EnumSet.noneOf(Difficulty.class);
        String base = parentPointer + "/" + LoadoutJson.COND_DIFFICULTIES;
        int declared = 0;
        int i = 0;
        for (JsonElement e : GsonHelper.getAsJsonArray(o, LoadoutJson.COND_DIFFICULTIES)) {
            declared++;
            String at = base + "/" + i++;
            Difficulty d = Difficulty.byName(e.getAsString().toLowerCase(Locale.ROOT));
            if (d == null) {
                problems.add(LoadoutProblem.error("BAD_DIFFICULTY", at,
                        "'" + e.getAsString() + "' is not a difficulty",
                        "one of: peaceful, easy, normal, hard"));
                continue;
            }
            set.add(d);
        }
        List<Difficulty> checked = finishRestriction(new ArrayList<>(set), declared,
                LoadoutJson.COND_DIFFICULTIES, base, problems);
        return checked == null ? null : set;
    }

    private static List<Integer> parseMoonPhases(JsonObject o, String parentPointer,
                                                 List<LoadoutProblem> problems) {
        if (!o.has(LoadoutJson.COND_MOON_PHASES)) {
            return null;
        }
        List<Integer> out = new ArrayList<>();
        String base = parentPointer + "/" + LoadoutJson.COND_MOON_PHASES;
        int declared = 0;
        int i = 0;
        for (JsonElement e : GsonHelper.getAsJsonArray(o, LoadoutJson.COND_MOON_PHASES)) {
            declared++;
            String at = base + "/" + i++;
            int phase = e.getAsInt();
            if (phase < 0 || phase > 7) {
                problems.add(LoadoutProblem.error("BAD_MOON_PHASE", at,
                        "moon phase " + phase + " is outside 0..7",
                        "0 is a full moon, 4 a new moon"));
                continue;
            }
            out.add(phase);
        }
        return finishRestriction(out, declared, LoadoutJson.COND_MOON_PHASES, base, problems);
    }

    // --- primitives --------------------------------------------------------------------------

    private static boolean getBoolean(JsonObject o, String key, boolean fallback, String pointer,
                                      List<LoadoutProblem> problems) {
        if (!o.has(key)) {
            return fallback;
        }
        try {
            return GsonHelper.getAsBoolean(o, key);
        } catch (Exception ex) {
            problems.add(LoadoutProblem.error("NOT_A_BOOLEAN", pointer + "/" + key,
                    "\"" + key + "\" must be true or false"));
            return fallback;
        }
    }

    private static int getInt(JsonObject o, String key, int fallback, String pointer,
                              List<LoadoutProblem> problems) {
        if (!o.has(key)) {
            return fallback;
        }
        try {
            return GsonHelper.getAsInt(o, key);
        } catch (Exception ex) {
            problems.add(LoadoutProblem.error("NOT_A_NUMBER", pointer + "/" + key,
                    "\"" + key + "\" must be a whole number"));
            return fallback;
        }
    }

    private static double readNonNegative(JsonObject o, String key, double fallback, String pointer,
                                          List<LoadoutProblem> problems) {
        if (!o.has(key)) {
            return fallback;
        }
        double v;
        try {
            v = GsonHelper.getAsDouble(o, key);
        } catch (Exception ex) {
            problems.add(LoadoutProblem.error("NOT_A_NUMBER", pointer + "/" + key,
                    "\"" + key + "\" must be a number"));
            return fallback;
        }
        if (v < 0.0) {
            problems.add(LoadoutProblem.warning("NEGATIVE_VALUE", pointer + "/" + key,
                    "\"" + key + "\" = " + v + " is negative", "clamped to 0"));
            return 0.0;
        }
        return v;
    }

    /** Read a [0,1] fraction, reporting (not silently clamping) a value outside that range. */
    private static double readFraction(JsonObject o, String key, String pointer,
                                       List<LoadoutProblem> problems) {
        double v;
        try {
            v = GsonHelper.getAsDouble(o, key);
        } catch (Exception ex) {
            problems.add(LoadoutProblem.error("NOT_A_NUMBER", pointer + "/" + key,
                    "\"" + key + "\" must be a number between 0 and 1"));
            return 1.0;
        }
        if (v < 0.0 || v > 1.0) {
            double clamped = Math.max(0.0, Math.min(1.0, v));
            problems.add(LoadoutProblem.warning("FRACTION_CLAMPED", pointer + "/" + key,
                    "\"" + key + "\" = " + v + " is outside 0..1",
                    v > 1.0 && v <= 100.0
                            ? "clamped to " + clamped + " — this is a fraction, not a percentage"
                            : "clamped to " + clamped));
            return clamped;
        }
        return v;
    }

    /** A stable digest of the canonical JSON, used as {@link LoadoutRecord#contentHash()}. */
    private static String hashOf(JsonElement json) {
        return Integer.toHexString(json.toString().hashCode());
    }

    private static LoadoutRecord rejected(ResourceLocation id, String packId, LoadoutSourceTier tier,
                                          ResourceLocation entityType, ResourceLocation profession,
                                          List<LoadoutProblem> problems, String hash) {
        return new LoadoutRecord(id, packId, tier, LoadoutRecord.Status.REJECTED, entityType,
                profession, null, problems, hash);
    }
}
