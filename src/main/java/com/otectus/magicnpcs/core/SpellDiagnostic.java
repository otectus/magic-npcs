package com.otectus.magicnpcs.core;

/**
 * A plain, Iron's-free snapshot of how a loadout's spell id resolves and whether a mob can cast it,
 * for the {@code /magicnpcs loadout} and {@code /magicnpcs validate} commands. Produced on the
 * integration side ({@code IronsBridge.diagnose}) so the command package never imports Iron's.
 *
 * @param id              the resolved (or raw, if unknown) registry id
 * @param exists          true if the id resolves to a registered Iron's spell
 * @param enabled         true if that spell is enabled in Iron's config
 * @param category        the reviewed mob-cast capability name (e.g. {@code TARGET_ENTITY})
 * @param castType        Iron's cast type ({@code INSTANT}/{@code LONG}/{@code CONTINUOUS}/{@code NONE})
 * @param support         {@code SUPPORTED}, {@code UNSUPPORTED}, or {@code UNVERIFIED}. Since 0.6.2
 *                        these are three distinct answers: "we checked and a mob can do it", "we
 *                        checked and it cannot", and "this spell is not in the manifest this build was
 *                        verified against" (audit SPI-002). 0.6.1 collapsed all three into "supported"
 * @param willCast        true if the mob would actually attempt this spell right now — an
 *                        {@code UNVERIFIED} spell only casts when {@code spells.allowUnverifiedSpells}
 *                        is on
 * @param unsupportedReason a short, actionable explanation when {@code support} is not SUPPORTED
 * @param requiresTarget  true if it needs a target entity (the mob must have a target to cast it)
 * @param baseCooldownTicks Iron's default cooldown in ticks (before loadout/config overrides)
 * @param provenance      which layer decided this spell's capability - {@code VERIFIED},
 *                        {@code OVERRIDE}, {@code MANIFEST}, {@code NAMESPACE_TRUSTED} or
 *                        {@code UNVERIFIED}. Since 0.9.0 support is layered, so "why is it
 *                        unsupported" and "where do I change it" are separate questions
 * @param fix             the concrete next step - a "did you mean" for an unknown id, or the keys and
 *                        files that would change the verdict - or {@code null} when there is none
 */
public record SpellDiagnostic(
        String id,
        boolean exists,
        boolean enabled,
        String category,
        String castType,
        String support,
        boolean willCast,
        String unsupportedReason,
        boolean requiresTarget,
        int baseCooldownTicks,
        String provenance,
        String fix
) {
    /** @return true when a mob may actually cast this spell — the flag callers should gate on. */
    public boolean supportedForMob() {
        return willCast;
    }

    /** @return true when the spell is outside the verified manifest for this build. */
    public boolean unverified() {
        return "UNVERIFIED".equals(support);
    }

    /**
     * @return true when this spell is castable only because its namespace is trusted - no one has
     *         stated what it actually needs, so it gets the default corridor geometry and no cast-data
     *         guarantee.
     */
    public boolean namespaceTrusted() {
        return "NAMESPACE_TRUSTED".equals(provenance);
    }
}
