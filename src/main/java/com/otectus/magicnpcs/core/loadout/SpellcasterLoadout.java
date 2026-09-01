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
 * @param spells     the spells this type may cast (may be empty only when {@code enabled} is false)
 * @param equipment  optional weighted starting gear granted on spawn; {@code null} = use the global
 *                   focus-gear behaviour ({@code equipment.spawnWithGearChance} + the spell_focuses tag)
 * @param conditions optional world-context gate (dimension/biome/difficulty/time/…); {@code null} = always
 * @param poolWeight relative weight when several loadouts match one mob and form a pick-one-per-NPC pool
 * @param source     the datapack file id this loadout was loaded from; used as the stable identity for
 *                   the sticky per-NPC pool pick. {@code null} for in-code loadouts (data generator)
 * @param replace    when {@code true}, this loadout overrides (clears) all non-replace loadouts that
 *                   share its effective key (entity type + optional profession) at load time, instead of
 *                   pooling with them — the explicit escape hatch for "my datapack wins" (0.5.0)
 * @param enabled    when {@code false} (0.6.0) the loadout is inert: it removes itself at load time, and
 *                   combined with {@code replace} it suppresses its whole effective-key group — the
 *                   datapack way to switch an entity type's casting off without editing a jar
 * @param tier       where the file came from (0.6.0). A higher {@link LoadoutSourceTier} wins outright
 *                   over a lower one for the same effective key, before {@code replace} is considered
 * @param goalPriority optional {@code GoalSelector} priority for this loadout's casting goal (0.6.0);
 *                   {@code null} inherits {@code general.castingGoalPriority}
 * @param nativeAttack how the casting goal coexists with the mob's own attack AI (0.6.0)
 * @param casterChance probability [0..1] that an individual NPC of this type becomes a caster at all
 *                   (0.6.0, restored in 0.6.2 — audit REL-002). {@code null} = every matching NPC casts.
 *                   The roll happens <b>once</b> per NPC and is persisted in {@code LoadoutData}, so a
 *                   {@code /reload} or chunk reload can never re-roll it into or out of being a caster
 */
public record SpellcasterLoadout(
        ResourceLocation entityType,
        ResourceLocation profession,
        double maxMana,
        double manaRegen,
        List<LoadoutEntry> spells,
        LoadoutEquipment equipment,
        LoadoutConditions conditions,
        int poolWeight,
        ResourceLocation source,
        boolean replace,
        boolean enabled,
        LoadoutSourceTier tier,
        Integer goalPriority,
        NativeAttackPolicy nativeAttack,
        Double casterChance
) {
    /** Convenience for a loadout that applies to a whole entity type (no profession scoping). */
    public SpellcasterLoadout(ResourceLocation entityType, double maxMana, double manaRegen, List<LoadoutEntry> spells) {
        this(entityType, null, maxMana, manaRegen, spells);
    }

    /** Convenience for a profession-scoped loadout without equipment, context conditions, or pooling. */
    public SpellcasterLoadout(ResourceLocation entityType, ResourceLocation profession,
                              double maxMana, double manaRegen, List<LoadoutEntry> spells) {
        this(entityType, profession, maxMana, manaRegen, spells, null, null, 1, null, false);
    }

    /**
     * Pre-0.6.0 shape: everything through {@code replace}, with the 0.6.0 fields at their defaults
     * (enabled, {@link LoadoutSourceTier#DATAPACK}, inherited goal priority, {@code coexist}). Kept so
     * in-code loadouts and tests written against the 0.5.0 record still compile and are never silently
     * outranked by a jar-tier loadout.
     */
    public SpellcasterLoadout(ResourceLocation entityType, ResourceLocation profession,
                              double maxMana, double manaRegen, List<LoadoutEntry> spells,
                              LoadoutEquipment equipment, LoadoutConditions conditions,
                              int poolWeight, ResourceLocation source, boolean replace) {
        this(entityType, profession, maxMana, manaRegen, spells, equipment, conditions, poolWeight,
                source, replace, true, LoadoutSourceTier.DATAPACK, null, NativeAttackPolicy.COEXIST, null);
    }

    /**
     * Pre-0.6.2 shape: everything through {@code nativeAttack}, with {@code caster_chance} unset (every
     * matching NPC casts). Kept so 0.6.1-era construction sites and tests still compile.
     */
    public SpellcasterLoadout(ResourceLocation entityType, ResourceLocation profession,
                              double maxMana, double manaRegen, List<LoadoutEntry> spells,
                              LoadoutEquipment equipment, LoadoutConditions conditions,
                              int poolWeight, ResourceLocation source, boolean replace, boolean enabled,
                              LoadoutSourceTier tier, Integer goalPriority, NativeAttackPolicy nativeAttack) {
        this(entityType, profession, maxMana, manaRegen, spells, equipment, conditions, poolWeight,
                source, replace, enabled, tier, goalPriority, nativeAttack, null);
    }

    /** A copy of this loadout tagged with the datapack file it came from (set on load). */
    public SpellcasterLoadout withSource(ResourceLocation src) {
        return new SpellcasterLoadout(entityType, profession, maxMana, manaRegen, spells, equipment,
                conditions, poolWeight, src, replace, enabled, tier, goalPriority, nativeAttack, casterChance);
    }

    /** A copy of this loadout tagged with the source tier resolved from the {@code ResourceManager}. */
    public SpellcasterLoadout withTier(LoadoutSourceTier newTier) {
        return new SpellcasterLoadout(entityType, profession, maxMana, manaRegen, spells, equipment,
                conditions, poolWeight, source, replace, enabled, newTier, goalPriority, nativeAttack,
                casterChance);
    }

    /**
     * A stable digest of everything that changes runtime behaviour, so a reconciler can tell "the same
     * loadout came back after {@code /reload}" from "the author edited it" without deep-comparing
     * records. Deliberately excludes {@link #source()} and {@link #tier()}: those describe where the
     * loadout came from, not what it does.
     */
    public String contentHash() {
        StringBuilder sb = new StringBuilder(128);
        sb.append(entityType).append('|').append(profession).append('|')
                .append(maxMana).append('|').append(manaRegen).append('|')
                .append(poolWeight).append('|').append(replace).append('|').append(enabled).append('|')
                .append(goalPriority).append('|').append(nativeAttack).append('|').append(casterChance)
                .append('|').append(equipment).append('|').append(conditions).append('|');
        for (LoadoutEntry e : spells) {
            sb.append(e).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    /** The load-time grouping key for override resolution: the optional profession (null = generic). */
    public ResourceLocation effectiveKey() {
        return profession;
    }
}
