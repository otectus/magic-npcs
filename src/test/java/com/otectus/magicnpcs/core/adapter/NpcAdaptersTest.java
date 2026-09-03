package com.otectus.magicnpcs.core.adapter;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Composition of the M2 additions: framework identity, ownership, traits, and held-item placement.
 *
 * <p>The split matters. Identity and equipment come from one adapter — two frameworks cannot both own
 * an NPC, and two adapters must not both write the same hand, or the second overwrites the first's
 * grant. Traits are the opposite: a fact one adapter knows about a mob is not made false by another
 * adapter not knowing it, so they are unioned.
 *
 * <p>No live {@code Mob} is needed: composition never dereferences the entity, and these stubs ignore
 * it, so the rules can be tested without a Minecraft runtime.
 */
class NpcAdaptersTest {

    private static final ResourceLocation CUSTOMNPCS = new ResourceLocation("customnpcs", "npc");
    private static final ResourceLocation EASY_NPC = new ResourceLocation("easy_npc", "npc");
    private static final ResourceLocation ROLE = new ResourceLocation("customnpcs", "role/trader");
    private static final ResourceLocation TEAM = new ResourceLocation("magicnpcs", "team/red");

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    /** An adapter that answers exactly what the test told it to. */
    private record Stub(int priority, ResourceLocation framework, UUID owner,
                        Set<ResourceLocation> traits, boolean handlesHeldItem) implements NpcAdapter {
        @Override public boolean appliesTo(Mob mob) { return true; }
        @Override public int priority() { return priority; }
        @Override public Optional<ResourceLocation> frameworkId() { return Optional.ofNullable(framework); }
        @Override public Optional<UUID> ownerId(Mob mob) { return Optional.ofNullable(owner); }
        @Override public Set<ResourceLocation> traits(Mob mob) { return traits; }
        @Override public boolean setHeldItem(Mob mob, InteractionHand hand, ItemStack stack) {
            return handlesHeldItem;
        }
    }

    /** An adapter that overrides nothing, so every M2 method falls through to its default. */
    private record Bare() implements NpcAdapter {
        @Override public boolean appliesTo(Mob mob) { return true; }
    }

    @BeforeEach
    @AfterEach
    void reset() {
        NpcAdapters.clearForTest();
    }

    @Test
    void aBareAdapterAnswersTheDefaults() {
        NpcAdapters.register(new Bare());
        NpcAdapter resolved = NpcAdapters.resolve(null);
        assertTrue(resolved.frameworkId().isEmpty());
        assertTrue(resolved.ownerId(null).isEmpty());
        assertEquals(Set.of(), resolved.traits(null));
        assertFalse(resolved.setHeldItem(null, InteractionHand.MAIN_HAND, null));
    }

    @Test
    void withNoAdaptersAtAllTheDefaultsStillHold() {
        NpcAdapter resolved = NpcAdapters.resolve(null);
        assertTrue(resolved.frameworkId().isEmpty());
        assertTrue(resolved.ownerId(null).isEmpty());
        assertEquals(Set.of(), resolved.traits(null));
        assertFalse(resolved.setHeldItem(null, InteractionHand.MAIN_HAND, null));
    }

    @Test
    void frameworkIdComesFromThePrimaryAdapter() {
        // Registration order deliberately puts the lower-priority adapter first.
        NpcAdapters.register(new Stub(-100, EASY_NPC, null, Set.of(), false));
        NpcAdapters.register(new Stub(100, CUSTOMNPCS, null, Set.of(), false));
        assertEquals(Optional.of(CUSTOMNPCS), NpcAdapters.resolve(null).frameworkId());
    }

    @Test
    void ownerIdComesFromThePrimaryAdapter() {
        NpcAdapters.register(new Stub(-100, EASY_NPC, OTHER_OWNER, Set.of(), false));
        NpcAdapters.register(new Stub(100, CUSTOMNPCS, OWNER, Set.of(), false));
        assertEquals(Optional.of(OWNER), NpcAdapters.resolve(null).ownerId(null));
    }

    @Test
    void setHeldItemComesFromThePrimaryAdapter() {
        // The generic adapter would happily claim the hand; only the primary's answer may be used, or
        // two adapters end up writing the same slot and the loser's grant vanishes.
        NpcAdapters.register(new Stub(-100, EASY_NPC, null, Set.of(), true));
        NpcAdapters.register(new Stub(100, CUSTOMNPCS, null, Set.of(), false));
        assertFalse(NpcAdapters.resolve(null).setHeldItem(null, InteractionHand.MAIN_HAND, null));

        NpcAdapters.clearForTest();
        NpcAdapters.register(new Stub(-100, EASY_NPC, null, Set.of(), false));
        NpcAdapters.register(new Stub(100, CUSTOMNPCS, null, Set.of(), true));
        assertTrue(NpcAdapters.resolve(null).setHeldItem(null, InteractionHand.MAIN_HAND, null));
    }

    @Test
    void traitsAreTheUnionOfEveryApplicableAdapter() {
        NpcAdapters.register(new Stub(-100, EASY_NPC, null, Set.of(TEAM), false));
        NpcAdapters.register(new Stub(100, CUSTOMNPCS, null, Set.of(ROLE), false));
        assertEquals(Set.of(ROLE, TEAM), NpcAdapters.resolve(null).traits(null));
    }

    /** An adapter that records every signal it is handed and answers a fixed veto. */
    private static final class Listener implements NpcAdapter {
        private final int priority;
        private final boolean veto;
        private final List<String> heard = new ArrayList<>();

        private Listener(int priority, boolean veto) {
            this.priority = priority;
            this.veto = veto;
        }

        @Override public boolean appliesTo(Mob mob) { return true; }
        @Override public int priority() { return priority; }
        @Override public boolean publish(Mob mob, MagicNpcSignal signal) {
            heard.add(signal.name());
            return veto;
        }
    }

    @Test
    void aSignalReachesEveryAdapterAndTheirVetoesAreOred() {
        Listener vetoing = new Listener(100, true);
        Listener passive = new Listener(-100, false);
        NpcAdapters.register(vetoing);
        NpcAdapters.register(passive);

        MagicNpcSignal signal = MagicNpcSignal.of(MagicNpcSignal.CAST_PRE, Map.of("level", 1));
        assertTrue(NpcAdapters.resolve(null).publish(null, signal),
                "either framework may stop a cast that has not started yet");
        // The point of not short-circuiting: the second adapter's script may have state riding on
        // hearing every cast, and a veto from the first must not silence it.
        assertEquals(List.of(MagicNpcSignal.CAST_PRE), vetoing.heard);
        assertEquals(List.of(MagicNpcSignal.CAST_PRE), passive.heard,
                "a veto from the higher-priority adapter must not stop the signal reaching the other");
    }

    @Test
    void withNoAdapterVetoingTheCastProceeds() {
        NpcAdapters.register(new Listener(100, false));
        NpcAdapters.register(new Bare());
        assertFalse(NpcAdapters.resolve(null).publish(null,
                MagicNpcSignal.of(MagicNpcSignal.CAST_STARTED, Map.of())));
    }
}
