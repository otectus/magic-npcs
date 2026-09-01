package com.otectus.magicnpcs.core.loadout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.LoadoutData;
import com.otectus.magicnpcs.core.util.Weights;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads spellcaster loadouts from {@code data/<ns>/spellcasters/*.json}. Each file declares the entity
 * type it applies to, so loadout presence is the opt-in (no separate tag). A loadout may optionally
 * declare a villager {@code profession} to scope it to one profession; multiple loadouts may therefore
 * target one entity type. Iron's-free — spell ids are resolved to Iron's spells on the integration
 * side, so this loads fine even without Iron's installed.
 *
 * <p>Load-time precedence for one entity type + profession key (0.6.0, ADR 0003):
 * <b>source tier</b> (a datapack beats a mod jar) → <b>{@code replace}</b> → <b>pool</b>.
 * A loadout with {@code "enabled": false} removes itself, and with {@code replace} it suppresses its
 * whole group.
 *
 * <p><b>0.6.2 (audit VAL-001).</b> Loading now publishes a whole {@link LoadoutCatalog}: every
 * discovered resource keeps a {@link LoadoutRecord} whatever happened to it, so a rejected file is
 * visible to {@code /magicnpcs validate} instead of surviving only as a log line. The catalog is
 * swapped in atomically at the end of the pass and carries a {@link LoadoutCatalog#generation()} that
 * managed casters are stamped with. A catastrophic failure keeps the last known-good catalog rather
 * than replacing the runtime map with a partial one.
 */
public class LoadoutManager extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "spellcasters";
    private static final Gson GSON = new GsonBuilder().create();

    /** @deprecated moved to {@link LoadoutParser#MAX_RECENT_DAMAGE_WINDOW}; kept for existing tests. */
    @Deprecated
    static final int MAX_RECENT_DAMAGE_WINDOW = LoadoutParser.MAX_RECENT_DAMAGE_WINDOW;

    /** The published catalog, swapped wholesale on reload; read from the server thread. */
    private static volatile LoadoutCatalog catalog = LoadoutCatalog.EMPTY;

    private static final AtomicInteger GENERATION = new AtomicInteger();

    public LoadoutManager() {
        super(GSON, FOLDER);
    }

    /** The complete outcome of the last load, including resources that never became active. */
    public static LoadoutCatalog catalog() {
        return catalog;
    }

    /** The generation of the currently published catalog; stamped onto every managed caster. */
    public static int generation() {
        return catalog.generation();
    }

    /** The current immutable loadout snapshot, keyed by entity type — for inspection/validation. */
    public static Map<ResourceLocation, List<SpellcasterLoadout>> snapshot() {
        return catalog.activeByType();
    }

    /** The (post-override) loadouts declared for {@code entityType}, or an empty list if none. */
    public static List<SpellcasterLoadout> loadoutsFor(ResourceLocation entityType) {
        return catalog.activeByType().getOrDefault(entityType, List.of());
    }

    /**
     * Cheap "could this type ever have a loadout?" test, for hot paths that must not pay for full
     * resolution (the per-mob tick handler — backlog B11).
     */
    public static boolean hasAnyFor(ResourceLocation entityType) {
        return catalog.activeByType().containsKey(entityType);
    }

    /**
     * Resolve the loadout that applies to {@code mob}, persisting the pool pick and the one-time
     * {@code caster_chance} roll. Profession-specific loadouts win over generic (profession-less) ones;
     * whichever bucket applies forms a <em>pool</em>, filtered by each loadout's context
     * {@link SpellcasterLoadout#conditions() conditions}. When more than one variant remains, one is
     * picked by {@code pool_weight} and that choice is persisted per-NPC ({@link LoadoutData}) so it
     * stays stable across reloads.
     *
     * @return the applicable loadout, or {@code null} if none (= not a spellcaster here/now)
     */
    public static SpellcasterLoadout resolve(Mob mob) {
        return assign(mob).loadout();
    }

    /**
     * As {@link #resolve(Mob)}, but returns the full {@link LoadoutResolution} so the caller can tell
     * "no loadout exists" apart from "one exists but a toggle/condition is blocking it" — the
     * difference between falling through to magic-school assignment and deliberately not casting.
     */
    public static LoadoutResolution assign(Mob mob) {
        return resolve(mob, true);
    }

    /**
     * Read-only resolution: identical logic to {@link #resolve(Mob)} but it writes no entity NBT and
     * draws nothing from the mob's RNG, so a diagnostic command cannot perturb the world (backlog B5).
     * A mob whose pool pick or {@code caster_chance} roll has not been persisted yet reports
     * {@link LoadoutResolution.Status#POOL_UNRESOLVED} / {@code CASTER_CHANCE_UNROLLED} rather than
     * inventing one.
     */
    public static LoadoutResolution peek(Mob mob) {
        return resolve(mob, false);
    }

    private static LoadoutResolution resolve(Mob mob, boolean persist) {
        ResourceLocation type = EntityType.getKey(mob.getType());
        List<SpellcasterLoadout> candidates = catalog.activeByType().get(type);
        if (candidates == null || candidates.isEmpty()) {
            return LoadoutResolution.none(catalog.suppressedTypes().contains(type)
                    ? LoadoutResolution.Status.ALL_LOADOUTS_DISABLED
                    : LoadoutResolution.Status.NO_LOADOUT);
        }
        // Config-level kill switches, applied here so every consumer (goal injection, the mana tick,
        // the diagnostic commands) inherits them rather than each re-implementing the gate (B4).
        if (MagicNpcsConfig.isEntityTypeDisabled(type)) {
            return LoadoutResolution.failed(LoadoutResolution.Status.TYPE_DISABLED_BY_CONFIG, candidates, null);
        }
        if (!MagicNpcsConfig.isLoadoutEnabledFor(type)) {
            String detail = MagicNpcsConfig.ownerModLoaded(type)
                    ? "the mod is installed — set compat." + type.getNamespace()
                      + " = true in config/magicnpcs-common.toml"
                    : "the owning mod is not installed";
            return LoadoutResolution.failed(LoadoutResolution.Status.COMPAT_TOGGLE_OFF, candidates, detail);
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
            // "Nothing matched" and "the author switched this bucket off" must not look the same:
            // only the latter stops the caller falling through to magic-school assignment, which
            // would hand the mob spells anyway and make the off switch look broken.
            boolean bucketSuppressed = profession != null
                    && catalog.suppressedProfessions().getOrDefault(type, Set.of()).contains(profession);
            if (bucketSuppressed) {
                return LoadoutResolution.failed(LoadoutResolution.Status.ALL_LOADOUTS_DISABLED, candidates,
                        "a loadout for " + entityTypeAndProfession(type, profession)
                                + " sets \"enabled\": false with \"replace\": true");
            }
            if (catalog.suppressedProfessionlessTypes().contains(type)) {
                return LoadoutResolution.failed(LoadoutResolution.Status.ALL_LOADOUTS_DISABLED, candidates,
                        "the profession-less loadout bucket for " + type
                                + " is switched off (\"enabled\": false with \"replace\": true)");
            }
            return LoadoutResolution.failed(LoadoutResolution.Status.NO_PROFESSION_MATCH, candidates, null);
        }

        // Context gate: keep only loadouts whose conditions currently hold.
        List<SpellcasterLoadout> passing = new ArrayList<>(pool.size());
        for (SpellcasterLoadout l : pool) {
            if (l.conditions() == null || l.conditions().test(mob)) {
                passing.add(l);
            }
        }
        if (passing.isEmpty()) {
            return LoadoutResolution.failed(LoadoutResolution.Status.CONDITIONS_FAILED, pool, null);
        }

        SpellcasterLoadout chosen;
        if (passing.size() == 1) {
            chosen = passing.get(0); // single match — no choice to persist
        } else {
            // Several variants apply: sticky weighted pick so the mob keeps one variant across reloads.
            chosen = null;
            ResourceLocation stored = LoadoutData.getSource(mob);
            if (stored != null) {
                for (SpellcasterLoadout l : passing) {
                    if (stored.equals(l.source())) {
                        chosen = l;
                        break;
                    }
                }
            }
            if (chosen == null) {
                if (!persist) {
                    return LoadoutResolution.failed(LoadoutResolution.Status.POOL_UNRESOLVED, passing, null);
                }
                chosen = weightedPick(passing, mob.getRandom());
                if (chosen.source() != null) {
                    LoadoutData.setSource(mob, chosen.source());
                }
            }
        }
        return applyCasterChance(mob, chosen, passing, persist);
    }

    /**
     * Apply the loadout-level {@code caster_chance} (0.6.0, restored in 0.6.2 — audit REL-002): the
     * probability that <em>this individual</em> is a caster at all.
     *
     * <p>The roll is made once and persisted, never repeated. A per-reload roll would let
     * {@code /reload} and chunk loading flip an NPC into and out of being a caster, which is exactly
     * the class of "the school comes and goes" report the sticky pool pick already exists to avoid.
     */
    private static LoadoutResolution applyCasterChance(Mob mob, SpellcasterLoadout chosen,
                                                       List<SpellcasterLoadout> pool, boolean persist) {
        Double chance = chosen.casterChance();
        if (chance == null || chance >= 1.0) {
            return LoadoutResolution.of(chosen, pool);
        }
        Boolean rolled = LoadoutData.getCasterRoll(mob);
        if (rolled == null) {
            if (!persist) {
                return LoadoutResolution.failed(LoadoutResolution.Status.CASTER_CHANCE_UNROLLED, pool, null);
            }
            rolled = mob.getRandom().nextDouble() < chance;
            LoadoutData.setCasterRoll(mob, rolled);
        }
        if (!rolled) {
            return LoadoutResolution.failed(LoadoutResolution.Status.NOT_A_CASTER, pool,
                    "caster_chance=" + chance + " in " + chosen.source());
        }
        return LoadoutResolution.of(chosen, pool);
    }

    private static SpellcasterLoadout weightedPick(List<SpellcasterLoadout> pool, RandomSource random) {
        long total = 0L;
        for (SpellcasterLoadout l : pool) {
            total = Weights.saturatingAdd(total, Weights.normalize(l.poolWeight()));
        }
        long roll = Weights.roll(total, random);
        for (SpellcasterLoadout l : pool) {
            roll -= Weights.normalize(l.poolWeight());
            if (roll < 0) {
                return l;
            }
        }
        return pool.get(pool.size() - 1);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager rm, ProfilerFiller profiler) {
        try {
            catalog = build(files, rm);
            logLoadSummary(catalog);
        } catch (Exception ex) {
            // Never leave the runtime map half-built: a partial catalog would silently disarm mobs
            // that were casting a moment ago. Keep the last known-good one and say so loudly.
            MagicNpcs.LOGGER.error("Spellcaster loadout reload failed; keeping the previous catalog "
                    + "(generation {}). Fix the error and run /reload again.", catalog.generation(), ex);
        }
    }

    /** Package-private so a loader test can drive a whole reload pass without a live server. */
    static LoadoutCatalog build(Map<ResourceLocation, JsonElement> files, ResourceManager rm) {
        boolean strict = MagicNpcsConfig.strictLoadoutSchema();
        List<LoadoutRecord> records = new ArrayList<>(files.size());
        Map<ResourceLocation, List<LoadoutRecord>> byType = new LinkedHashMap<>();

        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation id = entry.getKey();
            PackInfo pack = packInfoOf(rm, id);
            Shadowed inferred = inferFromShadowedResource(rm, id);
            LoadoutRecord record = LoadoutParser.parse(id, entry.getValue(), pack.packId(), pack.tier(),
                    strict, inferred.entityType(), inferred.profession());
            records.add(record);
            if (record.entityType() != null) {
                byType.computeIfAbsent(record.entityType(), k -> new ArrayList<>()).add(record);
            }
        }

        Map<ResourceLocation, List<SpellcasterLoadout>> active = new HashMap<>();
        Set<ResourceLocation> suppressedTypes = new HashSet<>();
        Map<ResourceLocation, Set<ResourceLocation>> suppressedProfessions = new HashMap<>();
        Set<ResourceLocation> professionlessSuppressed = new HashSet<>();
        Map<ResourceLocation, LoadoutRecord> finalStatus = new LinkedHashMap<>();

        byType.forEach((type, typeRecords) -> {
            List<SpellcasterLoadout> parsed = new ArrayList<>();
            for (LoadoutRecord r : typeRecords) {
                if (r.loadout() != null) {
                    parsed.add(r.loadout());
                }
            }
            OverrideResult override = resolveOverrides(parsed);
            List<SpellcasterLoadout> resolved = override.loadouts();
            Set<ResourceLocation> winners = new HashSet<>();
            for (SpellcasterLoadout l : resolved) {
                if (l.source() != null) {
                    winners.add(l.source());
                }
            }
            for (LoadoutRecord r : typeRecords) {
                LoadoutRecord updated = classify(r, winners, resolved.size());
                finalStatus.put(r.resourceId(), updated);
            }
            if (!override.suppressedProfessions().isEmpty()) {
                suppressedProfessions.put(type, Set.copyOf(override.suppressedProfessions()));
            }
            if (override.suppressedProfessionless()) {
                professionlessSuppressed.add(type);
            }
            if (resolved.isEmpty()) {
                suppressedTypes.add(type);
            } else {
                active.put(type, List.copyOf(resolved));
            }
        });

        List<LoadoutRecord> finalRecords = new ArrayList<>(records.size());
        for (LoadoutRecord r : records) {
            finalRecords.add(finalStatus.getOrDefault(r.resourceId(), r));
        }
        return new LoadoutCatalog(GENERATION.incrementAndGet(), finalRecords, active, suppressedTypes,
                suppressedProfessions, professionlessSuppressed);
    }

    /**
     * Decide a parsed record's final status now that override resolution has run, and attach the
     * problem that explains it. This is what makes "your file loaded, but another pack's file won" a
     * visible outcome rather than an invisible one.
     */
    private static LoadoutRecord classify(LoadoutRecord r, Set<ResourceLocation> winners, int keptCount) {
        if (r.status() == LoadoutRecord.Status.REJECTED) {
            return r;
        }
        if (r.loadout() != null && !r.loadout().enabled()) {
            return r.withStatus(LoadoutRecord.Status.SUPPRESSED);
        }
        if (winners.contains(r.resourceId())) {
            LoadoutRecord active = r.withStatus(LoadoutRecord.Status.ACTIVE);
            if (keptCount > 1) {
                return active.withProblem(LoadoutProblem.warning("POOLED", "",
                        "pooled with " + (keptCount - 1) + " other loadout(s) for "
                                + r.effectiveKey() + " — each NPC sticky-picks one by pool_weight",
                        "add \"replace\": true to the loadout that should win"));
            }
            return active;
        }
        return r.withStatus(LoadoutRecord.Status.SHADOWED)
                .withProblem(new LoadoutProblem(LoadoutProblem.Severity.INFO, "SHADOWED", "",
                        "another loadout owns " + r.effectiveKey()
                                + " — a datapack outranks a mod jar, and \"replace\": true outranks pooling",
                        "run /magicnpcs loadout id " + r.entityType() + " to see which one won"));
    }

    /** Where a resource came from, as the {@code ResourceManager} reports it. */
    private record PackInfo(String packId, LoadoutSourceTier tier) {}

    /** The entity type/profession a bare {@code enabled:false} stub inherits from what it shadows. */
    private record Shadowed(ResourceLocation entityType, ResourceLocation profession) {
        static final Shadowed NONE = new Shadowed(null, null);
    }

    /**
     * Resolve the source tier of one loadout file by asking the {@link ResourceManager} which pack
     * supplied it. Forge builds a mod's data pack as a {@code PathPackResources} with
     * {@code isBuiltin = true}, so {@code Resource.isBuiltin()} identifies jar-shipped data; a
     * {@code mod:} pack id is accepted as an independent second signal. If neither can be determined,
     * the file is treated as a datapack — which degrades resolution to exactly the 0.5.0
     * {@code replace}-then-pool behaviour rather than mis-ranking anything.
     */
    private static PackInfo packInfoOf(ResourceManager rm, ResourceLocation fileId) {
        try {
            Optional<Resource> resource = rm.getResource(preparedPathFor(fileId));
            if (resource.isEmpty()) {
                return new PackInfo("<unknown>", LoadoutSourceTier.DATAPACK);
            }
            Resource res = resource.get();
            String packId = res.sourcePackId();
            boolean fromModJar = res.isBuiltin() || (packId != null && packId.startsWith("mod:"));
            return new PackInfo(packId == null ? "<unknown>" : packId,
                    fromModJar ? LoadoutSourceTier.BUILT_IN : LoadoutSourceTier.DATAPACK);
        } catch (Exception ex) {
            MagicNpcs.LOGGER.debug("Could not determine the source pack of loadout {} ({}); "
                    + "treating it as a datapack", fileId, ex.toString());
            return new PackInfo("<unknown>", LoadoutSourceTier.DATAPACK);
        }
    }

    /**
     * Look under the winning resource at the same data path for one that declares an
     * {@code entity_type}, so a bare {@code { "enabled": false }} stub can inherit the key of the
     * loadout it is switching off.
     *
     * <p>The documented way to disable a shipped loadout has always been to drop a stub at the same
     * path. 0.6.1's parser demanded {@code entity_type} before it read {@code enabled}, so that exact
     * documented file was rejected (audit, "{@code enabled: false} incompatibility"). Rather than break
     * every existing pack by requiring the field, the loader reads the resource stack the vanilla
     * loader already collapsed and infers the key — recording an {@code INFERRED_ENTITY_TYPE} note so
     * the inference is visible in {@code /magicnpcs validate}.
     */
    private static Shadowed inferFromShadowedResource(ResourceManager rm, ResourceLocation fileId) {
        try {
            List<Resource> stack = rm.getResourceStack(preparedPathFor(fileId));
            // getResourceStack is lowest-priority first, so walk backwards from the runner-up.
            for (int i = stack.size() - 2; i >= 0; i--) {
                try (BufferedReader reader = stack.get(i).openAsReader()) {
                    JsonElement element = JsonParser.parseReader(reader);
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    if (!object.has(LoadoutJson.ENTITY_TYPE)) {
                        continue;
                    }
                    ResourceLocation type =
                            ResourceLocation.tryParse(object.get(LoadoutJson.ENTITY_TYPE).getAsString());
                    ResourceLocation profession = object.has(LoadoutJson.PROFESSION)
                            ? ResourceLocation.tryParse(object.get(LoadoutJson.PROFESSION).getAsString())
                            : null;
                    if (type != null) {
                        return new Shadowed(type, profession);
                    }
                }
            }
        } catch (Exception ex) {
            MagicNpcs.LOGGER.debug("Could not inspect the resource stack under loadout {} ({})", fileId, ex.toString());
        }
        return Shadowed.NONE;
    }

    /**
     * {@code spellcasters/<path>.json} for a logical resource id. Mirrors what
     * {@link SimpleJsonResourceReloadListener} does internally; written out here because the vanilla
     * helper is not accessible from a static context.
     */
    private static ResourceLocation preparedPathFor(ResourceLocation fileId) {
        return new ResourceLocation(fileId.getNamespace(), FOLDER + "/" + fileId.getPath() + ".json");
    }

    /**
     * Resolve overrides for one entity type's raw loadout list. Loadouts are grouped by their effective
     * key — the optional {@code profession} (a profession-less loadout is its own group) — and within
     * each group, in order:
     *
     * <ol>
     *   <li><b>tier</b> (0.6.0): only loadouts at the highest {@link LoadoutSourceTier} present survive,
     *       so a datapack beats a mod jar with no flag to discover (ADR 0003);</li>
     *   <li><b>{@code replace}</b> (0.5.0): if any survivor sets it, only replace-marked ones remain;</li>
     *   <li><b>{@code enabled}</b> (0.6.0): a disabled loadout always removes itself, and a disabled
     *       <em>replace</em> loadout suppresses its whole group — the datapack "off switch".</li>
     * </ol>
     *
     * Groups with no override are left intact (the 0.4.0 pooling behaviour). Input order is preserved.
     * Deterministic and independent of datapack load order, and pure (no logging, no config reads) so it
     * is unit-testable without a Minecraft runtime.
     */
    static List<SpellcasterLoadout> applyOverrides(List<SpellcasterLoadout> raw) {
        return resolveOverrides(raw).loadouts();
    }

    /**
     * What {@link #applyOverrides} decided, including <em>which</em> profession buckets were switched
     * off. The caller needs that detail: a suppressed bucket must report "deliberately disabled"
     * rather than "no loadout matched", or a datapack off switch scoped to one profession silently
     * falls through to magic-school assignment and the mob casts anyway — exactly the trap the
     * type-level suppression set already avoids.
     *
     * @param suppressedProfessions professions whose bucket was killed by a disabled {@code replace}
     * @param suppressedProfessionless true if the profession-less bucket was killed the same way
     */
    record OverrideResult(List<SpellcasterLoadout> loadouts,
                          Set<ResourceLocation> suppressedProfessions,
                          boolean suppressedProfessionless) {}

    static OverrideResult resolveOverrides(List<SpellcasterLoadout> raw) {
        // Highest tier present per effective key.
        Map<ResourceLocation, LoadoutSourceTier> bestTier = new HashMap<>();
        LoadoutSourceTier bestProfessionless = null;
        for (SpellcasterLoadout l : raw) {
            if (l.profession() == null) {
                bestProfessionless = maxTier(bestProfessionless, l.tier());
            } else {
                bestTier.merge(l.profession(), l.tier(), LoadoutManager::maxTier);
            }
        }

        // Pass 1: drop anything below its group's best tier, and anything disabled-but-not-replace.
        List<SpellcasterLoadout> tiered = new ArrayList<>(raw.size());
        Set<ResourceLocation> suppressedProfessions = new HashSet<>();
        boolean suppressProfessionless = false;
        for (SpellcasterLoadout l : raw) {
            LoadoutSourceTier groupBest = l.profession() == null ? bestProfessionless : bestTier.get(l.profession());
            if (groupBest != null && groupBest.outranks(l.tier())) {
                continue; // a higher-tier source owns this key
            }
            if (!l.enabled()) {
                // A disabled loadout is always inert; a disabled *replace* also kills its whole group.
                if (l.replace()) {
                    if (l.profession() == null) {
                        suppressProfessionless = true;
                    } else {
                        suppressedProfessions.add(l.profession());
                    }
                }
                continue;
            }
            tiered.add(l);
        }
        if (suppressProfessionless || !suppressedProfessions.isEmpty()) {
            List<SpellcasterLoadout> kept = new ArrayList<>(tiered.size());
            for (SpellcasterLoadout l : tiered) {
                boolean suppressed = l.profession() == null
                        ? suppressProfessionless
                        : suppressedProfessions.contains(l.profession());
                if (!suppressed) {
                    kept.add(l);
                }
            }
            tiered = kept;
        }

        // Pass 2: 0.5.0 replace semantics, within the surviving tier.
        boolean replaceProfessionless = false;
        Set<ResourceLocation> replacedProfessions = new HashSet<>();
        for (SpellcasterLoadout l : tiered) {
            if (l.replace()) {
                if (l.profession() == null) {
                    replaceProfessionless = true;
                } else {
                    replacedProfessions.add(l.profession());
                }
            }
        }
        if (!replaceProfessionless && replacedProfessions.isEmpty()) {
            // Nothing overrides — keep the (possibly tier-filtered) pool. Preserve the 0.5.0 identity
            // fast path so callers that compare instances still see the untouched list.
            return new OverrideResult(tiered.size() == raw.size() ? raw : tiered,
                    suppressedProfessions, suppressProfessionless);
        }
        List<SpellcasterLoadout> out = new ArrayList<>(tiered.size());
        for (SpellcasterLoadout l : tiered) {
            boolean groupHasReplace = l.profession() == null
                    ? replaceProfessionless
                    : replacedProfessions.contains(l.profession());
            if (!groupHasReplace || l.replace()) {
                out.add(l);
            }
        }
        return new OverrideResult(out, suppressedProfessions, suppressProfessionless);
    }

    /** "minecraft:villager (minecraft:cleric)" — names the effective key a message is about. */
    private static String entityTypeAndProfession(ResourceLocation type, ResourceLocation profession) {
        return profession == null ? type.toString() : type + " (" + profession + ")";
    }

    private static LoadoutSourceTier maxTier(LoadoutSourceTier a, LoadoutSourceTier b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.outranks(a) ? b : a;
    }

    /**
     * One-line startup/reload summary plus, when relevant, actionable warnings: rejected files (named,
     * with their first error), entity types whose loadouts are all suppressed, and compat toggles that
     * are off while the owning mod is installed.
     *
     * <p>The rejection lines matter most: before 0.6.2 a rejected file produced one terse
     * {@code Skipping invalid spellcaster loadout} line and vanished from every diagnostic, which is
     * how "the datapack is listed and /validate is clean, but nothing casts" happened.
     */
    private static void logLoadSummary(LoadoutCatalog published) {
        LoadoutCatalog.Counts counts = published.counts();
        MagicNpcs.LOGGER.info("Loaded spellcaster loadouts (generation {}): discovered {}, active {}, "
                        + "shadowed {}, suppressed {}, rejected {} — across {} entity type(s)",
                published.generation(), counts.discovered(), counts.active(), counts.shadowed(),
                counts.suppressed(), counts.rejected(), published.activeByType().size());

        for (LoadoutRecord r : published.records()) {
            if (r.status() != LoadoutRecord.Status.REJECTED) {
                continue;
            }
            MagicNpcs.LOGGER.error("Spellcaster loadout {} was REJECTED and is not in use:", r.describeSource());
            for (LoadoutProblem p : r.problems()) {
                if (p.severity() == LoadoutProblem.Severity.ERROR) {
                    MagicNpcs.LOGGER.error("    {}", p.describe());
                }
            }
            MagicNpcs.LOGGER.error("    Run /magicnpcs validate in game to see this again with the full list.");
        }
        for (LoadoutRecord r : published.records()) {
            for (LoadoutProblem p : r.problems()) {
                if (p.severity() == LoadoutProblem.Severity.WARNING) {
                    MagicNpcs.LOGGER.warn("Spellcaster loadout {}: {}", r.resourceId(), p.describe());
                }
            }
        }
        published.suppressedTypes().forEach(type ->
                MagicNpcs.LOGGER.info("Spellcaster {}: disabled — every loadout resolved away "
                        + "(\"enabled\": false). This type will not cast.", type));
        for (ResourceLocation type : published.activeByType().keySet()) {
            if (!MagicNpcsConfig.isLoadoutEnabledFor(type) && MagicNpcsConfig.ownerModLoaded(type)) {
                MagicNpcs.LOGGER.warn("Spellcaster {}: '{}' is installed but compat.{} is off, so its "
                                + "loadouts are inert. Set it to true in config/magicnpcs-common.toml.",
                        type, type.getNamespace(), type.getNamespace());
            }
            if (MagicNpcsConfig.isEntityTypeDisabled(type)) {
                MagicNpcs.LOGGER.info("Spellcaster {}: suppressed by general.disabledEntityTypes.", type);
            }
        }
    }

    /**
     * Publish a catalog built from in-code loadouts, for GameTests.
     *
     * <p>The 0.6.1 spellcasting tests constructed a loadout in code and inserted the goal by hand,
     * which is precisely why they stayed green while the datapack path was broken: nothing they did
     * went through resolution, override precedence, or reconciliation. Tests that need "a loadout now
     * exists for this type" publish it here instead, so they exercise the real runtime map and the
     * real generation counter.
     */
    public static void publishForTest(Map<ResourceLocation, List<SpellcasterLoadout>> active) {
        List<LoadoutRecord> records = new ArrayList<>();
        active.forEach((type, list) -> list.forEach(l -> records.add(new LoadoutRecord(
                l.source() == null ? new ResourceLocation("magicnpcs", "test") : l.source(),
                "<gametest>", l.tier(), LoadoutRecord.Status.ACTIVE, type, l.profession(), l,
                List.of(), l.contentHash()))));
        catalog = new LoadoutCatalog(GENERATION.incrementAndGet(), records, active,
                Set.of(), Map.of(), Set.of());
    }

    /**
     * Parse one loadout object the strict, throwing way.
     *
     * <p>Retained for the data generator and the parse unit tests, which want "this JSON is valid" as a
     * yes/no. The datapack loader uses {@link LoadoutParser} directly so it can keep every problem.
     *
     * @throws IllegalArgumentException naming the first error, as 0.5.0–0.6.1 did
     */
    static SpellcasterLoadout parse(JsonObject json, ResourceLocation source) {
        LoadoutRecord record = LoadoutParser.parse(source, json, "<in-code>", LoadoutSourceTier.DATAPACK,
                false, null, null, false);
        if (record.loadout() == null) {
            for (LoadoutProblem p : record.problems()) {
                if (p.severity() == LoadoutProblem.Severity.ERROR) {
                    throw new IllegalArgumentException(p.message()
                            + (p.suggestion() == null ? "" : " (" + p.suggestion() + ")"));
                }
            }
            throw new IllegalArgumentException("loadout could not be parsed");
        }
        return record.loadout();
    }
}
