package com.otectus.magicnpcs.core.diag;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * What one Iron's school's generated spell pool currently contains, and why each spell that did not
 * make it was dropped. Backs {@code /magicnpcs school pool} and the once-per-reload startup summary —
 * the direct answer to "some schools come and go from the random pool, is there something I'm missing
 * in the configs?" (W4).
 *
 * <p>Iron's-free (plain strings and ids), produced on the integration side so the command package
 * stays free of Iron's imports.
 *
 * @param school     the school id
 * @param registered how many spells Iron's registers for this school, before any filtering
 * @param survivors  the spells that survive every filter, in id order
 * @param drops      one entry per filtered-out spell, with the first filter that rejected it
 */
public record SchoolPoolReport(
        ResourceLocation school,
        int registered,
        List<Survivor> survivors,
        List<Drop> drops
) {
    /**
     * @param spellId the spell that made the pool
     * @param level   the level it would be cast at (after {@code maxSpellLevel} clamping)
     * @param rarity  its rarity at that level
     * @param role    ATTACK or SUPPORT, as classified by {@code schools.supportSpellIds} + name keywords
     * @param weight  its selection weight under the configured weighting mode
     */
    public record Survivor(String spellId, int level, String rarity, String role, int weight) {}

    /**
     * @param spellId the spell that was filtered out
     * @param reason  the first filter that rejected it, phrased so a pack author knows which config
     *                key to change
     */
    public record Drop(String spellId, String reason) {}

    /** @return true if this school can never be assigned under the current configuration. */
    public boolean isEmpty() {
        return survivors.isEmpty();
    }

    /** One-line summary for the reload log, e.g. {@code fire: 7 of 19 spells castable}. */
    public String summary() {
        if (survivors.isEmpty()) {
            return school + ": 0 of " + registered + " spells castable — this school can never be assigned";
        }
        return school + ": " + survivors.size() + " of " + registered + " spells castable";
    }
}
