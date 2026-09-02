package com.otectus.magicnpcs.compat.easynpc;

import com.otectus.magicnpcs.compat.EasyNpcConfigUiCompat;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.diag.DiagnosticContributors;
import com.otectus.magicnpcs.core.diag.DiagnosticReport;
import de.markusbordihn.easynpc.api.handler.EasyNPCPauseHandler;
import de.markusbordihn.easynpc.data.objective.ObjectiveDataEntry;
import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import de.markusbordihn.easynpc.entity.easynpc.data.FactionDataCapable;
import de.markusbordihn.easynpc.entity.easynpc.data.NavigationDataCapable;
import de.markusbordihn.easynpc.entity.easynpc.data.ObjectiveDataCapable;
import de.markusbordihn.easynpc.entity.easynpc.data.OwnerDataCapable;
import de.markusbordihn.easynpc.entity.easynpc.data.ProgressionDataCapable;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;

/**
 * The Easy NPC section of {@code /magicnpcs why}.
 *
 * <p>Every line here answers a question the generic report cannot: an NPC that is paused, or whose
 * faction is protecting the thing it is aiming at, or whose owner set it immovable, looks identical
 * from outside to one that is simply broken. Naming the cause is the difference between a one-line fix
 * and a bug report.
 */
final class EasyNpcDiagnostics {

    private EasyNpcDiagnostics() {}

    static void register() {
        DiagnosticContributors.register(EasyNpcDiagnostics::describe);
    }

    private static void describe(Mob mob, DiagnosticReport.Builder out) {
        if (!(mob instanceof EasyNPC<?> npc)) {
            return; // not an Easy NPC — say nothing rather than pad the report
        }
        out.info("Easy NPC: core present, configuration UI "
                + (EasyNpcConfigUiCompat.isLoaded() ? "present" : "absent (core-only install)"));

        if (!MagicNpcsConfig.EASYNPC_INTEGRATION_ENABLED.get()) {
            out.warn("easynpc.enabled is off [EASYNPC_DISABLED] — this NPC will not cast. Owner and "
                    + "faction protection are still applied.");
        }
        if (EasyNPCPauseHandler.isPaused(npc)) {
            out.bad("Easy NPC reports this NPC is PAUSED [EASYNPC_PAUSED] — it will not cast until it "
                    + "is resumed.");
        }

        describeOwnerAndFaction(npc, out);
        describeProgression(npc, out);
        describeNavigation(npc, out);
        describeObjectives(npc, out);
    }

    private static void describeOwnerAndFaction(EasyNPC<?> npc, DiagnosticReport.Builder out) {
        OwnerDataCapable<?> owner = npc.getEasyNPCOwnerData();
        if (owner != null && owner.hasNPCOwner()) {
            out.detail("owner: " + owner.getNPCOwnerName() + " (" + owner.getOwnerUUID()
                    + ") — never a valid target, online or not");
        } else {
            out.detail("owner: none");
        }
        FactionDataCapable<?> faction = npc.getEasyNPCFactionData();
        if (faction != null && faction.hasFactionName()) {
            out.detail("faction: " + faction.getFactionName()
                    + (MagicNpcsConfig.EASYNPC_RESPECT_FACTIONS.get()
                    ? " — same-faction entities are protected from its spells"
                    : " — ignored, easynpc.respectFactions is off"));
        } else {
            out.detail("faction: none");
        }
    }

    private static void describeProgression(EasyNPC<?> npc, DiagnosticReport.Builder out) {
        ProgressionDataCapable<?> progression = npc.getEasyNPCProgressionData();
        if (progression == null) {
            out.detail("progression: this NPC type carries no progression data; mana is unscaled");
            return;
        }
        int level = progression.getExperienceLevel();
        out.detail(String.format("progression: level %d of %d — mana scaled by %.2fx "
                        + "(easynpc.manaPerLevel = %.3f)",
                level, progression.getMaxExperienceLevel(),
                1.0 + level * MagicNpcsConfig.EASYNPC_MANA_PER_LEVEL.get(),
                MagicNpcsConfig.EASYNPC_MANA_PER_LEVEL.get()));
        int minLevel = MagicNpcsConfig.SCHOOLS_EASYNPC_MIN_LEVEL.get();
        if (MagicNpcsConfig.SCHOOLS_EASYNPC_ENABLED.get() && level < minLevel) {
            out.warn("below schools.easynpc.minLevelToCast (" + minLevel
                    + ") [EASYNPC_BELOW_MIN_LEVEL] — it will not be assigned a school automatically.");
        }
    }

    private static void describeNavigation(EasyNPC<?> npc, DiagnosticReport.Builder out) {
        NavigationDataCapable<?> navigation = npc.getEasyNPCNavigationData();
        if (navigation == null) {
            return;
        }
        if (navigation.isImmovable()) {
            out.detail("navigation: immovable [EASYNPC_IMMOVABLE] — it casts from where it stands.");
        } else if (navigation.hasHomePosition()) {
            out.detail("navigation: home position " + navigation.getHomePosition()
                    + " — repositioning is leashed to it.");
        } else {
            out.detail("navigation: free to move.");
        }
    }

    /**
     * List the NPC's objectives, and say whether our casting objective is among them.
     *
     * <p>An objective that exists but is not registered is the tell for "this NPC has the casting
     * objective but nothing to cast" — Easy NPC retries such an entry, so it self-corrects the moment
     * the NPC is given a loadout or a school, and saying so stops that looking like a failure.
     */
    private static void describeObjectives(EasyNPC<?> npc, DiagnosticReport.Builder out) {
        ObjectiveDataCapable<?> objectiveData = npc.getEasyNPCObjectiveData();
        if (objectiveData == null || objectiveData.getObjectiveDataSet() == null) {
            out.detail("objectives: none");
            return;
        }
        List<String> names = new ArrayList<>();
        ObjectiveDataEntry ours = null;
        for (ObjectiveDataEntry entry : objectiveData.getObjectiveDataSet().getObjectives()) {
            if (entry == null) {
                continue;
            }
            names.add(entry.getId() + "@" + entry.getPriority());
            if (EasyNpcSpellObjective.ID.toString().equals(entry.getId())) {
                ours = entry;
            }
        }
        out.detail("objectives: " + (names.isEmpty() ? "none" : String.join(", ", names)));
        if (!MagicNpcsConfig.EASYNPC_USE_OBJECTIVE.get()) {
            out.detail("casting objective: not registered (easynpc.useObjective is off). Datapack "
                    + "loadouts and schools still work.");
        } else if (ours == null) {
            out.detail("casting objective: absent. This NPC casts from a loadout or a school, not from "
                    + "an Easy NPC objective — which is fine, it is just the other route.");
        } else if (ours.isRegistered()) {
            out.good("casting objective: " + EasyNpcSpellObjective.ID + " active at priority "
                    + ours.getPriority() + ".");
        } else {
            out.warn("casting objective: " + EasyNpcSpellObjective.ID + " is present but has produced "
                    + "no goal yet [EASYNPC_OBJECTIVE_UNFULFILLED] — this NPC has no loadout and no "
                    + "school, so there is nothing for it to cast. Easy NPC retries, so giving it "
                    + "either one is enough.");
        }
    }
}
