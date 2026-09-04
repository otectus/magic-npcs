package com.otectus.magicnpcs.core.loadout;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The complete, immutable outcome of one datapack load: every discovered spellcaster resource with its
 * fate ({@link LoadoutRecord}), plus the fast runtime view keyed by entity type.
 *
 * <p>Published atomically at the end of a reload pass. Two views, deliberately:
 * {@link #records()} for diagnostics (it includes rejected, shadowed and suppressed resources, which
 * is the whole point — audit VAL-001), and {@link #activeByType()} for the hot resolution path, which
 * must stay a plain map lookup.
 *
 * <p>Each published catalog carries a monotonically increasing {@link #generation()}. Managed casters
 * record the generation they were built under, so a reconciler can tell "this mob is running the
 * current data" from "this mob predates the last {@code /reload}" without comparing loadouts.
 */
public final class LoadoutCatalog {

    /** The catalog before any datapack has loaded: no resources, generation 0. */
    public static final LoadoutCatalog EMPTY = new LoadoutCatalog(0, List.of(), Map.of(),
            Set.of(), Map.of(), Set.of());

    private final int generation;
    private final List<LoadoutRecord> records;
    private final Map<ResourceLocation, List<SpellcasterLoadout>> activeByType;
    private final Set<ResourceLocation> suppressedTypes;
    private final Map<ResourceLocation, Set<ResourceLocation>> suppressedProfessions;
    private final Set<ResourceLocation> suppressedProfessionlessTypes;

    public LoadoutCatalog(int generation,
                          List<LoadoutRecord> records,
                          Map<ResourceLocation, List<SpellcasterLoadout>> activeByType,
                          Set<ResourceLocation> suppressedTypes,
                          Map<ResourceLocation, Set<ResourceLocation>> suppressedProfessions,
                          Set<ResourceLocation> suppressedProfessionlessTypes) {
        this.generation = generation;
        this.records = List.copyOf(records);
        this.activeByType = Map.copyOf(activeByType);
        this.suppressedTypes = Set.copyOf(suppressedTypes);
        this.suppressedProfessions = Map.copyOf(suppressedProfessions);
        this.suppressedProfessionlessTypes = Set.copyOf(suppressedProfessionlessTypes);
    }

    /** Increments once per published catalog; stamped onto every managed caster built from it. */
    public int generation() {
        return generation;
    }

    /** Every discovered resource, in discovery order — including the ones that never loaded. */
    public List<LoadoutRecord> records() {
        return records;
    }

    /** The runtime map: entity type → the loadouts that survived override resolution. */
    public Map<ResourceLocation, List<SpellcasterLoadout>> activeByType() {
        return activeByType;
    }

    public Set<ResourceLocation> suppressedTypes() {
        return suppressedTypes;
    }

    public Map<ResourceLocation, Set<ResourceLocation>> suppressedProfessions() {
        return suppressedProfessions;
    }

    public Set<ResourceLocation> suppressedProfessionlessTypes() {
        return suppressedProfessionlessTypes;
    }

    /** @return the record for one logical resource id, or {@code null} if it was never discovered. */
    public LoadoutRecord record(ResourceLocation resourceId) {
        for (LoadoutRecord r : records) {
            if (r.resourceId().equals(resourceId)) {
                return r;
            }
        }
        return null;
    }

    /** @return every record declaring {@code entityType}, whatever its status. */
    public List<LoadoutRecord> recordsFor(ResourceLocation entityType) {
        List<LoadoutRecord> out = new ArrayList<>();
        for (LoadoutRecord r : records) {
            if (entityType.equals(r.entityType())) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Discovered/parsed/active/shadowed/suppressed/rejected/inapplicable counts, for the validation
     * header. An INAPPLICABLE file never parsed into a loadout either, but it is nobody's mistake, so
     * it is counted apart from the rejected ones.
     */
    public Counts counts() {
        int active = 0;
        int shadowed = 0;
        int suppressed = 0;
        int rejected = 0;
        int inapplicable = 0;
        for (LoadoutRecord r : records) {
            switch (r.status()) {
                case ACTIVE -> active++;
                case SHADOWED -> shadowed++;
                case SUPPRESSED -> suppressed++;
                case REJECTED -> rejected++;
                case INAPPLICABLE -> inapplicable++;
            }
        }
        return new Counts(records.size(), records.size() - rejected - inapplicable, active, shadowed,
                suppressed, rejected, inapplicable);
    }

    public record Counts(int discovered, int parsed, int active, int shadowed, int suppressed,
                         int rejected, int inapplicable) {}

    /** Every problem in the catalog at or above {@code min}, paired with the record that raised it. */
    public Map<LoadoutRecord, List<LoadoutProblem>> problems(LoadoutProblem.Severity min) {
        Map<LoadoutRecord, List<LoadoutProblem>> out = new LinkedHashMap<>();
        for (LoadoutRecord r : records) {
            List<LoadoutProblem> matching = new ArrayList<>();
            for (LoadoutProblem p : r.problems()) {
                if (p.severity().atLeast(min)) {
                    matching.add(p);
                }
            }
            if (!matching.isEmpty()) {
                out.put(r, matching);
            }
        }
        return out;
    }
}
