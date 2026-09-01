package com.otectus.magicnpcs.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards backlog B1: <b>reading school data must not write it</b>.
 *
 * <p>Before 0.6.0 the accessor {@code put} an empty {@code magicnpcs{}} compound into the entity's
 * persistent data on every read — and the tick handler read it for <em>every</em> mob in the world
 * before its early-out. Because Forge writes {@code ForgeData} for any entity whose persistent data
 * has been touched, that added a compound to every mob on the server, permanently, on disk.
 *
 * <p>These run against the {@link CompoundTag} directly (the accessors are split so the invariant is
 * testable without a Minecraft server).
 */
@SuppressWarnings("deprecation")
class PersistentDataTest {

    private static final ResourceLocation FIRE = new ResourceLocation("irons_spellbooks", "fire");

    @Test
    void readingAnUntouchedEntityWritesNothing() {
        CompoundTag persistentData = new CompoundTag();
        assertNull(SchoolData.getRaw(persistentData));
        assertTrue(persistentData.isEmpty(),
                "reading school data must not create the magicnpcs sub-compound");
        assertFalse(persistentData.contains(SchoolData.ROOT));
    }

    @Test
    void repeatedReadsStillWriteNothing() {
        CompoundTag persistentData = new CompoundTag();
        for (int i = 0; i < 100; i++) {
            SchoolData.getRaw(persistentData);
        }
        assertTrue(persistentData.isEmpty());
    }

    @Test
    void writingCreatesTheSubCompoundAndReadsBack() {
        CompoundTag persistentData = new CompoundTag();
        SchoolData.set(persistentData, FIRE, false);
        assertTrue(persistentData.contains(SchoolData.ROOT, CompoundTag.TAG_COMPOUND));
        assertEquals(FIRE.toString(), SchoolData.getRaw(persistentData));
    }

    @Test
    void anAutomaticAssignmentIsNotMarkedManual() {
        CompoundTag persistentData = new CompoundTag();
        SchoolData.set(persistentData, FIRE, false);
        assertFalse(SchoolData.isManual(persistentData));
    }

    @Test
    void aManualAssignmentIsRememberedAsManual() {
        // The flag is what makes a Tome/command choice outrank an explicit loadout on re-injection.
        // Without it, a chunk reload silently restored the loadout and threw the player's choice away.
        CompoundTag persistentData = new CompoundTag();
        SchoolData.set(persistentData, FIRE, true);
        assertTrue(SchoolData.isManual(persistentData));
        assertEquals(FIRE.toString(), SchoolData.getRaw(persistentData));
    }

    @Test
    void anAutomaticAssignmentOverAManualOneClearsTheFlag() {
        CompoundTag persistentData = new CompoundTag();
        SchoolData.set(persistentData, FIRE, true);
        SchoolData.set(persistentData, FIRE, false);
        assertFalse(SchoolData.isManual(persistentData),
                "the manual flag must not be sticky once an automatic roll overwrites the school");
    }

    @Test
    void readingTheManualFlagDoesNotCreateTheSubCompound() {
        CompoundTag persistentData = new CompoundTag();
        assertFalse(SchoolData.isManual(persistentData));
        assertTrue(persistentData.isEmpty(), "reads must never create persistent data (backlog B1)");
    }

    @Test
    void readReturnsADetachedTagWhenAbsentSoMutatingItCannotLeak() {
        CompoundTag persistentData = new CompoundTag();
        CompoundTag view = SchoolData.read(persistentData);
        view.putString("scribble", "value");
        assertTrue(persistentData.isEmpty(),
                "the empty view must be detached, not a live handle into persistent data");
    }
}
