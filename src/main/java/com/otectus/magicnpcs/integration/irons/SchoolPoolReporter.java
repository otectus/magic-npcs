package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.diag.SchoolPoolReport;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.List;

/**
 * Logs one line per allowed magic school after every datapack reload: how many of its spells survive
 * the configured filters, and — for a school that survives none — that it can never be assigned.
 *
 * <p>This is the "why did my reroll say 0 NPCs?" answer without anyone needing to run a command (W4).
 * A school emptied by {@code maxRarity}, {@code maxSpellLevel}, {@code allowedCastTypes} or a
 * blacklist used to be completely invisible: {@code buildLoadout} returned {@code null} and the caller
 * reported nothing.
 *
 * <p>Registered alongside the loadout listener so it also fires on {@code /reload}. Iron's-side:
 * reading the pool needs the Iron's spell registry.
 */
public final class SchoolPoolReporter extends SimplePreparableReloadListener<Void> {

    @Override
    protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
        return null; // nothing to read off-thread; the pool comes from the Iron's registry + config
    }

    @Override
    protected void apply(Void nothing, ResourceManager resourceManager, ProfilerFiller profiler) {
        if (!MagicNpcsConfig.SPEC.isLoaded() || !MagicNpcsConfig.SCHOOLS_ENABLED.get()) {
            return;
        }
        List<SchoolPoolReport> reports;
        try {
            reports = SchoolSpellPool.reportAll();
        } catch (Exception ex) {
            MagicNpcs.LOGGER.debug("Could not build the school pool summary: {}", ex.toString());
            return;
        }
        if (reports.isEmpty()) {
            MagicNpcs.LOGGER.warn("Magic schools are enabled but schools.allowedSchools resolves to no "
                    + "registered school — no NPC can ever be assigned one.");
            return;
        }
        int empty = 0;
        for (SchoolPoolReport report : reports) {
            if (report.isEmpty()) {
                empty++;
                MagicNpcs.LOGGER.warn("[schools] {} — raise schools.maxRarity / schools.maxSpellLevel, add a "
                                + "cast type to schools.allowedCastTypes, or check your spell blacklist. "
                                + "Run /magicnpcs school pool {} for the per-spell reasons.",
                        report.summary(), report.school());
            } else {
                MagicNpcs.LOGGER.info("[schools] {}", report.summary());
            }
        }
        if (empty > 0) {
            MagicNpcs.LOGGER.warn("[schools] {} of {} allowed school(s) currently yield no castable spells; "
                            + "rerolling a caster will skip them.", empty, reports.size());
        }
    }

    @Override
    public String getName() {
        return "magicnpcs:school_pool_summary";
    }
}
