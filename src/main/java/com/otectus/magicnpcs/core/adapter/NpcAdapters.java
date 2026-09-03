package com.otectus.magicnpcs.core.adapter;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of {@link NpcAdapter}s, and the composition rules that combine every applicable one.
 *
 * <p>0.6.1 <em>selected</em> a single adapter: the applicable one with the highest priority won and the
 * rest were discarded. But these policies are orthogonal. A Recruits adapter (priority 100) winning
 * over the generic owner/team adapter (priority -100) meant a recruit that was also on a scoreboard
 * team, or was somebody's pet, silently lost that protection — a safety property disappearing because a
 * more specific adapter existed is exactly backwards (audit TGT-001).
 *
 * <p>Composition rules, chosen so that adding an adapter can only ever make behaviour <em>safer</em>:
 *
 * <ul>
 *   <li><b>state blockers</b> ({@code canCastNow}, {@code canSupportCastNow}) combine with AND — any
 *       adapter may veto casting;</li>
 *   <li><b>relationships</b> ({@code isAlly}, {@code canCastAt}) take the most protective answer — an
 *       entity any adapter calls an ally is an ally;</li>
 *   <li><b>scaling and progression</b> ({@code manaScale}, {@code level}, {@code schoolAssignable})
 *       come from one explicitly selected provider: the highest-priority applicable adapter, because
 *       multiplying two mods' rank scaling together is not meaningful;</li>
 *   <li><b>movement</b> ({@code movementPolicy}) takes the most <em>restrictive</em> answer — see
 *       {@link NpcAdapter.MovementPolicy#and};</li>
 *   <li><b>identity and equipment</b> ({@code frameworkId}, {@code ownerId}, {@code setHeldItem}) come
 *       from the same single provider as scaling: two frameworks cannot both own one NPC, and two
 *       adapters must not both write the same hand;</li>
 *   <li><b>traits</b> ({@code traits}) is the <em>union</em> across every applicable adapter — a fact
 *       one adapter knows is not made false by another adapter not knowing it;</li>
 *   <li><b>signals</b> ({@code publish}) go to <em>every</em> applicable adapter and their veto answers
 *       are OR-ed — an NPC that belongs to two frameworks must be announced to both, and either one
 *       may stop a cast that has not started yet.</li>
 * </ul>
 *
 * <p>Pure — adapters are registered behind their own mod guards (e.g. {@code RecruitsCompat.isLoaded()}),
 * so this class never imports those mods.
 */
public final class NpcAdapters {
    private static final NpcAdapter DEFAULT = new NpcAdapter() {
        @Override
        public boolean appliesTo(Mob mob) {
            return true;
        }
    };

    private static final List<NpcAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    private NpcAdapters() {}

    public static void register(NpcAdapter adapter) {
        ADAPTERS.add(adapter);
    }

    /** Drop every registered adapter. For tests only — production registers once at mod construction. */
    public static void clearForTest() {
        ADAPTERS.clear();
    }

    /**
     * @return a composite view of every adapter that applies to {@code mob}, or the no-op default when
     *         none does. The return type is unchanged from 0.6.1, so every call site keeps working;
     *         what changed is that the answer now reflects all of them rather than only the winner.
     */
    public static NpcAdapter resolve(Mob mob) {
        List<NpcAdapter> applicable = new ArrayList<>(2);
        NpcAdapter primary = null;
        int bestPriority = Integer.MIN_VALUE;
        for (NpcAdapter adapter : ADAPTERS) {
            if (!adapter.appliesTo(mob)) {
                continue;
            }
            applicable.add(adapter);
            if (adapter.priority() > bestPriority) {
                bestPriority = adapter.priority();
                primary = adapter;
            }
        }
        if (applicable.isEmpty()) {
            return DEFAULT;
        }
        if (applicable.size() == 1) {
            return applicable.get(0); // nothing to compose; keep the concrete class for diagnostics
        }
        return new Composite(List.copyOf(applicable), primary);
    }

    /** @return the names of every adapter applying to {@code mob}, for {@code /magicnpcs why}. */
    public static List<String> describe(Mob mob) {
        List<String> names = new ArrayList<>(2);
        for (NpcAdapter adapter : ADAPTERS) {
            if (adapter.appliesTo(mob)) {
                names.add(adapter.getClass().getSimpleName());
            }
        }
        return names.isEmpty() ? List.of("none") : names;
    }

    /**
     * Every applicable adapter, combined by the rules in the class javadoc.
     *
     * @param all     each applicable adapter, in registration order
     * @param primary the highest-priority one, which owns scaling and progression
     */
    private record Composite(List<NpcAdapter> all, NpcAdapter primary) implements NpcAdapter {

        @Override
        public boolean appliesTo(Mob mob) {
            return true; // it was built from adapters that already said yes
        }

        @Override
        public int priority() {
            return primary.priority();
        }

        // --- scaling and progression: one selected provider ---------------------------------------

        @Override
        public double manaScale(Mob mob) {
            return primary.manaScale(mob);
        }

        @Override
        public int level(Mob mob) {
            return primary.level(mob);
        }

        @Override
        public SchoolRollPolicy schoolRollPolicy(Mob mob) {
            return primary.schoolRollPolicy(mob);
        }

        @Override
        public boolean schoolAssignable(Mob mob) {
            for (NpcAdapter adapter : all) {
                if (adapter.schoolAssignable(mob)) {
                    return true;
                }
            }
            return false;
        }

        // --- identity and equipment: one selected provider ------------------------------------------

        @Override
        public Optional<ResourceLocation> frameworkId() {
            return primary.frameworkId();
        }

        @Override
        public Optional<UUID> ownerId(Mob mob) {
            return primary.ownerId(mob);
        }

        @Override
        public boolean setHeldItem(Mob mob, InteractionHand hand, ItemStack stack) {
            return primary.setHeldItem(mob, hand, stack);
        }

        // --- traits: union --------------------------------------------------------------------------

        @Override
        public Set<ResourceLocation> traits(Mob mob) {
            Set<ResourceLocation> union = new LinkedHashSet<>();
            for (NpcAdapter adapter : all) {
                union.addAll(adapter.traits(mob));
            }
            return union;
        }

        // --- signals: every adapter hears it, vetoes are OR-ed ------------------------------------

        @Override
        public boolean publish(Mob mob, MagicNpcSignal signal) {
            boolean veto = false;
            for (NpcAdapter adapter : all) {
                // Deliberately not short-circuiting: a veto from the first adapter must not silence
                // the second one's script, which may have state riding on hearing every cast.
                veto |= adapter.publish(mob, signal);
            }
            return veto;
        }

        // --- state blockers: AND ------------------------------------------------------------------

        @Override
        public boolean canCastNow(Mob mob) {
            for (NpcAdapter adapter : all) {
                if (!adapter.canCastNow(mob)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean canSupportCastNow(Mob mob) {
            for (NpcAdapter adapter : all) {
                if (!adapter.canSupportCastNow(mob)) {
                    return false;
                }
            }
            return true;
        }

        // --- relationships: most protective wins ---------------------------------------------------

        @Override
        public boolean canCastAt(Mob caster, LivingEntity target) {
            for (NpcAdapter adapter : all) {
                if (!adapter.canCastAt(caster, target)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean tracksAllies() {
            for (NpcAdapter adapter : all) {
                if (adapter.tracksAllies()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isAlly(Mob caster, LivingEntity other) {
            for (NpcAdapter adapter : all) {
                if (adapter.isAlly(caster, other)) {
                    return true;
                }
            }
            return false;
        }

        // --- movement: most restrictive wins -------------------------------------------------------

        @Override
        public MovementPolicy movementPolicy(Mob mob) {
            MovementPolicy combined = MovementPolicy.FREE;
            for (NpcAdapter adapter : all) {
                combined = combined.and(adapter.movementPolicy(mob));
            }
            return combined;
        }

        /** Named for the diagnostics, which print the adapter's simple class name. */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Composite[");
            for (int i = 0; i < all.size(); i++) {
                if (i > 0) {
                    sb.append('+');
                }
                sb.append(all.get(i).getClass().getSimpleName());
            }
            return sb.append(']').toString();
        }
    }
}
