package com.otectus.magicnpcs.integration.irons;

import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Classifies an Iron's spell by the cast-data and lifecycle a <em>non-player mob</em> caster must
 * supply, so the casting path can set up the right data, pick sane defaults, validate loadouts, and
 * explain why a spell was skipped. Iron's exposes the cast <em>timing</em> ({@link CastType}) but not
 * the cast-<em>data</em> requirement, so the latter comes from a small curated id→category map that is
 * easy to extend; unmapped spells default to {@link Category#DIRECT_PROJECTILE_OR_SIMPLE_SELF}.
 *
 * <p>Lives in {@code integration.irons} (it imports {@link AbstractSpell}); only classloaded when
 * Iron's is present.
 */
public final class SpellCompat {
    private SpellCompat() {}

    /** What a mob caster must provide for a spell to work (orthogonal to its {@link CastType}). */
    public enum Category {
        /** Aimed projectile or simple self-buff: works from {@code getLookAngle()} alone. */
        DIRECT_PROJECTILE_OR_SIMPLE_SELF,
        /** Reads a single {@code TargetEntityCastData} target from the {@code MagicData}. */
        TARGET_ENTITY_REQUIRED,
        /** Reads a target position from cast data (we can derive one from the target). */
        TARGET_POSITION_REQUIRED,
        /** Reads {@code MultiTargetEntityCastData} — not synthesizable for a mob; unsupported. */
        MULTI_TARGET_REQUIRED,
        /** Forward ground AoE from the caster's facing/ground position; needs short range + facing. */
        GROUND_AOE_FORWARD,
        /** Player-only or otherwise unsupported for mob casting. */
        PLAYER_ONLY_OR_UNSUPPORTED,
        /** Non-combat utility (teleport/movement/etc.) with no useful mob behaviour. */
        UTILITY_OR_NON_COMBAT;
    }

    /**
     * Curated overrides for spells whose mob-cast requirement isn't "aimed projectile". Keyed by the
     * {@code irons_spellbooks} id path. Verified against Iron's 3.x: root/devour/wisp read a
     * {@code TargetEntityCastData} target in {@code onCast}; stomp is a forward ground AoE.
     */
    private static final Map<String, Category> BY_PATH = Map.ofEntries(
            Map.entry("root", Category.TARGET_ENTITY_REQUIRED),
            Map.entry("devour", Category.TARGET_ENTITY_REQUIRED),
            Map.entry("wisp", Category.TARGET_ENTITY_REQUIRED),
            Map.entry("stomp", Category.GROUND_AOE_FORWARD));

    /** Classify a resolved spell into its mob-cast {@link Category}. */
    public static Category categoryOf(AbstractSpell spell) {
        ResourceLocation id = spell.getSpellResource();
        if (id != null && "irons_spellbooks".equals(id.getNamespace())) {
            Category mapped = BY_PATH.get(id.getPath());
            if (mapped != null) {
                return mapped;
            }
        }
        return Category.DIRECT_PROJECTILE_OR_SIMPLE_SELF;
    }

    /** @return true if the spell needs a single {@code TargetEntityCastData} target before casting. */
    public static boolean requiresTargetEntity(Category category) {
        return category == Category.TARGET_ENTITY_REQUIRED;
    }

    /**
     * @return true if a generic mob can cast this category at all. Multi-target and player-only spells
     *         can't be supplied the data they need, so they're skipped with a diagnostic instead of
     *         mis-firing.
     */
    public static boolean supportedForMob(Category category) {
        return category != Category.PLAYER_ONLY_OR_UNSUPPORTED
                && category != Category.MULTI_TARGET_REQUIRED;
    }

    /** @return a short human-readable reason a category is unsupported, for skip logs. */
    public static String unsupportedReason(Category category) {
        return switch (category) {
            case MULTI_TARGET_REQUIRED -> "it needs multi-target cast data a mob can't supply";
            case PLAYER_ONLY_OR_UNSUPPORTED -> "it is player-only / unsupported for mob casting";
            default -> "unsupported";
        };
    }

    /** @return true if the spell is a long (channelled) cast that must run a wind-up before firing. */
    public static boolean isLongCast(AbstractSpell spell) {
        return spell.getCastType() == CastType.LONG;
    }
}
