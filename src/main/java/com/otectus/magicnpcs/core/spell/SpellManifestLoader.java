package com.otectus.magicnpcs.core.spell;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.core.loadout.LoadoutProblem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads spell manifests from {@code data/<ns>/spell_manifests/*.json} — the sibling of the
 * {@code spellcasters} folder the loadouts come from — and publishes the merged rows to
 * {@link SpellManifestStore}.
 *
 * <p>Files are merged in resource-id order, so a pack that means to correct another one names its
 * file later in that order; every override is logged with both files and the spell, because a silent
 * one is exactly the failure the loadout catalog was rebuilt to avoid. Registered before
 * {@code LoadoutManager} in the reload listener list, so the loadout validation and the school pools
 * see the new manifests in the same reload.
 */
public class SpellManifestLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "spell_manifests";
    private static final Gson GSON = new GsonBuilder().create();

    public SpellManifestLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        List<ResourceLocation> fileIds = new ArrayList<>(files.keySet());
        fileIds.sort(ResourceLocation::compareTo);

        Map<ResourceLocation, SpellSupportResolver.ManifestEntry> merged = new LinkedHashMap<>();
        int problemCount = 0;
        for (ResourceLocation fileId : fileIds) {
            SpellManifestJson.Parsed parsed = SpellManifestJson.parse(fileId, files.get(fileId));
            for (LoadoutProblem p : parsed.problems()) {
                problemCount++;
                switch (p.severity()) {
                    case ERROR -> MagicNpcs.LOGGER.error("Spell manifest {}: {}", fileId, p.describe());
                    case WARNING -> MagicNpcs.LOGGER.warn("Spell manifest {}: {}", fileId, p.describe());
                    case INFO -> MagicNpcs.LOGGER.info("Spell manifest {}: {}", fileId, p.describe());
                }
            }
            parsed.entries().forEach((spellId, entry) -> {
                SpellSupportResolver.ManifestEntry previous = merged.put(spellId, entry);
                if (previous != null && previous.fileId() != null && !previous.fileId().equals(fileId)) {
                    MagicNpcs.LOGGER.warn("Spell manifest {} overrides {} for {}: the later file wins.",
                            fileId, previous.fileId(), spellId);
                }
            });
        }
        SpellManifestStore.replace(merged);
        MagicNpcs.LOGGER.info("Loaded spell manifests: {} file(s), {} spell(s), {} problem(s).",
                fileIds.size(), merged.size(), problemCount);
    }
}
