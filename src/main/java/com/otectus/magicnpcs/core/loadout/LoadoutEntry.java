package com.otectus.magicnpcs.core.loadout;

import net.minecraft.resources.ResourceLocation;

/**
 * One spell a spellcasting NPC may use, from a datapack loadout. Iron's-free:
 * {@link #spell()} is a registry id resolved to an Iron's {@code AbstractSpell}
 * on the integration side at goal construction.
 *
 * @param spell        Iron's spell registry id (e.g. {@code irons_spellbooks:magic_missile})
 * @param level        spell level to cast at
 * @param weight       relative weight for random selection among castable spells
 * @param minRange     minimum distance (blocks) to the target for this spell to be eligible
 * @param maxRange     maximum distance (blocks) to the target for this spell to be eligible
 * @param safetyRadius friendly-fire clearance (blocks) around the firing line / impact point;
 *                     larger for AoE spells (used by the line-of-fire ally check)
 * @param role         ATTACK (cast at the hostile target) or SUPPORT (self-cast when hurt)
 */
public record LoadoutEntry(
        ResourceLocation spell,
        int level,
        int weight,
        double minRange,
        double maxRange,
        double safetyRadius,
        Role role
) {
    public enum Role { ATTACK, SUPPORT }
}
