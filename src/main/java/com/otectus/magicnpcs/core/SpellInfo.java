package com.otectus.magicnpcs.core;

/**
 * A plain, Iron's-free snapshot of one registered Iron's spell, for the
 * {@code /magicnpcs spells} command and the generated id reference. Produced on the
 * integration side ({@code IronsBridge.listSpells}) so the command package never
 * imports Iron's.
 *
 * @param id            full registry id, e.g. {@code irons_spellbooks:magic_missile}
 * @param school        school id path, e.g. {@code fire} (empty if none)
 * @param rarity        rarity name, e.g. {@code COMMON}
 * @param cooldownTicks Iron's default cooldown at level 1, in ticks (20 = 1 s)
 * @param castType      Iron's cast type, e.g. {@code INSTANT}, {@code LONG}, {@code CONTINUOUS}
 * @param mobFriendly   heuristic: reliable for a generic mob caster (instant, target-aimed)
 * @param provenance    which layer decided the spell's capability: {@code VERIFIED}, {@code OVERRIDE},
 *                      {@code MANIFEST}, {@code NAMESPACE_TRUSTED} or {@code UNVERIFIED}
 */
public record SpellInfo(
        String id,
        String school,
        String rarity,
        int cooldownTicks,
        String castType,
        boolean mobFriendly,
        String provenance
) {}
