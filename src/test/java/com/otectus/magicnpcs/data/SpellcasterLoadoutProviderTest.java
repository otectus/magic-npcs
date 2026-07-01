package com.otectus.magicnpcs.data;

import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the "no active bundled vanilla-mob loadout" requirement (0.5.0): the data generator must not
 * emit a {@code minecraft:skeleton} (or any other vanilla-mob) loadout, so the jar never changes
 * vanilla behaviour and never override-fights a modpack's own skeleton datapack. The shipped example
 * lives at {@code docs/loadouts/examples/skeleton.json} instead.
 */
@SuppressWarnings("deprecation")
class SpellcasterLoadoutProviderTest {

    @Test
    void doesNotShipAVanillaMobLoadout() {
        Map<String, SpellcasterLoadout> loadouts = SpellcasterLoadoutProvider.loadouts();
        assertFalse(loadouts.containsKey("skeleton"), "the jar must not ship an active skeleton loadout");
        for (SpellcasterLoadout loadout : loadouts.values()) {
            assertFalse("minecraft".equals(loadout.entityType().getNamespace()),
                    "no shipped loadout may target a vanilla (minecraft:) mob: " + loadout.entityType());
        }
    }

    @Test
    void stillShipsTheOptionalModLoadouts() {
        Map<String, SpellcasterLoadout> loadouts = SpellcasterLoadoutProvider.loadouts();
        // The Recruits/Guard Villagers examples remain (inert unless that mod is installed).
        assertTrue(loadouts.containsKey("recruit"));
        assertTrue(loadouts.containsKey("guard"));
        assertTrue(loadouts.get("guard").entityType().equals(new ResourceLocation("guardvillagers", "guard")));
    }
}
