package com.otectus.magicnpcs.core.loadout;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * The full outcome of asking "which loadout applies to this mob?" — the answer <em>and</em> why, so the
 * diagnostic commands can explain a mob that isn't casting instead of just reporting "no loadout".
 *
 * <p>Produced by {@link LoadoutManager#peek(net.minecraft.world.entity.Mob)}, which is strictly
 * read-only: it writes no entity NBT and draws nothing from the mob's RNG (0.5.0's {@code resolve} did
 * both, from a documented read-only command — backlog B5).
 *
 * @param loadout the applicable loadout, or {@code null} for any status other than {@link Status#OK}
 * @param status  why this is (or is not) the answer
 * @param pool    the candidate loadouts for this mob's effective key after load-time override
 *                resolution — never {@code null}, possibly empty. This is the honest "1 of N pooled"
 *                count, unlike 0.5.0's per-type count which included profession-scoped loadouts the
 *                mob could never receive (backlog B16)
 * @param detail  extra context for the status (the blocking namespace, the disabled type id), or {@code null}
 */
public record LoadoutResolution(
        SpellcasterLoadout loadout,
        Status status,
        List<SpellcasterLoadout> pool,
        String detail
) {
    public enum Status {
        /** A loadout applies. */
        OK,
        /** No loadout declares this entity type at all. */
        NO_LOADOUT,
        /** Loadouts exist for the type, but none matches this mob's profession bucket. */
        NO_PROFESSION_MATCH,
        /** The type is listed in {@code general.disabledEntityTypes}. */
        TYPE_DISABLED_BY_CONFIG,
        /** Every loadout for the effective key is {@code "enabled": false}. */
        ALL_LOADOUTS_DISABLED,
        /** The owning mod's {@code [compat]} namespace toggle is off. */
        COMPAT_TOGGLE_OFF,
        /** Loadouts apply to this mob, but none of their context {@code conditions} currently hold. */
        CONDITIONS_FAILED,
        /** Several variants apply and this mob has not yet persisted its pick (it picks on spawn). */
        POOL_UNRESOLVED,
        /**
         * A loadout applies to this type, but this individual NPC lost its one-time
         * {@code caster_chance} roll and is permanently a non-caster (0.6.0, restored 0.6.2).
         */
        NOT_A_CASTER,
        /** The one-time {@code caster_chance} roll has not happened yet (read-only resolution). */
        CASTER_CHANCE_UNROLLED
    }

    private static final List<SpellcasterLoadout> NONE = List.of();

    public static LoadoutResolution of(SpellcasterLoadout loadout, List<SpellcasterLoadout> pool) {
        return new LoadoutResolution(loadout, Status.OK, pool, null);
    }

    public static LoadoutResolution failed(Status status, List<SpellcasterLoadout> pool, String detail) {
        return new LoadoutResolution(null, status, pool == null ? NONE : pool, detail);
    }

    public static LoadoutResolution none(Status status) {
        return new LoadoutResolution(null, status, NONE, null);
    }

    public boolean isPresent() {
        return loadout != null;
    }

    /** A short, user-facing explanation for the diagnostic commands. */
    public String explain(ResourceLocation entityType) {
        return switch (status) {
            case OK -> "loadout " + loadout.source() + " (" + loadout.tier().label() + ")";
            case NO_LOADOUT -> "no spellcaster loadout declares " + entityType;
            case NO_PROFESSION_MATCH -> "loadouts exist for " + entityType
                    + " but all are scoped to other professions";
            case TYPE_DISABLED_BY_CONFIG -> entityType + " is listed in general.disabledEntityTypes";
            case ALL_LOADOUTS_DISABLED -> (detail != null ? detail
                    : "every loadout for " + entityType + " sets \"enabled\": false");
            case COMPAT_TOGGLE_OFF -> "the [compat] toggle for namespace '"
                    + entityType.getNamespace() + "' is off" + (detail == null ? "" : " (" + detail + ")");
            case CONDITIONS_FAILED -> "loadout context conditions do not hold here/now "
                    + "(dimension/biome/difficulty/time/y/weather/moon)";
            case POOL_UNRESOLVED -> pool.size() + " variants pool for this mob; "
                    + "it has not persisted a pick yet (chosen on spawn by pool_weight)";
            case NOT_A_CASTER -> "this individual lost its one-time caster_chance roll"
                    + (detail == null ? "" : " (" + detail + ")")
                    + " — use /magicnpcs school set to make it cast anyway";
            case CASTER_CHANCE_UNROLLED -> "the loadout sets caster_chance and this NPC has not been "
                    + "rolled yet (rolled once on first activation, then persisted)";
        };
    }
}
