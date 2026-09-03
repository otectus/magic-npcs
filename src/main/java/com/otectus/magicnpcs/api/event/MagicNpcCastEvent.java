package com.otectus.magicnpcs.api.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.eventbus.api.Cancelable;
import net.minecraftforge.eventbus.api.Event;


/**
 * The lifecycle of one NPC spell cast, announced on {@code MinecraftForge.EVENT_BUS} so any mod — or
 * any part of this one that is not Iron's-side — can watch or veto it without importing Iron's
 * Spellbooks, CustomNPCs, or the casting internals.
 *
 * <p>Vanilla and Magic NPCs types only, deliberately: an event another mod is expected to subscribe to
 * must be loadable on an install that has neither of the mods this bridge sits between. The spell is
 * named by its {@link ResourceLocation}, not by an {@code AbstractSpell}.
 *
 * <p>Posted by {@code core.caster.MagicNpcEvents}, which is the single emission point for both this
 * event and the adapter signal that mirrors it — so a listener and a script see the same cast once
 * each, and neither can be the cause of the other.
 */
public abstract class MagicNpcCastEvent extends Event {

    /** Who asked for this cast. */
    public enum CastSource {
        /** The casting goal decided to cast. */
        AI,
        /** A script, command, or dialog action asked for the cast. */
        SCRIPT
    }

    private final Mob caster;
    private final ResourceLocation spellId;
    private final int spellLevel;
    private final LivingEntity target;
    private final CastSource source;

    protected MagicNpcCastEvent(Mob caster, ResourceLocation spellId, int spellLevel,
                                LivingEntity target, CastSource source) {
        this.caster = caster;
        this.spellId = spellId;
        this.spellLevel = spellLevel;
        this.target = target;
        this.source = source;
    }

    public Mob getCaster() {
        return caster;
    }

    public ResourceLocation getSpellId() {
        return spellId;
    }

    public int getSpellLevel() {
        return spellLevel;
    }

    /** @return the entity being cast at, or {@code null} for a self-cast. */
    public LivingEntity getTarget() {
        return target;
    }

    public CastSource getSource() {
        return source;
    }

    /**
     * Fired before anything is spent. Cancelling stops the cast with no mana charged and no cooldown
     * started, and is followed by exactly one {@link Cancelled}.
     *
     * <p>{@link Cancelable} is not decoration: without it {@code setCanceled} throws at runtime rather
     * than compiling to a no-op.
     */
    @Cancelable
    public static class Pre extends MagicNpcCastEvent {

        private String cancelReason = "a listener cancelled the cast";

        public Pre(Mob caster, ResourceLocation spellId, int spellLevel,
                   LivingEntity target, CastSource source) {
            super(caster, spellId, spellLevel, target, source);
        }

        /** @return what the canceller wants {@code /magicnpcs why} and the script signal to say. */
        public String getCancelReason() {
            return cancelReason;
        }

        /** Say why, so a vetoed cast is explicable rather than a silent nothing. */
        public void setCancelReason(String cancelReason) {
            this.cancelReason = cancelReason == null ? "" : cancelReason;
        }
    }

    /** The cast was accepted: mana is spent and the cooldown is running. */
    public static class Started extends MagicNpcCastEvent {
        public Started(Mob caster, ResourceLocation spellId, int spellLevel,
                       LivingEntity target, CastSource source) {
            super(caster, spellId, spellLevel, target, source);
        }
    }

    /** The cast ran to the end of its duration. One of this or {@link Cancelled} follows a {@link Started}. */
    public static class Completed extends MagicNpcCastEvent {
        public Completed(Mob caster, ResourceLocation spellId, int spellLevel,
                         LivingEntity target, CastSource source) {
            super(caster, spellId, spellLevel, target, source);
        }
    }

    /** The cast ended early, or never started because {@link Pre} was cancelled. */
    public static class Cancelled extends MagicNpcCastEvent {

        private final String reason;

        public Cancelled(Mob caster, ResourceLocation spellId, int spellLevel,
                         LivingEntity target, CastSource source, String reason) {
            super(caster, spellId, spellLevel, target, source);
            this.reason = reason == null ? "" : reason;
        }

        /** @return a human-readable explanation, already phrased for a chat line. */
        public String getReason() {
            return reason;
        }
    }
}
