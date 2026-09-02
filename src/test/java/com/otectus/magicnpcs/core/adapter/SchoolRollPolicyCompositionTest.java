package com.otectus.magicnpcs.core.adapter;

import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A progression NPC must be rolled for a school under <em>its own</em> mod's settings.
 *
 * <p>Before {@link NpcAdapter#schoolRollPolicy}, the assignment code read {@code [schools.recruits]}
 * directly and gated the branch on {@link NpcAdapter#schoolAssignable} alone. The moment a second
 * adapter answered that true — Easy NPC being the first — its NPCs were silently rolled under Villager
 * Recruits' caster chance, rank threshold and type map, and its own configuration section did nothing
 * at all. That is a silent, config-shaped failure of exactly the kind this project keeps finding, so
 * the routing is pinned here.
 *
 * <p>No live {@code Mob} is needed: composition never dereferences the entity.
 */
class SchoolRollPolicyCompositionTest {

    private static final NpcAdapter.SchoolRollPolicy RECRUITS = new NpcAdapter.SchoolRollPolicy(
            true, 0.35, 0, "RANDOM", List.of());
    private static final NpcAdapter.SchoolRollPolicy EASY_NPC = new NpcAdapter.SchoolRollPolicy(
            false, 0.25, 5, "BY_TYPE", List.of("easy_npc:humanoid=irons_spellbooks:fire"));

    /** An adapter that claims a priority and optionally publishes a policy. */
    private record Stub(int priority, NpcAdapter.SchoolRollPolicy policy) implements NpcAdapter {
        @Override public boolean appliesTo(Mob mob) { return true; }
        @Override public int priority() { return priority; }
        @Override public boolean schoolAssignable(Mob mob) { return true; }
        @Override public NpcAdapter.SchoolRollPolicy schoolRollPolicy(Mob mob) { return policy; }
    }

    @BeforeEach
    @AfterEach
    void reset() {
        NpcAdapters.clearForTest();
    }

    @Test
    void singleAdapterPublishesItsOwnPolicy() {
        NpcAdapters.register(new Stub(100, EASY_NPC));
        NpcAdapter resolved = NpcAdapters.resolve(null);
        assertEquals(EASY_NPC, resolved.schoolRollPolicy(null));
    }

    @Test
    void policyComesFromTheHighestPriorityAdapterNotWhicheverAnsweredFirst() {
        // Registration order deliberately puts the lower-priority adapter first: the winner must be
        // chosen by priority, not by who happened to register earliest.
        NpcAdapters.register(new Stub(10, RECRUITS));
        NpcAdapters.register(new Stub(200, EASY_NPC));

        NpcAdapter resolved = NpcAdapters.resolve(null);
        assertEquals(EASY_NPC, resolved.schoolRollPolicy(null),
                "the owning mod's own settings must win, not the other mod's");
        assertEquals(5, resolved.schoolRollPolicy(null).minLevel());
        assertTrue(resolved.schoolAssignable(null));
    }

    @Test
    void anAdapterWithNoOpinionDoesNotMaskTheOneThatHasOne() {
        NpcAdapters.register(new Stub(-100, null)); // e.g. the generic owner/team adapter
        NpcAdapters.register(new Stub(100, RECRUITS));

        assertEquals(RECRUITS, NpcAdapters.resolve(null).schoolRollPolicy(null));
    }

    @Test
    void noPolicyAtAllMeansNoProgressionAssignment() {
        NpcAdapters.register(new Stub(-100, null));
        NpcAdapters.register(new Stub(0, null));

        // Null is the signal the assignment code uses to skip the progression branch entirely, so it
        // must survive composition rather than becoming some default policy.
        assertNull(NpcAdapters.resolve(null).schoolRollPolicy(null));
    }

    @Test
    void defaultAdapterPublishesNoPolicy() {
        assertNull(NpcAdapters.resolve(null).schoolRollPolicy(null),
                "a mob no adapter claims must not be rolled as a progression NPC");
    }
}
