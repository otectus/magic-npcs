package com.otectus.magicnpcs.integration.irons;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The reviewed mob-cast capability manifest for the Iron's Spells 'n Spellbooks spells this build was
 * checked against.
 *
 * <p>0.6.1 classified spells with a four-entry keyword map and defaulted <em>everything else</em> to
 * "aimed projectile, supported" — so {@code /magicnpcs validate} and the runtime both claimed support
 * for the entire registry, including player-only spells and spells whose whole effect lives in a cast
 * tick the bridge never ran (audit SPI-002). The manifest replaces that guess with an explicit,
 * per-spell verdict, and anything absent from it is {@link Capability#UNVERIFIED} rather than assumed
 * good.
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

    /** What a non-player mob must be able to supply for a spell to behave as designed. */
    public enum Capability {
        /** Aimed projectile or simple self-effect: the caster's look angle is all it reads. */
        DIRECT(true),
        /** Reads a single {@code TargetEntityCastData}, which the session supplies from the target. */
        TARGET_ENTITY(true),
        /** Builds its own target-area data in pre-cast; needs the pre-cast hook to actually run. */
        TARGET_AREA(true),
        /** Forward ground AoE from the caster's facing: wants a short range and correct facing. */
        GROUND_AOE_FORWARD(true),
        /** Summons entities; the session installs the spell's own empty cast data to track them. */
        SUMMON(true),
        /** Reads {@code MultiTargetEntityCastData}, which nothing builds for a mob. */
        MULTI_TARGET(false),
        /** Iron's prepares this through {@code IMagicEntity} hooks a foreign mob cannot implement. */
        SPECIAL_PREPARATION(false),
        /** Refuses a non-player caster, or only does anything for a {@code ServerPlayer}. */
        PLAYER_ONLY(false),
        /** Block/world manipulation with no combat behaviour for an NPC. */
        UTILITY_NON_COMBAT(false),
        /** Not in the manifest: an add-on spell, or one from a newer Iron's than this build verified. */
        UNVERIFIED(false);

        private final boolean supported;

        Capability(boolean supported) {
            this.supported = supported;
        }

        /** @return true if a generic mob can cast this and get the spell's designed behaviour. */
        public boolean supported() {
            return supported;
        }
    }

    /** Path -> capability, for the {@code irons_spellbooks} namespace. */
    private static final Map<String, Capability> BY_PATH = build();

    private SpellManifest() {}

    /** @return the reviewed capability for {@code spellId}, or {@link Capability#UNVERIFIED}. */
    public static Capability capabilityOf(net.minecraft.resources.ResourceLocation spellId) {
        if (spellId == null || !"irons_spellbooks".equals(spellId.getNamespace())) {
            return Capability.UNVERIFIED;
        }
        return BY_PATH.getOrDefault(spellId.getPath(), Capability.UNVERIFIED);
    }

    /** @return how many spells the manifest covers, for the {@code /magicnpcs config} report. */
    public static int size() {
        return BY_PATH.size();
    }

    private static Map<String, Capability> build() {
        Map<String, Capability> m = new LinkedHashMap<>(114);
        java.util.List<Map.Entry<String, Capability>> entries = java.util.List.of(
            e("abyssal_shroud", Capability.DIRECT),
            e("acid_orb", Capability.DIRECT),
            e("acupuncture", Capability.TARGET_ENTITY),
            e("angel_wing", Capability.DIRECT),
            e("arcane_shackle", Capability.DIRECT),
            e("arrow_volley", Capability.TARGET_ENTITY),
            e("ascension", Capability.DIRECT),
            e("ball_lightning", Capability.DIRECT),
            e("black_hole", Capability.DIRECT),
            e("blaze_storm", Capability.DIRECT),
            e("blessing_of_life", Capability.TARGET_ENTITY),
            e("blight", Capability.TARGET_ENTITY),
            e("blizzard", Capability.TARGET_ENTITY),
            e("blood_needles", Capability.DIRECT),
            e("blood_slash", Capability.DIRECT),
            e("blood_step", Capability.SPECIAL_PREPARATION),
            e("burning_dash", Capability.SPECIAL_PREPARATION),
            e("chain_creeper", Capability.TARGET_ENTITY),
            e("chain_lightning", Capability.TARGET_ENTITY),
            e("charge", Capability.DIRECT),
            e("cleanse", Capability.TARGET_AREA),
            e("cloud_of_regeneration", Capability.DIRECT),
            e("cone_of_cold", Capability.DIRECT),
            e("counterspell", Capability.DIRECT),
            e("devour", Capability.TARGET_ENTITY),
            e("divine_smite", Capability.DIRECT),
            e("dragon_breath", Capability.DIRECT),
            e("earthquake", Capability.TARGET_ENTITY),
            e("echoing_strikes", Capability.DIRECT),
            e("eldritch_blast", Capability.DIRECT),
            e("electrocute", Capability.DIRECT),
            e("evasion", Capability.DIRECT),
            e("fang_strike", Capability.DIRECT),
            e("fang_swirl", Capability.TARGET_ENTITY),
            e("fang_ward", Capability.DIRECT),
            e("fire_arrow", Capability.DIRECT),
            e("fire_breath", Capability.DIRECT),
            e("fireball", Capability.DIRECT),
            e("firebolt", Capability.DIRECT),
            e("firecracker", Capability.DIRECT),
            e("firefly_swarm", Capability.TARGET_ENTITY),
            e("flaming_barrage", Capability.MULTI_TARGET),
            e("flaming_strike", Capability.DIRECT),
            e("fortify", Capability.TARGET_AREA),
            e("frost_step", Capability.SPECIAL_PREPARATION),
            e("frostbite", Capability.DIRECT),
            e("frostwave", Capability.DIRECT),
            e("gluttony", Capability.DIRECT),
            e("gravity_fissure", Capability.DIRECT),
            e("greater_heal", Capability.DIRECT),
            e("guiding_bolt", Capability.DIRECT),
            e("gust", Capability.DIRECT),
            e("haste", Capability.TARGET_ENTITY),
            e("heal", Capability.DIRECT),
            e("healing_circle", Capability.TARGET_ENTITY),
            e("heartstop", Capability.DIRECT),
            e("heat_surge", Capability.DIRECT),
            e("ice_block", Capability.TARGET_ENTITY),
            e("ice_spikes", Capability.TARGET_ENTITY),
            e("ice_tomb", Capability.DIRECT),
            e("icicle", Capability.DIRECT),
            e("invisibility", Capability.DIRECT),
            e("lightning_bolt", Capability.DIRECT),
            e("lightning_lance", Capability.DIRECT),
            e("lob_creeper", Capability.DIRECT),
            e("magic_arrow", Capability.DIRECT),
            e("magic_missile", Capability.DIRECT),
            e("magma_bomb", Capability.DIRECT),
            e("oakskin", Capability.DIRECT),
            e("planar_sight", Capability.DIRECT),
            e("pocket_dimension", Capability.PLAYER_ONLY),
            e("poison_arrow", Capability.DIRECT),
            e("poison_breath", Capability.DIRECT),
            e("poison_splash", Capability.TARGET_ENTITY),
            e("portal", Capability.PLAYER_ONLY),
            e("raise_dead", Capability.SUMMON),
            e("raise_hell", Capability.DIRECT),
            e("ray_of_frost", Capability.DIRECT),
            e("ray_of_siphoning", Capability.SPECIAL_PREPARATION),
            e("recall", Capability.PLAYER_ONLY),
            e("root", Capability.TARGET_ENTITY),
            e("sacrifice", Capability.TARGET_ENTITY),
            e("scapegoat", Capability.DIRECT),
            e("scorch", Capability.TARGET_AREA),
            e("sculk_tentacles", Capability.TARGET_ENTITY),
            e("shadow_slash", Capability.DIRECT),
            e("shield", Capability.DIRECT),
            e("shockwave", Capability.DIRECT),
            e("slow", Capability.TARGET_ENTITY),
            e("snowball", Capability.DIRECT),
            e("sonic_boom", Capability.DIRECT),
            e("soulfire_ray", Capability.DIRECT),
            e("spectral_hammer", Capability.UTILITY_NON_COMBAT),
            e("spider_aspect", Capability.DIRECT),
            e("starfall", Capability.DIRECT),
            e("stomp", Capability.GROUND_AOE_FORWARD),
            e("summon_ender_chest", Capability.PLAYER_ONLY),
            e("summon_horse", Capability.SUMMON),
            e("summon_polar_bear", Capability.SUMMON),
            e("summon_swords", Capability.SUMMON),
            e("summon_vex", Capability.SUMMON),
            e("sunbeam", Capability.TARGET_ENTITY),
            e("telekinesis", Capability.TARGET_ENTITY),
            e("teleport", Capability.SPECIAL_PREPARATION),
            e("throw", Capability.DIRECT),
            e("thunder_step", Capability.MULTI_TARGET),
            e("thunderstorm", Capability.DIRECT),
            e("touch_dig", Capability.PLAYER_ONLY),
            e("volt_strike", Capability.DIRECT),
            e("wall_of_fire", Capability.DIRECT),
            e("wisp", Capability.TARGET_ENTITY),
            e("wither_skull", Capability.DIRECT),
            e("wololo", Capability.TARGET_ENTITY),
                e("none", Capability.UTILITY_NON_COMBAT));
        for (Map.Entry<String, Capability> entry : entries) {
            m.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(m);
    }

    private static Map.Entry<String, Capability> e(String path, Capability capability) {
        return Map.entry(path, capability);
    }
}
