package com.otectus.magicnpcs.core.loadout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.core.LoadoutData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads spellcaster loadouts from {@code data/<ns>/spellcasters/*.json}. Each file
 * declares the entity type it applies to, so loadout presence is the opt-in (no
 * separate tag). A loadout may optionally declare a villager {@code profession} to
 * scope it to one profession; multiple loadouts may therefore target one entity type.
 * Iron's-free — spell ids are resolved to Iron's spells on the integration side, so
 * this loads fine even without Iron's installed.
 */
public class LoadoutManager extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "spellcasters";
    private static final Gson GSON = new GsonBuilder().create();

    /** Immutable snapshot, swapped wholesale on reload; read from the server thread. */
    private static volatile Map<ResourceLocation, List<SpellcasterLoadout>> byType = Map.of();

    public LoadoutManager() {
        super(GSON, FOLDER);
    }

    /** The current immutable loadout snapshot, keyed by entity type — for inspection/validation commands. */
    public static Map<ResourceLocation, List<SpellcasterLoadout>> snapshot() {
        return byType;
    }

    /** The (post-override) loadouts declared for {@code entityType}, or an empty list if none. */
    public static List<SpellcasterLoadout> loadoutsFor(ResourceLocation entityType) {
        return byType.getOrDefault(entityType, List.of());
    }

    /**
     * Resolve the loadout that applies to {@code mob}. Profession-specific loadouts win over
     * generic (profession-less) ones; whichever bucket applies forms a <em>pool</em>. The pool
     * is filtered by each loadout's context {@link SpellcasterLoadout#conditions() conditions}
     * (dimension/biome/difficulty/time/…), evaluated fresh against the mob's current world. When
     * more than one variant remains, one is picked by {@code pool_weight} and that choice is
     * persisted per-NPC ({@link LoadoutData}) so it stays stable across reloads.
     *
     * @return the applicable loadout, or {@code null} if none (= not a spellcaster here/now)
     */
    public static SpellcasterLoadout resolve(Mob mob) {
        List<SpellcasterLoadout> candidates = byType.get(EntityType.getKey(mob.getType()));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        ResourceLocation profession = mob instanceof Villager villager
                ? BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession())
                : null;

        // Profession-specific loadouts take precedence; whichever bucket matches is the pool.
        List<SpellcasterLoadout> pool = new ArrayList<>();
        if (profession != null) {
            for (SpellcasterLoadout l : candidates) {
                if (profession.equals(l.profession())) {
                    pool.add(l);
                }
            }
        }
        if (pool.isEmpty()) {
            for (SpellcasterLoadout l : candidates) {
                if (l.profession() == null) {
                    pool.add(l);
                }
            }
        }
        if (pool.isEmpty()) {
            return null;
        }

        // Context gate: keep only loadouts whose conditions currently hold.
        List<SpellcasterLoadout> passing = new ArrayList<>(pool.size());
        for (SpellcasterLoadout l : pool) {
            if (l.conditions() == null || l.conditions().test(mob)) {
                passing.add(l);
            }
        }
        if (passing.isEmpty()) {
            return null;
        }
        if (passing.size() == 1) {
            return passing.get(0); // single match — no choice to persist (the 0.3.x fast path)
        }

        // Several variants apply: sticky weighted pick so the mob keeps one variant across reloads.
        ResourceLocation stored = LoadoutData.getSource(mob);
        if (stored != null) {
            for (SpellcasterLoadout l : passing) {
                if (stored.equals(l.source())) {
                    return l;
                }
            }
        }
        SpellcasterLoadout chosen = weightedPick(passing, mob.getRandom());
        if (chosen.source() != null) {
            LoadoutData.setSource(mob, chosen.source());
        }
        return chosen;
    }

    private static SpellcasterLoadout weightedPick(List<SpellcasterLoadout> pool, RandomSource random) {
        int total = 0;
        for (SpellcasterLoadout l : pool) {
            total += Math.max(1, l.poolWeight());
        }
        int roll = random.nextInt(total);
        for (SpellcasterLoadout l : pool) {
            roll -= Math.max(1, l.poolWeight());
            if (roll < 0) {
                return l;
            }
        }
        return pool.get(pool.size() - 1);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<ResourceLocation, List<SpellcasterLoadout>> result = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            try {
                SpellcasterLoadout loadout = parse(GsonHelper.convertToJsonObject(entry.getValue(), "loadout"), entry.getKey());
                // Several loadouts may share an entity type (and profession): they form a per-NPC
                // pick-one pool (resolved by pool_weight + conditions), so we keep them all.
                List<SpellcasterLoadout> list = result.computeIfAbsent(loadout.entityType(), k -> new ArrayList<>());
                list.add(loadout);
            } catch (Exception ex) {
                MagicNpcs.LOGGER.error("Skipping invalid spellcaster loadout {}: {}", entry.getKey(), ex.getMessage());
            }
        }
        Map<ResourceLocation, List<SpellcasterLoadout>> frozen = new HashMap<>();
        result.forEach((type, list) -> {
            // Apply explicit replace-overrides, then warn about any remaining pooling so a pack
            // author seeing two spells in-world knows exactly why (and how to override).
            List<SpellcasterLoadout> resolved = applyOverrides(list);
            logOverrideDiagnostics(type, list, resolved);
            frozen.put(type, List.copyOf(resolved));
        });
        byType = Map.copyOf(frozen);
        int total = frozen.values().stream().mapToInt(List::size).sum();
        MagicNpcs.LOGGER.info("Loaded {} spellcaster loadout(s) across {} entity type(s)", total, frozen.size());
    }

    /**
     * Resolve {@code replace} overrides for one entity type's raw loadout list. Loadouts are grouped
     * by their effective key — the optional {@code profession} (a profession-less loadout is its own
     * group) — and within each group, if <em>any</em> loadout sets {@code replace}, only the
     * replace-marked loadouts survive; the rest are dropped. Groups with no replace are left intact
     * (the 0.4.0 pooling behaviour). Input order is preserved. Deterministic and independent of
     * datapack load order (no cross-id priority is available here), and pure (no logging) so it is
     * unit-testable without a Minecraft runtime.
     */
    static List<SpellcasterLoadout> applyOverrides(List<SpellcasterLoadout> raw) {
        boolean replaceProfessionless = false;
        Set<ResourceLocation> replacedProfessions = new HashSet<>();
        for (SpellcasterLoadout l : raw) {
            if (l.replace()) {
                if (l.profession() == null) {
                    replaceProfessionless = true;
                } else {
                    replacedProfessions.add(l.profession());
                }
            }
        }
        if (!replaceProfessionless && replacedProfessions.isEmpty()) {
            return raw; // nothing overrides — keep the full pool
        }
        List<SpellcasterLoadout> out = new ArrayList<>(raw.size());
        for (SpellcasterLoadout l : raw) {
            boolean groupHasReplace = l.profession() == null
                    ? replaceProfessionless
                    : replacedProfessions.contains(l.profession());
            if (!groupHasReplace || l.replace()) {
                out.add(l);
            }
        }
        return out;
    }

    /**
     * Log what override resolution did for one entity type: an info line when {@code replace} dropped
     * lower-priority loadouts, and a warning per effective key that still has 2+ pooled sources (so the
     * user can add {@code "replace": true} if pooling wasn't intended).
     */
    private static void logOverrideDiagnostics(ResourceLocation type,
                                               List<SpellcasterLoadout> raw, List<SpellcasterLoadout> resolved) {
        if (resolved.size() < raw.size()) {
            String kept = sources(resolved);
            String dropped = raw.stream()
                    .filter(l -> !resolved.contains(l))
                    .map(l -> String.valueOf(l.source()))
                    .collect(java.util.stream.Collectors.joining(", "));
            MagicNpcs.LOGGER.info("Spellcaster {}: replace override active — keeping [{}], dropping [{}]",
                    type, kept, dropped);
        }
        // Per effective key, warn when 2+ loadouts remain pooled with no replace.
        Map<ResourceLocation, List<SpellcasterLoadout>> byProfession = new HashMap<>();
        List<SpellcasterLoadout> professionless = new ArrayList<>();
        for (SpellcasterLoadout l : resolved) {
            if (l.profession() == null) {
                professionless.add(l);
            } else {
                byProfession.computeIfAbsent(l.profession(), k -> new ArrayList<>()).add(l);
            }
        }
        warnPool(type, null, professionless);
        byProfession.forEach((prof, list) -> warnPool(type, prof, list));
    }

    private static void warnPool(ResourceLocation type, ResourceLocation profession, List<SpellcasterLoadout> group) {
        if (group.size() < 2) {
            return;
        }
        String key = profession == null ? type.toString() : type + " (profession " + profession + ")";
        MagicNpcs.LOGGER.warn("Spellcaster {}: {} loadouts pooled ({}) — each NPC sticky-picks one by "
                        + "pool_weight. Add \"replace\": true to the loadout that should win to override instead of pool.",
                key, group.size(), sources(group));
    }

    private static String sources(List<SpellcasterLoadout> list) {
        return list.stream().map(l -> String.valueOf(l.source()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static SpellcasterLoadout parse(JsonObject json, ResourceLocation source) {
        ResourceLocation entityType = new ResourceLocation(GsonHelper.getAsString(json, LoadoutJson.ENTITY_TYPE));
        ResourceLocation profession = json.has(LoadoutJson.PROFESSION)
                ? new ResourceLocation(GsonHelper.getAsString(json, LoadoutJson.PROFESSION))
                : null;
        // Clamp to sane ranges so a malformed pack can't break the mana/selection math.
        double maxMana = Math.max(0.0, GsonHelper.getAsDouble(json, LoadoutJson.MAX_MANA, 100.0));
        double manaRegen = Math.max(0.0, GsonHelper.getAsDouble(json, LoadoutJson.MANA_REGEN, 10.0));
        int poolWeight = Math.max(1, GsonHelper.getAsInt(json, LoadoutJson.POOL_WEIGHT, 1));
        boolean replace = GsonHelper.getAsBoolean(json, LoadoutJson.REPLACE, false);
        LoadoutEquipment equipment = json.has(LoadoutJson.EQUIPMENT)
                ? parseEquipment(GsonHelper.getAsJsonObject(json, LoadoutJson.EQUIPMENT), source)
                : null;
        LoadoutConditions conditions = json.has(LoadoutJson.CONDITIONS)
                ? parseConditions(GsonHelper.getAsJsonObject(json, LoadoutJson.CONDITIONS))
                : null;

        List<LoadoutEntry> spells = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, LoadoutJson.SPELLS)) {
            JsonObject o = GsonHelper.convertToJsonObject(element, "spell entry");
            // Optional tuning fields: absent → null (inherit the global config default at runtime).
            Double castChance = o.has(LoadoutJson.CAST_CHANCE)
                    ? Math.max(0.0, Math.min(1.0, GsonHelper.getAsDouble(o, LoadoutJson.CAST_CHANCE))) : null;
            Integer cooldown = o.has(LoadoutJson.COOLDOWN)
                    ? Math.max(0, GsonHelper.getAsInt(o, LoadoutJson.COOLDOWN)) : null;
            Double cooldownMult = o.has(LoadoutJson.COOLDOWN_MULTIPLIER)
                    ? Math.max(0.0, GsonHelper.getAsDouble(o, LoadoutJson.COOLDOWN_MULTIPLIER)) : null;
            Integer windup = o.has(LoadoutJson.WINDUP)
                    ? Math.max(0, GsonHelper.getAsInt(o, LoadoutJson.WINDUP)) : null;
            CastCondition condition = o.has(LoadoutJson.CONDITION)
                    ? parseCondition(GsonHelper.getAsJsonObject(o, LoadoutJson.CONDITION)) : null;
            spells.add(new LoadoutEntry(
                    new ResourceLocation(GsonHelper.getAsString(o, LoadoutJson.SPELL)),
                    Math.max(1, GsonHelper.getAsInt(o, LoadoutJson.LEVEL, 1)),
                    Math.max(1, GsonHelper.getAsInt(o, LoadoutJson.WEIGHT, 1)),
                    Math.max(0.0, GsonHelper.getAsDouble(o, LoadoutJson.MIN_RANGE, 0.0)),
                    Math.max(0.0, GsonHelper.getAsDouble(o, LoadoutJson.MAX_RANGE, 20.0)),
                    Math.max(0.0, GsonHelper.getAsDouble(o, LoadoutJson.SAFETY_RADIUS, 1.5)),
                    parseRole(GsonHelper.getAsString(o, LoadoutJson.ROLE, "attack")),
                    castChance, cooldown, cooldownMult, windup, condition
            ));
        }
        if (spells.isEmpty()) {
            throw new IllegalArgumentException("loadout has no spells");
        }
        return new SpellcasterLoadout(entityType, profession, maxMana, manaRegen, spells, equipment, conditions, poolWeight, source, replace);
    }

    /**
     * Parse an optional {@code equipment} block. An explicit block opts in, so {@code chance}
     * defaults to 1.0 (always grant) and {@code only_if_empty} to true. Each hand list accepts
     * either a bare item-id string (weight 1) or a {@code {item, weight}} object; an unparseable
     * item id is skipped with a warning, never fatal.
     */
    private static LoadoutEquipment parseEquipment(JsonObject o, ResourceLocation source) {
        List<LoadoutEquipment.WeightedItem> mainhand = parseWeightedItems(o, LoadoutJson.MAINHAND, source);
        List<LoadoutEquipment.WeightedItem> offhand = parseWeightedItems(o, LoadoutJson.OFFHAND, source);
        double chance = clamp01(GsonHelper.getAsDouble(o, LoadoutJson.CHANCE, 1.0));
        boolean onlyIfEmpty = GsonHelper.getAsBoolean(o, LoadoutJson.ONLY_IF_EMPTY, true);
        return new LoadoutEquipment(mainhand, offhand, chance, onlyIfEmpty);
    }

    private static List<LoadoutEquipment.WeightedItem> parseWeightedItems(
            JsonObject o, String key, ResourceLocation source) {
        if (!o.has(key)) {
            return List.of();
        }
        List<LoadoutEquipment.WeightedItem> out = new ArrayList<>();
        for (JsonElement e : GsonHelper.getAsJsonArray(o, key)) {
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
                MagicNpcs.LOGGER.warn("Loadout {}: equipment.{} has an unparseable item id '{}' — skipping",
                        source, key, rawId);
                continue;
            }
            out.add(new LoadoutEquipment.WeightedItem(id, weight));
        }
        return out;
    }

    private static LoadoutEntry.Role parseRole(String raw) {
        try {
            return LoadoutEntry.Role.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("role must be 'attack' or 'support', got '" + raw + "'");
        }
    }

    private static CastCondition parseCondition(JsonObject o) {
        Double selfHp = o.has(LoadoutJson.CON_SELF_HP_BELOW)
                ? clamp01(GsonHelper.getAsDouble(o, LoadoutJson.CON_SELF_HP_BELOW)) : null;
        Double targetHp = o.has(LoadoutJson.CON_TARGET_HP_BELOW)
                ? clamp01(GsonHelper.getAsDouble(o, LoadoutJson.CON_TARGET_HP_BELOW)) : null;
        Integer enemies = o.has(LoadoutJson.CON_ENEMIES_WITHIN)
                ? Math.max(0, GsonHelper.getAsInt(o, LoadoutJson.CON_ENEMIES_WITHIN)) : null;
        Double radius = o.has(LoadoutJson.CON_ENEMIES_RADIUS)
                ? Math.max(0.0, GsonHelper.getAsDouble(o, LoadoutJson.CON_ENEMIES_RADIUS)) : null;
        Boolean hurt = o.has(LoadoutJson.CON_WHEN_RECENTLY_HURT)
                ? GsonHelper.getAsBoolean(o, LoadoutJson.CON_WHEN_RECENTLY_HURT) : null;
        Integer window = o.has(LoadoutJson.CON_RECENT_DAMAGE_WINDOW)
                ? Math.max(0, GsonHelper.getAsInt(o, LoadoutJson.CON_RECENT_DAMAGE_WINDOW)) : null;
        return new CastCondition(selfHp, targetHp, enemies, radius, hurt, window);
    }

    private static LoadoutConditions parseConditions(JsonObject o) {
        List<ResourceLocation> dims = parseIdList(o, LoadoutJson.COND_DIMENSIONS);
        List<String> biomes = parseStringList(o, LoadoutJson.COND_BIOMES);
        Set<Difficulty> diffs = parseDifficulties(o);
        LoadoutConditions.TimeOfDay time = o.has(LoadoutJson.COND_TIME)
                ? parseTime(GsonHelper.getAsString(o, LoadoutJson.COND_TIME)) : null;
        Integer minY = o.has(LoadoutJson.COND_MIN_Y) ? GsonHelper.getAsInt(o, LoadoutJson.COND_MIN_Y) : null;
        Integer maxY = o.has(LoadoutJson.COND_MAX_Y) ? GsonHelper.getAsInt(o, LoadoutJson.COND_MAX_Y) : null;
        Boolean raid = o.has(LoadoutJson.COND_REQUIRE_RAID)
                ? GsonHelper.getAsBoolean(o, LoadoutJson.COND_REQUIRE_RAID) : null;
        Boolean storm = o.has(LoadoutJson.COND_REQUIRE_STORM)
                ? GsonHelper.getAsBoolean(o, LoadoutJson.COND_REQUIRE_STORM) : null;
        List<Integer> moon = parseIntList(o, LoadoutJson.COND_MOON_PHASES);
        return new LoadoutConditions(dims, biomes, diffs, time, minY, maxY, raid, storm, moon);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static List<ResourceLocation> parseIdList(JsonObject o, String key) {
        if (!o.has(key)) {
            return null;
        }
        List<ResourceLocation> out = new ArrayList<>();
        for (JsonElement e : GsonHelper.getAsJsonArray(o, key)) {
            ResourceLocation id = ResourceLocation.tryParse(e.getAsString());
            if (id != null) {
                out.add(id);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<String> parseStringList(JsonObject o, String key) {
        if (!o.has(key)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonElement e : GsonHelper.getAsJsonArray(o, key)) {
            out.add(e.getAsString());
        }
        return out.isEmpty() ? null : out;
    }

    private static List<Integer> parseIntList(JsonObject o, String key) {
        if (!o.has(key)) {
            return null;
        }
        List<Integer> out = new ArrayList<>();
        for (JsonElement e : GsonHelper.getAsJsonArray(o, key)) {
            out.add(e.getAsInt());
        }
        return out.isEmpty() ? null : out;
    }

    private static Set<Difficulty> parseDifficulties(JsonObject o) {
        if (!o.has(LoadoutJson.COND_DIFFICULTIES)) {
            return null;
        }
        EnumSet<Difficulty> set = EnumSet.noneOf(Difficulty.class);
        for (JsonElement e : GsonHelper.getAsJsonArray(o, LoadoutJson.COND_DIFFICULTIES)) {
            Difficulty d = Difficulty.byName(e.getAsString().toLowerCase(Locale.ROOT));
            if (d != null) {
                set.add(d);
            }
        }
        return set.isEmpty() ? null : set;
    }

    private static LoadoutConditions.TimeOfDay parseTime(String raw) {
        try {
            return LoadoutConditions.TimeOfDay.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("time must be 'day', 'night', or 'any', got '" + raw + "'");
        }
    }
}
