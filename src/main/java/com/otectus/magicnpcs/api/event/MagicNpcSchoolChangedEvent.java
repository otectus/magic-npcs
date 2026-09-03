package com.otectus.magicnpcs.api.event;

import com.otectus.magicnpcs.core.SchoolData;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.eventbus.api.Event;

/**
 * An entity's magic school assignment changed, announced on {@code MinecraftForge.EVENT_BUS} once per
 * mutation — not once per write. Posted from the {@link SchoolData} writers themselves, which is the
 * only choke point every path (Tome, command, automatic roll, script) actually passes through.
 *
 * <p>Not cancellable: by the time it is posted the value is already stored, and a school change is a
 * bookkeeping fact rather than an action. Vetoing belongs on {@link MagicNpcCastEvent.Pre}.
 *
 * <p>Takes an {@link Entity} rather than a {@code Mob} because {@link SchoolData} does: the assignment
 * lives in persistent data and is readable and writable for anything that has some.
 */
public class MagicNpcSchoolChangedEvent extends Event {

    /** What caused the change. */
    public enum ChangeSource {
        /** A player sneak-clicked with the School Tome. */
        TOME,
        /** {@code /magicnpcs school ...}. */
        COMMAND,
        /** The automatic spawn/join roll. */
        AUTO_ROLL,
        /** The assignment was wiped — a manual clear, or a villager's career change. */
        CLEAR,
        /** A manual assignment was dropped, returning the entity to automatic. */
        RETURN_TO_AUTO,
        /** An NPC script asked for the change. */
        SCRIPT
    }

    private final Entity entity;
    private final String oldSchool;
    private final String newSchool;
    private final SchoolData.Mode mode;
    private final ChangeSource source;

    /**
     * @param oldSchool the previous raw value: a school id, {@link SchoolData#NONE}, or {@code null}
     *                  when the entity had never been assigned one
     * @param newSchool the new raw value, under the same three-way convention
     * @param mode      the assignment state the entity is in <em>after</em> the change
     */
    public MagicNpcSchoolChangedEvent(Entity entity, String oldSchool, String newSchool,
                                      SchoolData.Mode mode, ChangeSource source) {
        this.entity = entity;
        this.oldSchool = oldSchool;
        this.newSchool = newSchool;
        this.mode = mode;
        this.source = source;
    }

    public Entity getEntity() {
        return entity;
    }

    /** @return the previous raw value, or {@code null} if there was none. */
    public String getOldSchool() {
        return oldSchool;
    }

    /** @return the new raw value, or {@code null} if the assignment was removed entirely. */
    public String getNewSchool() {
        return newSchool;
    }

    public SchoolData.Mode getMode() {
        return mode;
    }

    public ChangeSource getSource() {
        return source;
    }
}
