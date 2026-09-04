package com.otectus.magicnpcs.core.spell;

/**
 * What a non-player mob must be able to supply for a spell to behave as designed.
 *
 * <p>Lives in {@code core} (Iron's-free) because the layers that decide a spell's capability — the
 * datapack manifest loader, the config overrides, and the resolver that ranks them — must not import
 * {@code integration.irons}. The reviewed per-spell table for the {@code irons_spellbooks} namespace
 * still lives in {@code integration.irons.SpellManifest}; this enum is only the vocabulary.
 */
public enum SpellCapability {
    /** Aimed projectile or simple self-effect: the caster's look angle is all it reads. */
    DIRECT(true),
    /** Reads a single {@code TargetEntityCastData}, which the session supplies from the target. */
    TARGET_ENTITY(true),
    /** Builds its own target-area data in pre-cast; needs the pre-cast hook to actually run. */
    TARGET_AREA(true),
    /** Forward ground AoE from the caster's facing: wants a short range and correct facing. */
    GROUND_AOE_FORWARD(true),
    /** Summons entities; the session installs the spell's own empty cast data to track them. */
    SUMMON(true),
    /**
     * Namespace-trusted, not verified: treated as {@link #DIRECT} for geometry and targeting; the cast
     * session supplies target data opportunistically (when the caster has a live target), so a
     * single-target add-on spell works without a manifest and a spell that builds its own cast data
     * overwrites it.
     */
    ADDON_DEFAULT(true),
    /** Reads {@code MultiTargetEntityCastData}, which nothing builds for a mob. */
    MULTI_TARGET(false),
    /** Iron's prepares this through {@code IMagicEntity} hooks a foreign mob cannot implement. */
    SPECIAL_PREPARATION(false),
    /** Refuses a non-player caster, or only does anything for a {@code ServerPlayer}. */
    PLAYER_ONLY(false),
    /** Block/world manipulation with no combat behaviour for an NPC. */
    UTILITY_NON_COMBAT(false),
    /** Not covered by any layer: an add-on spell, or one from a newer Iron's than this build verified. */
    UNVERIFIED(false);

    private final boolean supported;

    SpellCapability(boolean supported) {
        this.supported = supported;
    }

    /** @return true if a generic mob can cast this and get the spell's designed behaviour. */
    public boolean supported() {
        return supported;
    }
}
