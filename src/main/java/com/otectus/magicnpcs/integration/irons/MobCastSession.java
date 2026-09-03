package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.api.event.MagicNpcCastEvent;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
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
    private boolean effectCommitted;

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
        if (!IronsBridge.isAllowedSpell(spell)) {
            return refuse(RefusalReason.BLACKLISTED, spell, caster, null);
        }
        if (!SpellCompat.castableByMob(spell)) {
            return refuse(RefusalReason.NOT_CASTABLE_BY_MOB, spell, caster,
                    SpellCompat.unsupportedReason(spell));
        }
        boolean needsTarget = SpellCompat.requiresTargetEntity(spell);
        if (needsTarget && target == null) {
            return refuse(RefusalReason.NEEDS_TARGET, spell, caster, null);
        }
        if (!IronsBridge.canAfford(caster, spell, level)) {
            return refuse(RefusalReason.INSUFFICIENT_MANA, spell, caster, null);
        }
        MagicData data = MagicData.getPlayerMagicData(caster);
        if (data.isCasting()) {
            // Never stomp a channel that is already running: initiateCast would overwrite its state
            // and Iron's would finish the wrong spell against the wrong cast data.
            return refuse(RefusalReason.ALREADY_CASTING, spell, caster, null);
        }

        boolean ownsCastData = installCastData(data, spell, target, needsTarget);
        // Every spell gets its own pre-cast step, not just the target-locked ones. Many Iron's spells
        // BUILD their cast data here rather than in onCast: HasteSpell's checkPreCastConditions
        // raycasts for a target via Utils.preCastTargetHelper, spawns a TargetedAreaEntity and installs
        // the cast data its onCast then requires. Skipping this for non-target spells left roughly
        // twenty of them — haste, blessing_of_life, healing_circle, sunbeam, chain_lightning, slow,
        // wololo, arrow_volley, blight, earthquake and more — doing nothing at all.
        if (!spell.checkPreCastConditions(caster.level(), level, caster, data)) {
            if (ownsCastData || data.getAdditionalCastData() != null) {
                data.resetAdditionalCastData();
            }
            return refuse(RefusalReason.PRE_CAST_REFUSED, spell, caster, null);
        }

        MobCastSession session = new MobCastSession(caster, target, spell, level, data,
                ownsCastData || data.getAdditionalCastData() != null, eventSource);
        // Publish "this mob is mid-cast" where the vanilla-side movement goal can see it without
        // importing Iron's. A caster that strafes through its own channel throws away the aim it
        // takes every tick.
        com.otectus.magicnpcs.core.caster.ManagedCasterState.of(caster).setChannelling(true);
        int castTime = SpellCompat.effectiveCastTime(spell, level, caster);
        data.initiateCast(spell, level, castTime, CastSource.MOB, SLOT);
        spell.onServerPreCast(caster.level(), level, caster, data);
        // The transaction point: Iron's has accepted the cast and owns the state from here.
        data.addMana(-spell.getManaCost(level));
        if (MagicNpcsConfig.debugLogging()) {
            MagicNpcs.LOGGER.info("[cast] {} began {} (lvl {}, {} for {}t): mana now {}",
                    EntityType.getKey(caster.getType()), spell.getSpellName(), level,
                    session.castType, castTime, data.getMana());
        }
        return new Start(session, null, null);
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
            // A spell that raycasts for its own target simply overwrites this; because the goal snaps
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
     * @return true while the session is still running; false once it has completed or cancelled
     */
    public boolean tick() {
        if (state != State.CHANNELLING) {
            return false;
        }
        data.handleCastDuration();
        if (data.isCasting()) {
            spell.onServerCastTick(caster.level(), level, caster, data);
        }
        if (target != null && target.isAlive()) {
            // Iron's own casting mob calls forceLookAtTarget here every tick; a channelled spell that
            // reads the caster's look angle (breaths, rays, cones) is aimed by this, not by LookControl,
            // which applies too late in the tick to be read during a cast.
            caster.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        if (data.getCastDurationRemaining() <= 0) {
            if (castType == CastType.LONG || castType == CastType.INSTANT) {
                spell.onCast(caster.level(), level, caster, CastSource.MOB, data);
                effectCommitted = true;
            }
            finish(false);
            state = State.COMPLETE;
            MagicNpcEvents.postCompleted(caster, spell.getSpellResource(), level, target, eventSource);
            return false;
        }
        if (castType == CastType.CONTINUOUS
                && (data.getCastDurationRemaining() + 1) % CONTINUOUS_CADENCE == 0) {
            spell.onCast(caster.level(), level, caster, CastSource.MOB, data);
            effectCommitted = true;
        }
        return true;
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
        finish(true);
        state = State.CANCELLED;
        // The terminal guard in MagicNpcEvents backs this up: a session cancelled by both its goal and
        // itself must still announce exactly one ending.
        MagicNpcEvents.postCancelled(caster, spell.getSpellResource(), level, target, eventSource,
                reason.description());
    }

    private void finish(boolean cancelled) {
        try {
            spell.onServerCastComplete(caster.level(), level, caster, data, cancelled);
        } finally {
            data.resetCastingState();
            if (ownsCastData) {
                data.resetAdditionalCastData(); // never leak this cast's target into the next one
            }
            // Every exit path releases the movement hold, including the cancel path — a caster
            // frozen in place by a channel that was interrupted would simply never move again.
            com.otectus.magicnpcs.core.caster.ManagedCasterState.of(caster).setChannelling(false);
        }
    }

    /** @return true if the spell should stop channelling for a reason only Iron's knows. */
    public boolean spellWantsToStop() {
        return target != null && spell.shouldAIStopCasting(level, caster, target);
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
