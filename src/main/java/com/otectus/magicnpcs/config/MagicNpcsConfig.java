package com.otectus.magicnpcs.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * Server-authoritative configuration (auto-synced to clients on login), mirroring
 * the ars-n-spells {@code AnsConfig} pattern. Holds every gameplay tunable for NPC
 * spellcasting. Iron's-free and Recruits-free, so it registers and loads even
 * without those mods present.
 */
public final class MagicNpcsConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLE_SPELLCASTING;
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ForgeConfigSpec.DoubleValue MANA_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue COOLDOWN_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue REGEN_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue DECISION_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue SUPPORT_HEALTH_THRESHOLD;
    public static final ForgeConfigSpec.BooleanValue FRIENDLY_FIRE_CHECK;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPELL_BLACKLIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPELL_WHITELIST;
    public static final ForgeConfigSpec.BooleanValue RECRUITS_INTEGRATION_ENABLED;
    public static final ForgeConfigSpec.DoubleValue RECRUITS_MANA_PER_LEVEL;
    public static final ForgeConfigSpec.BooleanValue RECRUITS_USE_IRONS_AI;
    public static final ForgeConfigSpec.DoubleValue RECRUITS_IRONS_AI_SPEED;
    public static final ForgeConfigSpec.IntValue RECRUITS_IRONS_AI_INTERVAL;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("general");
        ENABLE_SPELLCASTING = b
                .comment("Master switch for all NPC spellcasting. If false, no casting goals are injected.")
                .define("enableSpellcasting", true);
        DEBUG_LOGGING = b
                .comment("Log each NPC cast (spell + mana before/after) at INFO level.")
                .define("debugLogging", false);
        b.pop();

        b.push("balance");
        MANA_MULTIPLIER = b
                .comment("Multiplier applied to each loadout's max-mana pool.")
                .defineInRange("manaMultiplier", 1.0D, 0.0D, 1000.0D);
        COOLDOWN_MULTIPLIER = b
                .comment("Multiplier applied to NPC spell cooldowns (>1 = NPCs cast less often).")
                .defineInRange("cooldownMultiplier", 1.0D, 0.0D, 100.0D);
        REGEN_MULTIPLIER = b
                .comment("Multiplier applied to NPC mana regen per regen tick.")
                .defineInRange("regenMultiplier", 1.0D, 0.0D, 100.0D);
        DECISION_INTERVAL_TICKS = b
                .comment("Minimum ticks between an NPC's cast attempts.")
                .defineInRange("decisionIntervalTicks", 10, 1, 200);
        SUPPORT_HEALTH_THRESHOLD = b
                .comment("An NPC self-casts SUPPORT spells when its health fraction drops below this (0..1).")
                .defineInRange("supportHealthThreshold", 0.5D, 0.0D, 1.0D);
        FRIENDLY_FIRE_CHECK = b
                .comment("Skip an attack cast when an ally (per the mob's adapter) is in the line of fire / blast radius.")
                .define("friendlyFireCheck", true);
        b.pop();

        b.push("spells");
        SPELL_BLACKLIST = b
                .comment("Spell ids NPCs may never cast, e.g. \"irons_spellbooks:fireball\".")
                .defineListAllowEmpty("spellBlacklist", () -> List.<String>of(), MagicNpcsConfig::isResourceId);
        SPELL_WHITELIST = b
                .comment("If non-empty, NPCs may ONLY cast spell ids in this list.")
                .defineListAllowEmpty("spellWhitelist", () -> List.<String>of(), MagicNpcsConfig::isResourceId);
        b.pop();

        b.push("recruits");
        RECRUITS_INTEGRATION_ENABLED = b
                .comment("Enable the Villager Recruits adapter (rank-scaled mana + diplomacy-aware targeting).")
                .define("enabled", true);
        RECRUITS_MANA_PER_LEVEL = b
                .comment("Extra max-mana fraction per recruit rank/level (e.g. 0.10 = +10% per level).")
                .defineInRange("manaPerLevel", 0.10D, 0.0D, 100.0D);
        RECRUITS_USE_IRONS_AI = b
                .comment("Opt-in: recruits use Iron's own combat AI (WizardAttackGoal) instead of the built-in goal.",
                        "Requires the Recruits mixin (both Iron's and Recruits present); falls back to the built-in goal otherwise.")
                .define("useIronsAI", false);
        RECRUITS_IRONS_AI_SPEED = b
                .comment("Movement speed used by Iron's-AI recruits while engaging.")
                .defineInRange("ironsAiSpeed", 0.5D, 0.0D, 5.0D);
        RECRUITS_IRONS_AI_INTERVAL = b
                .comment("Attack interval (ticks) for Iron's-AI recruits.")
                .defineInRange("ironsAiIntervalTicks", 25, 1, 200);
        b.pop();

        SPEC = b.build();
    }

    private MagicNpcsConfig() {}

    /** @return whether a spell id passes the whitelist/blacklist filters. */
    public static boolean isAllowed(String spellId) {
        List<? extends String> whitelist = SPELL_WHITELIST.get();
        if (!whitelist.isEmpty() && !whitelist.contains(spellId)) {
            return false;
        }
        return !SPELL_BLACKLIST.get().contains(spellId);
    }

    private static boolean isResourceId(Object o) {
        return o instanceof String s && ResourceLocation.tryParse(s) != null;
    }
}
