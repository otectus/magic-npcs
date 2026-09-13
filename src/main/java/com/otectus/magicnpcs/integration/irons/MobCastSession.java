package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.api.event.MagicNpcCastEvent;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.caster.CasterFacing;
import com.otectus.magicnpcs.core.caster.MagicNpcEvents;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.ICastDataSerializable;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * One NPC spell cast, run through Iron's <em>real</em> casting lifecycle.
 *
 * <p>0.6.1's bridge was a one-shot: it checked pre-cast conditions, called {@code onCast} directly,
 * called {@code onServerCastComplete} for LONG spells only, and then deducted mana unconditionally. It
 * never called {@code MagicData.initiateCast}, {@code onServerPreCast}, or {@code onServerCastTick}, and
 * had no notion of a continuous cast at all. That is not what Iron's spells are written against, so
 * every spell whose effect lives in a cast tick — starfall's comets, blaze storm's fireballs, ray of
 * siphoning's channel, telekinesis' pull — did nothing visible while still charging the mob mana and
 * starting a cooldown (audit SPI-001).
 *
 * <p>This session reproduces the order {@code AbstractSpellCastingMob} uses for Iron's own casting mobs:
 *
 * <pre>
 *   begin:  capability + allow-list + mana checks
 *           install the cast data the spell needs
 *           checkPreCastConditions
 *           MagicData.initiateCast(spell, level, getEffectiveCastTime(level, caster), MOB, slot)
 *           onServerPreCast
 *   tick:   MagicData.handleCastDuration()
 *           onServerCastTick while still casting
 *           re-aim at the target
 *           duration over  -> onCast for LONG/INSTANT, then complete
 *           CONTINUOUS     -> onCast on Iron's ten-tick cadence
 *   end:    onServerCastComplete(cancelled), resetCastingState, drop cast data
 * </pre>
 *
 * <p><b>Transaction point.</b> Mana is charged and the cooldown starts exactly once, at the moment
 * Iron's accepts the cast ({@code initiateCast} — the same point a player pays). Nothing is charged
 * when the session is refused before that, so a spell that cannot cast is free. A channel interrupted
 * afterwards keeps its mana spent and its cooldown running, exactly as an interrupted player cast does,
 * and deliberately so: refunding would let a caster restart a telegraphed channel every tick.
 */
public final class MobCastSession {

    /**
     * The equipment slot string Iron's stores on the synced cast state. Mobs have no spell book slot;
     * Iron's own casting mobs pass their main hand, so this matches what a client would see.
     */
    private static final String SLOT = "mainhand";

    /** Iron's fires a CONTINUOUS spell's effect every tenth tick of the channel. */
    private static final int CONTINUOUS_CADENCE = 10;

    private final Mob caster;
    private final AbstractSpell spell;
    private final int level;
    private final MagicData data;
    private final CastType castType;
    private final boolean ownsCastData;
    /** Whose decision this cast was, carried so the terminal announcements match the start. */
    private final MagicNpcCastEvent.CastSource eventSource;

    private LivingEntity target;
    private State state = State.CHANNELLING;
    private int lastTickedAt = Integer.MIN_VALUE;
    private boolean effectCommitted;
    /** True once an effect hook has been ENTERED, whether or not it returned. See {@link #enterEffect}. */
    private boolean effectEntered;
    /** Cleanup is at most once: a session ended by both its owner and itself must not run it twice. */
    private boolean cleanedUp;

    /** Where a session ended up. Only {@link #COMPLETE} means the spell's effect actually landed. */
    public enum State { CHANNELLING, COMPLETE, CANCELLED }

    /** Why a session was cut short, for {@code /magicnpcs why} and the debug log. */
    public enum CancelReason {
        TARGET_LOST("the target died, was removed, or became an ally"),
        TARGET_OUT_OF_RANGE("the target left the spell's range"),
        TARGET_NOT_VISIBLE("the target moved out of line of sight"),
        CASTER_UNAVAILABLE("the caster died, was removed, or had its AI disabled"),
        SPELL_ASKED_TO_STOP("Iron's asked the caster to stop this spell"),
        RECONCILED("the mob's loadout or configuration changed mid-cast"),
        GOAL_STOPPED("the casting goal was interrupted"),
        DIALOG_OPENED("a player started talking to this NPC"),
        ADAPTER_REFUSED("the NPC's own mod refused the cast at the moment it was committed");

        private final String description;

        CancelReason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /** Why {@link #begin} refused to start. Nothing is spent for any of these. */
    public enum RefusalReason {
        BLACKLISTED("the spell is excluded by spells.spellBlacklist / spellWhitelist"),
        NOT_CASTABLE_BY_MOB("no verified mob-cast strategy"),
        NEEDS_TARGET("the spell needs a target entity and none was supplied"),
        INSUFFICIENT_MANA("the caster cannot afford the spell"),
        ALREADY_CASTING("the caster is already channelling a spell"),
        PRE_CAST_REFUSED("Iron's pre-cast conditions were not met");

        private final String description;

        RefusalReason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /** The outcome of {@link #begin}: either a live session, or the reason there is not one. */
    public record Start(MobCastSession session, RefusalReason refusal, String detail) {
        public boolean started() {
            return session != null;
        }
    }

    private MobCastSession(Mob caster, LivingEntity target, AbstractSpell spell, int level,
                           MagicData data, boolean ownsCastData,
                           MagicNpcCastEvent.CastSource eventSource) {
        this.caster = caster;
        this.target = target;
        this.spell = spell;
        this.level = level;
        this.data = data;
        this.castType = spell.getCastType();
        this.ownsCastData = ownsCastData;
        this.eventSource = eventSource;
    }

    /**
     * Run every gate, then hand the cast to Iron's.
     *
     * @param target the hostile target for an ATTACK cast, or {@code null} for a self-cast
     * @return a started session, or the refusal reason — nothing is charged on refusal
     */
    public static Start begin(Mob caster, LivingEntity target, AbstractSpell spell, int level) {
        return begin(caster, target, spell, level, MagicNpcCastEvent.CastSource.AI);
    }

    /**
     * As {@link #begin(Mob, LivingEntity, AbstractSpell, int)}, for a cast nobody's AI decided on.
     *
     * <p>The source is carried on the session rather than passed to each announcement, because the
     * terminal ones are posted from here — the session is the only thing that knows a cast finished —
     * and a completion attributed to the AI for a cast a script asked for would be a lie.
     */
    public static Start begin(Mob caster, LivingEntity target, AbstractSpell spell, int level,
                              MagicNpcCastEvent.CastSource eventSource) {
        return begin(caster, target, spell, level,
                SpellCompat.effectiveCastTime(spell, level, caster), eventSource);
    }

    /**
     * As {@link #begin(Mob, LivingEntity, AbstractSpell, int, MagicNpcCastEvent.CastSource)}, with an
     * explicit duration for this cast only; the shared spell object is never modified.
     *
     * @param resolvedCastTime the cast duration in ticks, already resolved from the loadout's
     *                         {@code cast_time} / {@code cast_time_multiplier} overrides
     */
    public static Start begin(Mob caster, LivingEntity target, AbstractSpell spell, int level,
                              int resolvedCastTime, MagicNpcCastEvent.CastSource eventSource) {
        if (!IronsBridge.isAllowedSpell(spell)) {
            return refuse(RefusalReason.BLACKLISTED, spell, caster, null);
        }
        if (!SpellCompat.castableByMob(spell)) {
            return refuse(RefusalReason.NOT_CASTABLE_BY_MOB, spell, caster,
                    SpellCompat.unsupportedReason(spell));
        }
        boolean requiresTarget = SpellCompat.requiresTargetEntity(spell);
        if (requiresTarget && target == null) {
            return refuse(RefusalReason.NEEDS_TARGET, spell, caster, null);
        }
        if (!IronsBridge.canAfford(caster, spell, level)) {
            return refuse(RefusalReason.INSUFFICIENT_MANA, spell, caster, null);
        }

        Prepared prepared = prepare(caster, target, spell, level);
        if (!prepared.passed()) {
            return refuse(prepared.refusal(), spell, caster, null);
        }
        MagicData data = prepared.data();

        MobCastSession session = new MobCastSession(caster, target, spell, level, data,
                prepared.ownsCastData(), eventSource);
        // Publish "this mob is mid-cast" where the vanilla-side movement goal can see it without
        // importing Iron's. A caster that strafes through its own channel throws away the aim it
        // takes every tick.
        com.otectus.magicnpcs.core.caster.ManagedCasterState.of(caster).setChannelling(true);
        int castTime = Math.max(0, resolvedCastTime);
        try {
            data.initiateCast(spell, level, castTime, CastSource.MOB, SLOT);
            spell.onServerPreCast(caster.level(), level, caster, data);
        } catch (RuntimeException | LinkageError failure) {
            // Iron's accepted nothing, so nothing is owed. What it may have left behind is a caster
            // Iron's still believes is casting, which would block every later cast on this mob for
            // as long as it lived (roadmap MN-004). Undo exactly that and refuse.
            session.releaseWithoutCharging();
            diagnose(caster, spell, "pre-cast", failure);
            return refuse(RefusalReason.PRE_CAST_REFUSED, spell, caster, failure.toString());
        }
        // The transaction point, in one place: Iron's has accepted the cast, and this is where — and
        // the only place where — the caster pays for it. A caller cannot announce acceptance before
        // the charge, because the charge is part of acceptance.
        data.addMana(-spell.getManaCost(level));
        if (MagicNpcsConfig.debugLogging()) {
            MagicNpcs.LOGGER.info("[cast] {} began {} (lvl {}, {} for {}t): mana now {}",
                    EntityType.getKey(caster.getType()), spell.getSpellName(), level,
                    session.castType, castTime, data.getMana());
        }
        return new Start(session, null, null);
    }

    /**
     * One diagnostic line for a spell that threw, classified by spell, stage and the Iron's build.
     *
     * <p>One line rather than a stack trace per tick, and scoped to the spell rather than the mod: a
     * single misbehaving spell must not read as "Magic NPCs is broken", and every other NPC must keep
     * advancing (MN-004).
     */
    private static void diagnose(Mob caster, AbstractSpell spell, String stage, Throwable failure) {
        MagicNpcs.LOGGER.error("[cast] {} threw during {} for {} (Iron's {}). This cast is abandoned; "
                        + "other casters are unaffected.",
                spell.getSpellResource(), stage, EntityType.getKey(caster.getType()),
                MagicNpcs.IRONS_VERIFIED_RANGE, failure);
    }

    /**
     * Undo a preparation that never became an accepted cast.
     *
     * <p>Distinct from {@link #finish}: nothing was charged and Iron's completion hook is not owed a
     * call, because as far as the spell is concerned the cast never began.
     */
    private void releaseWithoutCharging() {
        state = State.CANCELLED;
        cleanedUp = true;
        try {
            data.resetCastingState();
            if (ownsCastData) {
                data.resetAdditionalCastData();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Already on the failure path; a second failure here must not replace the first.
        }
        com.otectus.magicnpcs.core.caster.ManagedCasterState.of(caster).setChannelling(false);
    }

    /**
     * The outcome of {@link #prepare}: the caster's magic data, whether this call installed the cast
     * data (and must therefore clean it up), and either a pass or the reason Iron's said no.
     *
     * @param refusal the reason the preparation failed, or {@code null} when it passed
     */
    record Prepared(MagicData data, boolean ownsCastData, boolean passed, RefusalReason refusal) {}

    /**
     * Everything that has to be true — and true <em>in this order</em> — before Iron's will accept a
     * cast: the caster is not already channelling, the cast data the spell reads is installed, and the
     * spell's own pre-cast check passes.
     *
     * <p><b>Why the facing snap lives here.</b> Iron's target helpers ({@code Utils.preCastTargetHelper},
     * which roughly twenty spells call from {@code checkPreCastConditions}) raycast along the caster's
     * current facing and never consult a pre-installed {@code TargetEntityCastData}. A caster that has
     * not turned toward its target yet is refused before the cast starts, whatever we installed. Doing
     * the snap in the casting goal alone was not enough: the audit probe and every non-ATTACK role went
     * without it, so it belongs on the one path all of them share.
     *
     * <p>Nothing is charged here; a refusal is free.
     */
    static Prepared prepare(Mob caster, LivingEntity target, AbstractSpell spell, int level) {
        // A namespace-trusted add-on spell is not known to need a target, so it must stay castable
        // without one - but when the caster does have one, giving it the same TargetEntityCastData a
        // verified TARGET_ENTITY spell gets costs nothing (a spell that builds its own overwrites it)
        // and is what makes single-target add-on spells work with no manifest at all.
        boolean needsTarget = SpellCompat.requiresTargetEntity(spell)
                || (SpellCompat.suppliesTargetOpportunistically(spell) && target != null);
        MagicData data = MagicData.getPlayerMagicData(caster);
        if (data.isCasting()) {
            // Never stomp a channel that is already running: initiateCast would overwrite its state
            // and Iron's would finish the wrong spell against the wrong cast data.
            return new Prepared(data, false, false, RefusalReason.ALREADY_CASTING);
        }
        if (needsTarget && target != null) {
            CasterFacing.snap(caster, target);
        }

        // Ownership is recorded from what THIS call installed, never from "there happened to be cast
        // data on the caster". Treating a pre-existing instance as ours meant a session could reset
        // another system's cast data on its way out (roadmap MN-004).
        boolean ownsCastData = installCastData(data, spell, target, needsTarget);
        // Every spell gets its own pre-cast step, not just the target-locked ones. Many Iron's spells
        // BUILD their cast data here rather than in onCast: HasteSpell's checkPreCastConditions
        // raycasts for a target via Utils.preCastTargetHelper, spawns a TargetedAreaEntity and installs
        // the cast data its onCast then requires. Skipping this for non-target spells left roughly
        // twenty of them — haste, blessing_of_life, healing_circle, sunbeam, chain_lightning, slow,
        // wololo, arrow_volley, blight, earthquake and more — doing nothing at all.
        boolean passed;
        try {
            passed = spell.checkPreCastConditions(caster.level(), level, caster, data);
        } catch (RuntimeException | LinkageError failure) {
            // A spell whose own pre-cast check throws is refused, not crashed through. Nothing has
            // been charged at this point, so the caster is exactly as it was.
            diagnose(caster, spell, "checkPreCastConditions", failure);
            passed = false;
        }
        if (!passed) {
            if (ownsCastData) {
                data.resetAdditionalCastData();
            }
            return new Prepared(data, ownsCastData, false, RefusalReason.PRE_CAST_REFUSED);
        }
        return new Prepared(data, ownsCastData, true, null);
    }

    /**
     * Install the cast data the spell needs before its pre-cast check runs.
     *
     * @return true if this session installed the data and must clean it up
     */
    private static boolean installCastData(MagicData data, AbstractSpell spell, LivingEntity target,
                                           boolean needsTarget) {
        if (needsTarget) {
            // Set the target BEFORE the pre-cast check — root/devour/wisp and friends read it there.
            // A spell that raycasts for its own target simply overwrites this; because prepare snaps
            // the caster's facing at the target first, that raycast lands on the same entity anyway,
            // and this is the fallback for when it does not.
            data.setAdditionalCastData(new TargetEntityCastData(target));
            return true;
        }
        // Summons and other spells that declare their own empty cast data need an instance to write
        // into. For a player, Iron's cast machinery supplies one; nothing did for a mob, so the
        // instanceof check inside onCast failed and the spell's bookkeeping was skipped. getEmptyCastData
        // is API, so this stays generic rather than naming any concrete data class.
        if (data.getAdditionalCastData() == null) {
            ICastDataSerializable empty = spell.getEmptyCastData();
            if (empty != null) {
                data.setAdditionalCastData(empty);
                return true;
            }
        }
        return false;
    }

    private static Start refuse(RefusalReason reason, AbstractSpell spell, Mob caster, String detail) {
        if (MagicNpcsConfig.debugLogging()) {
            MagicNpcs.LOGGER.info("[cast] skipping {} for {}: {}{}", spell.getSpellName(),
                    EntityType.getKey(caster.getType()), reason.description(),
                    detail == null ? "" : " — " + detail);
        }
        return new Start(null, reason, detail);
    }

    /**
     * Advance the cast by one server tick, in Iron's canonical order.
     *
     * <p>At most one advance per server tick: a goal started this tick is also ticked this tick by
     * {@code GoalSelector.tickRunningGoals(true)}, so before this guard a {@code windup: 0} LONG cast
     * advanced twice on its start tick and finished one tick early. INSTANT casts are unaffected —
     * their first advance completes them, and the session is no longer channelling.
     *
     * @return true while the session is still running; false once it has completed or cancelled
     */
    public boolean tick() {
        if (state != State.CHANNELLING) {
            return false;
        }
        if (caster.tickCount == lastTickedAt) {
            return true;
        }
        lastTickedAt = caster.tickCount;
        try {
            data.handleCastDuration();
            if (data.isCasting()) {
                spell.onServerCastTick(caster.level(), level, caster, data);
            }
        } catch (RuntimeException | LinkageError failure) {
            diagnose(caster, spell, "onServerCastTick", failure);
            terminate(State.CANCELLED, CancelReason.SPELL_ASKED_TO_STOP);
            return false;
        }
        if (target != null && target.isAlive()) {
            // Iron's own casting mob calls forceLookAtTarget here every tick; a channelled spell that
            // reads the caster's look angle (breaths, rays, cones) is aimed by this, not by LookControl,
            // which applies too late in the tick to be read during a cast.
            caster.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        if (data.getCastDurationRemaining() <= 0) {
            if (castType == CastType.LONG || castType == CastType.INSTANT) {
                if (!enterEffect()) {
                    return false;
                }
            }
            terminate(State.COMPLETE, null);
            return false;
        }
        if (castType == CastType.CONTINUOUS
                && (data.getCastDurationRemaining() + 1) % CONTINUOUS_CADENCE == 0) {
            if (!enterEffect()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Call the spell's effect hook, recording that it was entered <b>before</b> calling it.
     *
     * <p>The order is the whole point. A spell can spawn its projectile, apply its effect or start its
     * area entity and then throw; if entry were recorded afterwards, the mod would believe nothing had
     * happened and would be free to refund and let the caster try again — paying once for two effects.
     * The roadmap states the rule directly: after entering an effect hook, do not automatically refund
     * and retry (MN-004).
     *
     * @return true when the effect returned normally and the session may continue
     */
    private boolean enterEffect() {
        effectEntered = true;
        try {
            spell.onCast(caster.level(), level, caster, CastSource.MOB, data);
            effectCommitted = true;
            return true;
        } catch (RuntimeException | LinkageError failure) {
            diagnose(caster, spell, "onCast", failure);
            // Terminal, and deliberately not refunded: an effect may already have landed.
            terminate(State.CANCELLED, CancelReason.SPELL_ASKED_TO_STOP);
            return false;
        }
    }

    /**
     * The one terminal path: take ownership of the ending, run cleanup, then announce it.
     *
     * <p>Ownership before announcement, because the announcement is externally re-entrant. A listener
     * answering a completion by starting a replacement cast on the same caster used to do so while
     * this session was still, by its own state, channelling — so the replacement's terminal could be
     * consumed by the old cast's bookkeeping (MN-004, MN-013).
     *
     * @param reason the cancellation reason, or {@code null} for a completion
     */
    private void terminate(State terminal, CancelReason reason) {
        if (state != State.CHANNELLING) {
            return; // at most once, however many owners think they ended this cast
        }
        state = terminal;
        finish(terminal == State.CANCELLED);
        if (terminal == State.COMPLETE) {
            MagicNpcEvents.postCompleted(caster, spell.getSpellResource(), level, target, eventSource);
        } else {
            MagicNpcEvents.postCancelled(caster, spell.getSpellResource(), level, target, eventSource,
                    reason == null ? CancelReason.GOAL_STOPPED.description() : reason.description());
        }
    }

    /**
     * End the session early. Iron's completion hook still runs, with {@code cancelled = true}, so a
     * spell that spawned an area entity or applied a synced effect can tear it down — 0.6.1 had no
     * cancel path at all, so anything a pre-cast hook created simply leaked.
     */
    public void cancel(CancelReason reason) {
        if (state != State.CHANNELLING) {
            return;
        }
        if (MagicNpcsConfig.debugLogging()) {
            MagicNpcs.LOGGER.info("[cast] {} cancelled {}: {}", EntityType.getKey(caster.getType()),
                    spell.getSpellName(), reason.description());
        }
        // The terminal guard in MagicNpcEvents backs this up: a session cancelled by both its goal and
        // itself must still announce exactly one ending.
        terminate(State.CANCELLED, reason);
    }

    /**
     * Release everything this session owns, at most once.
     *
     * <p>Every step is independently guarded. A completion hook that throws must not stop the channel
     * state being reset, and a reset that throws must not stop the movement hold being released: a
     * caster frozen in place by an interrupted channel would simply never move again, and one Iron's
     * still believes is casting would never cast again.
     */
    private void finish(boolean cancelled) {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        try {
            spell.onServerCastComplete(caster.level(), level, caster, data, cancelled);
        } catch (RuntimeException | LinkageError failure) {
            diagnose(caster, spell, "onServerCastComplete", failure);
        } finally {
            try {
                data.resetCastingState();
                if (ownsCastData) {
                    data.resetAdditionalCastData(); // never leak this cast's target into the next one
                }
            } catch (RuntimeException | LinkageError failure) {
                diagnose(caster, spell, "resetCastingState", failure);
            }
            // Every exit path releases the movement hold, including the cancel path.
            com.otectus.magicnpcs.core.caster.ManagedCasterState.of(caster).setChannelling(false);
        }
    }

    /** @return true if the spell should stop channelling for a reason only Iron's knows. */
    public boolean spellWantsToStop() {
        if (target == null) {
            return false;
        }
        try {
            return spell.shouldAIStopCasting(level, caster, target);
        } catch (RuntimeException | LinkageError failure) {
            // A stop query that throws is answered "stop": continuing to channel a spell whose own
            // code is failing is the worse of the two outcomes, and the session ends cleanly either way.
            diagnose(caster, spell, "shouldAIStopCasting", failure);
            return true;
        }
    }

    /** @return true once an effect hook has been entered, whether or not it returned normally. */
    public boolean effectEntered() {
        return effectEntered;
    }

    /** Point the session at a new target (the goal re-validates its target every tick). */
    public void retarget(LivingEntity newTarget) {
        this.target = newTarget;
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state == State.CHANNELLING;
    }

    /** @return true once the spell's effect has actually been applied at least once. */
    public boolean effectCommitted() {
        return effectCommitted;
    }

    public AbstractSpell spell() {
        return spell;
    }

    public LivingEntity target() {
        return target;
    }

    /** @return ticks left in the channel, for the {@code /magicnpcs why} report. */
    public int remainingTicks() {
        return Math.max(0, data.getCastDurationRemaining());
    }
}
