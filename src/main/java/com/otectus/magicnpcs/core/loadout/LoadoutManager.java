package com.otectus.magicnpcs.core.loadout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.otectus.magicnpcs.MagicNpcs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads spellcaster loadouts from {@code data/<ns>/spellcasters/*.json}. Each file
 * declares the entity type it applies to, so loadout presence is the opt-in (no
 * separate tag). Iron's-free — spell ids are resolved to Iron's spells on the
 * integration side, so this loads fine even without Iron's installed.
 */
public class LoadoutManager extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "spellcasters";
    private static final Gson GSON = new GsonBuilder().create();

    /** Immutable snapshot, swapped wholesale on reload; read from the server thread. */
    private static volatile Map<ResourceLocation, SpellcasterLoadout> byType = Map.of();

    public LoadoutManager() {
        super(GSON, FOLDER);
    }

    /** @return the loadout for an entity type id, or {@code null} if none (= not a spellcaster). */
    public static SpellcasterLoadout get(ResourceLocation entityType) {
        return byType.get(entityType);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        Map<ResourceLocation, SpellcasterLoadout> result = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            try {
                SpellcasterLoadout loadout = parse(GsonHelper.convertToJsonObject(entry.getValue(), "loadout"));
                if (result.put(loadout.entityType(), loadout) != null) {
                    MagicNpcs.LOGGER.warn("Duplicate spellcaster loadout for {} (last one wins, file {})",
                            loadout.entityType(), entry.getKey());
                }
            } catch (Exception ex) {
                MagicNpcs.LOGGER.error("Skipping invalid spellcaster loadout {}: {}", entry.getKey(), ex.getMessage());
            }
        }
        byType = Map.copyOf(result);
        MagicNpcs.LOGGER.info("Loaded {} spellcaster loadout(s)", byType.size());
    }

    private static SpellcasterLoadout parse(JsonObject json) {
        ResourceLocation entityType = new ResourceLocation(GsonHelper.getAsString(json, "entity_type"));
        double maxMana = GsonHelper.getAsDouble(json, "max_mana", 100.0);
        double manaRegen = GsonHelper.getAsDouble(json, "mana_regen", 10.0);

        List<LoadoutEntry> spells = new ArrayList<>();
        for (JsonElement element : GsonHelper.getAsJsonArray(json, "spells")) {
            JsonObject o = GsonHelper.convertToJsonObject(element, "spell entry");
            spells.add(new LoadoutEntry(
                    new ResourceLocation(GsonHelper.getAsString(o, "spell")),
                    GsonHelper.getAsInt(o, "level", 1),
                    GsonHelper.getAsInt(o, "weight", 1),
                    GsonHelper.getAsDouble(o, "min_range", 0.0),
                    GsonHelper.getAsDouble(o, "max_range", 20.0),
                    GsonHelper.getAsDouble(o, "safety_radius", 1.5),
                    LoadoutEntry.Role.valueOf(GsonHelper.getAsString(o, "role", "attack").toUpperCase(Locale.ROOT))
            ));
        }
        if (spells.isEmpty()) {
            throw new IllegalArgumentException("loadout has no spells");
        }
        return new SpellcasterLoadout(entityType, maxMana, manaRegen, spells);
    }
}
