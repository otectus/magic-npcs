package com.otectus.magicnpcs.core.loadout;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Everything known about <em>one discovered</em> {@code data/&lt;ns&gt;/spellcasters/*.json} resource,
 * whether or not it parsed and whether or not it survived override resolution.
 *
 * <p>This exists because 0.6.1's loader was split-brain: {@code LoadoutManager.apply} caught a parse
 * exception, wrote a log line, and dropped the file, while {@code snapshot()} exposed only the
 * successfully parsed and resolved loadouts. {@code /magicnpcs validate} therefore inspected the one
 * set of files that could not contain the user's mistake, and truthfully answered "no issues" while
 * the skeleton loadout it was asked about had been rejected minutes earlier (audit VAL-001). Keeping
 * a record per discovered resource — including rejected ones — is what lets validation be honest.
 *
 * @param resourceId  the logical resource id, e.g. {@code my_magic:skeleton_missile}
 * @param packId      the pack the {@code ResourceManager} attributes it to, or {@code "<unknown>"}
 * @param tier        whether it came from a datapack or a mod jar (drives override precedence)
 * @param status      the resource's fate — see {@link Status}
 * @param entityType  the declared entity type, or {@code null} when parsing failed before reading it
 * @param profession  the declared villager profession, or {@code null}
 * @param loadout     the parsed loadout, or {@code null} for a {@link Status#REJECTED} resource
 * @param problems    every problem found, most severe first is not guaranteed — read {@link #worst()}
 * @param contentHash a stable digest of the file's canonical JSON, so a reconciler can tell "the same
 *                    loadout came back" from "the author edited it" without deep-comparing records
 */
public record LoadoutRecord(
        ResourceLocation resourceId,
        String packId,
        LoadoutSourceTier tier,
        Status status,
        ResourceLocation entityType,
        ResourceLocation profession,
        SpellcasterLoadout loadout,
        List<LoadoutProblem> problems,
        String contentHash
) {

    /** The fate of a discovered resource. Only {@link #ACTIVE} records reach the runtime map. */
    public enum Status {
        /** Parsed, resolved, and installed in the runtime map. */
        ACTIVE,
        /** Parsed and valid, but a higher-tier or {@code replace} resource owns its effective key. */
        SHADOWED,
        /** Parsed, but {@code "enabled": false} makes it inert (and possibly kills its group). */
        SUPPRESSED,
        /** Could not be parsed or failed validation. Never used at runtime; kept for diagnostics. */
        REJECTED;

        public boolean isUsable() {
            return this == ACTIVE;
        }
    }

    public LoadoutRecord {
        problems = List.copyOf(problems);
    }

    /** A copy of this record with a different status (override resolution decides it after parsing). */
    public LoadoutRecord withStatus(Status newStatus) {
        return new LoadoutRecord(resourceId, packId, tier, newStatus, entityType, profession,
                loadout, problems, contentHash);
    }

    /** A copy of this record with {@code extra} appended to its problem list. */
    public LoadoutRecord withProblem(LoadoutProblem extra) {
        List<LoadoutProblem> merged = new java.util.ArrayList<>(problems);
        merged.add(extra);
        return new LoadoutRecord(resourceId, packId, tier, status, entityType, profession,
                loadout, merged, contentHash);
    }

    /** @return the most severe problem's severity, or {@code null} when the record is clean. */
    public LoadoutProblem.Severity worst() {
        LoadoutProblem.Severity worst = null;
        for (LoadoutProblem p : problems) {
            if (worst == null || p.severity().atLeast(worst)) {
                worst = p.severity();
            }
        }
        return worst;
    }

    public boolean hasErrors() {
        for (LoadoutProblem p : problems) {
            if (p.severity() == LoadoutProblem.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    /** {@code my_magic:skeleton (pack my_magic) [datapack]} — names the file a message is about. */
    public String describeSource() {
        return resourceId + " (pack " + packId + ") [" + tier.label() + "]";
    }

    /** The effective override key this record competes on: {@code entityType} plus optional profession. */
    public String effectiveKey() {
        return entityType + (profession == null ? "" : " (" + profession + ")");
    }
}
