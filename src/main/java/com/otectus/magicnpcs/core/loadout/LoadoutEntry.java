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
 * @param castChance         optional cast probability [0..1] per decision; {@code null} inherits
 *                           the global {@code balance.castChance}
 * @param cooldownTicks      optional explicit cooldown in ticks; {@code null} falls through to the
 *                           multiplier path. Highest precedence when set (still floored)
 * @param cooldownMultiplier optional per-spell cooldown multiplier replacing the global one;
 *                           {@code null} inherits {@code balance.cooldownMultiplier}
 * @param windupTicks        optional aim wind-up in ticks before an attack spell fires; {@code null}
 *                           inherits {@code targeting.castWindupTicks}
 */
public record LoadoutEntry(
        ResourceLocation spell,
        int level,
        int weight,
        double minRange,
        double maxRange,
        double safetyRadius,
        Role role,
        Double castChance,
        Integer cooldownTicks,
        Double cooldownMultiplier,
        Integer windupTicks
) {
    public enum Role { ATTACK, SUPPORT }

    /**
     * Back-compat constructor for call sites that don't set the optional tuning fields
     * (data generator, school spell pool): each optional field is {@code null}, i.e. it
     * inherits its global config default at runtime.
     */
    public LoadoutEntry(ResourceLocation spell, int level, int weight,
                        double minRange, double maxRange, double safetyRadius, Role role) {
        this(spell, level, weight, minRange, maxRange, safetyRadius, role, null, null, null, null);
    }
}
