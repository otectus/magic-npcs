package com.otectus.magicnpcs.core.spell;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The precedence table for spell support: config override beats datapack manifest beats the built-in
 * reviewed table beats namespace trust beats nothing. One test per adjacent pair, plus the two cases
 * the layering exists for — an add-on spell nobody vouched for stays UNVERIFIED, and a trusted one
 * becomes ADDON_DEFAULT without anyone claiming to have verified it.
 */
class SpellSupportResolverTest {

    private static final ResourceLocation IRONS_FIREBALL = new ResourceLocation("irons_spellbooks", "fireball");
    private static final ResourceLocation ADDON_LANCE = new ResourceLocation("traveloptics", "tidal_lance");
    private static final ResourceLocation MANIFEST_FILE = new ResourceLocation("traveloptics", "spells");

    /** The built-in layer: it answers for Iron's only, with UNVERIFIED for a path it never reviewed. */
    private static final Function<ResourceLocation, SpellCapability> BUILTIN = id ->
            "irons_spellbooks".equals(id.getNamespace())
                    ? ("fireball".equals(id.getPath()) ? SpellCapability.DIRECT : SpellCapability.UNVERIFIED)
                    : null;

    private static SpellSupportResolver.ManifestEntry manifest(SpellCapability capability) {
        return new SpellSupportResolver.ManifestEntry(capability, MANIFEST_FILE, "traveloptics 1.2.0");
    }

    @Test
    void nullIdIsUnverified() {
        SpellSupportResolver.Verdict v = SpellSupportResolver.resolve(null, BUILTIN, Map.of(), Map.of(), Set.of());
        assertEquals(SpellCapability.UNVERIFIED, v.capability());
        assertEquals(SpellSupportResolver.Provenance.UNVERIFIED, v.provenance());
    }

    @Test
    void anUntrustedAddonSpellIsUnverified() {
        SpellSupportResolver.Verdict v = SpellSupportResolver.resolve(ADDON_LANCE, BUILTIN,
                Map.of(), Map.of(), Set.of());
        assertEquals(SpellCapability.UNVERIFIED, v.capability());
        assertEquals(SpellSupportResolver.Provenance.UNVERIFIED, v.provenance());
    }

    @Test
    void aTrustedAddonSpellIsAddonDefault() {
        SpellSupportResolver.Verdict v = SpellSupportResolver.resolve(ADDON_LANCE, BUILTIN,
                Map.of(), Map.of(), Set.of("traveloptics"));
        assertEquals(SpellCapability.ADDON_DEFAULT, v.capability());
        assertEquals(SpellSupportResolver.Provenance.NAMESPACE_TRUSTED, v.provenance());
        assertEquals("spells.trustedNamespaces", v.source());
    }

    @Test
    void aManifestBeatsNamespaceTrust() {
        SpellSupportResolver.Verdict v = SpellSupportResolver.resolve(ADDON_LANCE, BUILTIN,
                Map.of(ADDON_LANCE, manifest(SpellCapability.PLAYER_ONLY)), Map.of(), Set.of("traveloptics"));
        assertEquals(SpellCapability.PLAYER_ONLY, v.capability());
        assertEquals(SpellSupportResolver.Provenance.MANIFEST, v.provenance());
        assertEquals(MANIFEST_FILE.toString(), v.source());
    }

    @Test
    void theBuiltInTableBeatsNamespaceTrust() {
        // An Iron's spell we deliberately did not verify stays UNVERIFIED even with the namespace
        // trusted: the table already answered for it, and namespace trust is the weaker claim.
        ResourceLocation unreviewed = new ResourceLocation("irons_spellbooks", "brand_new_spell");
        SpellSupportResolver.Verdict v = SpellSupportResolver.resolve(unreviewed, BUILTIN,
                Map.of(), Map.of(), Set.of("irons_spellbooks"));
        assertEquals(SpellCapability.UNVERIFIED, v.capability());
        assertEquals(SpellSupportResolver.Provenance.VERIFIED, v.provenance());
    }

    @Test
    void aManifestBeatsTheBuiltInTable() {
        SpellSupportResolver.Verdict v = SpellSupportResolver.resolve(IRONS_FIREBALL, BUILTIN,
                Map.of(IRONS_FIREBALL, manifest(SpellCapability.TARGET_ENTITY)), Map.of(), Set.of());
        assertEquals(SpellCapability.TARGET_ENTITY, v.capability());
        assertEquals(SpellSupportResolver.Provenance.MANIFEST, v.provenance());
    }

    @Test
    void anOverrideBeatsAManifest() {
        SpellSupportResolver.Verdict v = SpellSupportResolver.resolve(ADDON_LANCE, BUILTIN,
                Map.of(ADDON_LANCE, manifest(SpellCapability.PLAYER_ONLY)),
                Map.of(ADDON_LANCE, SpellCapability.TARGET_ENTITY), Set.of("traveloptics"));
        assertEquals(SpellCapability.TARGET_ENTITY, v.capability());
        assertEquals(SpellSupportResolver.Provenance.OVERRIDE, v.provenance());
        assertEquals("traveloptics:tidal_lance=TARGET_ENTITY", v.source());
    }

    @Test
    void anOverrideBeatsTheBuiltInTable() {
        SpellSupportResolver.Verdict v = SpellSupportResolver.resolve(IRONS_FIREBALL, BUILTIN,
                Map.of(), Map.of(IRONS_FIREBALL, SpellCapability.PLAYER_ONLY), Set.of());
        assertEquals(SpellCapability.PLAYER_ONLY, v.capability());
        assertEquals(SpellSupportResolver.Provenance.OVERRIDE, v.provenance());
    }

    @Test
    void theBuiltInTableAnswersWhenNothingElseDoes() {
        SpellSupportResolver.Verdict v = SpellSupportResolver.resolve(IRONS_FIREBALL, BUILTIN,
                Map.of(), Map.of(), Set.of());
        assertEquals(SpellCapability.DIRECT, v.capability());
        assertEquals(SpellSupportResolver.Provenance.VERIFIED, v.provenance());
        assertEquals(SpellSupportResolver.BUILTIN_SOURCE, v.source());
    }

    @Test
    void addonDefaultIsASupportedCapability() {
        // The point of ADDON_DEFAULT: castable, unlike UNVERIFIED, without claiming verification.
        assertEquals(List.of(true, false), List.of(SpellCapability.ADDON_DEFAULT.supported(),
                SpellCapability.UNVERIFIED.supported()));
    }
}
