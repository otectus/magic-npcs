package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
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

    private DetachedCastDriver() {}

    /** Why a detached cast could not be started, for the caller to report to whoever asked for it. */
    public enum Refusal {
        SPELL_UNKNOWN,
        NOT_A_CASTER,
        REFUSED
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
     * <p>Applies exactly the gates the AI path applies — {@code MobCastSession.begin} runs the spell
     * allow-list, the mob-castability manifest, the mana check and Iron's own pre-cast conditions — so
     * a scripted cast can never do something a chosen cast could not. Skipping those filters on a
     * secondary cast path is the defect this repository already recorded as B6.
     *
     * @param target the entity to cast at, or null for a self-cast
     * @param level  spell level; clamped by the spell's own minimum and maximum
     */
    public static Result cast(Mob caster, LivingEntity target, ResourceLocation spellId, int level) {
        if (caster == null || caster.level().isClientSide()) {
            return Result.no(Refusal.NOT_A_CASTER, "no server-side caster");
        }
        AbstractSpell spell = IronsBridge.getSpell(spellId);
        if (spell == null) {
            return Result.no(Refusal.SPELL_UNKNOWN, "unknown spell " + spellId
                    + " — run /magicnpcs spells to list the ids this build accepts");
        }
        MobCastSession.Start start = MobCastSession.begin(caster, target, spell, level);
        if (!start.started()) {
            return Result.no(Refusal.REFUSED, start.refusal() == null
                    ? start.detail() : start.refusal().description());
        }
        ACTIVE.add(new Detached(caster, start.session()));
        if (MagicNpcsConfig.debugLogging()) {
            MagicNpcs.LOGGER.info("[cast] detached cast of {} started ({} active)",
                    spellId, ACTIVE.size());
        }
        return Result.ok();
    }

    /**
     * Advance every detached session by one tick. Called from the existing server-tick handler rather
     * than subscribing separately, so there is one place that decides what Magic NPCs does per tick.
     */
    public static void tickAll() {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (Iterator<Detached> it = ACTIVE.iterator(); it.hasNext(); ) {
            Detached detached = it.next();
            MobCastSession session = detached.session();
            Mob caster = detached.caster();
            if (caster == null || !caster.isAlive() || caster.isRemoved()) {
                session.cancel(MobCastSession.CancelReason.CASTER_UNAVAILABLE);
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
    }

    /** Cancel and forget every detached session. Called when the server stops. */
    public static void clearAll() {
        for (Detached detached : ACTIVE) {
            detached.session().cancel(MobCastSession.CancelReason.GOAL_STOPPED);
        }
        ACTIVE.clear();
    }

    /** @return how many detached casts are running, for {@code /magicnpcs config}. */
    public static int activeCount() {
        return ACTIVE.size();
    }
}
