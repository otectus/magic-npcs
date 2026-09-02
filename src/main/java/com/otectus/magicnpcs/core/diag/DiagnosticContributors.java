package com.otectus.magicnpcs.core.diag;

import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lets a mod-specific integration add its own rows to {@code /magicnpcs why}.
 *
 * <p>The report is the answer to "why is this mob not casting", and for an NPC belonging to another
 * mod the answer is frequently something only that mod knows — it is paused, it belongs to a faction
 * that is not hostile to what it is looking at, its owner is offline. Without a seam like this, the
 * diagnostics would either stay silent about those causes or the Iron's-side reporting code would have
 * to import every supported NPC mod, which is exactly the classloading the compat guards exist to
 * prevent.
 *
 * <p>Contributors are registered behind their own mod guards, like
 * {@link com.otectus.magicnpcs.core.adapter.NpcAdapters} and
 * {@link com.otectus.magicnpcs.core.caster.CasterGoalListeners}.
 */
public final class DiagnosticContributors {

    /** Adds mod-specific rows for one mob. Called only for mobs the contributor recognises. */
    @FunctionalInterface
    public interface Contributor {
        /**
         * @param mob the mob being diagnosed; a contributor must check it belongs to its own mod and
         *            return without writing anything when it does not
         */
        void describe(Mob mob, DiagnosticReport.Builder out);
    }

    private static final List<Contributor> CONTRIBUTORS = new CopyOnWriteArrayList<>();

    private DiagnosticContributors() {}

    public static void register(Contributor contributor) {
        CONTRIBUTORS.add(contributor);
    }

    /** Drop every contributor. For tests only — production registers once at mod construction. */
    public static void clearForTest() {
        CONTRIBUTORS.clear();
    }

    /**
     * Append every contributor's rows.
     *
     * <p>A contributor that throws must not take the whole report down: a diagnostic command that
     * crashes is strictly worse than one missing a section, because the crash hides everything the
     * other sections had already worked out.
     */
    public static void describeAll(Mob mob, DiagnosticReport.Builder out) {
        for (Contributor contributor : CONTRIBUTORS) {
            try {
                contributor.describe(mob, out);
            } catch (Exception ex) {
                out.warn("a diagnostic contributor (" + contributor.getClass().getName()
                        + ") failed: " + ex);
            }
        }
    }
}
