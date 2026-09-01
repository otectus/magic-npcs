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
 * @param condition          optional reactive trigger gating this spell's eligibility (self/target HP,
 *                           nearby-enemy count, recently-hurt); {@code null} = no extra condition
 * @param requireHeldItem    when true this spell may only be cast while the caster holds one of
 *                           {@code requiredItems} (0.6.0, restored in 0.6.2 — audit REL-002).
 *                           Independent of the global {@code equipment.requireSpellFocus}, which
 *                           gates <em>all</em> casting on the {@code #magicnpcs:spell_focuses} tag
 * @param requiredItems      item ids, and/or {@code #namespace:tag} references, satisfying
 *                           {@code requireHeldItem}; empty falls back to {@code #magicnpcs:spell_focuses}
 * @param requiredHand       which hand must hold the item — {@link HandRequirement#EITHER} by default
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
        Integer windupTicks,
        CastCondition condition,
        boolean requireHeldItem,
        java.util.List<String> requiredItems,
        HandRequirement requiredHand
) {
    public enum Role { ATTACK, SUPPORT }

    /** Which hand must satisfy {@link #requireHeldItem()}. */
    public enum HandRequirement {
        MAIN("main"), OFF("off"), EITHER("either");

        private final String json;

        HandRequirement(String json) {
            this.json = json;
        }

        public String jsonValue() {
            return json;
        }

        /** @throws IllegalArgumentException with an actionable message on an unknown value */
        public static HandRequirement parse(String raw) {
            for (HandRequirement h : values()) {
                if (h.json.equalsIgnoreCase(raw) || h.name().equalsIgnoreCase(raw)) {
                    return h;
                }
            }
            throw new IllegalArgumentException(
                    "required_hand must be 'main', 'off', or 'either', got '" + raw + "'");
        }
    }

    public LoadoutEntry {
        requiredItems = requiredItems == null ? java.util.List.of() : java.util.List.copyOf(requiredItems);
        requiredHand = requiredHand == null ? HandRequirement.EITHER : requiredHand;
    }

    /**
     * Pre-0.6.2 shape, for call sites that set the tuning fields but no held-item requirement.
     */
    public LoadoutEntry(ResourceLocation spell, int level, int weight, double minRange, double maxRange,
                        double safetyRadius, Role role, Double castChance, Integer cooldownTicks,
                        Double cooldownMultiplier, Integer windupTicks, CastCondition condition) {
        this(spell, level, weight, minRange, maxRange, safetyRadius, role, castChance, cooldownTicks,
                cooldownMultiplier, windupTicks, condition, false, java.util.List.of(), HandRequirement.EITHER);
    }

    /**
     * Back-compat constructor for call sites that don't set the optional tuning fields
     * (data generator, school spell pool): each optional field is {@code null}, i.e. it
     * inherits its global config default at runtime.
     */
    public LoadoutEntry(ResourceLocation spell, int level, int weight,
                        double minRange, double maxRange, double safetyRadius, Role role) {
        this(spell, level, weight, minRange, maxRange, safetyRadius, role, null, null, null, null, null,
                false, java.util.List.of(), HandRequirement.EITHER);
    }
}
