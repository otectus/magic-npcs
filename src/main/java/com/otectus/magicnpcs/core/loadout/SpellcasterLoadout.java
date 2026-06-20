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
 */
public record SpellcasterLoadout(
        ResourceLocation entityType,
        ResourceLocation profession,
        double maxMana,
        double manaRegen,
        List<LoadoutEntry> spells
) {
    /** Convenience for a loadout that applies to a whole entity type (no profession scoping). */
    public SpellcasterLoadout(ResourceLocation entityType, double maxMana, double manaRegen, List<LoadoutEntry> spells) {
        this(entityType, null, maxMana, manaRegen, spells);
    }
}
