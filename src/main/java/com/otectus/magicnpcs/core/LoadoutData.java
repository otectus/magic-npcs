package com.otectus.magicnpcs.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * Per-entity record of loadout state that must survive a chunk reload: which loadout variant a mob was
 * assigned when several matched (a pick-one pool), whether it won its one-time {@code caster_chance}
 * roll, and which loadout its managed equipment was granted for. Stored in persistent data in the same
 * {@link SchoolData#ROOT} sub-compound, and — like {@link SchoolData} — <b>reads never create it</b>
 * (backlog B1).
 *
 * <p>Goals are not persisted, so on every chunk reload the spawn handler sees a mob with no casting
 * goal and re-applies the loadout. Anything decided by a dice roll therefore has to be recorded here,
 * or the roll silently repeats: before 0.6.0 a loadout with {@code only_if_empty: false} replaced the
 * mob's held item on every reload, and {@code spawnWithGearChance} re-rolled until it eventually won
 * (backlog B10).
 *
 * <p><b>0.6.2 (audit RCN-004).</b> The old {@code equipped} boolean was a permanent latch: once set, a
 * changed loadout could never grant newly required gear, and a removed loadout never undid what it had
 * granted. It is replaced by the <em>source hash</em> of the loadout the equipment was applied for, so
 * "already equipped for this loadout" and "equipped for a different, older loadout" are distinguishable.
 * A legacy {@code equipped: true} flag still reads as "equipped for an unknown loadout", so upgrading a
 * world never re-rolls gear for an NPC that already has it.
 */
public final class LoadoutData {
    private static final String KEY_LOADOUT = "loadout";
    /** Legacy 0.5.0–0.6.1 boolean latch. Read for back-compat, never written. */
    private static final String KEY_EQUIPPED = "equipped";
    private static final String KEY_EQUIPPED_FOR = "equippedFor";
    private static final String KEY_CASTER_ROLL = "casterRoll";

    /** Stands in for "equipped under a version that did not record which loadout it was for". */
    public static final String LEGACY_EQUIPMENT_MARK = "<legacy>";

    private LoadoutData() {}

    /** @return the persisted chosen-loadout source id, or {@code null} if none stored. */
    public static ResourceLocation getSource(Entity entity) {
        var r = SchoolData.read(entity);
        return r.contains(KEY_LOADOUT) ? ResourceLocation.tryParse(r.getString(KEY_LOADOUT)) : null;
    }

    public static void setSource(Entity entity, ResourceLocation source) {
        SchoolData.write(entity).putString(KEY_LOADOUT, source.toString());
    }

    /**
     * @return {@code TRUE} if this NPC won its one-time {@code caster_chance} roll, {@code FALSE} if it
     *         lost, {@code null} if it has not been rolled yet
     */
    public static Boolean getCasterRoll(Entity entity) {
        var r = SchoolData.read(entity);
        return r.contains(KEY_CASTER_ROLL) ? r.getBoolean(KEY_CASTER_ROLL) : null;
    }

    /** Persist the {@code caster_chance} outcome. Once written it is never re-rolled. */
    public static void setCasterRoll(Entity entity, boolean isCaster) {
        SchoolData.write(entity).putBoolean(KEY_CASTER_ROLL, isCaster);
    }

    /**
     * @return the identity of the loadout this NPC's managed equipment was granted for, or
     *         {@code null} if it has never been through the equipment step.
     *         {@link #LEGACY_EQUIPMENT_MARK} means "equipped before 0.6.2, loadout unknown".
     */
    public static String getEquippedFor(Entity entity) {
        var r = SchoolData.read(entity);
        if (r.contains(KEY_EQUIPPED_FOR)) {
            return r.getString(KEY_EQUIPPED_FOR);
        }
        return r.getBoolean(KEY_EQUIPPED) ? LEGACY_EQUIPMENT_MARK : null;
    }

    /** Record that managed equipment has been applied for {@code loadoutMark} (win or lose). */
    public static void setEquippedFor(Entity entity, String loadoutMark) {
        SchoolData.write(entity).putString(KEY_EQUIPPED_FOR, loadoutMark);
    }

    public static void clear(Entity entity) {
        var r = SchoolData.read(entity);
        r.remove(KEY_LOADOUT);
        r.remove(KEY_EQUIPPED);
        r.remove(KEY_EQUIPPED_FOR);
        r.remove(KEY_CASTER_ROLL);
    }
}
