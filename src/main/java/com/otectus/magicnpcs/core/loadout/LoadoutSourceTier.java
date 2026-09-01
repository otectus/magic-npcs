package com.otectus.magicnpcs.core.loadout;

/**
 * Where a {@link SpellcasterLoadout} came from. A higher tier wins outright at load time, so a
 * datapack's loadout replaces a mod-jar one for the same entity type (+ profession) without the
 * author needing to discover {@code "replace": true} — see ADR 0003.
 *
 * <p>The tier is derived from the {@code ResourceManager} at load time
 * ({@code Resource.isBuiltin()} / a {@code mod:} pack id), never from the file's namespace: a
 * datapack may legitimately place files under the {@code magicnpcs} namespace.
 *
 * <p>Vanilla-only, so it is usable from the Iron's-free core and unit-testable.
 */
public enum LoadoutSourceTier {
    /** Shipped inside a mod jar's own data pack — the lowest-precedence source. */
    BUILT_IN,
    /** Any other pack: a world datapack, a global pack, OpenLoader, or an in-code loadout. */
    DATAPACK;

    /** @return true if this tier outranks {@code other}. */
    public boolean outranks(LoadoutSourceTier other) {
        return ordinal() > other.ordinal();
    }

    /** Human-readable label for diagnostics ({@code /magicnpcs loadout}, load-time logs). */
    public String label() {
        return this == BUILT_IN ? "jar" : "datapack";
    }
}
