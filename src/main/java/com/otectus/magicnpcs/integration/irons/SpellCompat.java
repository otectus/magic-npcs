package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;

/**
 * The mob-cast verdict for one Iron's spell: what cast data it needs, whether Magic NPCs can supply
 * it, and — when it cannot — a reason a human can act on.
 *
 * <p>This used to be a four-entry keyword map with an "everything else is a simple aimed projectile"
 * default, so the validator and the runtime both claimed support for essentially the whole Iron's
 * registry, player-only spells included (audit SPI-002). It is now a thin reader over
 * {@link SpellManifest}, the reviewed per-spell list derived from the Iron's jar this build was checked
 * against, and it <b>fails closed</b>: a spell the manifest does not cover is
 * {@link SpellManifest.Capability#UNVERIFIED} and is not cast unless the server operator opts in with
 * {@code spells.allowUnverifiedSpells}.
 *
 * <p>Lives in {@code integration.irons} (it imports {@link AbstractSpell}); only classloaded when
 * Iron's is present.
 */
public final class SpellCompat {
    private SpellCompat() {}

    /**
     * The support verdict shown by {@code /magicnpcs spells} and {@code /magicnpcs validate}. Kept
     * distinct from the capability itself because "we have not checked this" and "we checked, and a
     * mob cannot do it" are different answers and a pack author needs to tell them apart.
     */
    public enum Support {
        /** Verified: a mob gets the spell's designed behaviour. */
        SUPPORTED,
        /** Verified: a mob cannot get the designed behaviour, so the spell is never cast. */
        UNSUPPORTED,
        /** Not in the manifest. Skipped unless {@code spells.allowUnverifiedSpells} is on. */
        UNVERIFIED
    }

    /** @return the reviewed capability for {@code spell}. */
    public static SpellManifest.Capability capabilityOf(AbstractSpell spell) {
        return SpellManifest.capabilityOf(spell == null ? null : spell.getSpellResource());
    }

    /** @return the support verdict for {@code spell}, honouring the unverified opt-in. */
    public static Support supportOf(AbstractSpell spell) {
        SpellManifest.Capability capability = capabilityOf(spell);
        if (capability == SpellManifest.Capability.UNVERIFIED) {
            return Support.UNVERIFIED;
        }
        return capability.supported() ? Support.SUPPORTED : Support.UNSUPPORTED;
    }

    /**
     * @return true if a mob may actually be allowed to cast this spell now. An
     *         {@link Support#UNVERIFIED} spell passes only when the operator has opted in; an
     *         {@link Support#UNSUPPORTED} one never does.
     */
    public static boolean castableByMob(AbstractSpell spell) {
        return switch (supportOf(spell)) {
            case SUPPORTED -> true;
            case UNVERIFIED -> MagicNpcsConfig.allowUnverifiedSpells();
            case UNSUPPORTED -> false;
        };
    }

    /** @return true if the spell reads a single {@code TargetEntityCastData} the session must supply. */
    public static boolean requiresTargetEntity(AbstractSpell spell) {
        return capabilityOf(spell) == SpellManifest.Capability.TARGET_ENTITY;
    }

    /** @return a short, actionable reason {@code spell} is not being cast, for logs and diagnostics. */
    public static String unsupportedReason(AbstractSpell spell) {
        SpellManifest.Capability capability = capabilityOf(spell);
        return switch (capability) {
            case MULTI_TARGET -> "it reads multi-target cast data that nothing builds for a mob";
            case PLAYER_ONLY -> "Iron's refuses this spell for any caster that is not a player";
            case SPECIAL_PREPARATION -> "Iron's prepares this one through its own casting-mob hooks "
                    + "(teleport destination / dash direction / aiming data) that a foreign mob cannot supply";
            case UTILITY_NON_COMBAT -> "it manipulates blocks rather than fighting, so it does nothing useful "
                    + "for an NPC";
            case UNVERIFIED -> "its mob-cast behaviour has not been verified against Iron's "
                    + SpellManifest.VERIFIED_AGAINST + " (set spells.allowUnverifiedSpells = true to "
                    + "cast it anyway, at your own risk)";
            default -> "unsupported";
        };
    }

    /** @return the capability name for the diagnostic tables, lower-cased at the call site. */
    public static String categoryName(AbstractSpell spell) {
        return capabilityOf(spell).name();
    }

    /**
     * @return Iron's <em>effective</em> cast time (ticks) for {@code spell} at {@code level} on
     *         {@code caster}, or 0 for an instant cast.
     *
     *         <p>0.6.1 used the raw {@code getCastTime}, so any caster-side cast-time modifier Iron's
     *         applies was ignored and the mod's own wind-up drifted out of step with the cast session
     *         it was meant to be pacing.
     */
    public static int effectiveCastTime(AbstractSpell spell, int level,
                                        net.minecraft.world.entity.LivingEntity caster) {
        if (spell.getCastType() == CastType.INSTANT || spell.getCastType() == CastType.NONE) {
            return 0;
        }
        return Math.max(0, spell.getEffectiveCastTime(level, caster));
    }

    /**
     * @return the shape this spell's danger takes, so the friendly-fire check measures the right
     *         region. Derived from the reviewed capability rather than from the author's
     *         {@code safety_radius}, which is a distance and cannot describe a shape.
     */
    public static com.otectus.magicnpcs.core.util.LineOfFire.Geometry geometryOf(AbstractSpell spell) {
        return switch (capabilityOf(spell)) {
            case GROUND_AOE_FORWARD -> com.otectus.magicnpcs.core.util.LineOfFire.Geometry.CASTER_AOE;
            case TARGET_AREA -> com.otectus.magicnpcs.core.util.LineOfFire.Geometry.TARGET_BLAST;
            default -> com.otectus.magicnpcs.core.util.LineOfFire.Geometry.CORRIDOR;
        };
    }
}
