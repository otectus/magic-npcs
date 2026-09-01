package com.otectus.magicnpcs.core.loadout;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * Optional per-loadout starting equipment: with probability {@link #chance()}, give a spawning
 * caster a weighted-random item in each populated hand. Backward-compatible — a loadout without an
 * {@code equipment} block leaves the global focus-gear behaviour
 * ({@code equipment.spawnWithGearChance} + the {@code magicnpcs:spell_focuses} tag) untouched.
 *
 * <p>Iron's-free: items are plain registry ids resolved on the integration side at spawn.
 *
 * @param mainhand   weighted main-hand candidates (empty = leave main hand alone)
 * @param offhand    weighted off-hand candidates (empty = leave off hand alone)
 * @param chance     probability [0..1] that this equipment is granted at all
 * @param onlyIfEmpty when true (default), only fill a hand that is currently empty
 */
public record LoadoutEquipment(
        List<WeightedItem> mainhand,
        List<WeightedItem> offhand,
        double chance,
        boolean onlyIfEmpty
) {
    /** One candidate item with a relative selection {@link #weight()} (clamped to ≥ 1 at parse time). */
    public record WeightedItem(ResourceLocation item, int weight) {}

    /**
     * Weighted-random pick from {@code items} (mirrors the spell weighted-pick in the casting goal).
     *
     * @return the chosen item id, or {@code null} if the list is empty
     */
    public static ResourceLocation pick(List<WeightedItem> items, RandomSource random) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        long total = 0L;
        for (WeightedItem w : items) {
            total = com.otectus.magicnpcs.core.util.Weights.saturatingAdd(
                    total, com.otectus.magicnpcs.core.util.Weights.normalize(w.weight()));
        }
        long roll = com.otectus.magicnpcs.core.util.Weights.roll(total, random);
        for (WeightedItem w : items) {
            roll -= com.otectus.magicnpcs.core.util.Weights.normalize(w.weight());
            if (roll < 0) {
                return w.item();
            }
        }
        return items.get(items.size() - 1).item();
    }
}
