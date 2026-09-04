package com.otectus.magicnpcs.core.spell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;

/**
 * "Did you mean …?" for a spell id that did not resolve, built from a {@code path -> namespaces}
 * index of the live spell registry.
 *
 * <p>Two mistakes are common now that add-ons register spells of their own: writing a bare id (which
 * only ever resolves under {@code irons_spellbooks}), and writing the wrong namespace for a path that
 * several mods register. Resolution itself is unchanged — a bare id still means
 * {@code irons_spellbooks:} and nothing else — so a foreign namespace is only ever <em>suggested</em>.
 * Pure and Iron's-free.
 */
public final class SpellIdSuggestions {

    private static final String IRONS = "irons_spellbooks";

    private SpellIdSuggestions() {}

    /**
     * @param pathToNamespaces every registered spell path mapped to the namespaces registering it
     * @param rawId            the id the author wrote (bare or namespaced)
     * @return a suggestion sentence, or empty when nothing plausible exists
     */
    public static Optional<String> suggest(Map<String, List<String>> pathToNamespaces, String rawId) {
        if (pathToNamespaces == null || rawId == null || rawId.isBlank()) {
            return Optional.empty();
        }
        String id = rawId.trim().toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        String namespace = colon > 0 ? id.substring(0, colon) : "";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        List<String> registered = pathToNamespaces.get(path);
        if (registered == null || registered.isEmpty()) {
            return Optional.empty();
        }
        // A bare id (or the minecraft namespace a bare id parses to) is not "wrong namespace": every
        // candidate is a suggestion. Otherwise the namespace the author wrote is the one that failed.
        boolean bare = namespace.isEmpty() || "minecraft".equals(namespace);
        List<String> candidates = new ArrayList<>();
        for (String ns : registered) {
            if (bare || !ns.equals(namespace)) {
                candidates.add(ns);
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        candidates.sort((a, b) -> {
            if (a.equals(b)) {
                return 0;
            }
            if (IRONS.equals(a)) {
                return -1;
            }
            if (IRONS.equals(b)) {
                return 1;
            }
            return a.compareTo(b);
        });
        StringBuilder sb = new StringBuilder("did you mean ")
                .append(candidates.get(0)).append(':').append(path).append('?');
        if (candidates.size() > 1) {
            sb.append(" (also registered as");
            for (int i = 1; i < candidates.size(); i++) {
                sb.append(i == 1 ? " " : ", ").append(candidates.get(i)).append(':').append(path);
            }
            sb.append(')');
        }
        return Optional.of(sb.toString());
    }
}
