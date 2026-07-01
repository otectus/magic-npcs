package com.otectus.magicnpcs.core.loadout;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LoadoutManager#applyOverrides}: the load-time resolution of the 0.5.0 {@code replace}
 * flag against the 0.4.0 pooling behaviour. Pure logic, no Minecraft runtime.
 */
@SuppressWarnings("deprecation")
class LoadoutResolveTest {

    private static final ResourceLocation SKELETON = new ResourceLocation("minecraft", "skeleton");
    private static final ResourceLocation MAGIC_MISSILE = new ResourceLocation("irons_spellbooks", "magic_missile");

    private static SpellcasterLoadout loadout(String source, ResourceLocation profession, boolean replace) {
        LoadoutEntry entry = new LoadoutEntry(MAGIC_MISSILE, 1, 1, 0.0, 20.0, 1.5, LoadoutEntry.Role.ATTACK);
        return new SpellcasterLoadout(SKELETON, profession, 100.0, 10.0, List.of(entry),
                null, null, 1, new ResourceLocation(source), replace);
    }

    @Test
    void noReplaceKeepsTheWholePool() {
        List<SpellcasterLoadout> raw = List.of(
                loadout("magicnpcs:skeleton", null, false),
                loadout("fortuna_rpg:skeleton", null, false));
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(raw);
        // Both pooled; the list is returned untouched (same instance, the no-override fast path).
        assertSame(raw, resolved);
        assertEquals(2, resolved.size());
    }

    @Test
    void replaceDropsLowerPriorityLoadoutsForTheSameKey() {
        SpellcasterLoadout jar = loadout("magicnpcs:skeleton", null, false);
        SpellcasterLoadout pack = loadout("fortuna_rpg:skeleton", null, true);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(jar, pack));
        assertEquals(1, resolved.size());
        assertSame(pack, resolved.get(0));
    }

    @Test
    void multipleReplaceLoadoutsPoolAmongThemselves() {
        SpellcasterLoadout nonReplace = loadout("magicnpcs:skeleton", null, false);
        SpellcasterLoadout replaceA = loadout("pack_a:skeleton", null, true);
        SpellcasterLoadout replaceB = loadout("pack_b:skeleton", null, true);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(nonReplace, replaceA, replaceB));
        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(replaceA) && resolved.contains(replaceB));
        assertTrue(!resolved.contains(nonReplace));
    }

    @Test
    void professionlessReplaceDoesNotClearProfessionScopedLoadouts() {
        // A profession-less replace and a profession-scoped loadout are different effective keys.
        ResourceLocation cleric = new ResourceLocation("minecraft", "cleric");
        SpellcasterLoadout generic = loadout("pack_a:skeleton", null, true);
        SpellcasterLoadout professionScoped = loadout("pack_b:skeleton", cleric, false);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(generic, professionScoped));
        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(generic) && resolved.contains(professionScoped));
    }

    @Test
    void replaceIsScopedToItsOwnProfessionGroup() {
        ResourceLocation cleric = new ResourceLocation("minecraft", "cleric");
        SpellcasterLoadout genericA = loadout("pack_a:skeleton", null, false);
        SpellcasterLoadout genericB = loadout("pack_b:skeleton", null, false);
        SpellcasterLoadout clericReplace = loadout("pack_c:skeleton", cleric, true);
        SpellcasterLoadout clericOther = loadout("pack_d:skeleton", cleric, false);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(
                List.of(genericA, genericB, clericReplace, clericOther));
        // The cleric group is overridden to just the replace one; the profession-less group is untouched.
        assertEquals(3, resolved.size());
        assertTrue(resolved.contains(genericA) && resolved.contains(genericB) && resolved.contains(clericReplace));
        assertTrue(!resolved.contains(clericOther));
    }
}
