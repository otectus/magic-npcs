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
 *
 * <p>The companion {@code manual} flag records that a <em>player</em> set this value with the School
 * Tome or {@code /magicnpcs school}, as opposed to the automatic spawn roll. A manual assignment
 * outranks an explicit datapack loadout and survives chunk reloads; an automatic one does not.
 * Without it, re-injection after a chunk reload resolved the explicit loadout first and silently
 * threw the player's choice away — the "the school comes and goes" report. The flag is absent on
 * pre-0.6.1 entities, which reads as "automatic", so existing worlds are unaffected.
 *
 * <p><b>Reads never write.</b> Before 0.6.0 the accessor {@code put} an empty
 * {@code magicnpcs{}} compound on every read, which — because Forge persists
 * {@code ForgeData} for any entity whose persistent data has been touched — added a
 * compound to every mob in the world, permanently, on disk (backlog B1). Read paths now
 * use {@link CompoundTag#getCompound(String)}, which returns a detached empty tag when the
 * key is absent; only {@link #set}, {@link #markNonCaster} and {@link #clear} create it.
 */
public final class SchoolData {
    /** Sub-compound under {@link Entity#getPersistentData()} so we never clobber other mods' keys. */
    public static final String ROOT = "magicnpcs";
    private static final String KEY_SCHOOL = "school";
    /** Set when a player (Tome or command) chose this value, rather than the automatic spawn roll. */
    private static final String KEY_MANUAL = "manual";
    /** The villager profession the stored assignment was decided for. */
    private static final String KEY_PROFESSION = "profession";
    /** Sentinel marking "rolled, not a caster". */
    public static final String NONE = "none";

    /**
     * The three states a mob's school assignment can be in, made explicit in 0.6.2 (audit "Manual
     * override fallback defect").
     *
     * <p>0.6.1 stored the same two keys but read them ad hoc, and the injection path fell through from
     * a manual school to an explicit datapack loadout whenever that school happened to yield nothing
     * today — schools disabled, the school removed from Iron's, the pool emptied by a config cap. The
     * command and the help text both promised that a manual assignment "overrides any loadout and
     * persists", so a player's choice silently became a datapack's. Naming the states makes the
     * contract checkable: {@link #MANUAL_SCHOOL} installs that school or nothing, and says why.
     */
    public enum Mode {
        /** No player has touched this mob: the spawn roll and datapack loadouts decide. */
        AUTO,
        /** A player assigned a specific school. It wins over any loadout, or the mob does not cast. */
        MANUAL_SCHOOL,
        /** A player cleared this mob by hand. It does not cast, whatever any datapack says. */
        MANUAL_DISABLED
    }

    private SchoolData() {}

    /** @return which of the three assignment states {@code entity} is in. */
    public static Mode mode(Entity entity) {
        if (!isManual(entity)) {
            return Mode.AUTO;
        }
        return getSchool(entity) == null ? Mode.MANUAL_DISABLED : Mode.MANUAL_SCHOOL;
    }

    /**
     * Return a mob to {@link Mode#AUTO}, so the spawn roll and datapack loadouts decide again.
     *
     * <p>Distinct from {@link #clear}: {@code clear} wipes the record entirely, while this only drops
     * the "a player chose this" flag. There was no way to undo a manual assignment short of assigning
     * another school, which made "I set this by mistake" unrecoverable.
     */
    public static void returnToAuto(Entity entity) {
        if (entity.getPersistentData().contains(ROOT, CompoundTag.TAG_COMPOUND)) {
            CompoundTag tag = read(entity);
            tag.remove(KEY_SCHOOL);
            tag.remove(KEY_MANUAL);
        }
    }

    /** Read-only view of our sub-compound: an empty, detached tag when we have never written to it. */
    static CompoundTag read(Entity entity) {
        return read(entity.getPersistentData());
    }

    /** Mutable view of our sub-compound, created on first use. Call only when actually writing. */
    static CompoundTag write(Entity entity) {
        return write(entity.getPersistentData());
    }

    /**
     * {@link CompoundTag#getCompound(String)} returns the live sub-tag when present and a fresh,
     * <em>detached</em> empty one when absent — so this read leaves {@code persistentData} untouched.
     * Split out from the {@link Entity} overload so the no-write invariant is unit-testable.
     */
    static CompoundTag read(CompoundTag persistentData) {
        return persistentData.getCompound(ROOT);
    }

    static CompoundTag write(CompoundTag persistentData) {
        if (!persistentData.contains(ROOT, CompoundTag.TAG_COMPOUND)) {
            persistentData.put(ROOT, new CompoundTag());
        }
        return persistentData.getCompound(ROOT);
    }

    /** @return the raw stored value: a school id, {@link #NONE}, or {@code null} if not yet rolled. */
    public static String getRaw(Entity entity) {
        return getRaw(entity.getPersistentData());
    }

    static String getRaw(CompoundTag persistentData) {
        CompoundTag r = read(persistentData);
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

    /** @return true if this entity's stored value was chosen by a player rather than rolled. */
    public static boolean isManual(Entity entity) {
        return isManual(entity.getPersistentData());
    }

    static boolean isManual(CompoundTag persistentData) {
        return read(persistentData).getBoolean(KEY_MANUAL);
    }

    /** Record an automatic (rolled) assignment. */
    public static void set(Entity entity, ResourceLocation school) {
        set(entity.getPersistentData(), school, false);
    }

    /**
     * Record an assignment, flagging whether a player chose it. A manual assignment outranks any
     * explicit loadout in {@code tryInject} and therefore survives chunk reloads.
     */
    public static void set(Entity entity, ResourceLocation school, boolean manual) {
        set(entity.getPersistentData(), school, manual);
    }

    static void set(CompoundTag persistentData, ResourceLocation school, boolean manual) {
        CompoundTag tag = write(persistentData);
        tag.putString(KEY_SCHOOL, school.toString());
        setManualFlag(tag, manual);
    }

    /** Sticky-mark this entity as rolled-but-not-a-caster so it is not re-rolled. */
    public static void markNonCaster(Entity entity) {
        markNonCaster(entity, false);
    }

    /**
     * Sticky-mark this entity a non-caster. When {@code manual}, the mark also suppresses an explicit
     * loadout — otherwise "clear" would be permanent for school casters and purely cosmetic for
     * loadout-driven ones, which is how a cleared recruit came back after a reload.
     */
    public static void markNonCaster(Entity entity, boolean manual) {
        CompoundTag tag = write(entity);
        tag.putString(KEY_SCHOOL, NONE);
        setManualFlag(tag, manual);
    }

    /** Remove any assignment, returning the entity to the unrolled state. */
    public static void clear(Entity entity) {
        if (entity.getPersistentData().contains(ROOT, CompoundTag.TAG_COMPOUND)) {
            CompoundTag tag = read(entity);
            tag.remove(KEY_SCHOOL);
            tag.remove(KEY_MANUAL);
            tag.remove(KEY_PROFESSION);
        }
    }

    /**
     * The villager profession this entity's assignment was decided for, or {@code null} if none was
     * recorded. Villagers change job routinely (job site broken → {@code none} → new job), and the
     * profession drives both the school map and profession-scoped loadouts — so an assignment is only
     * valid for the profession it was made under.
     */
    public static ResourceLocation getRolledProfession(Entity entity) {
        CompoundTag r = read(entity);
        return r.contains(KEY_PROFESSION) ? ResourceLocation.tryParse(r.getString(KEY_PROFESSION)) : null;
    }

    /** Record which profession the current assignment was decided for. */
    public static void setRolledProfession(Entity entity, ResourceLocation profession) {
        write(entity).putString(KEY_PROFESSION, profession.toString());
    }

    /** Store the flag only when true, so an automatic roll leaves no extra bytes on disk. */
    private static void setManualFlag(CompoundTag tag, boolean manual) {
        if (manual) {
            tag.putBoolean(KEY_MANUAL, true);
        } else {
            tag.remove(KEY_MANUAL);
        }
    }
}
