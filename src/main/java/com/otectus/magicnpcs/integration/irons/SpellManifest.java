package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.core.spell.SpellCapability;
import com.otectus.magicnpcs.core.spell.SpellManifestStore;
import com.otectus.magicnpcs.core.spell.SpellSupportResolver;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The reviewed mob-cast capability manifest for the Iron's Spells 'n Spellbooks spells this build was
 * checked against.
 *
 * <p>0.6.1 classified spells with a four-entry keyword map and defaulted <em>everything else</em> to
 * "aimed projectile, supported" — so {@code /magicnpcs validate} and the runtime both claimed support
 * for the entire registry, including player-only spells and spells whose whole effect lives in a cast
 * tick the bridge never ran (audit SPI-002). The table below replaces that guess with an explicit,
 * per-spell verdict for the {@code irons_spellbooks} namespace.
 *
 * <p><b>It is no longer the only layer.</b> Through 0.8.0 a spell this table did not cover was
 * {@link SpellCapability#UNVERIFIED} full stop, which made every Iron's add-on's spells uncastable
 * unless the operator turned off the global safety net. {@link #verdictOf} now asks, in order, the
 * config {@code spells.capabilityOverrides}, the datapack spell manifests
 * ({@code data/<ns>/spell_manifests/*.json}), this table, and finally {@code spells.trustedNamespaces}
 * - which yields {@link SpellCapability#ADDON_DEFAULT}, a positive claim about the namespace rather
 * than about the spell. Only when no layer answers is the spell {@link SpellCapability#UNVERIFIED}.
 * The ranking itself lives in the Iron's-free {@link SpellSupportResolver}; this class supplies the
 * built-in layer and the Iron's version it was verified against.
 *
 * <p><b>How this was produced.</b> Every {@code AbstractSpell} subclass in the Iron's 1.20.1-3.16.3 jar was
 * disassembled and classified on what it actually does: which {@code ICastData} type its
 * {@code onCast}/{@code checkPreCastConditions} reads, whether {@code checkPreCastConditions} refuses a
 * non-{@code ServerPlayer} caster, whether {@code AbstractSpellCastingMob.initiateCastSpell} gives it
 * special preparation through {@code IMagicEntity}, and its {@code CastType}. Regenerate it against a
 * new Iron's release before widening the supported version range in {@code mods.toml}; the checked-in
 * list is the contract this build is allowed to make claims about.
 *
 * <p>Timing ({@code CastType}) is deliberately <em>not</em> recorded here. {@link MobCastSession} runs
 * Iron's canonical INSTANT/LONG/CONTINUOUS lifecycle generically, so a spell's cast type no longer
 * changes whether it is supported — only its cast-<em>data</em> requirement does.
 */
public final class SpellManifest {

    /** The Iron's version whose bytecode this manifest was derived from. */
    public static final String VERIFIED_AGAINST = "1.20.1-3.16.3";

    /** Path -> capability, for the {@code irons_spellbooks} namespace. */
    private static final Map<String, SpellCapability> BY_PATH = build();

    private SpellManifest() {}

    /**
     * @return the winning capability for {@code spellId} and the layer that decided it. The built-in
     *         layer answers for {@code irons_spellbooks} only - including with
     *         {@link SpellCapability#UNVERIFIED} for a spell this build deliberately did not verify,
     *         which is a different answer from "we have never heard of this namespace" and stops an
     *         unreviewed Iron's spell being swept up by namespace trust.
     */
    public static SpellSupportResolver.Verdict verdictOf(ResourceLocation spellId) {
        SpellSupportResolver.Verdict verdict = SpellSupportResolver.resolve(spellId,
                SpellManifest::builtin,
                SpellManifestStore.snapshot(),
                MagicNpcsConfig.capabilityOverrides(),
                MagicNpcsConfig.trustedNamespaces());
        if (verdict.provenance() == SpellSupportResolver.Provenance.VERIFIED) {
            // The resolver is Iron's-free, so it cannot name the version this table was built from.
            return new SpellSupportResolver.Verdict(verdict.capability(), verdict.provenance(),
                    SpellSupportResolver.BUILTIN_SOURCE + " (" + VERIFIED_AGAINST + ")");
        }
        return verdict;
    }

    /** @return the capability of {@link #verdictOf}, for callers that do not care which layer won. */
    public static SpellCapability capabilityOf(ResourceLocation spellId) {
        return verdictOf(spellId).capability();
    }

    /**
     * The built-in layer: this build's reviewed verdict, or {@code null} for a namespace it never
     * reviewed at all (which is the only case namespace trust may speak for).
     */
    private static SpellCapability builtin(ResourceLocation spellId) {
        if (spellId == null || !"irons_spellbooks".equals(spellId.getNamespace())) {
            return null;
        }
        return BY_PATH.getOrDefault(spellId.getPath(), SpellCapability.UNVERIFIED);
    }

    /** @return how many spells the manifest covers, for the {@code /magicnpcs config} report. */
    public static int size() {
        return BY_PATH.size();
    }

    /**
     * @return how many spells this build actually made a claim about: {@link #size()} without Iron's
     *         {@code none} placeholder, which is a registry entry rather than a spell.
     */
    public static int verifiedCount() {
        return BY_PATH.containsKey("none") ? BY_PATH.size() - 1 : BY_PATH.size();
    }

    /** @return every manifest path, for the registry reconciliation in {@link ManifestReconciler}. */
    public static java.util.Set<String> paths() {
        return java.util.Collections.unmodifiableSet(BY_PATH.keySet());
    }

    private static Map<String, SpellCapability> build() {
        Map<String, SpellCapability> m = new LinkedHashMap<>(112);
        java.util.List<Map.Entry<String, SpellCapability>> entries = java.util.List.of(
            e("abyssal_shroud", SpellCapability.DIRECT),
            e("acid_orb", SpellCapability.DIRECT),
            e("acupuncture", SpellCapability.TARGET_ENTITY),
            e("angel_wing", SpellCapability.DIRECT),
            e("arcane_shackle", SpellCapability.DIRECT),
            e("arrow_volley", SpellCapability.TARGET_ENTITY),
            e("ascension", SpellCapability.DIRECT),
            e("ball_lightning", SpellCapability.DIRECT),
            e("black_hole", SpellCapability.DIRECT),
            e("blaze_storm", SpellCapability.DIRECT),
            e("blessing_of_life", SpellCapability.TARGET_ENTITY),
            e("blight", SpellCapability.TARGET_ENTITY),
            e("blizzard", SpellCapability.TARGET_ENTITY),
            e("blood_needles", SpellCapability.DIRECT),
            e("blood_slash", SpellCapability.DIRECT),
            e("blood_step", SpellCapability.SPECIAL_PREPARATION),
            e("burning_dash", SpellCapability.SPECIAL_PREPARATION),
            e("chain_creeper", SpellCapability.TARGET_ENTITY),
            e("chain_lightning", SpellCapability.TARGET_ENTITY),
            e("charge", SpellCapability.DIRECT),
            e("cleanse", SpellCapability.TARGET_AREA),
            e("cloud_of_regeneration", SpellCapability.DIRECT),
            e("cone_of_cold", SpellCapability.DIRECT),
            e("counterspell", SpellCapability.DIRECT),
            e("devour", SpellCapability.TARGET_ENTITY),
            e("divine_smite", SpellCapability.DIRECT),
            e("dragon_breath", SpellCapability.DIRECT),
            e("earthquake", SpellCapability.TARGET_ENTITY),
            e("echoing_strikes", SpellCapability.DIRECT),
            e("eldritch_blast", SpellCapability.DIRECT),
            e("electrocute", SpellCapability.DIRECT),
            e("evasion", SpellCapability.DIRECT),
            e("fang_strike", SpellCapability.DIRECT),
            e("fang_swirl", SpellCapability.TARGET_ENTITY),
            e("fang_ward", SpellCapability.DIRECT),
            e("fire_arrow", SpellCapability.DIRECT),
            e("fire_breath", SpellCapability.DIRECT),
            e("fireball", SpellCapability.DIRECT),
            e("firebolt", SpellCapability.DIRECT),
            e("firecracker", SpellCapability.DIRECT),
            e("firefly_swarm", SpellCapability.TARGET_ENTITY),
            e("flaming_barrage", SpellCapability.MULTI_TARGET),
            e("flaming_strike", SpellCapability.DIRECT),
            e("fortify", SpellCapability.TARGET_AREA),
            e("frost_step", SpellCapability.SPECIAL_PREPARATION),
            e("frostbite", SpellCapability.DIRECT),
            e("frostwave", SpellCapability.DIRECT),
            e("gluttony", SpellCapability.DIRECT),
            e("gravity_fissure", SpellCapability.DIRECT),
            e("greater_heal", SpellCapability.DIRECT),
            e("guiding_bolt", SpellCapability.DIRECT),
            e("gust", SpellCapability.DIRECT),
            // Haste raycasts for an ally (Utils.shouldHealEntity) and falls back to the caster when
            // nothing healable is hit, so a mob can self-cast it as SUPPORT and never buffs an enemy.
            e("haste", SpellCapability.DIRECT),
            e("heal", SpellCapability.DIRECT),
            e("healing_circle", SpellCapability.TARGET_ENTITY),
            e("heartstop", SpellCapability.DIRECT),
            e("heat_surge", SpellCapability.DIRECT),
            e("ice_block", SpellCapability.TARGET_ENTITY),
            e("ice_spikes", SpellCapability.TARGET_ENTITY),
            e("ice_tomb", SpellCapability.DIRECT),
            e("icicle", SpellCapability.DIRECT),
            e("invisibility", SpellCapability.DIRECT),
            e("lightning_bolt", SpellCapability.DIRECT),
            e("lightning_lance", SpellCapability.DIRECT),
            e("lob_creeper", SpellCapability.DIRECT),
            e("magic_arrow", SpellCapability.DIRECT),
            e("magic_missile", SpellCapability.DIRECT),
            e("magma_bomb", SpellCapability.DIRECT),
            e("oakskin", SpellCapability.DIRECT),
            e("planar_sight", SpellCapability.DIRECT),
            e("pocket_dimension", SpellCapability.PLAYER_ONLY),
            e("poison_arrow", SpellCapability.DIRECT),
            e("poison_breath", SpellCapability.DIRECT),
            e("poison_splash", SpellCapability.TARGET_ENTITY),
            e("portal", SpellCapability.PLAYER_ONLY),
            e("raise_dead", SpellCapability.SUMMON),
            e("raise_hell", SpellCapability.DIRECT),
            e("ray_of_frost", SpellCapability.DIRECT),
            e("ray_of_siphoning", SpellCapability.SPECIAL_PREPARATION),
            e("recall", SpellCapability.PLAYER_ONLY),
            e("root", SpellCapability.TARGET_ENTITY),
            // Sacrifice only accepts one of the caster's own IMagicSummon entities as its target.
            e("sacrifice", SpellCapability.SPECIAL_PREPARATION),
            e("scapegoat", SpellCapability.DIRECT),
            e("scorch", SpellCapability.TARGET_AREA),
            e("sculk_tentacles", SpellCapability.TARGET_ENTITY),
            e("shadow_slash", SpellCapability.DIRECT),
            e("shield", SpellCapability.DIRECT),
            e("shockwave", SpellCapability.DIRECT),
            e("slow", SpellCapability.TARGET_ENTITY),
            e("snowball", SpellCapability.DIRECT),
            e("sonic_boom", SpellCapability.DIRECT),
            e("spectral_hammer", SpellCapability.UTILITY_NON_COMBAT),
            e("spider_aspect", SpellCapability.DIRECT),
            e("starfall", SpellCapability.DIRECT),
            e("stomp", SpellCapability.GROUND_AOE_FORWARD),
            e("summon_ender_chest", SpellCapability.PLAYER_ONLY),
            e("summon_horse", SpellCapability.SUMMON),
            e("summon_polar_bear", SpellCapability.SUMMON),
            e("summon_swords", SpellCapability.SUMMON),
            e("summon_vex", SpellCapability.SUMMON),
            e("sunbeam", SpellCapability.TARGET_ENTITY),
            e("telekinesis", SpellCapability.TARGET_ENTITY),
            e("teleport", SpellCapability.SPECIAL_PREPARATION),
            e("throw", SpellCapability.DIRECT),
            e("thunderstorm", SpellCapability.DIRECT),
            e("touch_dig", SpellCapability.PLAYER_ONLY),
            e("volt_strike", SpellCapability.DIRECT),
            e("wall_of_fire", SpellCapability.DIRECT),
            e("wisp", SpellCapability.TARGET_ENTITY),
            e("wither_skull", SpellCapability.DIRECT),
            // Wololo only accepts a Sheep as its target (it recolours wool).
            e("wololo", SpellCapability.UTILITY_NON_COMBAT),
                e("none", SpellCapability.UTILITY_NON_COMBAT));
        for (Map.Entry<String, SpellCapability> entry : entries) {
            m.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(m);
    }

    private static Map.Entry<String, SpellCapability> e(String path, SpellCapability capability) {
        return Map.entry(path, capability);
    }
}
