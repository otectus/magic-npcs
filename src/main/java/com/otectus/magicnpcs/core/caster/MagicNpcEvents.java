package com.otectus.magicnpcs.core.caster;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.api.event.MagicNpcCastEvent;
import com.otectus.magicnpcs.api.event.MagicNpcSchoolChangedEvent;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.adapter.MagicNpcSignal;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.common.MinecraftForge;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single place a cast or a school change is announced.
 *
 * <p>Each announcement goes out twice, in one order and from one method: the Forge event first, then
 * the same fact as a {@link MagicNpcSignal} handed to the mob's adapter. An NPC framework therefore
 * hears about a cast only through {@code NpcAdapter.publish} and never by subscribing to the Forge
 * bus — which is what keeps a script from being run twice for one cast, and keeps the re-entry guard
 * in one class instead of two.
 *
 * <p>Neutral: vanilla, Forge and Magic NPCs types only. It is called from the Iron's-side casting code
 * but names none of it, and it names no compat package either.
 *
 * <p><b>One terminal per cast.</b> A cast can plausibly be ended twice — the session cancels itself and
 * the goal that owned it cancels as well — and a script that heard two cancellations for one cast
 * would double-count. So {@link #postStarted} records the caster as having an open cast and the first
 * terminal clears it; a second terminal for the same open cast is dropped.
 */
public final class MagicNpcEvents {

    // Payload keys. Named here rather than at each call site so the script-facing contract is one list.
    private static final String KEY_SPELL = "spell";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_TARGET = "target";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_REASON = "reason";
    private static final String KEY_OLD_SCHOOL = "old_school";
    private static final String KEY_NEW_SCHOOL = "new_school";
    private static final String KEY_MODE = "mode";

    /**
     * Casters with a started cast that has not terminated yet, and which cast it is.
     *
     * <p>A bare set of caster ids was not enough. Completion is dispatched synchronously, so a
     * listener may answer one by starting a replacement cast on the same caster — and the old cast's
     * second terminal, arriving after that, would consume the replacement's ownership and silently
     * swallow the replacement's real ending (roadmap MN-004/MN-013). A terminal now has to name the
     * cast it is ending: a stale one finds a different spell recorded and is dropped, which is exactly
     * what it deserves.
     */
    private static final Map<UUID, ResourceLocation> CAST_OPEN = new ConcurrentHashMap<>();

    private MagicNpcEvents() {}

    /**
     * Announce a cast that is about to be committed, and collect vetoes.
     *
     * <p>Called before anything is spent, so a refusal here costs the caster no mana and starts no
     * cooldown. A veto — a Forge listener cancelling {@link MagicNpcCastEvent.Pre}, or an adapter
     * answering true to the {@code cast_pre} signal — is reported as a
     * {@link MagicNpcCastEvent.Cancelled} and a {@code cast_cancelled} signal, so a vetoed cast is
     * never a silent nothing.
     *
     * @return true if the cast may proceed
     */
    public static boolean postCastPre(Mob caster, ResourceLocation spellId, int level,
                                      LivingEntity target, MagicNpcCastEvent.CastSource source) {
        if (caster == null || spellId == null) {
            return true; // never block a cast because the announcement could not be made
        }
        MagicNpcCastEvent.Pre event = new MagicNpcCastEvent.Pre(caster, spellId, level, target, source);
        boolean cancelled = MinecraftForge.EVENT_BUS.post(event);
        String reason = cancelled ? event.getCancelReason() : "an NPC script vetoed the cast";
        if (!cancelled) {
            cancelled = NpcAdapters.resolve(caster).publish(caster,
                    MagicNpcSignal.of(MagicNpcSignal.CAST_PRE,
                            castPayload(spellId, level, target, source, null)));
        }
        if (!cancelled) {
            return true;
        }
        postCancelledInternal(caster, spellId, level, target, source, reason, false);
        return false;
    }

    /** The cast was accepted: mana is spent and the cooldown is running. Opens the terminal guard. */
    public static void postStarted(Mob caster, ResourceLocation spellId, int level,
                                   LivingEntity target, MagicNpcCastEvent.CastSource source) {
        if (caster == null || spellId == null) {
            return;
        }
        CAST_OPEN.put(caster.getUUID(), spellId);
        logCast("started", caster, spellId);
        post(caster, "started", new MagicNpcCastEvent.Started(caster, spellId, level, target, source));
        publish(caster, "started", MagicNpcSignal.of(MagicNpcSignal.CAST_STARTED,
                castPayload(spellId, level, target, source, null)));
    }

    /** The cast ran to the end of its duration. Dropped if this caster's cast already terminated. */
    public static void postCompleted(Mob caster, ResourceLocation spellId, int level,
                                     LivingEntity target, MagicNpcCastEvent.CastSource source) {
        if (caster == null || spellId == null || !CAST_OPEN.remove(caster.getUUID(), spellId)) {
            return;
        }
        logCast("completed", caster, spellId);
        post(caster, "completed", new MagicNpcCastEvent.Completed(caster, spellId, level, target, source));
        publish(caster, "completed", MagicNpcSignal.of(MagicNpcSignal.CAST_COMPLETED,
                castPayload(spellId, level, target, source, null)));
    }


    /**
     * The one operator-visible trace of a cast lifecycle. Nothing else prints Started or Completed, so
     * a run that is investigating whether a mob casts at all has only mana to go on; this makes the
     * two announcements greppable in the log without adding a per-cast line to a normal server.
     */
    private static void logCast(String phase, Mob caster, ResourceLocation spellId) {
        if (MagicNpcsConfig.debugLogging()) {
            MagicNpcs.LOGGER.debug("[magicnpcs] cast {}: {} ({}) casting {}", phase,
                    EntityType.getKey(caster.getType()), caster.getUUID(), spellId);
        }
    }

    /** The cast ended early. Dropped if this caster's cast already terminated. */
    public static void postCancelled(Mob caster, ResourceLocation spellId, int level,
                                     LivingEntity target, MagicNpcCastEvent.CastSource source,
                                     String reason) {
        if (caster == null || spellId == null) {
            return;
        }
        postCancelledInternal(caster, spellId, level, target, source, reason, true);
    }

    /**
     * A cast could not be started at all — an unknown spell, a refusal from Iron's, a gate the caller
     * applied before the pre-cast announcement. Signal only: nothing began, so there is no lifecycle
     * for a Forge listener to follow, and a cancellation for a cast that never existed would break the
     * started/terminal pairing this class guarantees.
     */
    public static void postFailed(Mob caster, ResourceLocation spellId, int level,
                                  LivingEntity target, MagicNpcCastEvent.CastSource source,
                                  String reason) {
        if (caster == null || spellId == null) {
            return;
        }
        publish(caster, "failed", MagicNpcSignal.of(MagicNpcSignal.CAST_FAILED,
                castPayload(spellId, level, target, source, reason)));
    }

    /**
     * An entity's school assignment changed. Posted from the {@link SchoolData} writers, so every path
     * — Tome, command, automatic roll, script — announces exactly once.
     *
     * @param oldSchool the previous raw value, or {@code null} when there was none
     * @param newSchool the new raw value, or {@code null} when the assignment was removed
     */
    public static void postSchoolChanged(Entity entity, String oldSchool, String newSchool,
                                         SchoolData.Mode mode,
                                         MagicNpcSchoolChangedEvent.ChangeSource source) {
        if (entity == null || entity.level().isClientSide()) {
            return;
        }
        MinecraftForge.EVENT_BUS.post(
                new MagicNpcSchoolChangedEvent(entity, oldSchool, newSchool, mode, source));
        if (!(entity instanceof Mob mob)) {
            return; // only a Mob has an adapter to publish to
        }
        Map<String, Object> payload = new LinkedHashMap<>(4);
        if (oldSchool != null) {
            payload.put(KEY_OLD_SCHOOL, oldSchool);
        }
        if (newSchool != null) {
            payload.put(KEY_NEW_SCHOOL, newSchool);
        }
        payload.put(KEY_MODE, mode.name().toLowerCase(Locale.ROOT));
        payload.put(KEY_SOURCE, source.name().toLowerCase(Locale.ROOT));
        NpcAdapters.resolve(mob).publish(mob, MagicNpcSignal.of(MagicNpcSignal.SCHOOL_CHANGED, payload));
    }

    /** @return true while this caster has a started cast that has not terminated yet. */
    public static boolean isCastOpen(Mob caster) {
        return caster != null && CAST_OPEN.containsKey(caster.getUUID());
    }

    /** @return the spell this caster's open cast is, or {@code null} when it has none. */
    public static ResourceLocation openCastSpell(Mob caster) {
        return caster == null ? null : CAST_OPEN.get(caster.getUUID());
    }

    /** @return how many casts are open, for the diagnostics summary. */
    public static int openCasts() {
        return CAST_OPEN.size();
    }

    /** Forget every open cast. Server shutdown — a UUID from a past world must not gate a new one. */
    public static void clear() {
        CAST_OPEN.clear();
    }

    /**
     * @param guarded true when this terminal must consume an open cast (a real cancellation), false for
     *                a veto, which never started one and so has nothing to consume
     */
    private static void postCancelledInternal(Mob caster, ResourceLocation spellId, int level,
                                              LivingEntity target,
                                              MagicNpcCastEvent.CastSource source, String reason,
                                              boolean guarded) {
        if (guarded && !CAST_OPEN.remove(caster.getUUID(), spellId)) {
            return;
        }
        post(caster, "cancelled",
                new MagicNpcCastEvent.Cancelled(caster, spellId, level, target, source, reason));
        publish(caster, "cancelled", MagicNpcSignal.of(MagicNpcSignal.CAST_CANCELLED,
                castPayload(spellId, level, target, source, reason)));
    }

    /**
     * Post a terminal or start event without letting a listener's failure escape.
     *
     * <p>These announcements are made from the middle of a cast session's terminal path. A third-party
     * listener that throws used to take the throw back through the session, past its cleanup, and on
     * into the goal selector — one misbehaving listener could leave a caster channelling forever and
     * stop the rest of that mob's AI (roadmap MN-004). The failure is reported once, against the
     * listener's phase, and the cast finishes.
     *
     * <p>Deliberately not applied to {@link #postCastPre}: a veto is a contract, and a listener that
     * throws while deciding whether a cast may happen has not answered. That path keeps Forge's own
     * behaviour so a broken veto cannot be read as consent.
     */
    private static void post(Mob caster, String phase, Object event) {
        try {
            MinecraftForge.EVENT_BUS.post((net.minecraftforge.eventbus.api.Event) event);
        } catch (RuntimeException | LinkageError failure) {
            MagicNpcs.LOGGER.error("[magicnpcs] a listener threw handling the cast-{} event for {}; "
                            + "the cast is unaffected and other casters continue.",
                    phase, EntityType.getKey(caster.getType()), failure);
        }
    }

    /** As {@link #post}, for the adapter signal half of the same announcement. */
    private static void publish(Mob caster, String phase, MagicNpcSignal signal) {
        try {
            NpcAdapters.resolve(caster).publish(caster, signal);
        } catch (RuntimeException | LinkageError failure) {
            MagicNpcs.LOGGER.error("[magicnpcs] an adapter threw publishing the cast-{} signal for {}; "
                            + "the cast is unaffected and other casters continue.",
                    phase, EntityType.getKey(caster.getType()), failure);
        }
    }

    /** The shared payload shape. {@code target} and {@code reason} are absent rather than null. */
    private static Map<String, Object> castPayload(ResourceLocation spellId, int level,
                                                   LivingEntity target,
                                                   MagicNpcCastEvent.CastSource source, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>(5);
        payload.put(KEY_SPELL, spellId.toString());
        payload.put(KEY_LEVEL, level);
        if (target != null) {
            payload.put(KEY_TARGET, target.getUUID().toString());
        }
        payload.put(KEY_SOURCE, source.name().toLowerCase(Locale.ROOT));
        if (reason != null && !reason.isEmpty()) {
            payload.put(KEY_REASON, reason);
        }
        return payload;
    }
}
