package com.otectus.magicnpcs.compat.customnpcs;

import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Per-NPC bookkeeping the bridge keeps outside CustomNPCs' own objects: when each NPC was last seen
 * alive by its update event, and how many times its goals have had to be repaired.
 *
 * <p>Kept here rather than in a field on the adapter or the bridge because it has to be dropped when
 * the entity leaves the world, and that is a FORGE-bus event. Registered as a class (its handler is
 * static) on {@code MinecraftForge.EVENT_BUS}; the CustomNPCs API events go to the API bus instead,
 * see {@link CustomNpcsEventBridge}.
 *
 * <p>In-memory and unbounded only in the sense that the world is: every entry is removed on
 * {@link EntityLeaveLevelEvent}, and {@link #clear()} drops the lot on shutdown.
 *
 * <p>Also counts open dialogs per NPC. A depth rather than a flag because CustomNPCs will happily let
 * two players talk to the same NPC: a single boolean would have the first player closing the dialog
 * un-pause an NPC the second one is still mid-conversation with.
 */
public final class CustomNpcsActivityState {

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    /**
     * Cancels an NPC's in-flight cast, or {@code null} when Iron's Spellbooks is absent.
     *
     * <p>A {@code Consumer<Mob>} set from outside, for the same reason the event bridge's repair hook
     * is one: naming the Iron's-side class here would put {@code integration.irons} on this class's
     * resolution path, and this class is registered on the Forge bus whether Iron's is installed or not.
     */
    private static volatile Consumer<Mob> cancelCastHook;

    private static final class State {
        volatile long lastUpdateGameTime = Long.MIN_VALUE;
        volatile int repairs;
        volatile int dialogDepth;
        /**
         * Set by a script through {@code setCastingSuspended}. Deliberately not persisted: a suspension
         * is a scene-length instruction ("stop casting while this cutscene runs"), and a flag that
         * survived a reload would leave an NPC permanently mute with nothing in its NBT to explain it.
         */
        volatile boolean scriptSuspended;
    }

    private CustomNpcsActivityState() {}

    /** Record that CustomNPCs ticked this NPC, so a stalled bridge is visible per-NPC and not just globally. */
    public static void noteUpdate(Mob mob, long gameTime) {
        STATES.computeIfAbsent(mob.getUUID(), id -> new State()).lastUpdateGameTime = gameTime;
    }

    /** @return the game time this NPC's last update event arrived, or {@link Long#MIN_VALUE} if never. */
    public static long lastUpdate(Mob mob) {
        State state = STATES.get(mob.getUUID());
        return state == null ? Long.MIN_VALUE : state.lastUpdateGameTime;
    }

    /** Count a goal repair against this NPC. */
    public static void noteRepair(Mob mob) {
        STATES.computeIfAbsent(mob.getUUID(), id -> new State()).repairs++;
    }

    /** @return how many times this NPC's goals have been repaired this session. */
    public static int repairs(Mob mob) {
        State state = STATES.get(mob.getUUID());
        return state == null ? 0 : state.repairs;
    }

    /**
     * Wire the cast-cancel hook. Called from {@link CustomNpcsIntegration} inside its Iron's guard;
     * {@code null} leaves dialog tracking working with no cast to cancel.
     */
    static void setCancelCastHook(Consumer<Mob> hook) {
        cancelCastHook = hook;
    }

    /**
     * A player opened a dialog with this NPC. The first open interrupts whatever it was casting: a
     * conversation partner that finishes channelling a fireball mid-sentence is not the NPC the pack
     * author placed. Cooldowns are untouched, so the cast is abandoned rather than refunded.
     */
    public static void open(Mob mob) {
        State state = STATES.computeIfAbsent(mob.getUUID(), id -> new State());
        boolean first;
        synchronized (state) {
            first = state.dialogDepth == 0;
            state.dialogDepth++;
        }
        Consumer<Mob> hook = cancelCastHook;
        if (first && hook != null) {
            hook.accept(mob);
        }
    }

    /** A player closed a dialog with this NPC. Never goes below zero — a close without an open is dropped. */
    public static void close(Mob mob) {
        State state = STATES.get(mob.getUUID());
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.dialogDepth > 0) {
                state.dialogDepth--;
            }
        }
    }

    /** @return true while at least one player has a dialog open with this NPC. */
    public static boolean isDialogOpen(UUID id) {
        State state = STATES.get(id);
        return state != null && state.dialogDepth > 0;
    }

    /**
     * A script asked this NPC to stop, or to start again. Suspension blocks the decision to cast; it
     * does not touch cooldowns, so resuming does not hand the NPC a free volley and does not reset a
     * cooldown that was already running.
     */
    public static void setScriptSuspended(Mob mob, boolean suspended) {
        if (!suspended) {
            State existing = STATES.get(mob.getUUID());
            if (existing == null) {
                return; // nothing was suspended; do not allocate a state to say so
            }
            existing.scriptSuspended = false;
            return;
        }
        STATES.computeIfAbsent(mob.getUUID(), id -> new State()).scriptSuspended = true;
    }

    /** @return true while a script has this NPC's casting suspended. */
    public static boolean isScriptSuspended(UUID id) {
        State state = STATES.get(id);
        return state != null && state.scriptSuspended;
    }

    /** Drop everything known about one NPC, dialog depth included. */
    public static void forget(UUID id) {
        STATES.remove(id);
    }

    /** @return how many NPCs are currently tracked, for the diagnostics summary. */
    public static int trackedCount() {
        return STATES.size();
    }

    /** Forget an NPC that has left the world — death, unload, or dimension change. */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Mob mob) {
            forget(mob.getUUID());
        }
    }

    /** Drop everything. Server shutdown, and the bridge's own teardown. */
    public static void clear() {
        STATES.clear();
        cancelCastHook = null;
    }
}
