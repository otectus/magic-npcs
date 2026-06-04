package com.otectus.magicnpcs.core.loadout;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * A resolved per-entity-type spellcaster loadout, parsed from
 * {@code data/<ns>/spellcasters/*.json}. The presence of a loadout for an entity
 * type is the opt-in: tagged-style gating is replaced by "has a loadout".
 *
 * @param entityType the target entity type id (e.g. {@code minecraft:skeleton})
 * @param maxMana    base value applied to Iron's {@code MAX_MANA} attribute on spawn
 * @param manaRegen  base value applied to Iron's {@code MANA_REGEN} attribute on spawn
 * @param spells     the spells this type may cast
 */
public record SpellcasterLoadout(
        ResourceLocation entityType,
        double maxMana,
        double manaRegen,
        List<LoadoutEntry> spells
) {}
