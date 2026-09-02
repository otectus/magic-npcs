package com.otectus.magicnpcs.core.caster;

import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Notification seam for "the casting goal on this mob was installed, replaced, or removed".
 *
 * <p>Exists because another mod may hold a <em>reference</em> to our goal object rather than merely
 * knowing one exists. Easy NPC is the motivating case: its {@code ObjectiveDataEntry} caches the
 * {@code Goal} its factory returned and re-adds that exact instance whenever it refreshes the
 * objective. A datapack reload that changes a loadout makes the reconciler build a new goal and drop
 * the old one — and without this notification Easy NPC would later resurrect the dead instance,
 * leaving the NPC running a goal that no longer matches any loadout, with no way to tell from
 * in-game that it had happened.
 *
 * <p>Deliberately a one-way publish from the reconciler outward, and deliberately Iron's-free: the
 * casting layer must not learn about the mods that consume it. Listeners are registered behind their
 * own mod guards, exactly like {@link com.otectus.magicnpcs.core.adapter.NpcAdapters}.
 */
public final class CasterGoalListeners {

    /** Notified after the set of Magic NPCs goals on a mob has changed. */
    @FunctionalInterface
    public interface Listener {
        /**
         * @param mob the mob whose casting goal was installed, replaced or removed. Callers must
         *            re-read the goal rather than assume one is present.
         */
        void onCastingGoalChanged(Mob mob);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private CasterGoalListeners() {}

    public static void register(Listener listener) {
        LISTENERS.add(listener);
    }

    /** Drop every registered listener. For tests only — production registers once at mod construction. */
    public static void clearForTest() {
        LISTENERS.clear();
    }

    /**
     * Tell every listener that {@code mob}'s casting goals changed.
     *
     * <p>A listener that throws must not abort a reconcile: reconciliation runs in bulk over every
     * loaded mob after a reload, and one badly behaved integration taking the pass down with it would
     * leave the rest of the world half-reconciled.
     */
    public static void fireGoalChanged(Mob mob) {
        if (LISTENERS.isEmpty()) {
            return;
        }
        for (Listener listener : LISTENERS) {
            try {
                listener.onCastingGoalChanged(mob);
            } catch (Exception ex) {
                com.otectus.magicnpcs.MagicNpcs.LOGGER.error(
                        "[magicnpcs] casting-goal listener {} threw; continuing",
                        listener.getClass().getName(), ex);
            }
        }
    }
}
