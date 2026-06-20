package com.otectus.magicnpcs.core.loadout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.otectus.magicnpcs.MagicNpcs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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

    /**
     * Resolve the loadout that applies to {@code mob}: for a villager, the loadout whose
     * {@code profession} matches its profession, else the generic (profession-less) loadout
     * for its type; for any other mob, the generic loadout.
     *
     * @return the applicable loadout, or {@code null} if none (= not a spellcaster)
     */
    public static SpellcasterLoadout resolve(Mob mob) {
        List<SpellcasterLoadout> candidates = byType.get(EntityType.getKey(mob.getType()));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        ResourceLocation profession = mob instanceof Villager villager
                ? BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession())
                : null;
        SpellcasterLoadout generic = null;
        for (SpellcasterLoadout loadout : candidates) {
            if (loadout.profession() == null) {
                if (generic == null) {
                    generic = loadout; // first profession-less entry is the fallback
                }
            } else if (profession != null && loadout.profession().equals(profession)) {
                return loadout; // an exact profession match always wins
            }
        }
        return generic;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<ResourceLocation, List<SpellcasterLoadout>> result = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            try {
                SpellcasterLoadout loadout = parse(GsonHelper.convertToJsonObject(entry.getValue(), "loadout"));
                List<SpellcasterLoadout> list = result.computeIfAbsent(loadout.entityType(), k -> new ArrayList<>());
                if (list.removeIf(l -> Objects.equals(l.profession(), loadout.profession()))) {
                    MagicNpcs.LOGGER.warn("Duplicate spellcaster loadout for {} (profession {}) — last one wins, file {}",
                            loadout.entityType(), loadout.profession(), entry.getKey());
                }
                list.add(loadout);
            } catch (Exception ex) {
                MagicNpcs.LOGGER.error("Skipping invalid spellcaster loadout {}: {}", entry.getKey(), ex.getMessage());
            }
        }
        Map<ResourceLocation, List<SpellcasterLoadout>> frozen = new HashMap<>();
        result.forEach((type, list) -> frozen.put(type, List.copyOf(list)));
        byType = Map.copyOf(frozen);
        int total = frozen.values().stream().mapToInt(List::size).sum();
        MagicNpcs.LOGGER.info("Loaded {} spellcaster loadout(s) across {} entity type(s)", total, frozen.size());
    }

    private static SpellcasterLoadout parse(JsonObject json) {
        ResourceLocation entityType = new ResourceLocation(GsonHelper.getAsString(json, LoadoutJson.ENTITY_TYPE));
        ResourceLocation profession = json.has(LoadoutJson.PROFESSION)
                ? new ResourceLocation(GsonHelper.getAsString(json, LoadoutJson.PROFESSION))
                : null;
        // Clamp to sane ranges so a malformed pack can't break the mana/selection math.
        double maxMana = Math.max(0.0, GsonHelper.getAsDouble(json, LoadoutJson.MAX_MANA, 100.0));
        double manaRegen = Math.max(0.0, GsonHelper.getAsDouble(json, LoadoutJson.MANA_REGEN, 10.0));

        List<LoadoutEntry> spells = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, LoadoutJson.SPELLS)) {
            JsonObject o = GsonHelper.convertToJsonObject(element, "spell entry");
            spells.add(new LoadoutEntry(
                    new ResourceLocation(GsonHelper.getAsString(o, LoadoutJson.SPELL)),
                    Math.max(1, GsonHelper.getAsInt(o, LoadoutJson.LEVEL, 1)),
                    Math.max(1, GsonHelper.getAsInt(o, LoadoutJson.WEIGHT, 1)),
                    Math.max(0.0, GsonHelper.getAsDouble(o, LoadoutJson.MIN_RANGE, 0.0)),
                    Math.max(0.0, GsonHelper.getAsDouble(o, LoadoutJson.MAX_RANGE, 20.0)),
                    Math.max(0.0, GsonHelper.getAsDouble(o, LoadoutJson.SAFETY_RADIUS, 1.5)),
                    parseRole(GsonHelper.getAsString(o, LoadoutJson.ROLE, "attack"))
            ));
        }
        if (spells.isEmpty()) {
            throw new IllegalArgumentException("loadout has no spells");
        }
        return new SpellcasterLoadout(entityType, profession, maxMana, manaRegen, spells);
    }

    private static LoadoutEntry.Role parseRole(String raw) {
        try {
            return LoadoutEntry.Role.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("role must be 'attack' or 'support', got '" + raw + "'");
        }
    }
}
