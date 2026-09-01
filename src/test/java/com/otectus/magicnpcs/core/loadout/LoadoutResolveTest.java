package com.otectus.magicnpcs.core.loadout;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LoadoutManager#applyOverrides}: the load-time precedence chain
 * <b>source tier → {@code replace} → pool</b>.
 *
 * <p>Tier is the 0.6.0 addition (ADR 0003): a datapack loadout beats a mod-jar one for the same
 * entity type + profession with no flag to discover, which is what the "guard villagers use your
 * spells, not my JSON" report needed. {@code replace} keeps its 0.5.0 meaning <em>within</em> a tier.
 *
 * <p>Pure logic, no Minecraft runtime.
 */
@SuppressWarnings("deprecation")
class LoadoutResolveTest {

    private static final ResourceLocation SKELETON = new ResourceLocation("minecraft", "skeleton");
    private static final ResourceLocation MAGIC_MISSILE = new ResourceLocation("irons_spellbooks", "magic_missile");
    private static final ResourceLocation CLERIC = new ResourceLocation("minecraft", "cleric");

    private static SpellcasterLoadout loadout(String source, ResourceLocation profession, boolean replace) {
        return loadout(source, profession, replace, LoadoutSourceTier.DATAPACK, true);
    }

    private static SpellcasterLoadout loadout(String source, ResourceLocation profession, boolean replace,
                                              LoadoutSourceTier tier, boolean enabled) {
        LoadoutEntry entry = new LoadoutEntry(MAGIC_MISSILE, 1, 1, 0.0, 20.0, 1.5, LoadoutEntry.Role.ATTACK);
        return new SpellcasterLoadout(SKELETON, profession, 100.0, 10.0, List.of(entry),
                null, null, 1, new ResourceLocation(source), replace, enabled, tier, null,
                NativeAttackPolicy.COEXIST);
    }

    // --- 0.4.0/0.5.0 behaviour: pooling and replace, unchanged within one tier ---

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
        assertFalse(resolved.contains(nonReplace));
    }

    @Test
    void professionlessReplaceDoesNotClearProfessionScopedLoadouts() {
        // A profession-less replace and a profession-scoped loadout are different effective keys.
        SpellcasterLoadout generic = loadout("pack_a:skeleton", null, true);
        SpellcasterLoadout professionScoped = loadout("pack_b:skeleton", CLERIC, false);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(generic, professionScoped));
        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(generic) && resolved.contains(professionScoped));
    }

    @Test
    void replaceIsScopedToItsOwnProfessionGroup() {
        SpellcasterLoadout genericA = loadout("pack_a:skeleton", null, false);
        SpellcasterLoadout genericB = loadout("pack_b:skeleton", null, false);
        SpellcasterLoadout clericReplace = loadout("pack_c:skeleton", CLERIC, true);
        SpellcasterLoadout clericOther = loadout("pack_d:skeleton", CLERIC, false);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(
                List.of(genericA, genericB, clericReplace, clericOther));
        // The cleric group is overridden to just the replace one; the profession-less group is untouched.
        assertEquals(3, resolved.size());
        assertTrue(resolved.contains(genericA) && resolved.contains(genericB) && resolved.contains(clericReplace));
        assertFalse(resolved.contains(clericOther));
    }

    // --- 0.6.0: source tiering (ADR 0003) ---

    @Test
    void datapackLoadoutBeatsAJarLoadoutWithNoReplaceFlag() {
        // The W6 case: the jar ships guardvillagers:guard, the author writes their own. Theirs wins.
        SpellcasterLoadout jar = loadout("magicnpcs:guard", null, false, LoadoutSourceTier.BUILT_IN, true);
        SpellcasterLoadout pack = loadout("mypack:guard", null, false, LoadoutSourceTier.DATAPACK, true);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(jar, pack));
        assertEquals(1, resolved.size());
        assertSame(pack, resolved.get(0));
    }

    @Test
    void severalDatapackLoadoutsStillPoolWithEachOtherOnceTheJarIsOut() {
        SpellcasterLoadout jar = loadout("magicnpcs:guard", null, false, LoadoutSourceTier.BUILT_IN, true);
        SpellcasterLoadout packA = loadout("pack_a:guard", null, false, LoadoutSourceTier.DATAPACK, true);
        SpellcasterLoadout packB = loadout("pack_b:guard", null, false, LoadoutSourceTier.DATAPACK, true);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(jar, packA, packB));
        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(packA) && resolved.contains(packB));
        assertFalse(resolved.contains(jar));
    }

    @Test
    void tierIsAppliedBeforeReplace() {
        // A jar loadout marked replace must NOT beat a datapack loadout: tier is the outer rule.
        SpellcasterLoadout jarReplace = loadout("magicnpcs:guard", null, true, LoadoutSourceTier.BUILT_IN, true);
        SpellcasterLoadout pack = loadout("mypack:guard", null, false, LoadoutSourceTier.DATAPACK, true);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(jarReplace, pack));
        assertEquals(1, resolved.size());
        assertSame(pack, resolved.get(0));
    }

    @Test
    void tierOnlyAppliesWithinTheSameProfessionKey() {
        // A datapack's cleric-scoped loadout must not evict the jar's generic one for other villagers.
        SpellcasterLoadout jarGeneric = loadout("magicnpcs:villager", null, false, LoadoutSourceTier.BUILT_IN, true);
        SpellcasterLoadout packCleric = loadout("mypack:cleric", CLERIC, false, LoadoutSourceTier.DATAPACK, true);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(jarGeneric, packCleric));
        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(jarGeneric) && resolved.contains(packCleric));
    }

    // --- 0.6.0: "enabled": false as a datapack off switch (W3c) ---

    @Test
    void disabledLoadoutRemovesItselfButLeavesTheRestOfThePool() {
        SpellcasterLoadout off = loadout("pack_a:skeleton", null, false, LoadoutSourceTier.DATAPACK, false);
        SpellcasterLoadout on = loadout("pack_b:skeleton", null, false, LoadoutSourceTier.DATAPACK, true);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(off, on));
        assertEquals(1, resolved.size());
        assertSame(on, resolved.get(0));
    }

    @Test
    void disabledReplaceLoadoutSuppressesItsWholeGroup() {
        // One file with {"enabled": false, "replace": true} switches the entity type off entirely.
        SpellcasterLoadout kill = loadout("mypack:off", null, true, LoadoutSourceTier.DATAPACK, false);
        SpellcasterLoadout other = loadout("pack_b:skeleton", null, false, LoadoutSourceTier.DATAPACK, true);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(kill, other));
        assertTrue(resolved.isEmpty(), "a disabled replace loadout must suppress the whole group");
    }

    @Test
    void disabledReplaceDoesNotReachAcrossProfessionGroups() {
        SpellcasterLoadout killCleric = loadout("mypack:off", CLERIC, true, LoadoutSourceTier.DATAPACK, false);
        SpellcasterLoadout generic = loadout("pack_b:villager", null, false, LoadoutSourceTier.DATAPACK, true);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(killCleric, generic));
        assertEquals(1, resolved.size());
        assertSame(generic, resolved.get(0));
    }

    @Test
    void aSuppressedProfessionBucketIsReportedSoItCannotFallThroughToSchools() {
        // The off switch must be distinguishable from "nothing matched". Only the former stops the
        // caller falling through to magic-school assignment — otherwise a datapack that switches the
        // cleric bucket off gets a cleric casting school spells anyway, and the switch looks broken.
        SpellcasterLoadout killCleric = loadout("mypack:off", CLERIC, true, LoadoutSourceTier.DATAPACK, false);
        SpellcasterLoadout otherProfession = loadout("pack_b:villager",
                new ResourceLocation("minecraft", "farmer"), false, LoadoutSourceTier.DATAPACK, true);
        LoadoutManager.OverrideResult result =
                LoadoutManager.resolveOverrides(List.of(killCleric, otherProfession));
        assertTrue(result.suppressedProfessions().contains(CLERIC),
                "the cleric bucket was switched off and must be reported as such");
        assertFalse(result.suppressedProfessionless(),
                "a profession-scoped off switch must not claim the fallback bucket too");
    }

    @Test
    void aSuppressedProfessionlessBucketIsReportedSeparately() {
        SpellcasterLoadout killGeneric = loadout("mypack:off", null, true, LoadoutSourceTier.DATAPACK, false);
        LoadoutManager.OverrideResult result = LoadoutManager.resolveOverrides(List.of(killGeneric));
        assertTrue(result.suppressedProfessionless());
        assertTrue(result.suppressedProfessions().isEmpty());
    }

    @Test
    void aDisabledJarLoadoutDoesNotSuppressADatapackOne() {
        // Tier runs first, so the jar entry is gone before its "replace" could suppress anything.
        SpellcasterLoadout jarKill = loadout("magicnpcs:guard", null, true, LoadoutSourceTier.BUILT_IN, false);
        SpellcasterLoadout pack = loadout("mypack:guard", null, false, LoadoutSourceTier.DATAPACK, true);
        List<SpellcasterLoadout> resolved = LoadoutManager.applyOverrides(List.of(jarKill, pack));
        assertEquals(1, resolved.size());
        assertSame(pack, resolved.get(0));
    }
}
