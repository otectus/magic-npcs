package com.otectus.magicnpcs.compat.customnpcs;

import com.otectus.magicnpcs.compat.CustomNpcsCompat;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.caster.MagicNpcEvents;
import com.otectus.magicnpcs.core.diag.DiagnosticContributors;
import com.otectus.magicnpcs.core.diag.DiagnosticReport;
import net.minecraft.world.entity.Mob;
import noppes.npcs.api.NpcAPI;
import noppes.npcs.api.entity.ICustomNpc;
import noppes.npcs.api.handler.data.IFaction;

/**
 * The CustomNPCs section of {@code /magicnpcs why}, plus the global bridge summary.
 *
 * <p>Answers the questions that only CustomNPCs can answer about one of its NPCs — what faction it is
 * in, what role and job an author gave it, which AI modes it is set to — and the one question only the
 * bridge can answer: whether it is still hearing from CustomNPCs at all. A silent NPC whose last
 * update event was two thousand ticks ago is a broken bridge, not a broken caster, and that
 * distinction is invisible without a heartbeat.
 */
public final class CustomNpcsDiagnostics implements DiagnosticContributors.Contributor {

    @Override
    public void describe(Mob mob, DiagnosticReport.Builder out) {
        if (!NpcAPI.IsAvailable() || !(NpcAPI.Instance().getIEntity(mob) instanceof ICustomNpc<?> npc)) {
            return; // not one of ours; contribute nothing
        }
        out.header("CustomNPCs");
        out.info("wrapper: yes");

        IFaction faction = npc.getFaction();
        out.info("faction: " + (faction == null ? "none" : faction.getId() + " (" + faction.getName() + ")"));
        out.info("role: " + CustomNpcsIds.role(npc.getRole().getType()));
        out.info("job: " + CustomNpcsIds.job(npc.getJob().getType()));
        out.info("moving: " + CustomNpcsIds.moving(npc.getAi().getMovingType()));
        out.info("retaliate: " + CustomNpcsIds.retaliate(npc.getAi().getRetaliateType()));

        long last = CustomNpcsActivityState.lastUpdate(mob);
        if (last == Long.MIN_VALUE) {
            out.warn("no CustomNPCs update event has ever been seen for this NPC");
        } else {
            out.info("ticks since last update event: " + (mob.level().getGameTime() - last));
        }
        out.info("goal repairs for this NPC: " + CustomNpcsActivityState.repairs(mob));

        // The script-side facts. A suspended NPC looks exactly like a broken one from the outside, and
        // "which signal did its script last hear" is the only way to tell a script that never fires
        // from one that fires and does nothing.
        if (CustomNpcsActivityState.isScriptSuspended(mob.getUUID())) {
            out.bad("a script has this NPC's casting suspended (MagicNPCs.setCastingSuspended)");
        } else {
            out.info("script suspension: none");
        }
        out.info("cast open: " + MagicNpcEvents.isCastOpen(mob));
        out.info("last signal to its script: " + CustomNpcsScriptBridge.lastSignal(mob));
    }

    /** One block of text for {@code /magicnpcs config}: the state of the bridge as a whole. */
    public static String global() {
        StringBuilder out = new StringBuilder();
        out.append("status: ").append(CustomNpcsCompat.statusLine()).append('\n');
        out.append("detected version: ").append(String.valueOf(CustomNpcsCompat.detectedVersion()))
                .append(", supported: ").append(String.join(", ", CustomNpcsCompat.SUPPORTED_VERSIONS))
                .append('\n');
        out.append("probe: ").append(CustomNpcsIntegration.probeResult()).append('\n');
        out.append("API bus registered: ").append(CustomNpcsIntegration.busRegistered()).append('\n');
        long last = CustomNpcsEventBridge.lastEventGameTime();
        out.append("heartbeat: ")
                .append(last == Long.MIN_VALUE ? "no event seen yet" : "last event at game time " + last)
                .append('\n');
        // Read through the integration, which holds the counter source as a reference created inside
        // the Iron's guard. Naming CustomNpcsAiRepair here would put its irons imports on this class's
        // own resolution path, and this class runs on every /magicnpcs why.
        out.append("repairs/duplicates/failures: ")
                .append(CustomNpcsIntegration.repairCounters()).append('\n');
        out.append("bridge faults: ").append(CustomNpcsIntegration.bridgeFaults()).append('\n');
        // The script surface. Six flags rather than one "scripting: on", because each one is
        // separately switchable and every support question about a script that does nothing comes down
        // to which of them is off.
        out.append("script trigger id: ").append(MagicNpcsConfig.customNpcsScriptTriggerId())
                .append(", emit: ").append(MagicNpcsConfig.customNpcsEmitScriptTriggers())
                .append(", mailbox: ").append(MagicNpcsConfig.customNpcsScriptMailboxEnabled())
                .append(", mutations: ").append(MagicNpcsConfig.customNpcsScriptMutationsEnabled())
                .append(", cancel handshake: ")
                .append(MagicNpcsConfig.customNpcsScriptCancelHandshakeEnabled()).append('\n');
        out.append("script global '").append(CustomNpcsScriptGlobal.GLOBAL_NAME).append("': ")
                .append(CustomNpcsScriptGlobal.installed() ? "installed" : "not installed");
        if (!CustomNpcsScriptGlobal.installed() && !CustomNpcsScriptGlobal.failure().isEmpty()) {
            out.append(" — ").append(CustomNpcsScriptGlobal.failure());
        }
        out.append('\n');
        out.append("signals emitted: ").append(CustomNpcsScriptBridge.signalsEmitted())
                .append(", mailbox requests: ").append(CustomNpcsScriptBridge.mailboxRequests())
                .append(", last result: ").append(CustomNpcsScriptBridge.lastResultCode()).append('\n');
        out.append("open casts: ").append(MagicNpcEvents.openCasts());
        return out.toString();
    }
}
