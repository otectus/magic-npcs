package com.otectus.magicnpcs.core.spell;

import java.util.List;

/**
 * Matches a spell id against the {@code spells.spellWhitelist} / {@code spells.spellBlacklist}
 * patterns: an exact {@code namespace:path}, or a whole-namespace wildcard {@code namespace:*}.
 *
 * <p>The wildcard exists because a non-empty whitelist is absolute: without it, allowing a
 * seventy-seven-spell add-on pack meant seventy-seven lines, and trusting a namespace while a
 * whitelist was set silently enabled nothing. Pure and Iron's-free.
 */
public final class SpellIdFilter {

    private SpellIdFilter() {}

    /**
     * @param patterns exact ids and/or {@code namespace:*} wildcards; may be {@code null} or empty
     * @param id       a full {@code namespace:path} spell id
     * @return true if any pattern matches {@code id}
     */
    public static boolean matches(List<? extends String> patterns, String id) {
        if (patterns == null || patterns.isEmpty() || id == null) {
            return false;
        }
        int colon = id.indexOf(':');
        String namespaceWildcard = colon > 0 ? id.substring(0, colon) + ":*" : null;
        for (String pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            String trimmed = pattern.trim();
            if (trimmed.equals(id) || trimmed.equals(namespaceWildcard)) {
                return true;
            }
        }
        return false;
    }
}
