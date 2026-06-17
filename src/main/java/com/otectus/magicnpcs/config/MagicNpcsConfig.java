package com.otectus.magicnpcs.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.Map;

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
    public static final ForgeConfigSpec.BooleanValue PEACEFUL_DISABLES_CASTING;
    public static final ForgeConfigSpec.BooleanValue DIFFICULTY_SCALING;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPELL_BLACKLIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPELL_WHITELIST;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_LINE_OF_SIGHT;
    public static final ForgeConfigSpec.BooleanValue PROTECT_BYSTANDERS;
    public static final ForgeConfigSpec.BooleanValue PROTECT_OWNERS;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_SPELL_FOCUS;
    public static final ForgeConfigSpec.DoubleValue SPAWN_WITH_GEAR_CHANCE;
    public static final ForgeConfigSpec.BooleanValue RECRUITS_INTEGRATION_ENABLED;
    public static final ForgeConfigSpec.DoubleValue RECRUITS_MANA_PER_LEVEL;
    public static final ForgeConfigSpec.BooleanValue RECRUITS_USE_IRONS_AI;
    public static final ForgeConfigSpec.DoubleValue RECRUITS_IRONS_AI_SPEED;
    public static final ForgeConfigSpec.IntValue RECRUITS_IRONS_AI_INTERVAL;

    // Per-mod compat toggles for NPC mods we cannot compile against. They gate
    // whether datapack loadouts targeting that mod's entity-type namespace apply.
    // Conservative: default OFF, so a modpack author opts in deliberately.
    public static final ForgeConfigSpec.BooleanValue COMPAT_GUARDVILLAGERS;
    public static final ForgeConfigSpec.BooleanValue COMPAT_MCA;
    public static final ForgeConfigSpec.BooleanValue COMPAT_MINECOLONIES;
    public static final ForgeConfigSpec.BooleanValue COMPAT_EASYNPC;
    public static final ForgeConfigSpec.BooleanValue COMPAT_HUMANCOMPANIONS;
    public static final ForgeConfigSpec.BooleanValue COMPAT_MOREVILLAGERS;
    public static final ForgeConfigSpec.BooleanValue COMPAT_VILLAGERSPLUS;

    /** Maps an entity-type namespace (the loadout owner mod) to its compat toggle. */
    private static final Map<String, ForgeConfigSpec.BooleanValue> NAMESPACE_TOGGLES;

    // --- Magic schools: per-individual school assignment for recruits & villagers ---
    public static final ForgeConfigSpec.BooleanValue SCHOOLS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SCHOOLS_ALLOWED;
    public static final ForgeConfigSpec.ConfigValue<String> SCHOOLS_MAX_RARITY;
    public static final ForgeConfigSpec.IntValue SCHOOLS_MAX_SPELL_LEVEL;
    public static final ForgeConfigSpec.IntValue SCHOOLS_SPELLS_PER_SCHOOL;
    public static final ForgeConfigSpec.BooleanValue SCHOOLS_INCLUDE_SUPPORT;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SCHOOLS_SUPPORT_IDS;
    public static final ForgeConfigSpec.ConfigValue<String> SCHOOLS_WEIGHTING_MODE;
    public static final ForgeConfigSpec.DoubleValue SCHOOLS_ATTACK_MAX_RANGE;
    public static final ForgeConfigSpec.DoubleValue SCHOOLS_BASE_MAX_MANA;
    public static final ForgeConfigSpec.DoubleValue SCHOOLS_BASE_MANA_REGEN;
    public static final ForgeConfigSpec.BooleanValue SCHOOLS_SCHOOL_AWARE_FOCUS;

    public static final ForgeConfigSpec.BooleanValue SCHOOLS_RECRUITS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue SCHOOLS_RECRUITS_CASTER_CHANCE;
    public static final ForgeConfigSpec.ConfigValue<String> SCHOOLS_RECRUITS_MODE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SCHOOLS_RECRUITS_TYPE_SCHOOLS;
    public static final ForgeConfigSpec.IntValue SCHOOLS_RECRUITS_MIN_RANK;

    public static final ForgeConfigSpec.BooleanValue SCHOOLS_VILLAGERS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue SCHOOLS_VILLAGERS_CASTER_CHANCE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SCHOOLS_VILLAGERS_PROFESSION_SCHOOLS;
    public static final ForgeConfigSpec.BooleanValue SCHOOLS_VILLAGERS_UNMAPPED_RANDOM;

    public static final ForgeConfigSpec.BooleanValue SCHOOLS_COMMAND_ENABLED;
    public static final ForgeConfigSpec.IntValue SCHOOLS_COMMAND_PERMISSION;
    public static final ForgeConfigSpec.BooleanValue SCHOOLS_ITEM_ENABLED;

    /** Valid Iron's school ids (namespace confirmed from the dep jar: irons_spellbooks). */
    private static final List<String> DEFAULT_SCHOOLS = List.of(
            "irons_spellbooks:fire", "irons_spellbooks:ice", "irons_spellbooks:lightning",
            "irons_spellbooks:holy", "irons_spellbooks:ender", "irons_spellbooks:blood",
            "irons_spellbooks:evocation", "irons_spellbooks:nature", "irons_spellbooks:eldritch");

    private static final String TKEY = "magicnpcs.configuration.";

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("general");
        ENABLE_SPELLCASTING = b
                .comment("Master switch for all NPC spellcasting. If false, no casting goals are injected.")
                .translation(TKEY + "enableSpellcasting")
                .define("enableSpellcasting", true);
        DEBUG_LOGGING = b
                .comment("Log each NPC cast (spell + mana before/after) at INFO level.")
                .translation(TKEY + "debugLogging")
                .define("debugLogging", false);
        b.pop();

        b.push("balance");
        MANA_MULTIPLIER = b
                .comment("Multiplier applied to each loadout's max-mana pool.")
                .translation(TKEY + "manaMultiplier")
                .defineInRange("manaMultiplier", 1.0D, 0.0D, 1000.0D);
        COOLDOWN_MULTIPLIER = b
                .comment("Multiplier applied to NPC spell cooldowns (>1 = NPCs cast less often).")
                .translation(TKEY + "cooldownMultiplier")
                .defineInRange("cooldownMultiplier", 1.0D, 0.0D, 100.0D);
        REGEN_MULTIPLIER = b
                .comment("Multiplier applied to NPC mana regen per regen tick.")
                .translation(TKEY + "regenMultiplier")
                .defineInRange("regenMultiplier", 1.0D, 0.0D, 100.0D);
        DECISION_INTERVAL_TICKS = b
                .comment("Minimum ticks between an NPC's cast attempts.")
                .translation(TKEY + "decisionIntervalTicks")
                .defineInRange("decisionIntervalTicks", 10, 1, 200);
        SUPPORT_HEALTH_THRESHOLD = b
                .comment("An NPC self-casts SUPPORT spells when its health fraction drops below this (0..1).")
                .translation(TKEY + "supportHealthThreshold")
                .defineInRange("supportHealthThreshold", 0.5D, 0.0D, 1.0D);
        FRIENDLY_FIRE_CHECK = b
                .comment("Skip an attack cast when an ally (per the mob's adapter) is in the line of fire / blast radius.")
                .translation(TKEY + "friendlyFireCheck")
                .define("friendlyFireCheck", true);
        PEACEFUL_DISABLES_CASTING = b
                .comment("Suppress NPC spellcasting entirely while the world difficulty is Peaceful.")
                .translation(TKEY + "peacefulDisablesCasting")
                .define("peacefulDisablesCasting", true);
        DIFFICULTY_SCALING = b
                .comment("Scale NPC mana pools modestly with world difficulty (Easy 0.85x, Normal 1.0x, Hard 1.2x).")
                .translation(TKEY + "difficultyScaling")
                .define("difficultyScaling", true);
        b.pop();

        b.push("targeting");
        REQUIRE_LINE_OF_SIGHT = b
                .comment("Require an unobstructed line of sight to the target before an NPC casts an attack spell.")
                .translation(TKEY + "requireLineOfSight")
                .define("requireLineOfSight", true);
        PROTECT_BYSTANDERS = b
                .comment("Treat nearby non-combatants (villagers, iron golems, players, tamed pets) as protected",
                        "so an NPC will not catch them in an attack spell's line of fire / blast radius.")
                .translation(TKEY + "protectBystanders")
                .define("protectBystanders", true);
        PROTECT_OWNERS = b
                .comment("Enable the generic owner/team friendly-fire adapter (vanilla OwnableEntity + scoreboard teams).",
                        "Protects companion/pet/follower NPCs and their owner without a mod-specific adapter.")
                .translation(TKEY + "protectOwners")
                .define("protectOwners", true);
        b.pop();

        b.push("equipment");
        REQUIRE_SPELL_FOCUS = b
                .comment("Require a spellcasting NPC to hold an item in the 'magicnpcs:spell_focuses' item tag to cast.",
                        "Default off; populate that tag (e.g. with Iron's staves/spellbooks) to use it.")
                .translation(TKEY + "requireSpellFocus")
                .define("requireSpellFocus", false);
        SPAWN_WITH_GEAR_CHANCE = b
                .comment("Chance [0..1] that a spellcasting NPC spawns holding a random item from 'magicnpcs:spell_focuses'.",
                        "Default 0 (off). Has no effect if that tag is empty.")
                .translation(TKEY + "spawnWithGearChance")
                .defineInRange("spawnWithGearChance", 0.0D, 0.0D, 1.0D);
        b.pop();

        b.push("spells");
        SPELL_BLACKLIST = b
                .comment("Spell ids NPCs may never cast, e.g. \"irons_spellbooks:fireball\".")
                .translation(TKEY + "spellBlacklist")
                .defineListAllowEmpty("spellBlacklist", () -> List.<String>of(), MagicNpcsConfig::isResourceId);
        SPELL_WHITELIST = b
                .comment("If non-empty, NPCs may ONLY cast spell ids in this list.")
                .translation(TKEY + "spellWhitelist")
                .defineListAllowEmpty("spellWhitelist", () -> List.<String>of(), MagicNpcsConfig::isResourceId);
        b.pop();

        b.push("recruits");
        RECRUITS_INTEGRATION_ENABLED = b
                .comment("Enable the Villager Recruits adapter (rank-scaled mana + diplomacy-aware targeting).")
                .translation(TKEY + "recruits.enabled")
                .define("enabled", true);
        RECRUITS_MANA_PER_LEVEL = b
                .comment("Extra max-mana fraction per recruit rank/level (e.g. 0.10 = +10% per level).")
                .translation(TKEY + "recruits.manaPerLevel")
                .defineInRange("manaPerLevel", 0.10D, 0.0D, 100.0D);
        RECRUITS_USE_IRONS_AI = b
                .comment("Opt-in: recruits use Iron's own combat AI (WizardAttackGoal) instead of the built-in goal.",
                        "Requires the Recruits mixin (both Iron's and Recruits present); falls back to the built-in goal otherwise.")
                .translation(TKEY + "recruits.useIronsAI")
                .define("useIronsAI", false);
        RECRUITS_IRONS_AI_SPEED = b
                .comment("Movement speed used by Iron's-AI recruits while engaging.")
                .translation(TKEY + "recruits.ironsAiSpeed")
                .defineInRange("ironsAiSpeed", 0.5D, 0.0D, 5.0D);
        RECRUITS_IRONS_AI_INTERVAL = b
                .comment("Attack interval (ticks) for Iron's-AI recruits.")
                .translation(TKEY + "recruits.ironsAiIntervalTicks")
                .defineInRange("ironsAiIntervalTicks", 25, 1, 200);
        b.pop();

        b.push("compat");
        b.comment("Per-mod toggles gating whether datapack loadouts targeting that mod's entity types apply.",
                "Conservative: default OFF. Enabling a toggle with the mod absent simply has no effect.");
        COMPAT_GUARDVILLAGERS = b
                .comment("Allow loadouts targeting Guard Villagers entity types (namespace 'guardvillagers').")
                .translation(TKEY + "compat.guardvillagers")
                .define("guardvillagers", false);
        COMPAT_MCA = b
                .comment("Allow loadouts targeting MCA Reborn entity types (namespace 'mca'). Use sparingly — only for guards/special villagers.")
                .translation(TKEY + "compat.mca")
                .define("mca", false);
        COMPAT_MINECOLONIES = b
                .comment("Allow loadouts targeting MineColonies entity types (namespace 'minecolonies').")
                .translation(TKEY + "compat.minecolonies")
                .define("minecolonies", false);
        COMPAT_EASYNPC = b
                .comment("Allow loadouts targeting Easy NPC entity types (namespace 'easy_npc').")
                .translation(TKEY + "compat.easynpc")
                .define("easynpc", false);
        COMPAT_HUMANCOMPANIONS = b
                .comment("Allow loadouts targeting Human Companions entity types (namespace 'humancompanions').")
                .translation(TKEY + "compat.humancompanions")
                .define("humancompanions", false);
        COMPAT_MOREVILLAGERS = b
                .comment("Allow loadouts targeting More Villagers entity types (namespace 'morevillagers').")
                .translation(TKEY + "compat.morevillagers")
                .define("morevillagers", false);
        COMPAT_VILLAGERSPLUS = b
                .comment("Allow loadouts targeting VillagersPlus entity types (namespace 'villagersplus').")
                .translation(TKEY + "compat.villagersplus")
                .define("villagersplus", false);
        b.pop();

        b.push("schools");
        b.comment("Per-individual magic-school assignment. Each eligible recruit/villager is assigned one",
                "Iron's school; its spell pool is built dynamically from that school's enabled spells.");
        SCHOOLS_ENABLED = b
                .comment("Master switch for the school-assignment system (recruits + villagers).")
                .translation(TKEY + "schools.enableSchools")
                .define("enableSchools", true);
        SCHOOLS_ALLOWED = b
                .comment("School ids NPCs may be assigned. Default: all nine Iron's schools.")
                .translation(TKEY + "schools.allowedSchools")
                .defineListAllowEmpty("allowedSchools", () -> DEFAULT_SCHOOLS, MagicNpcsConfig::isResourceId);
        SCHOOLS_MAX_RARITY = b
                .comment("Highest spell rarity an NPC may be given: COMMON, UNCOMMON, RARE, EPIC, LEGENDARY.")
                .translation(TKEY + "schools.maxRarity")
                .define("maxRarity", "RARE", MagicNpcsConfig::isRarity);
        SCHOOLS_MAX_SPELL_LEVEL = b
                .comment("Cap on the spell level NPCs cast school spells at.")
                .translation(TKEY + "schools.maxSpellLevel")
                .defineInRange("maxSpellLevel", 3, 1, 10);
        SCHOOLS_SPELLS_PER_SCHOOL = b
                .comment("How many spells from the school each caster is given.")
                .translation(TKEY + "schools.spellsPerSchool")
                .defineInRange("spellsPerSchool", 4, 1, 32);
        SCHOOLS_INCLUDE_SUPPORT = b
                .comment("Include support spells (heal/buff) in generated pools.")
                .translation(TKEY + "schools.includeSupportSpells")
                .define("includeSupportSpells", true);
        SCHOOLS_SUPPORT_IDS = b
                .comment("Spell ids classified as SUPPORT (self-cast when hurt). Others in a school are ATTACK.",
                        "Anything matching heal/cure/blessing/regen/haste/shield/ward is also treated as support.")
                .translation(TKEY + "schools.supportSpellIds")
                .defineListAllowEmpty("supportSpellIds", () -> List.of(
                        "irons_spellbooks:heal", "irons_spellbooks:greater_heal", "irons_spellbooks:cure_wounds",
                        "irons_spellbooks:blessing_of_life", "irons_spellbooks:haste", "irons_spellbooks:angel_wing",
                        "irons_spellbooks:fortify", "irons_spellbooks:heartstop"),
                        MagicNpcsConfig::isResourceId);
        SCHOOLS_WEIGHTING_MODE = b
                .comment("Spell pick weighting: UNIFORM or INVERSE_RARITY (commoner spells cast more often).")
                .translation(TKEY + "schools.weightingMode")
                .define("weightingMode", "INVERSE_RARITY", MagicNpcsConfig::isWeightingMode);
        SCHOOLS_ATTACK_MAX_RANGE = b
                .comment("Max target distance (blocks) for school attack spells.")
                .translation(TKEY + "schools.attackMaxRange")
                .defineInRange("attackMaxRange", 20.0D, 1.0D, 128.0D);
        SCHOOLS_BASE_MAX_MANA = b
                .comment("Base max-mana for a school caster (before adapter rank scaling and manaMultiplier).")
                .translation(TKEY + "schools.baseMaxMana")
                .defineInRange("baseMaxMana", 100.0D, 1.0D, 100000.0D);
        SCHOOLS_BASE_MANA_REGEN = b
                .comment("Base mana regen for a school caster.")
                .translation(TKEY + "schools.baseManaRegen")
                .defineInRange("baseManaRegen", 10.0D, 0.0D, 10000.0D);
        SCHOOLS_SCHOOL_AWARE_FOCUS = b
                .comment("When requireSpellFocus is on, accept a held item that is a focus for the caster's school",
                        "(Iron's per-school focus tag) in addition to the magicnpcs:spell_focuses tag.")
                .translation(TKEY + "schools.schoolAwareFocus")
                .define("schoolAwareFocus", false);

        b.push("recruits");
        SCHOOLS_RECRUITS_ENABLED = b
                .comment("Assign schools to Villager Recruits.")
                .translation(TKEY + "schools.recruits.enabled")
                .define("enabled", true);
        SCHOOLS_RECRUITS_CASTER_CHANCE = b
                .comment("Chance [0..1] a spawned recruit becomes a school caster (rolled once, persisted).")
                .translation(TKEY + "schools.recruits.casterChance")
                .defineInRange("casterChance", 0.35D, 0.0D, 1.0D);
        SCHOOLS_RECRUITS_MODE = b
                .comment("School assignment: RANDOM (from allowedSchools), BY_TYPE (typeSchools map), BY_RANK.")
                .translation(TKEY + "schools.recruits.assignmentMode")
                .define("assignmentMode", "RANDOM", MagicNpcsConfig::isAssignmentMode);
        SCHOOLS_RECRUITS_TYPE_SCHOOLS = b
                .comment("BY_TYPE map: 'entityType=school[,school]', e.g. 'recruits:captain=irons_spellbooks:fire,irons_spellbooks:holy'.")
                .translation(TKEY + "schools.recruits.typeSchools")
                .defineListAllowEmpty("typeSchools", () -> List.<String>of(), MagicNpcsConfig::isPairMapping);
        SCHOOLS_RECRUITS_MIN_RANK = b
                .comment("Minimum recruit XP rank to be eligible for a school.")
                .translation(TKEY + "schools.recruits.minRankToCast")
                .defineInRange("minRankToCast", 0, 0, 100);
        b.pop();

        b.push("villagers");
        SCHOOLS_VILLAGERS_ENABLED = b
                .comment("Assign schools to villagers (vanilla + profession mods extending Villager).",
                        "Note: a magic villager only actually casts when it has a target (raids, guard mods, MCA guards).")
                .translation(TKEY + "schools.villagers.enabled")
                .define("enabled", true);
        SCHOOLS_VILLAGERS_CASTER_CHANCE = b
                .comment("Chance [0..1] an eligible villager becomes a school caster (rolled once, persisted).")
                .translation(TKEY + "schools.villagers.casterChance")
                .defineInRange("casterChance", 0.5D, 0.0D, 1.0D);
        SCHOOLS_VILLAGERS_PROFESSION_SCHOOLS = b
                .comment("Profession→school map: 'profession=school[,school]'. Only listed professions cast by default.")
                .translation(TKEY + "schools.villagers.professionSchools")
                .defineListAllowEmpty("professionSchools", () -> List.of(
                        "minecraft:cleric=irons_spellbooks:holy",
                        "minecraft:librarian=irons_spellbooks:evocation",
                        "minecraft:weaponsmith=irons_spellbooks:fire",
                        "minecraft:toolsmith=irons_spellbooks:lightning",
                        "minecraft:fletcher=irons_spellbooks:ender",
                        "minecraft:farmer=irons_spellbooks:nature"),
                        MagicNpcsConfig::isPairMapping);
        SCHOOLS_VILLAGERS_UNMAPPED_RANDOM = b
                .comment("If true, villagers whose profession is not in the map get a RANDOM allowed school.")
                .translation(TKEY + "schools.villagers.unmappedGetRandom")
                .define("unmappedGetRandom", false);
        b.pop();

        b.push("control");
        SCHOOLS_COMMAND_ENABLED = b
                .comment("Register the /magicnpcs school command.")
                .translation(TKEY + "schools.command.enabled")
                .define("commandEnabled", true);
        SCHOOLS_COMMAND_PERMISSION = b
                .comment("Permission level required for /magicnpcs school (0-4).")
                .translation(TKEY + "schools.command.permissionLevel")
                .defineInRange("commandPermissionLevel", 2, 0, 4);
        SCHOOLS_ITEM_ENABLED = b
                .comment("Enable the School Tome item's right-click school assignment.")
                .translation(TKEY + "schools.item.enabled")
                .define("itemEnabled", true);
        b.pop();
        b.pop();

        SPEC = b.build();

        NAMESPACE_TOGGLES = Map.of(
                "guardvillagers", COMPAT_GUARDVILLAGERS,
                "mca", COMPAT_MCA,
                "minecolonies", COMPAT_MINECOLONIES,
                "easy_npc", COMPAT_EASYNPC,
                "humancompanions", COMPAT_HUMANCOMPANIONS,
                "morevillagers", COMPAT_MOREVILLAGERS,
                "villagersplus", COMPAT_VILLAGERSPLUS);
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

    /**
     * @return whether a loadout targeting {@code entityType} should be applied,
     *         given the per-mod compat toggles. Always-on namespaces (the mod's own
     *         data, vanilla, and the first-class Recruits integration) are never
     *         gated; a namespace owned by an optional NPC mod requires both its
     *         toggle enabled and (for clarity in logs) the mod loaded.
     */
    public static boolean isLoadoutEnabledFor(ResourceLocation entityType) {
        String ns = entityType.getNamespace();
        ForgeConfigSpec.BooleanValue toggle = NAMESPACE_TOGGLES.get(ns);
        if (toggle == null) {
            return true; // magicnpcs / minecraft / recruits / any namespace without a managed toggle
        }
        return toggle.get();
    }

    /** @return true if the optional mod that owns this loadout's namespace is actually installed (for logging). */
    public static boolean ownerModLoaded(ResourceLocation entityType) {
        return ModList.get() != null && ModList.get().isLoaded(entityType.getNamespace());
    }

    private static boolean isResourceId(Object o) {
        return o instanceof String s && ResourceLocation.tryParse(s) != null;
    }

    private static boolean isRarity(Object o) {
        if (!(o instanceof String s)) return false;
        return switch (s.toUpperCase(java.util.Locale.ROOT)) {
            case "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY" -> true;
            default -> false;
        };
    }

    private static boolean isWeightingMode(Object o) {
        return o instanceof String s
                && (s.equalsIgnoreCase("UNIFORM") || s.equalsIgnoreCase("INVERSE_RARITY"));
    }

    private static boolean isAssignmentMode(Object o) {
        if (!(o instanceof String s)) return false;
        return switch (s.toUpperCase(java.util.Locale.ROOT)) {
            case "RANDOM", "BY_TYPE", "BY_RANK" -> true;
            default -> false;
        };
    }

    /** Validates a "key=value[,value]" mapping where key and each value are resource ids. */
    private static boolean isPairMapping(Object o) {
        if (!(o instanceof String s)) return false;
        int eq = s.indexOf('=');
        if (eq <= 0 || eq == s.length() - 1) return false;
        if (ResourceLocation.tryParse(s.substring(0, eq).trim()) == null) return false;
        for (String v : s.substring(eq + 1).split(",")) {
            if (ResourceLocation.tryParse(v.trim()) == null) return false;
        }
        return true;
    }

    // --- Parsed accessors (called from the Iron's-side school code) ---

    public static List<ResourceLocation> allowedSchoolIds() {
        return toIds(SCHOOLS_ALLOWED.get());
    }

    public static boolean isSupportSpellId(String id) {
        return SCHOOLS_SUPPORT_IDS.get().contains(id);
    }

    /** Parse a pair-mapping list into key→list-of-ids (e.g. profession→schools, type→schools). */
    public static Map<ResourceLocation, List<ResourceLocation>> parsePairMap(List<? extends String> entries) {
        Map<ResourceLocation, List<ResourceLocation>> map = new java.util.HashMap<>();
        for (String s : entries) {
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            ResourceLocation key = ResourceLocation.tryParse(s.substring(0, eq).trim());
            if (key == null) continue;
            List<ResourceLocation> values = new java.util.ArrayList<>();
            for (String v : s.substring(eq + 1).split(",")) {
                ResourceLocation id = ResourceLocation.tryParse(v.trim());
                if (id != null) values.add(id);
            }
            if (!values.isEmpty()) map.put(key, values);
        }
        return map;
    }

    private static List<ResourceLocation> toIds(List<? extends String> raw) {
        List<ResourceLocation> ids = new java.util.ArrayList<>();
        for (String s : raw) {
            ResourceLocation id = ResourceLocation.tryParse(s);
            if (id != null) ids.add(id);
        }
        return ids;
    }
}
