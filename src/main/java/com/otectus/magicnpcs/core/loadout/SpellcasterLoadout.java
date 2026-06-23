package com.otectus.magicnpcs.core.loadout;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * A resolved per-entity-type spellcaster loadout, parsed from
 * {@code data/<ns>/spellcasters/*.json}. The presence of a loadout for an entity
 * type is the opt-in: tagged-style gating is replaced by "has a loadout".
 *
 * @param entityType the target entity type id (e.g. {@code minecraft:skeleton})
 * @param profession optional villager-profession id (e.g. {@code minecraft:cleric}); when
 *                   non-null the loadout applies only to villagers of that profession, so a
 *                   pack can make e.g. clerics cast without affecting all villagers. A
 *                   profession-less loadout for the same type is the generic fallback.
 * @param maxMana    base value applied to Iron's {@code MAX_MANA} attribute on spawn
 * @param manaRegen  base value applied to Iron's {@code MANA_REGEN} attribute on spawn
 * @param spells     the spells this type may cast
 * @param conditions optional world-context gate (dimension/biome/difficulty/time/…); {@code null} = always
 * @param poolWeight relative weight when several loadouts match one mob and form a pick-one-per-NPC pool
 * @param source     the datapack file id this loadout was loaded from; used as the stable identity for
 *                   the sticky per-NPC pool pick. {@code null} for in-code loadouts (data generator)
 */
public record SpellcasterLoadout(
        ResourceLocation entityType,
        ResourceLocation profession,
        double maxMana,
        double manaRegen,
        List<LoadoutEntry> spells,
        LoadoutConditions conditions,
        int poolWeight,
        ResourceLocation source
) {
    /** Convenience for a loadout that applies to a whole entity type (no profession scoping). */
    public SpellcasterLoadout(ResourceLocation entityType, double maxMana, double manaRegen, List<LoadoutEntry> spells) {
        this(entityType, null, maxMana, manaRegen, spells);
    }

    /** Convenience for a profession-scoped loadout without context conditions or pooling. */
    public SpellcasterLoadout(ResourceLocation entityType, ResourceLocation profession,
                              double maxMana, double manaRegen, List<LoadoutEntry> spells) {
        this(entityType, profession, maxMana, manaRegen, spells, null, 1, null);
    }

    /** A copy of this loadout tagged with the datapack file it came from (set on load). */
    public SpellcasterLoadout withSource(ResourceLocation src) {
        return new SpellcasterLoadout(entityType, profession, maxMana, manaRegen, spells, conditions, poolWeight, src);
    }
}
