package com.otectus.magicnpcs.core.spell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The manifest measured against the spells Iron's actually registered.
 *
 * <p>A checked-in table drifts silently: 0.9.0 shipped rows for {@code soulfire_ray} and
 * {@code thunder_step}, which no longer register in 3.16.3, and nothing noticed until an instance run
 * counted 114 claimed rows against 111 live spells. This diff turns the next such drift into one WARN
 * per stale row instead of a number nobody can reconcile.
 *
 * <p>Pure and registry-free so it can be unit-tested; the live registry read lives in
 * {@code integration.irons.ManifestReconciler}.
 */
public final class ManifestReconciliation {

    /** Iron's own placeholder spell, which is not a claim about anything and is never counted. */
    private static final String PLACEHOLDER = "none";

    private ManifestReconciliation() {}

    /**
     * @param unregistered manifest rows with no registered spell — stale claims, sorted
     * @param unlisted     registered spells the manifest never mentions, sorted
     * @param rows         how many manifest rows were compared ({@code none} excluded)
     */
    public record Result(List<String> unregistered, List<String> unlisted, int rows) {

        /** One line for {@code /magicnpcs config} and the audit report header. */
        public String summary() {
            return "manifest: " + rows + " rows, " + unregistered.size() + " unregistered, "
                    + unlisted.size() + " unlisted";
        }
    }

    /** Compare both directions; {@code none} counts on neither side. */
    public static Result diff(Set<String> manifestPaths, Set<String> registeredPaths) {
        List<String> unregistered = new ArrayList<>();
        List<String> unlisted = new ArrayList<>();
        int rows = 0;
        for (String path : manifestPaths) {
            if (PLACEHOLDER.equals(path)) {
                continue;
            }
            rows++;
            if (!registeredPaths.contains(path)) {
                unregistered.add(path);
            }
        }
        for (String path : registeredPaths) {
            if (!PLACEHOLDER.equals(path) && !manifestPaths.contains(path)) {
                unlisted.add(path);
            }
        }
        Collections.sort(unregistered);
        Collections.sort(unlisted);
        return new Result(List.copyOf(unregistered), List.copyOf(unlisted), rows);
    }
}
