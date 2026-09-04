package com.otectus.magicnpcs.core.spell;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Ranks the layers that can decide a spell's {@link SpellCapability} and says which one decided.
 *
 * <p>Through 0.8.0 there was exactly one layer — the reviewed Iron's table — and every spell outside
 * the {@code irons_spellbooks} namespace was {@link SpellCapability#UNVERIFIED} on namespace alone, so
 * an Iron's add-on's spells could only be cast by turning off the global safety net
 * ({@code spells.allowUnverifiedSpells}). The layers are now, highest first:
 *
 * <ol>
 *   <li>{@code spells.capabilityOverrides} — the operator's own statement about one spell;</li>
 *   <li>a datapack manifest ({@code data/&lt;ns&gt;/spell_manifests/*.json}) — the pack author's;</li>
 *   <li>the built-in reviewed table — ours, for the Iron's version this build was checked against;</li>
 *   <li>{@code spells.trustedNamespaces} — the weakest positive claim, yielding
 *       {@link SpellCapability#ADDON_DEFAULT};</li>
 *   <li>nothing — {@link SpellCapability#UNVERIFIED}.</li>
 * </ol>
 *
 * <p>Pure and Iron's-free: the built-in table is passed in as a function, so this is one unit-test
 * table rather than conditionals scattered across the integration package.
 */
public final class SpellSupportResolver {

    private SpellSupportResolver() {}

    /** Which layer produced a capability — printed by the diagnostics so the fix is obvious. */
    public enum Provenance {
        /** The built-in reviewed table for the Iron's version this build was checked against. */
        VERIFIED,
        /** A {@code spells.capabilityOverrides} entry. */
        OVERRIDE,
        /** A datapack spell manifest. */
        MANIFEST,
        /** {@code spells.trustedNamespaces}: trusted by namespace, never actually verified. */
        NAMESPACE_TRUSTED,
        /** No layer covers this spell. */
        UNVERIFIED
    }

    /**
     * @param capability  the winning capability
     * @param provenance  which layer decided it
     * @param source      the exact key, file or list that decided, or {@code null} when nothing did
     */
    public record Verdict(SpellCapability capability, Provenance provenance, String source) {}

    /**
     * One manifest row.
     *
     * @param capability      the declared capability
     * @param fileId          the manifest file the row came from, for diagnostics
     * @param verifiedAgainst the manifest's free-text {@code verified_against}, or {@code null}
     */
    public record ManifestEntry(SpellCapability capability, ResourceLocation fileId, String verifiedAgainst) {}

    /** The source text for a built-in verdict; the Iron's adapter appends the version it verified. */
    public static final String BUILTIN_SOURCE = "built-in";

    /**
     * @param id                the spell id, or {@code null}
     * @param builtin           the reviewed table: a capability for a namespace this build reviewed
     *                          (including {@link SpellCapability#UNVERIFIED} for an unlisted spell in
     *                          that namespace), or {@code null} for a namespace it never reviewed
     * @param manifest          merged datapack manifest rows
     * @param overrides         parsed {@code spells.capabilityOverrides}
     * @param trustedNamespaces {@code spells.trustedNamespaces}
     * @return the winning verdict; never {@code null}
     */
    public static Verdict resolve(ResourceLocation id,
                                  Function<ResourceLocation, SpellCapability> builtin,
                                  Map<ResourceLocation, ManifestEntry> manifest,
                                  Map<ResourceLocation, SpellCapability> overrides,
                                  Set<String> trustedNamespaces) {
        if (id == null) {
            return new Verdict(SpellCapability.UNVERIFIED, Provenance.UNVERIFIED, null);
        }
        SpellCapability override = overrides == null ? null : overrides.get(id);
        if (override != null) {
            return new Verdict(override, Provenance.OVERRIDE, id + "=" + override.name());
        }
        ManifestEntry entry = manifest == null ? null : manifest.get(id);
        if (entry != null) {
            return new Verdict(entry.capability(), Provenance.MANIFEST, String.valueOf(entry.fileId()));
        }
        SpellCapability reviewed = builtin == null ? null : builtin.apply(id);
        if (reviewed != null) {
            // Deliberately not verified is still a verdict we made: an Iron's spell missing from the
            // table is UNVERIFIED with VERIFIED provenance, not a candidate for namespace trust.
            return new Verdict(reviewed, Provenance.VERIFIED, BUILTIN_SOURCE);
        }
        if (trustedNamespaces != null && trustedNamespaces.contains(id.getNamespace())) {
            return new Verdict(SpellCapability.ADDON_DEFAULT, Provenance.NAMESPACE_TRUSTED,
                    "spells.trustedNamespaces");
        }
        return new Verdict(SpellCapability.UNVERIFIED, Provenance.UNVERIFIED, null);
    }
}
