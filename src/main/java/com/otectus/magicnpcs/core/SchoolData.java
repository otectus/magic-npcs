package com.otectus.magicnpcs.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Per-entity assigned magic school, stored in the entity's persistent data (so it
 * survives save/load, chunk reloads, and dimension travel without a custom
 * capability). Vanilla-only — no Iron's import — so it is safe to read/write from
 * anywhere, including code paths that run without Iron's present.
 *
 * <p>State machine for the {@code school} key:
 * <ul>
 *   <li>absent — the entity has never been rolled for caster eligibility;</li>
 *   <li>{@link #NONE} ("none") — rolled and chosen NOT to be a caster (sticky, so we
 *       do not re-roll every chunk load);</li>
 *   <li>a school id string — the assigned school.</li>
 * </ul>
 */
public final class SchoolData {
    /** Sub-compound under {@link Entity#getPersistentData()} so we never clobber other mods' keys. */
    public static final String ROOT = "magicnpcs";
    private static final String KEY_SCHOOL = "school";
    /** Sentinel marking "rolled, not a caster". */
    public static final String NONE = "none";

    private SchoolData() {}

    private static CompoundTag root(Entity entity) {
        CompoundTag data = entity.getPersistentData();
        if (!data.contains(ROOT, CompoundTag.TAG_COMPOUND)) {
            data.put(ROOT, new CompoundTag());
        }
        return data.getCompound(ROOT);
    }

    /** @return the raw stored value: a school id, {@link #NONE}, or {@code null} if not yet rolled. */
    public static String getRaw(Entity entity) {
        CompoundTag r = root(entity);
        return r.contains(KEY_SCHOOL) ? r.getString(KEY_SCHOOL) : null;
    }

    /** @return the assigned school id, or {@code null} if unassigned or a marked non-caster. */
    public static ResourceLocation getSchool(Entity entity) {
        String raw = getRaw(entity);
        if (raw == null || raw.isEmpty() || raw.equals(NONE)) {
            return null;
        }
        return ResourceLocation.tryParse(raw);
    }

    /** @return true once the entity has been rolled (assigned a school or marked non-caster). */
    public static boolean hasRolled(Entity entity) {
        return getRaw(entity) != null;
    }

    public static void set(Entity entity, ResourceLocation school) {
        root(entity).putString(KEY_SCHOOL, school.toString());
    }

    /** Sticky-mark this entity as rolled-but-not-a-caster so it is not re-rolled. */
    public static void markNonCaster(Entity entity) {
        root(entity).putString(KEY_SCHOOL, NONE);
    }

    /** Remove any assignment, returning the entity to the unrolled state. */
    public static void clear(Entity entity) {
        root(entity).remove(KEY_SCHOOL);
    }
}
