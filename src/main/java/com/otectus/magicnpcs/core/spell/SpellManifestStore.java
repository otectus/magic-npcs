package com.otectus.magicnpcs.core.spell;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The published, merged spell-manifest rows: what every loaded datapack manifest currently declares.
 *
 * <p>Swapped wholesale by {@link SpellManifestLoader} at the end of a reload (the merge itself lives
 * in the loader, which is the only place that knows the file order), and read from the server thread
 * by the resolver. Iron's-free, so a manifest naming a mod that is not installed simply never matches.
 */
public final class SpellManifestStore {

    private static volatile Map<ResourceLocation, SpellSupportResolver.ManifestEntry> entries = Map.of();

    private SpellManifestStore() {}

    /** Publish a new merged set of rows, replacing the previous one. */
    public static void replace(Map<ResourceLocation, SpellSupportResolver.ManifestEntry> merged) {
        entries = merged == null ? Map.of() : Map.copyOf(merged);
    }

    /** @return the current rows, unmodifiable. */
    public static Map<ResourceLocation, SpellSupportResolver.ManifestEntry> snapshot() {
        return entries;
    }

    /** @return the manifest files the current rows came from, sorted, for {@code /magicnpcs config}. */
    public static List<ResourceLocation> sources() {
        Set<ResourceLocation> files = new LinkedHashSet<>();
        for (SpellSupportResolver.ManifestEntry entry : entries.values()) {
            if (entry.fileId() != null) {
                files.add(entry.fileId());
            }
        }
        List<ResourceLocation> out = new ArrayList<>(files);
        out.sort(ResourceLocation::compareTo);
        return List.copyOf(out);
    }

    /** Drop every row. For tests only. */
    public static void clearForTest() {
        entries = Map.of();
    }
}
