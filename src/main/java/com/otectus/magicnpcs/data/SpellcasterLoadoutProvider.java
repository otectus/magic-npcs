package com.otectus.magicnpcs.data;

import com.google.gson.JsonObject;
import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.LoadoutJson;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Generates the shipped spellcaster loadouts under {@code data/magicnpcs/spellcasters/}.
 * This is the single source of truth for the bundled loadouts (replaces hand-authored
 * JSON). Vanilla-only: spell/entity ids are plain {@link ResourceLocation}s, so it runs
 * without Iron's on the classpath.
 */
public final class SpellcasterLoadoutProvider implements DataProvider {
    private static final String IRONS = "irons_spellbooks";

    private final PackOutput.PathProvider path;

    public SpellcasterLoadoutProvider(PackOutput output) {
        this.path = output.createPathProvider(PackOutput.Target.DATA_PACK, "spellcasters");
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        loadouts().forEach((name, loadout) -> {
            JsonObject json = LoadoutJson.toJson(loadout);
            futures.add(DataProvider.saveStable(cache, json, path.json(new ResourceLocation(MagicNpcs.MODID, name))));
        });
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * File-name → loadout. Order is preserved for stable, reviewable output.
     *
     * <p>Every shipped loadout here targets an <b>optional NPC mod's</b> entity (Recruits,
     * Guard Villagers): inert unless that mod is installed, so the jar never changes vanilla
     * mob behaviour without the pack author opting in. We deliberately do <b>not</b> ship a
     * {@code minecraft:skeleton} loadout — an active vanilla-mob default would silently pool
     * with (and override-fight) a modpack's own skeleton datapack. A documented example lives at
     * {@code docs/loadouts/examples/skeleton.json} for authors to copy.
     */
    static Map<String, SpellcasterLoadout> loadouts() {
        Map<String, SpellcasterLoadout> out = new LinkedHashMap<>();
        out.put("recruit", new SpellcasterLoadout(rec("recruit"), 80, 8, List.of(
                attack(irons("magic_missile"), 1, 3, 0.0, 16.0, 1.0),
                support(irons("heal"), 1, 1))));
        out.put("bowman", new SpellcasterLoadout(rec("bowman"), 90, 8, List.of(
                attack(irons("fire_arrow"), 1, 2, 4.0, 24.0, 1.0),
                attack(irons("firebolt"), 1, 2, 0.0, 20.0, 1.0))));
        out.put("crossbowman", new SpellcasterLoadout(rec("crossbowman"), 100, 8, List.of(
                attack(irons("lightning_bolt"), 1, 2, 4.0, 24.0, 1.5),
                attack(irons("icicle"), 1, 2, 0.0, 20.0, 1.0))));
        out.put("captain", new SpellcasterLoadout(rec("captain"), 140, 10, List.of(
                attack(irons("fireball"), 2, 2, 6.0, 24.0, 4.0),
                support(irons("haste"), 1, 1),
                support(irons("heal"), 1, 1))));
        out.put("guard", new SpellcasterLoadout(new ResourceLocation("guardvillagers", "guard"), 110, 9, List.of(
                attack(irons("magic_missile"), 1, 3, 0.0, 18.0, 1.0),
                attack(irons("guiding_bolt"), 1, 2, 4.0, 22.0, 1.0),
                support(irons("heal"), 1, 1))));
        return out;
    }

    private static LoadoutEntry attack(ResourceLocation spell, int level, int weight, double min, double max, double safety) {
        return new LoadoutEntry(spell, level, weight, min, max, safety, LoadoutEntry.Role.ATTACK);
    }

    private static LoadoutEntry support(ResourceLocation spell, int level, int weight) {
        return new LoadoutEntry(spell, level, weight, 0.0, 20.0, 1.5, LoadoutEntry.Role.SUPPORT);
    }

    private static ResourceLocation irons(String path) { return new ResourceLocation(IRONS, path); }
    private static ResourceLocation rec(String path) { return new ResourceLocation("recruits", path); }

    @Override
    public String getName() {
        return "Magic NPCs spellcaster loadouts";
    }
}
