package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.api.event.MagicNpcCastEvent;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.caster.MagicNpcEvents;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Runs cast sessions that no goal owns.
 *
 * <p>{@link NpcSpellAttackGoal} drives its own {@link MobCastSession} from {@code Goal#tick}, which is
 * fine while a cast is something the AI decided to do. A cast triggered from outside the AI — an Easy
 * NPC dialog button, an action on a trigger — has no goal behind it, and a session that is begun and
 * never ticked would charge the mana, call Iron's {@code initiateCast}, and then hang: the spell would
 * never complete, and {@code MagicData.isCasting()} would stay true forever, blocking every later cast
 * on that mob.
 *
 * <p>So a detached cast is registered here and ticked on the server tick until it finishes. Sessions
 * are dropped as soon as their caster dies or leaves the world, with Iron's completion hook run on the
 * cancel path so anything a pre-cast step created is torn down rather than leaked.
 *
 * <p><b>Structural changes are staged.</b> Completion and cancellation notifications are dispatched
 * synchronously, and a listener is entirely entitled to answer one by asking the same NPC — or another
 * — to cast again. Through 0.9.0 that reached {@code ACTIVE.add} while {@code tickAll} was part-way
 * through iterating the same list, which is a {@code ConcurrentModificationException} waiting for a
 * script author to find (roadmap MN-013). Additions and removals now go into pending queues and are
 * applied between passes, never during one.
 */
public final class DetachedCastDriver {

    /**
     * A running session plus the mob it belongs to.
     *
     * <p>The caster is held here rather than read back off the session because {@link MobCastSession}
     * deliberately exposes only what a goal needs, and widening its surface for one extra caller would
     * be the wrong trade.
     */
    private record Detached(Mob caster, MobCastSession session) {}

    private static final List<Detached> ACTIVE = new ArrayList<>();

    /** Sessions accepted while a tick or a notification was in progress. Drained between passes. */
    private static final List<Detached> PENDING_ADDITIONS = new ArrayList<>();

    /** Sessions a callback asked to drop while a pass was running. Applied with the additions. */
    private static final List<MobCastSession> PENDING_REMOVALS = new ArrayList<>();

    /** True while {@link #tickAll} or {@link #clearAll} is walking {@link #ACTIVE}. */
    private static boolean processing;

    /**
     * True from the moment teardown begins until the next server starts.
     *
     * <p>Deliberately reset by {@link #armForNewServer()} rather than latched for the JVM's life: an
     * integrated server is stopped and started again every time a player leaves a world and opens
     * another, and a guard that stayed set would silently disable detached casting for the rest of
     * the session. Full reactivation of the NPC bridges across worlds remains a separate, deferred
     * problem (MN-010); this only scopes the guard this class added.
     */
    private static boolean tearingDown;

    /** Nothing here is the AI's decision: a detached cast was asked for by a script, command or dialog. */
    private static final MagicNpcCastEvent.CastSource SOURCE = MagicNpcCastEvent.CastSource.SCRIPT;

    private DetachedCastDriver() {}

    /** Why a detached cast could not be started, for the caller to report to whoever asked for it. */
    public enum Refusal {
        SPELL_UNKNOWN,
        NOT_A_CASTER,
        ON_COOLDOWN,
        REFUSED,
        /** The server is shutting down; new work is declined rather than queued into a dying world. */
        SHUTTING_DOWN
    }

    /**
     * @param refusal null when the cast started
     * @param detail  a human-readable reason, or null
     */
    public record Result(boolean started, Refusal refusal, String detail) {
        static Result ok() {
            return new Result(true, null, null);
        }

        static Result no(Refusal refusal, String detail) {
            return new Result(false, refusal, detail);
        }
    }

    /**
     * Begin a one-off cast of {@code spellId} by {@code caster}.
     *
     * <p>Applies exactly the gates the AI path applies, and applies them through the same object:
     * {@link CastRequestValidator} runs framework eligibility, the spell allow-list, the
     * mob-castability manifest, target identity/relationship, the canonical cooldown and the mana
     * check, then {@link MobCastSession#begin} runs Iron's own pre-cast conditions. A scripted cast
     * can never do something a chosen cast could not — skipping those filters on a secondary cast path
     * is the defect this repository already recorded as B6 and the roadmap records as MN-003.
     *
     * <p>The per-spell cooldown is one of those gates, and it is keyed by the spell's canonical id, so
     * a script using the registry id and a loadout using a bare one share one rest (MN-005).
     *
     * @param target the entity to cast at, or null for a self-cast
     * @param level  spell level; bounded by the spell's own minimum and maximum before anything reads it
     */
    public static Result cast(Mob caster, LivingEntity target, ResourceLocation spellId, int level) {
        if (caster == null || caster.level().isClientSide()) {
            return Result.no(Refusal.NOT_A_CASTER, "no server-side caster");
        }
        if (tearingDown) {
            // Accepting here would charge mana and register a session into a world that is being
            // torn down, and its terminal event would fire against bookkeeping already cleared.
            return Result.no(Refusal.SHUTTING_DOWN, "the server is shutting down");
        }
        CastRequest request = CastRequest.of(caster, target, spellId, level, SOURCE);
        if (request == null) {
            return Result.no(Refusal.SPELL_UNKNOWN, "unknown spell " + spellId
                    + " — run /magicnpcs spells to list the ids this build accepts");
        }
        CastRequestValidator.Verdict verdict = CastRequestValidator.validate(request);
        if (!verdict.ok()) {
            // The AI path never even considers a spell that is resting, so neither may a script:
            // without this a caller could recast on the very next tick and pay only the mana.
            Refusal refusal = verdict.problem() == CastRequestValidator.Problem.ON_COOLDOWN
                    ? Refusal.ON_COOLDOWN
                    : Refusal.REFUSED;
            return Result.no(refusal, verdict.describe());
        }
        // The same announcements the AI path makes, only attributed to whoever asked. A script that
        // vetoes its own NPC's cast in a cast_pre handler is answered here, before anything is spent.
        if (!MagicNpcEvents.postCastPre(caster, request.canonicalId(), request.level(), target, SOURCE)) {
            return Result.no(Refusal.REFUSED, "the cast was vetoed before it started");
        }
        MobCastSession.Start start = MobCastSession.begin(caster, target, request.spell(),
                request.level(), SOURCE);
        if (!start.started()) {
            String detail = start.refusal() == null ? start.detail() : start.refusal().description();
            MagicNpcEvents.postFailed(caster, request.canonicalId(), request.level(), target, SOURCE, detail);
            return Result.no(Refusal.REFUSED, detail);
        }
        // Cooldown starts with the cast, not with its completion — the same rule the goal applies, so
        // a detached cast that is cancelled part-way through is not immediately retryable either.
        ManagedCasterState.of(caster).startCooldown(caster, request.canonicalId(),
                NpcSpellAttackGoal.cooldownFor(caster, request.canonicalId(), request.spell()));
        enqueue(new Detached(caster, start.session()));
        MagicNpcEvents.postStarted(caster, request.canonicalId(), request.level(), target, SOURCE);
        if (MagicNpcsConfig.debugLogging()) {
            MagicNpcs.LOGGER.info("[cast] detached cast of {} started ({} active)",
                    request.describe(), activeCount());
        }
        return Result.ok();
    }

    /**
     * Register an accepted session, staging it when a pass is in progress.
     *
     * <p>A session created from inside a completion callback must not be advanced by the pass that is
     * dispatching that callback: the old session's cleanup has not finished, and the new one would
     * take two advances in one tick. Staged work gets its first advance on the next tick boundary.
     */
    private static void enqueue(Detached detached) {
        if (processing) {
            PENDING_ADDITIONS.add(detached);
        } else {
            ACTIVE.add(detached);
        }
    }

    /**
     * Advance every detached session by one tick. Called from the existing server-tick handler rather
     * than subscribing separately, so there is one place that decides what Magic NPCs does per tick.
     *
     * <p>Not re-entrant: a callback that somehow reached this method again would advance every session
     * a second time within one tick, double-charging a continuous spell's cadence.
     */
    public static void tickAll() {
        if (processing || (ACTIVE.isEmpty() && PENDING_ADDITIONS.isEmpty())) {
            return;
        }
        processing = true;
        try {
            drainPending();
            for (Iterator<Detached> it = ACTIVE.iterator(); it.hasNext(); ) {
                Detached detached = it.next();
                MobCastSession session = detached.session();
                Mob caster = detached.caster();
                if (caster == null || !caster.isAlive() || caster.isRemoved()) {
                    session.cancel(MobCastSession.CancelReason.CASTER_UNAVAILABLE);
                    it.remove();
                    continue;
                }
                // Continuation applies the same live policy the request passed, not merely "is the
                // caster still here". A target that died, left the level or became an ally part-way
                // through a channel is no longer an authorised recipient (MN-003).
                MobCastSession.CancelReason lost = continuationFailure(caster, session);
                if (lost != null) {
                    session.cancel(lost);
                    it.remove();
                    continue;
                }
                if (session.spellWantsToStop()) {
                    session.cancel(MobCastSession.CancelReason.SPELL_ASKED_TO_STOP);
                    it.remove();
                    continue;
                }
                if (!session.tick()) {
                    it.remove();
                }
            }
        } finally {
            // Restored in a finally so one throwing session cannot wedge the driver shut for the rest
            // of the server's life.
            processing = false;
        }
        drainPending();
    }

    /**
     * @return why this session may no longer continue, or {@code null} while it may.
     *
     *         <p>Deliberately only the mandatory conditions. Range and the AI's discretionary policy
     *         are the goal's business; a scripted cast is allowed to be out of the range a loadout
     *         would have required.
     */
    private static MobCastSession.CancelReason continuationFailure(Mob caster, MobCastSession session) {
        if (caster.isNoAi()) {
            return MobCastSession.CancelReason.CASTER_UNAVAILABLE;
        }
        if (!com.otectus.magicnpcs.core.caster.FrameworkEligibility.check(caster).allowed()) {
            return MobCastSession.CancelReason.ADAPTER_REFUSED;
        }
        LivingEntity target = session.target();
        if (target == null) {
            return null; // a genuine self-cast has no recipient to revalidate
        }
        if (!target.isAlive() || target.isRemoved() || target.level() != caster.level()) {
            return MobCastSession.CancelReason.TARGET_LOST;
        }
        // The accepted cast stays bound to the recipient it was authorised for; nothing retargets it.
        if (!com.otectus.magicnpcs.core.adapter.NpcAdapters.resolve(caster).canCastAt(caster, target)) {
            return MobCastSession.CancelReason.TARGET_LOST;
        }
        return null;
    }

    /** Apply staged structural changes. Only ever called with no iteration in progress. */
    private static void drainPending() {
        if (!PENDING_REMOVALS.isEmpty()) {
            List<MobCastSession> removals = List.copyOf(PENDING_REMOVALS);
            PENDING_REMOVALS.clear();
            ACTIVE.removeIf(detached -> removals.contains(detached.session()));
        }
        if (!PENDING_ADDITIONS.isEmpty()) {
            List<Detached> additions = List.copyOf(PENDING_ADDITIONS);
            PENDING_ADDITIONS.clear();
            ACTIVE.addAll(additions);
        }
    }

    /**
     * Cancel and forget every detached session. Called when the server stops.
     *
     * <p>The teardown guard is raised <em>before</em> the cancellation callbacks run, because those
     * callbacks are exactly where a script gets one last chance to ask for another cast. A session
     * accepted there would be registered into a world that is going away and would never terminate.
     */
    public static void clearAll() {
        tearingDown = true;
        boolean reentrant = processing;
        // Iterate a snapshot: a cancellation callback that reaches back into the driver finds the
        // staging queues, not the list being walked.
        List<Detached> live = List.copyOf(ACTIVE);
        processing = true;
        try {
            for (Detached detached : live) {
                detached.session().cancel(MobCastSession.CancelReason.GOAL_STOPPED);
            }
        } finally {
            processing = reentrant;
        }
        if (reentrant) {
            // A callback asked to clear the driver while a pass is still walking it. Stage the
            // removals; the pass that owns the iterator applies them when it finishes.
            live.forEach(detached -> PENDING_REMOVALS.add(detached.session()));
            PENDING_ADDITIONS.clear();
            return;
        }
        ACTIVE.clear();
        PENDING_ADDITIONS.clear();
        PENDING_REMOVALS.clear();
    }

    /**
     * Allow detached casting again for a newly started server.
     *
     * <p>Called from server-start, not from a static initialiser: the guard has to be released once
     * per server lifetime, and an integrated client starts several in one JVM.
     */
    public static void armForNewServer() {
        tearingDown = false;
        processing = false;
        ACTIVE.clear();
        PENDING_ADDITIONS.clear();
        PENDING_REMOVALS.clear();
    }

    /** @return true while teardown has begun and new detached casts are being declined. */
    public static boolean isTearingDown() {
        return tearingDown;
    }

    /** @return how many detached casts are running, for {@code /magicnpcs config}. */
    public static int activeCount() {
        return ACTIVE.size() + PENDING_ADDITIONS.size();
    }
}
