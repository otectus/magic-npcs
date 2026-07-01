package com.otectus.magicnpcs.core.loadout;

import com.otectus.magicnpcs.core.loadout.LoadoutEquipment.WeightedItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LoadoutEquipment#pick}: an empty list is null-safe, a single item is always
 * returned, and a 5:1 weighting picks the heavy item roughly 5× as often (seeded RNG, so stable).
 */
class WeightedItemPickTest {

    @SuppressWarnings("deprecation")
    private static final ResourceLocation HEAVY = new ResourceLocation("irons_spellbooks", "pyrium_staff");
    @SuppressWarnings("deprecation")
    private static final ResourceLocation LIGHT = new ResourceLocation("irons_spellbooks", "graybeard_staff");

    @Test
    void emptyListReturnsNull() {
        assertNull(LoadoutEquipment.pick(List.of(), RandomSource.create(1L)));
        assertNull(LoadoutEquipment.pick(null, RandomSource.create(1L)));
    }

    @Test
    void singleItemAlwaysPicked() {
        ResourceLocation only = LoadoutEquipment.pick(List.of(new WeightedItem(HEAVY, 1)), RandomSource.create(42L));
        assertEquals(HEAVY, only);
    }

    @Test
    void weightingFavoursHeavierItem() {
        List<WeightedItem> items = List.of(new WeightedItem(HEAVY, 5), new WeightedItem(LIGHT, 1));
        RandomSource rng = RandomSource.create(12345L);
        int heavy = 0;
        int total = 60_000;
        for (int i = 0; i < total; i++) {
            if (HEAVY.equals(LoadoutEquipment.pick(items, rng))) {
                heavy++;
            }
        }
        double heavyFraction = heavy / (double) total;
        // Expected 5/6 ≈ 0.833; allow a generous tolerance for RNG noise.
        assertTrue(heavyFraction > 0.78 && heavyFraction < 0.88,
                "5:1 weighting should pick the heavy item ~83% of the time, got " + heavyFraction);
    }
}
