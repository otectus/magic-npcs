package com.otectus.magicnpcs.core;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The reroll policy for {@code /magicnpcs school reroll}, as a pure function so it is unit-testable
 * without a Minecraft server.
 *
 * <p>0.5.0 picked <em>one</em> random school per mob and reported only a success count. When that school
 * happened to yield no castable spells the command printed "Re-rolled schools for 0 NPC(s)" with no
 * school named and no reason — and because the pick was random per invocation, the same command
 * succeeded or failed run to run. That is the "schools come and go from the random pool" report (W4).
 *
 * <p>The policy now walks the <em>whole</em> shuffled allowed pool, excluding the mob's current school,
 * and stops at the first school that actually produces a loadout. Only when every allowed school fails
 * does it report failure — with the reason each one gave.
 */
public final class SchoolReroll {
    private SchoolReroll() {}

    /**
     * @param assigned the school that was successfully assigned, or {@code null} if none could be
     * @param failures every school that was tried and rejected, in the order tried, with its reason
     */
    public record Outcome(ResourceLocation assigned, Map<ResourceLocation, SchoolAssignResult> failures) {
        public boolean ok() {
            return assigned != null;
        }

        /** A compact "tried A (reason), B (reason)" summary for command output. */
        public String describeFailures() {
            List<String> parts = new ArrayList<>(failures.size());
            failures.forEach((school, result) -> parts.add(result.describe(school)));
            return String.join("; ", parts);
        }
    }

    /**
     * Try every allowed school, in a random order, until one sticks.
     *
     * @param allowed the configured allowed school ids (order is not used; the list is shuffled)
     * @param current the mob's current school, excluded from the candidates so a "reroll" changes
     *                something; ignored when it is the only allowed school
     * @param random  the mob's RNG, so a reroll is reproducible from a seeded world
     * @param attempt applies a school to the mob and reports the outcome
     */
    public static Outcome reroll(List<ResourceLocation> allowed, ResourceLocation current,
                                 RandomSource random, Function<ResourceLocation, SchoolAssignResult> attempt) {
        List<ResourceLocation> candidates = new ArrayList<>(allowed);
        if (current != null && candidates.size() > 1) {
            candidates.remove(current);
        }
        shuffle(candidates, random);

        Map<ResourceLocation, SchoolAssignResult> failures = new LinkedHashMap<>();
        for (ResourceLocation school : candidates) {
            SchoolAssignResult result = attempt.apply(school);
            if (result.ok()) {
                return new Outcome(school, ordered(failures));
            }
            failures.put(school, result);
            if (result == SchoolAssignResult.SCHOOLS_DISABLED) {
                break; // a global switch — trying the rest would repeat the same answer
            }
        }
        return new Outcome(null, ordered(failures));
    }

    /**
     * Advance one step through the allowed schools for the School Tome: the next school after
     * {@code current} that actually yields a castable pool, or "cleared" once the list is exhausted.
     *
     * <p>Deliberately ordered rather than shuffled — a Tome click should be predictable — and
     * deliberately skips empty schools. The Tome used to compute {@code (index + 1) % size} and call
     * apply once; because a failed apply leaves the stored school untouched, the next click recomputed
     * the *same* dead school, so a school with an empty pool trapped the cycle forever.
     *
     * <p>Cycling past the last school clears the assignment, which is what keeps "stop casting"
     * reachable from the item now that sneak means "cycle". A cleared or unassigned mob starts again
     * at the first school.
     *
     * @param attempt applies a school to the mob and reports the outcome
     */
    public static CycleOutcome cycle(List<ResourceLocation> allowed, ResourceLocation current,
                                     Function<ResourceLocation, SchoolAssignResult> attempt) {
        Map<ResourceLocation, SchoolAssignResult> failures = new LinkedHashMap<>();
        int start = current == null ? 0 : allowed.indexOf(current) + 1;
        for (int i = Math.max(0, start); i < allowed.size(); i++) {
            ResourceLocation school = allowed.get(i);
            SchoolAssignResult result = attempt.apply(school);
            if (result.ok()) {
                return new CycleOutcome(school, false, ordered(failures));
            }
            failures.put(school, result);
            if (result == SchoolAssignResult.SCHOOLS_DISABLED) {
                return new CycleOutcome(null, false, ordered(failures));
            }
        }
        return new CycleOutcome(null, true, ordered(failures));
    }

    /**
     * @param assigned the school now assigned, or {@code null}
     * @param cleared  true when the cycle ran past the last school, meaning "stop casting"
     * @param failures schools tried and rejected on the way, in order, with the reason each gave
     */
    public record CycleOutcome(ResourceLocation assigned, boolean cleared,
                               Map<ResourceLocation, SchoolAssignResult> failures) {
        public boolean ok() {
            return assigned != null || cleared;
        }

        /** A compact "tried A (reason), B (reason)" summary. */
        public String describeFailures() {
            List<String> parts = new ArrayList<>(failures.size());
            failures.forEach((school, result) -> parts.add(result.describe(school)));
            return String.join("; ", parts);
        }
    }

    /**
     * An immutable copy that keeps insertion order. {@code Map.copyOf} returns a hash-ordered map, so
     * the "in the order tried" contract these outcomes document was quietly not honoured.
     */
    private static Map<ResourceLocation, SchoolAssignResult> ordered(
            Map<ResourceLocation, SchoolAssignResult> failures) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(failures));
    }

    /** Fisher–Yates against the mob's {@link RandomSource} (vanilla's RandomSource has no shuffle). */
    private static void shuffle(List<ResourceLocation> list, RandomSource random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            ResourceLocation tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }
}
