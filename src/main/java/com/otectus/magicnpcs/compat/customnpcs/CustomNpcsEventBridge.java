package com.otectus.magicnpcs.compat.customnpcs;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.compat.CustomNpcsCompat;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import noppes.npcs.api.event.DialogEvent;
import noppes.npcs.api.event.NpcEvent;

import java.util.function.Consumer;

/**
 * Listens on <b>CustomNPCs' own API event bus</b> ({@code NpcAPI.Instance().events()}) — not the Forge
 * bus. CustomNPCs publishes its NPC lifecycle there and nowhere else, so a {@code @SubscribeEvent}
 * method registered on {@code MinecraftForge.EVENT_BUS} would compile, register, and never fire.
 *
 * <p>An instance rather than a static holder, because it has to be able to unregister <em>itself</em>
 * from that bus when it trips its own breaker.
 *
 * <p>Fault handling is deliberately blunt. This code runs inside another mod's event dispatch, on
 * every NPC, every update tick: an exception thrown here does not stay a Magic NPCs problem, it
 * derails CustomNPCs' own update. So faults are counted, and after {@link #FAULT_LIMIT} of them the
 * bridge takes itself off the bus entirely rather than keep breaking someone else's mod. Only
 * {@link LinkageError} and {@link RuntimeException} are caught — an {@code Error} that is not a
 * linkage failure is not ours to swallow.
 */
public final class CustomNpcsEventBridge {

    /** Faults tolerated in one session before the bridge unregisters itself. */
    private static final int FAULT_LIMIT = 5;

    private static volatile long lastEventGameTime = Long.MIN_VALUE;

    private final IEventBus bus;

    /**
     * Iron's-side goal repair, or {@code null} when Iron's Spellbooks is absent.
     *
     * <p>A {@code Consumer<Mob>} and not a field of type {@link CustomNpcsAiRepair}: the field type
     * alone would make that class — and the {@code integration.irons} package it imports — part of
     * this class's own resolution, so a no-Iron's install would fail to link the bridge.
     */
    private final Consumer<Mob> repairHook;

    private int faults;

    CustomNpcsEventBridge(IEventBus bus, Consumer<Mob> repairHook) {
        this.bus = bus;
        this.repairHook = repairHook;
    }

    /** An NPC finished initialising: its goal selectors exist for the first time. */
    @SubscribeEvent
    public void onInit(NpcEvent.InitEvent event) {
        handle(event);
    }

    /**
     * An NPC ticked. The moment after a goal-selector rebuild is somewhere in this stream, and so is
     * the only regular opportunity to answer a script's mailbox request.
     *
     * <p>The mailbox runs <em>after</em> the repair, not before: a script asking "is this NPC a caster"
     * on the same tick its goals were rebuilt should get the answer for the repaired NPC.
     */
    @SubscribeEvent
    public void onUpdate(NpcEvent.UpdateEvent event) {
        handle(event);
        try {
            CustomNpcsScriptBridge.processMailbox(event.npc);
        } catch (LinkageError | RuntimeException ex) {
            recordFault(ex);
        }
    }

    /** A player opened a dialog with an NPC: it stops casting until every dialog on it is closed. */
    @SubscribeEvent
    public void onDialogOpen(DialogEvent.OpenEvent event) {
        Mob mob = serverNpc(event);
        if (mob != null) {
            CustomNpcsActivityState.open(mob);
        }
    }

    /** A player closed a dialog. The NPC casts again once no dialog is left open on it. */
    @SubscribeEvent
    public void onDialogClose(DialogEvent.CloseEvent event) {
        Mob mob = serverNpc(event);
        if (mob != null) {
            CustomNpcsActivityState.close(mob);
        }
    }

    /**
     * @return the server-side entity behind an event, or {@code null} when there is none to act on.
     *         Faults are absorbed by the same breaker as the lifecycle handlers, because these run in
     *         the same dispatch and doing otherwise would let a dialog break CustomNPCs' own GUI.
     */
    private Mob serverNpc(NpcEvent event) {
        try {
            Mob mob = event.npc.getMCEntity();
            return mob == null || mob.level().isClientSide() ? null : mob;
        } catch (LinkageError | RuntimeException ex) {
            recordFault(ex);
            return null;
        }
    }

    private void handle(NpcEvent event) {
        try {
            // ICustomNpc is declared over Mob, so this needs no instanceof — but it can still be null
            // for an NPC whose entity has already gone away by the time the event is dispatched.
            Mob mob = event.npc.getMCEntity();
            if (mob == null || mob.level().isClientSide()) {
                return;
            }
            lastEventGameTime = mob.level().getGameTime();
            CustomNpcsActivityState.noteUpdate(mob, lastEventGameTime);
            if (repairHook == null
                    || !MagicNpcsConfig.customNpcsBridgeEnabled()
                    || !MagicNpcsConfig.customNpcsRepairAfterAiRebuild()) {
                return;
            }
            repairHook.accept(mob);
        } catch (LinkageError | RuntimeException ex) {
            recordFault(ex);
        }
    }

    private void recordFault(Throwable cause) {
        faults++;
        if (faults < FAULT_LIMIT) {
            MagicNpcs.LOGGER.warn("[magicnpcs] CustomNPCs bridge fault {} of {}: {}",
                    faults, FAULT_LIMIT, cause.toString(), cause);
            return;
        }
        bus.unregister(this);
        CustomNpcsCompat.markError("the CustomNPCs event bridge failed " + FAULT_LIMIT
                + " times and has been unregistered; NPC casting goals will no longer be repaired", cause);
    }

    /** @return the game time the last CustomNPCs event arrived, or {@link Long#MIN_VALUE} if none has. */
    public static long lastEventGameTime() {
        return lastEventGameTime;
    }

    /** @return how many faults this bridge has absorbed, for the diagnostics summary. */
    public int faults() {
        return faults;
    }

    /** Forget the heartbeat. Server shutdown, and the bridge's own teardown. */
    static void resetHeartbeat() {
        lastEventGameTime = Long.MIN_VALUE;
    }
}
