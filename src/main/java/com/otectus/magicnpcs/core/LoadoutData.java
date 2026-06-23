package com.otectus.magicnpcs.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Per-entity record of which loadout variant a mob was assigned, when several loadouts match
 * its type (and profession) and form a pick-one pool. Stored in persistent data, in the same
 * {@link SchoolData#ROOT} sub-compound, so the choice is stable across chunk/world reloads
 * rather than re-rolling the variant every load. Vanilla-only — safe to read/write without
 * Iron's present.
 *
 * <p>Unlike {@link SchoolData} there is no "rolled non-caster" sentinel: loadout context
 * {@code conditions} are re-evaluated each spawn (so a context-gated loadout can come and go),
 * and only the pick among several currently-eligible variants needs to persist.
 */
public final class LoadoutData {
    private static final String KEY_LOADOUT = "loadout";

    private LoadoutData() {}

    private static CompoundTag root(Entity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(SchoolData.ROOT, CompoundTag.TAG_COMPOUND)) {
            data.put(SchoolData.ROOT, new CompoundTag());
        }
        return data.getCompound(SchoolData.ROOT);
    }

    /** @return the persisted chosen-loadout source id, or {@code null} if none stored. */
    public static ResourceLocation getSource(Entity entity) {
        CompoundTag r = root(entity);
        return r.contains(KEY_LOADOUT) ? ResourceLocation.tryParse(r.getString(KEY_LOADOUT)) : null;
    }

    public static void setSource(Entity entity, ResourceLocation source) {
        root(entity).putString(KEY_LOADOUT, source.toString());
    }

    public static void clear(Entity entity) {
        root(entity).remove(KEY_LOADOUT);
    }
}
