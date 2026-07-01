package com.otectus.magicnpcs.core;

/**
 * A plain, Iron's-free snapshot of how a loadout's spell id resolves and whether a mob can cast it,
 * for the {@code /magicnpcs loadout} and {@code /magicnpcs validate} commands. Produced on the
 * integration side ({@code IronsBridge.diagnose}) so the command package never imports Iron's.
 *
 * @param id              the resolved (or raw, if unknown) registry id
 * @param exists          true if the id resolves to a registered Iron's spell
 * @param enabled         true if that spell is enabled in Iron's config
 * @param category        the {@code SpellCompat.Category} name (e.g. {@code TARGET_ENTITY_REQUIRED})
 * @param castType        Iron's cast type ({@code INSTANT}/{@code LONG}/{@code CONTINUOUS}/{@code NONE})
 * @param supportedForMob true if a generic mob caster can cast this category at all
 * @param requiresTarget  true if it needs a target entity (the mob must have a target to cast it)
 * @param baseCooldownTicks Iron's default cooldown in ticks (before loadout/config overrides)
 */
public record SpellDiagnostic(
        String id,
        boolean exists,
        boolean enabled,
        String category,
        String castType,
        boolean supportedForMob,
        boolean requiresTarget,
        int baseCooldownTicks
) {}
