package com.otectus.magicnpcs.compat.customnpcs;

import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import com.otectus.magicnpcs.core.caster.ReconcileReason;
import com.otectus.magicnpcs.integration.irons.CasterReconciler;
import com.otectus.magicnpcs.integration.irons.IronsSpellcasterHandler;
import net.minecraft.world.entity.Mob;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts back the casting goals CustomNPCs deletes.
 *
 * <p>CustomNPCs rebuilds each NPC's AI on a fixed cadence, and does it by clearing both goal selectors
 * outright — every goal any other mod injected goes with them. That is not a bug to work around in
 * CustomNPCs' code; it is how the mod keeps an NPC's behaviour in sync with the settings an author can
 * change at any moment. The answer is to notice and re-run the reconcile that installed the goals in
 * the first place.
 *
 * <p>Nothing here touches {@code goalSelector}. The repair is a request onto Magic NPCs' existing
 * bounded, deduplicated server-tick queue, so it lands on a later tick — mutating the selector from
 * inside the event CustomNPCs fires while rebuilding it would be a concurrent modification of the
 * collection being rebuilt.
 *
 * <p>This class imports {@code integration.irons}. It must therefore only ever be reached from behind
 * an {@code IronsCompat.isLoaded()} guard — {@link CustomNpcsIntegration} holds it as a
 * {@code Consumer<Mob>} for exactly that reason.
 */
public final class CustomNpcsAiRepair {

    /** entity id → the game time we last asked for a repair, so one tick yields at most one request. */
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    private static volatile int repairs;
    private static volatile int duplicatesSeen;
    private static volatile int failures;

    private CustomNpcsAiRepair() {}

    /**
     * Check one NPC and request a reconcile if anything Magic NPCs owns has gone missing.
     *
     * <p>Ordered cheapest-first: a mob that was never a managed caster costs one map lookup, which
     * matters because this runs for every CustomNPC on every update event.
     */
    public static void check(Mob mob) {
        if (ManagedCasterState.peek(mob) == null) {
            return; // never managed by us — there is nothing of ours that could be missing
        }
        if (CasterReconciler.ownedGoalsIntact(mob)) {
            return;
        }
        long now = mob.level().getGameTime();
        Long previous = LAST_REQUEST.put(mob.getUUID(), now);
        if (previous != null && previous == now) {
            duplicatesSeen++;
            return;
        }
        try {
            IronsSpellcasterHandler.requestReconcile(mob, ReconcileReason.CUSTOMNPCS_AI_REBUILD);
            repairs++;
            CustomNpcsActivityState.noteRepair(mob);
        } catch (RuntimeException ex) {
            failures++;
            throw ex; // the bridge's own breaker decides what to do about it
        }
    }

    /**
     * @return {@code repairs/duplicates/failures} as one string.
     *
     * <p>Formatted here rather than by the diagnostics contributor so the counters can be reported
     * without that contributor naming this class: it is reached as a method reference created inside
     * the Iron's guard, which is the only way an Iron's-free install may never resolve it.
     */
    public static String countersLine() {
        return repairs + "/" + duplicatesSeen + "/" + failures;
    }

    public static int repairs() {
        return repairs;
    }

    public static int duplicatesSeen() {
        return duplicatesSeen;
    }

    public static int failures() {
        return failures;
    }

    /** Drop the counters and the dedup table. Server shutdown, and the bridge's own teardown. */
    public static void reset() {
        LAST_REQUEST.clear();
        repairs = 0;
        duplicatesSeen = 0;
        failures = 0;
    }
}
