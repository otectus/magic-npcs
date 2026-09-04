package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.spell.SpellCapability;
import com.otectus.magicnpcs.core.spell.SpellSupportResolver;
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
 * {@link SpellCapability#UNVERIFIED} and is not cast unless the server operator opts in with
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
    public static SpellCapability capabilityOf(AbstractSpell spell) {
        return SpellManifest.capabilityOf(spell == null ? null : spell.getSpellResource());
    }

    /** @return the capability <em>and</em> the layer that decided it (override/manifest/table/trust). */
    public static SpellSupportResolver.Verdict verdictOf(AbstractSpell spell) {
        return SpellManifest.verdictOf(spell == null ? null : spell.getSpellResource());
    }

    /** @return which layer decided this spell's capability, for the diagnostics' provenance column. */
    public static SpellSupportResolver.Provenance provenanceOf(AbstractSpell spell) {
        return verdictOf(spell).provenance();
    }

    /**
     * @return true if the session should install target cast data for this spell whenever the caster
     *         has a live target, rather than because the spell demands it. That is the whole of what
     *         {@link SpellCapability#ADDON_DEFAULT} buys a namespace-trusted spell: a single-target
     *         add-on spell finds the data it looks for, and a spell that builds its own overwrites it.
     */
    public static boolean suppliesTargetOpportunistically(AbstractSpell spell) {
        return capabilityOf(spell) == SpellCapability.ADDON_DEFAULT;
    }

    /**
     * @return the layers that can change this verdict, named in the order an operator should try
     *         them: the datapack manifest first (shareable and per-spell), then namespace trust, then
     *         the global opt-in. For a spell some layer already decided <em>against</em>, the source
     *         that decided is named, because editing anything else will not help.
     */
    public static String fixHint(AbstractSpell spell) {
        SpellSupportResolver.Verdict verdict = verdictOf(spell);
        String namespace = spell == null || spell.getSpellResource() == null
                ? "<namespace>" : spell.getSpellResource().getNamespace();
        if (verdict.provenance() != SpellSupportResolver.Provenance.UNVERIFIED
                && !verdict.capability().supported()) {
            return "its capability " + verdict.capability().name() + " comes from " + verdict.source()
                    + "; change it there, or add a data/" + namespace
                    + "/spell_manifests/*.json entry, to cast it anyway";
        }
        return "declare it in a spell manifest (data/" + namespace + "/spell_manifests/*.json), or add \""
                + namespace + "\" to spells.trustedNamespaces, or set spells.allowUnverifiedSpells = true "
                + "to cast every unverified spell at your own risk";
    }

    /** @return the support verdict for {@code spell}, honouring the unverified opt-in. */
    public static Support supportOf(AbstractSpell spell) {
        SpellCapability capability = capabilityOf(spell);
        if (capability == SpellCapability.UNVERIFIED) {
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
        return capabilityOf(spell) == SpellCapability.TARGET_ENTITY;
    }

    /** @return a short, actionable reason {@code spell} is not being cast, for logs and diagnostics. */
    public static String unsupportedReason(AbstractSpell spell) {
        SpellCapability capability = capabilityOf(spell);
        return switch (capability) {
            case MULTI_TARGET -> "it reads multi-target cast data that nothing builds for a mob";
            case PLAYER_ONLY -> "Iron's refuses this spell for any caster that is not a player";
            case SPECIAL_PREPARATION -> "Iron's prepares this one through its own casting-mob hooks "
                    + "(teleport destination / dash direction / aiming data) that a foreign mob cannot supply";
            case UTILITY_NON_COMBAT -> "it manipulates blocks rather than fighting, so it does nothing useful "
                    + "for an NPC";
            case UNVERIFIED -> "no layer states what this spell needs of a mob (built-in table verified "
                    + "against Iron's " + SpellManifest.VERIFIED_AGAINST + "): " + fixHint(spell);
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
     * @return whether {@code spell} actually charges — Iron's {@code LONG} or {@code CONTINUOUS} —
     *         rather than resolving on the tick it starts.
     */
    public static boolean hasCastDuration(AbstractSpell spell) {
        return spell.getCastType() == CastType.LONG || spell.getCastType() == CastType.CONTINUOUS;
    }

    /**
     * As {@link #effectiveCastTime(AbstractSpell, int, net.minecraft.world.entity.LivingEntity)}, with
     * the loadout's per-spell overrides applied by
     * {@link com.otectus.magicnpcs.core.loadout.CastTimeResolver}.
     *
     * @param absoluteTicks per-spell {@code cast_time} in ticks, or {@code null}
     * @param multiplier    per-spell {@code cast_time_multiplier}, or {@code null}
     * @return the cast duration in ticks for this one cast; both overrides are ignored (and 0 is
     *         returned) for a spell with no cast duration
     */
    public static int effectiveCastTime(AbstractSpell spell, int level,
                                        net.minecraft.world.entity.LivingEntity caster,
                                        Integer absoluteTicks, Double multiplier) {
        return com.otectus.magicnpcs.core.loadout.CastTimeResolver.resolve(hasCastDuration(spell),
                effectiveCastTime(spell, level, caster), absoluteTicks, multiplier);
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
