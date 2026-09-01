package com.otectus.magicnpcs.core.adapter;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for adapter composition (audit TGT-001).
 *
 * <p>0.6.1 picked the single highest-priority applicable adapter and discarded the rest, so a recruit
 * that was also somebody's pet, or on a scoreboard team, or in a raid, lost that protection because a
 * <em>more specific</em> adapter existed. The rules asserted here are chosen so that registering an
 * extra adapter can only ever make behaviour safer.
 *
 * <p>No live {@code Mob} is needed: the composition logic never dereferences the entity, and these
 * stub adapters ignore it, so the rules can be tested without a Minecraft runtime.
 */
class NpcAdapterCompositionTest {

    /** A stub whose every answer is set by the test. */
    private static final class Stub implements NpcAdapter {
        private final int priority;
        private final boolean canCast;
        private final boolean canCastAt;
        private final boolean ally;
        private final boolean tracksAllies;
        private final double manaScale;
        private final int level;
        private final boolean schoolAssignable;

        Stub(int priority, boolean canCast, boolean canCastAt, boolean ally, boolean tracksAllies,
             double manaScale, int level, boolean schoolAssignable) {
            this.priority = priority;
            this.canCast = canCast;
            this.canCastAt = canCastAt;
            this.ally = ally;
            this.tracksAllies = tracksAllies;
            this.manaScale = manaScale;
            this.level = level;
            this.schoolAssignable = schoolAssignable;
        }

        static Stub permissive(int priority) {
            return new Stub(priority, true, true, false, false, 1.0, 0, false);
        }

        @Override public boolean appliesTo(Mob mob) { return true; }
        @Override public int priority() { return priority; }
        @Override public boolean canCastNow(Mob mob) { return canCast; }
        @Override public boolean canCastAt(Mob caster, LivingEntity target) { return canCastAt; }
        @Override public boolean isAlly(Mob caster, LivingEntity other) { return ally; }
        @Override public boolean tracksAllies() { return tracksAllies; }
        @Override public double manaScale(Mob mob) { return manaScale; }
        @Override public int level(Mob mob) { return level; }
        @Override public boolean schoolAssignable(Mob mob) { return schoolAssignable; }
    }

    @BeforeEach
    void reset() {
        NpcAdapters.clearForTest();
    }

    @AfterEach
    void tearDown() {
        NpcAdapters.clearForTest();
    }

    @Test
    void withNoAdaptersTheDefaultIsPermissive() {
        NpcAdapter resolved = NpcAdapters.resolve(null);
        assertTrue(resolved.canCastNow(null));
        assertTrue(resolved.canCastAt(null, null));
        assertFalse(resolved.isAlly(null, null));
        assertEquals(1.0, resolved.manaScale(null));
    }

    @Test
    void aSingleAdapterIsReturnedUnwrappedSoDiagnosticsKeepItsName() {
        Stub only = Stub.permissive(10);
        NpcAdapters.register(only);
        assertEquals(only, NpcAdapters.resolve(null));
    }

    @Test
    void aLowPriorityVetoStillBlocksCasting() {
        // The 0.6.1 failure mode in miniature: the high-priority adapter says yes, and used to be the
        // only one asked. State blockers combine with AND, so any adapter may still say no.
        NpcAdapters.register(new Stub(100, true, true, false, false, 1.0, 0, false));
        NpcAdapters.register(new Stub(-150, false, true, false, false, 1.0, 0, false));
        assertFalse(NpcAdapters.resolve(null).canCastNow(null));
    }

    @Test
    void aLowPriorityAllyIsStillProtected() {
        NpcAdapters.register(new Stub(100, true, true, false, false, 1.0, 0, false));
        NpcAdapters.register(new Stub(-100, true, false, true, true, 1.0, 0, false));
        NpcAdapter resolved = NpcAdapters.resolve(null);
        assertTrue(resolved.isAlly(null, null), "an entity any adapter calls an ally is an ally");
        assertFalse(resolved.canCastAt(null, null), "…and must not be shot at");
        assertTrue(resolved.tracksAllies(), "the ally scan must run if anything tracks allies");
    }

    @Test
    void manaScalingComesFromTheHighestPriorityAdapterAlone() {
        // Multiplying two mods' rank scaling together is not meaningful, so exactly one provides it.
        NpcAdapters.register(new Stub(100, true, true, false, false, 2.5, 7, true));
        NpcAdapters.register(new Stub(-100, true, true, false, false, 9.0, 3, false));
        NpcAdapter resolved = NpcAdapters.resolve(null);
        assertEquals(2.5, resolved.manaScale(null));
        assertEquals(7, resolved.level(null));
    }

    @Test
    void schoolEligibilityIsAUnionBecauseAnyAdapterMayKnowTheMobIsAProgressionNpc() {
        NpcAdapters.register(new Stub(100, true, true, false, false, 1.0, 0, false));
        NpcAdapters.register(new Stub(-100, true, true, false, false, 1.0, 0, true));
        assertTrue(NpcAdapters.resolve(null).schoolAssignable(null));
    }

    @Test
    void movementTakesTheMostRestrictivePolicy() {
        // Same direction as every other rule here: an extra adapter may only ever make the NPC less
        // free. A caster that ignores a hold-position order is worse than one that stands still.
        NpcAdapters.register(withMovement(100, NpcAdapter.MovementPolicy.FREE));
        NpcAdapters.register(withMovement(-100, NpcAdapter.MovementPolicy.PINNED));
        assertTrue(NpcAdapters.resolve(null).movementPolicy(null).pinned(),
                "a PINNED adapter must win over a FREE one, whatever the priorities");
    }

    @Test
    void twoAnchorsResolveToTheShorterLeash() {
        Vec3 anchor = new Vec3(0.0, 0.0, 0.0);
        NpcAdapters.register(withMovement(100, NpcAdapter.MovementPolicy.anchored(anchor, 12.0)));
        NpcAdapters.register(withMovement(-100, NpcAdapter.MovementPolicy.anchored(anchor, 4.0)));
        assertEquals(4.0, NpcAdapters.resolve(null).movementPolicy(null).leash());
    }

    @Test
    void anchoredBeatsFreeAndPinnedBeatsAnchored() {
        Vec3 anchor = new Vec3(0.0, 0.0, 0.0);
        NpcAdapters.register(withMovement(0, NpcAdapter.MovementPolicy.FREE));
        NpcAdapters.register(withMovement(1, NpcAdapter.MovementPolicy.anchored(anchor, 6.0)));
        assertEquals(NpcAdapter.MovementPolicy.Freedom.ANCHORED,
                NpcAdapters.resolve(null).movementPolicy(null).freedom());
        NpcAdapters.register(withMovement(2, NpcAdapter.MovementPolicy.PINNED));
        assertTrue(NpcAdapters.resolve(null).movementPolicy(null).pinned());
    }

    @Test
    void withNoOpinionTheDefaultIsFree() {
        NpcAdapters.register(Stub.permissive(10));
        assertEquals(NpcAdapter.MovementPolicy.Freedom.FREE,
                NpcAdapters.resolve(null).movementPolicy(null).freedom());
    }

    /** A stub that answers only {@code movementPolicy}; everything else is permissive. */
    private static NpcAdapter withMovement(int priority, NpcAdapter.MovementPolicy policy) {
        return new NpcAdapter() {
            @Override public boolean appliesTo(Mob mob) { return true; }
            @Override public int priority() { return priority; }
            @Override public MovementPolicy movementPolicy(Mob mob) { return policy; }
        };
    }

    @Test
    void supportCastingFollowsTheSameAndRuleAsAttackCasting() {
        NpcAdapters.register(new Stub(100, true, true, false, false, 1.0, 0, false));
        NpcAdapters.register(new Stub(-150, false, true, false, false, 1.0, 0, false));
        assertFalse(NpcAdapters.resolve(null).canSupportCastNow(null),
                "an adapter that blocks all casting blocks self-healing too");
    }
}
