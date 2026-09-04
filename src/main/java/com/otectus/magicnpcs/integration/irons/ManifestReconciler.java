package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.core.spell.ManifestReconciliation;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Runs {@link ManifestReconciliation} against the live Iron's registry, once per server start.
 *
 * <p>{@link SpellManifest} is a checked-in table verified against one Iron's version, but
 * {@code mods.toml} accepts a range; a row that registers in no accepted version is a claim about a
 * spell nobody can cast, and a spell missing from the table is one this build has no verdict for. Both
 * used to be invisible. The result is cached because the registry cannot change while a server runs.
 */
public final class ManifestReconciler {

    private static ManifestReconciliation.Result cached;

    private ManifestReconciler() {}

    /**
     * Reconcile the manifest against the registry, or return the result of the run that already did.
     *
     * @return the diff for the {@code irons_spellbooks} namespace
     */
    public static synchronized ManifestReconciliation.Result runOnce() {
        if (cached != null) {
            return cached;
        }
        Set<String> registered = new HashSet<>();
        for (AbstractSpell spell : SpellRegistry.REGISTRY.get()) {
            if (spell == null) {
                continue;
            }
            ResourceLocation id = spell.getSpellResource();
            if (id != null && "irons_spellbooks".equals(id.getNamespace())) {
                registered.add(id.getPath());
            }
        }
        ManifestReconciliation.Result result =
                ManifestReconciliation.diff(SpellManifest.paths(), registered);
        String version = ModList.get().getModContainerById("irons_spellbooks")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("absent");
        for (String path : result.unregistered()) {
            MagicNpcs.LOGGER.warn("[manifest] manifest row '{}' has no registered spell in Iron's {}; "
                    + "remove it or bump VERIFIED_AGAINST", path, version);
        }
        for (String path : result.unlisted()) {
            MagicNpcs.LOGGER.info("[manifest] Iron's spell '{}' has no manifest row; it is unverified "
                    + "until one is added", path);
        }
        MagicNpcs.LOGGER.info("[manifest] {} (verified against {})", result.summary(),
                SpellManifest.VERIFIED_AGAINST);
        cached = result;
        return result;
    }

    /** @return the reconciliation of this server run, if it has happened yet. */
    public static synchronized Optional<ManifestReconciliation.Result> current() {
        return Optional.ofNullable(cached);
    }

    /** @return {@link ManifestReconciliation.Result#summary()}, or why there is not one yet. */
    public static synchronized String summary() {
        return cached == null ? "manifest: not yet reconciled" : cached.summary();
    }

    /** Drop the cached result so the next {@link #runOnce()} reads the registry again. */
    public static synchronized void reset() {
        cached = null;
    }
}
