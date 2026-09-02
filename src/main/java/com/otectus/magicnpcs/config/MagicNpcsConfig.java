package com.otectus.magicnpcs.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuration for NPC spellcasting, split across two specs (ADR 0004):
 *
 * <ul>
 *   <li>{@link #SPEC} — {@code magicnpcs-server.toml}, {@code ModConfig.Type.SERVER}: per-world and
 *       auto-synced to clients on login. Every gameplay tunable lives here.</li>
 *   <li>{@link #COMMON_SPEC} — {@code magicnpcs-common.toml}, {@code ModConfig.Type.COMMON}: one file in
 *       {@code config/} that applies to every world. Holds settings that describe the <em>installation</em>
 *       rather than this world's balance: the {@code [compat]} namespace toggles and {@code debugLogging}.</li>
 * </ul>
 *
 * <p>The moved keys are still present in the server spec for one release and are OR-ed into the common
 * value, so an existing world's enabled toggles are never silently reset (see {@link #warnOnLegacyKeys()}).
 *
 * <p>Iron's-free and Recruits-free, so both specs register and load even without those mods present.
 * Every accessor that may be called before a spec is loaded guards on {@code isLoaded()} — a
 * {@code ForgeConfigSpec} read before load throws.
 */
public final class MagicNpcsConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec COMMON_SPEC;

    // --- general (server) ---
    public static final ForgeConfigSpec.BooleanValue ENABLE_SPELLCASTING;
    public static final ForgeConfigSpec.IntValue CASTING_GOAL_PRIORITY;
    public static final ForgeConfigSpec.BooleanValue CASTING_GOAL_USES_LOOK_FLAG;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DISABLED_ENTITY_TYPES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SUPPRESSIBLE_ATTACK_GOALS;
    public static final ForgeConfigSpec.BooleanValue STRICT_LOADOUT_SCHEMA;
    public static final ForgeConfigSpec.IntValue RECONCILE_BATCH_SIZE;
    /** @deprecated moved to {@code magicnpcs-common.toml}; read for one release. Use {@link #debugLogging()}. */
    @Deprecated
    public static final ForgeConfigSpec.BooleanValue LEGACY_DEBUG_LOGGING;

    // --- general (common) ---
    public static final ForgeConfigSpec.BooleanValue COMMON_DEBUG_LOGGING;

    // --- balance ---
    public static final ForgeConfigSpec.DoubleValue MANA_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue COOLDOWN_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue REGEN_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue DECISION_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue CAST_CHANCE;
    public static final ForgeConfigSpec.IntValue MIN_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.DoubleValue SUPPORT_HEALTH_THRESHOLD;
    public static final ForgeConfigSpec.BooleanValue SUPPORT_OUT_OF_COMBAT;
    public static final ForgeConfigSpec.IntValue SUPPORT_OUT_OF_COMBAT_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue FRIENDLY_FIRE_CHECK;
    public static final ForgeConfigSpec.BooleanValue PEACEFUL_DISABLES_CASTING;
    public static final ForgeConfigSpec.BooleanValue DIFFICULTY_SCALING;
    public static final ForgeConfigSpec.BooleanValue CASTER_MOVEMENT;
    public static final ForgeConfigSpec.DoubleValue CASTER_MOVEMENT_SPEED;
    public static final ForgeConfigSpec.DoubleValue RANK_LEVEL_PER_RANK;
    public static final ForgeConfigSpec.IntValue RANK_LEVEL_MAX_BONUS;

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPELL_BLACKLIST;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SPELL_WHITELIST;
    public static final ForgeConfigSpec.BooleanValue ALLOW_UNVERIFIED_SPELLS;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_LINE_OF_SIGHT;
    public static final ForgeConfigSpec.IntValue CAST_WINDUP_TICKS;
    public static final ForgeConfigSpec.BooleanValue PROTECT_BYSTANDERS;
    public static final ForgeConfigSpec.BooleanValue PROTECT_TARGETED_PLAYERS;
    public static final ForgeConfigSpec.BooleanValue PROTECT_OWNERS;
    public static final ForgeConfigSpec.BooleanValue PROTECT_RAID_ALLIES;
    public static final ForgeConfigSpec.BooleanValue SITTING_PETS_MAY_CAST;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_SPELL_FOCUS;
    public static final ForgeConfigSpec.DoubleValue SPAWN_WITH_GEAR_CHANCE;
    public static final ForgeConfigSpec.BooleanValue REACTIVE_CASTING_ENABLED;
    public static final ForgeConfigSpec.DoubleValue MATCHED_CONDITION_WEIGHT_BONUS;
    public static final ForgeConfigSpec.BooleanValue FEEDBACK_TELEGRAPHS;
    public static final ForgeConfigSpec.BooleanValue FEEDBACK_SCHOOL_PARTICLES;
    public static final ForgeConfigSpec.BooleanValue FEEDBACK_TELEGRAPH_GLOW;
    public static final ForgeConfigSpec.DoubleValue FEEDBACK_TELEGRAPH_VOLUME;
    public static final ForgeConfigSpec.IntValue FEEDBACK_MIN_DANGER_TIER;
    public static final ForgeConfigSpec.BooleanValue RECRUITS_INTEGRATION_ENABLED;
    public static final ForgeConfigSpec.DoubleValue RECRUITS_MANA_PER_LEVEL;
    public static final ForgeConfigSpec.BooleanValue EASYNPC_INTEGRATION_ENABLED;
    public static final ForgeConfigSpec.DoubleValue EASYNPC_MANA_PER_LEVEL;
    public static final ForgeConfigSpec.BooleanValue EASYNPC_USE_OBJECTIVE;
    public static final ForgeConfigSpec.BooleanValue EASYNPC_RESPECT_FACTIONS;

    // Per-mod compat toggles for NPC mods we cannot compile against. They gate
    // whether datapack loadouts targeting that mod's entity-type namespace apply.
    // Conservative: default OFF, so a modpack author opts in deliberately.
    // Live in the COMMON spec (0.6.0) — "which mods are installed" is a pack-level
    // fact, not per-world balance. The SERVER copies are legacy fallbacks.
    private static final Map<String, ForgeConfigSpec.BooleanValue> NAMESPACE_TOGGLES;
    private static final Map<String, ForgeConfigSpec.BooleanValue> LEGACY_NAMESPACE_TOGGLES;

    /**
     * Per-loadout toggles for the loadouts Magic NPCs itself ships (0.6.0, restored in 0.6.2 — audit
     * REL-002). Keyed by the built-in loadout's file name. Turning one off is equivalent to shipping a
     * datapack that disables it, without the datapack.
     */
    private static final Map<String, ForgeConfigSpec.BooleanValue> BUILTIN_LOADOUTS;

    /** The loadouts shipped in this jar's own data pack: {file name, entity type, display name}. */
    private static final String[][] BUILTIN_LOADOUT_FILES = {
            {"recruit", "recruits:recruit", "Recruits - recruit"},
            {"bowman", "recruits:bowman", "Recruits - bowman"},
            {"crossbowman", "recruits:crossbowman", "Recruits - crossbowman"},
            {"captain", "recruits:captain", "Recruits - captain"},
            {"guard", "guardvillagers:guard", "Guard Villagers - guard"},
    };

    /** {namespace, config key, display name, optional extra caution line} — declaration = doc order. */
    private static final String[][] COMPAT_NAMESPACES = {
            {"guardvillagers", "guardvillagers", "Guard Villagers", ""},
            {"mca", "mca", "MCA Reborn",
                    "Use sparingly: MCA shares entity types across all villager roles, so a loadout "
                            + "makes every one of them cast."},
            {"minecolonies", "minecolonies", "MineColonies",
                    "Citizens are driven by colony AI; prefer enabling this only for raiders."},
            {"easy_npc", "easynpc", "Easy NPC", ""},
            {"humancompanions", "humancompanions", "Human Companions", ""},
            {"morevillagers", "morevillagers", "More Villagers", ""},
            {"villagersplus", "villagersplus", "VillagersPlus", ""},
    };

    // --- Magic schools: per-individual school assignment for recruits & villagers ---
    public static final ForgeConfigSpec.BooleanValue SCHOOLS_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SCHOOLS_ALLOWED;
    public static final ForgeConfigSpec.ConfigValue<String> SCHOOLS_MAX_RARITY;
    public static final ForgeConfigSpec.IntValue SCHOOLS_MAX_SPELL_LEVEL;
    public static final ForgeConfigSpec.IntValue SCHOOLS_SPELLS_PER_SCHOOL;
    public static final ForgeConfigSpec.BooleanValue SCHOOLS_INCLUDE_SUPPORT;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SCHOOLS_SUPPORT_IDS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SCHOOLS_ALLOWED_CAST_TYPES;
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

    public static final ForgeConfigSpec.BooleanValue SCHOOLS_EASYNPC_ENABLED;
    public static final ForgeConfigSpec.DoubleValue SCHOOLS_EASYNPC_CASTER_CHANCE;
    public static final ForgeConfigSpec.ConfigValue<String> SCHOOLS_EASYNPC_MODE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SCHOOLS_EASYNPC_TYPE_SCHOOLS;
    public static final ForgeConfigSpec.IntValue SCHOOLS_EASYNPC_MIN_LEVEL;

    public static final ForgeConfigSpec.BooleanValue SCHOOLS_VILLAGERS_ENABLED;
    public static final ForgeConfigSpec.DoubleValue SCHOOLS_VILLAGERS_CASTER_CHANCE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SCHOOLS_VILLAGERS_PROFESSION_SCHOOLS;
    public static final ForgeConfigSpec.BooleanValue SCHOOLS_VILLAGERS_UNMAPPED_RANDOM;
    public static final ForgeConfigSpec.BooleanValue SCHOOLS_VILLAGERS_SELF_DEFENSE;

    public static final ForgeConfigSpec.BooleanValue SCHOOLS_COMMAND_ENABLED;
    public static final ForgeConfigSpec.IntValue SCHOOLS_COMMAND_PERMISSION;
    public static final ForgeConfigSpec.BooleanValue SCHOOLS_ITEM_ENABLED;

    /** Valid Iron's school ids (namespace confirmed from the dep jar: irons_spellbooks). */
    private static final List<String> DEFAULT_SCHOOLS = List.of(
            "irons_spellbooks:fire", "irons_spellbooks:ice", "irons_spellbooks:lightning",
            "irons_spellbooks:holy", "irons_spellbooks:ender", "irons_spellbooks:blood",
            "irons_spellbooks:evocation", "irons_spellbooks:nature", "irons_spellbooks:eldritch");

    /**
     * Vanilla attack-goal classes the {@code "native_attack": "suppress"} policy may remove, and that
     * {@code "yield"} treats as "the mob is already attacking". Matched on the simple class name so it
     * covers subclasses in other mods without importing them. Extend with
     * {@code general.suppressibleAttackGoals}.
     */
    private static final List<String> DEFAULT_ATTACK_GOALS = List.of(
            "MeleeAttackGoal", "RangedAttackGoal", "RangedBowAttackGoal", "RangedCrossbowAttackGoal",
            "AbstractSkeleton$1", "SpellcasterIllager$SpellcasterCastingSpellGoal", "ZombieAttackGoal",
            // Villager Recruits gives EVERY recruit a RecruitMeleeAttackGoal in its base
            // registerGoals(); only Bowmen and Crossbowmen get a ranged goal on top. Without this
            // entry, "native_attack": "suppress" on a recruit loadout silently did nothing and the
            // recruit kept charging into sword range to cast. Suppression is reversible (the goal is
            // wrapped, not destroyed), so listing it by default costs nothing when unused.
            "RecruitMeleeAttackGoal", "RecruitRangedBowAttackGoal", "RecruitRangedCrossbowAttackGoal",
            "RecruitRangedMusketAttackGoal",
            // Easy NPC's attack objectives (de.markusbordihn.easynpc.entity.easynpc.ai.goal). Its
            // ZombieAttackGoal shares a simple name with the vanilla entry above, which already covers
            // it — matching is by simple name precisely so mods we cannot import are reachable.
            "BowAttackGoal", "CrossbowAttackGoal", "GunAttackGoal", "CustomMeleeAttackGoal");

    private static final String TKEY = "magicnpcs.configuration.";

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("general");
        ENABLE_SPELLCASTING = b
                .comment("Master switch for all NPC spellcasting. If false, no casting goals are injected.")
                .translation(TKEY + "enableSpellcasting")
                .define("enableSpellcasting", true);
        CASTING_GOAL_PRIORITY = b
                .comment("GoalSelector priority the casting goal is injected at (lower number = higher priority).",
                        "Only matters for flag contention and the 'suppress'/'yield' native_attack policies:",
                        "GoalSelector runs goals in insertion order, not priority order. A loadout's",
                        "'goal_priority' field overrides this per entity type.")
                .translation(TKEY + "castingGoalPriority")
                .defineInRange("castingGoalPriority", 2, 0, 99);
        CASTING_GOAL_USES_LOOK_FLAG = b
                .comment("Pre-0.6.0 behaviour: make the casting goal claim the LOOK control flag.",
                        "Leave false. With the flag, a mob whose own attack goal declares LOOK at an equal or",
                        "better priority (e.g. minecraft:witch at priority 2) can starve the casting goal",
                        "entirely, and a skeleton's bow goal gets preempted every cast. The goal does not need",
                        "the flag: it snaps its own rotation at cast time. See docs/decisions/0002.")
                .translation(TKEY + "castingGoalUsesLookFlag")
                .define("castingGoalUsesLookFlag", false);
        DISABLED_ENTITY_TYPES = b
                .comment("Entity type ids that must never cast, e.g. \"minecraft:skeleton\".",
                        "A no-datapack escape hatch: listing a type here suppresses every loadout for it.")
                .translation(TKEY + "disabledEntityTypes")
                .defineListAllowEmpty("disabledEntityTypes", () -> List.<String>of(), MagicNpcsConfig::isResourceId);
        SUPPRESSIBLE_ATTACK_GOALS = b
                .comment("Extra goal class names (simple name, e.g. \"MyModRangedGoal\") that a loadout's",
                        "\"native_attack\": \"suppress\" may remove and that \"yield\" counts as an active attack.",
                        "Vanilla attack goals are always included.")
                .translation(TKEY + "suppressibleAttackGoals")
                .defineListAllowEmpty("suppressibleAttackGoals", () -> List.<String>of(),
                        o -> o instanceof String s && !s.isBlank());
        STRICT_LOADOUT_SCHEMA = b
                .comment("Reject a spellcaster loadout that contains a key Magic NPCs does not recognise,",
                        "instead of warning about it and loading the rest of the file.",
                        "Unknown keys are always reported by /magicnpcs validate; this decides whether they are",
                        "fatal. Turn it on while authoring a pack so a typo cannot silently become a default.")
                .translation(TKEY + "strictLoadoutSchema")
                .define("strictLoadoutSchema", false);
        RECONCILE_BATCH_SIZE = b
                .comment("How many already-loaded mobs a datapack reload reconciles per server tick.",
                        "A reload re-evaluates every loaded mob, not just existing casters, so a mob that was",
                        "not a caster becomes one when a new datapack matches it. The work is spread over",
                        "ticks so a large server does not stall on /reload.")
                .translation(TKEY + "reconcileBatchSize")
                .defineInRange("reconcileBatchSize", 200, 1, 20000);
        LEGACY_DEBUG_LOGGING = b
                .comment("DEPRECATED — moved to config/magicnpcs-common.toml as of 0.6.0.",
                        "Still read for one release: if either file sets it true, debug logging is on.",
                        "This copy is removed in 0.8.0.")
                .translation(TKEY + "debugLogging")
                .define("debugLogging", false);
        b.pop();

        b.push("balance");
        MANA_MULTIPLIER = b
                .comment("Multiplier applied to each loadout's max-mana pool.")
                .translation(TKEY + "manaMultiplier")
                .defineInRange("manaMultiplier", 1.0D, 0.0D, 1000.0D);
        COOLDOWN_MULTIPLIER = b
                .comment("Multiplier applied to NPC spell cooldowns (>1 = NPCs cast less often).",
                        "NOTE (0.6.0): cooldowns and the decision interval now count real game ticks.",
                        "Before 0.6.0 they advanced only on alternating ticks, so every value behaved as",
                        "roughly double. Raise this to ~2.0 to reproduce your pre-0.6.0 cast rate.")
                .translation(TKEY + "cooldownMultiplier")
                .defineInRange("cooldownMultiplier", 1.0D, 0.0D, 100.0D);
        REGEN_MULTIPLIER = b
                .comment("Multiplier applied to NPC mana regen per regen tick.")
                .translation(TKEY + "regenMultiplier")
                .defineInRange("regenMultiplier", 1.0D, 0.0D, 100.0D);
        DECISION_INTERVAL_TICKS = b
                .comment("Minimum ticks between an NPC's cast attempts while it has a target (20 ticks = 1s).")
                .translation(TKEY + "decisionIntervalTicks")
                .defineInRange("decisionIntervalTicks", 10, 1, 200);
        CAST_CHANCE = b
                .comment("Default probability [0..1] that a caster actually casts on each decision (its 'hesitation').",
                        "1.0 = cast whenever able. A loadout entry's 'cast_chance' overrides this per spell.")
                .translation(TKEY + "castChance")
                .defineInRange("castChance", 1.0D, 0.0D, 1.0D);
        MIN_COOLDOWN_TICKS = b
                .comment("Hard floor (ticks) applied to every spell cooldown, including explicit per-spell overrides.")
                .translation(TKEY + "minCooldownTicks")
                .defineInRange("minCooldownTicks", 20, 0, 1200);
        SUPPORT_HEALTH_THRESHOLD = b
                .comment("An NPC self-casts SUPPORT spells when its health fraction drops below this (0..1).")
                .translation(TKEY + "supportHealthThreshold")
                .defineInRange("supportHealthThreshold", 0.5D, 0.0D, 1.0D);
        SUPPORT_OUT_OF_COMBAT = b
                .comment("Let wounded casters use SUPPORT spells (heals/buffs) with no hostile target.",
                        "Self-cast only, on the slower supportOutOfCombatIntervalTicks cadence, and only while",
                        "hurt — ATTACK spells are never selected without a target. False = pre-0.6.0 behaviour",
                        "(support only ever fires in combat).")
                .translation(TKEY + "supportOutOfCombat")
                .define("supportOutOfCombat", true);
        SUPPORT_OUT_OF_COMBAT_INTERVAL_TICKS = b
                .comment("Ticks between out-of-combat SUPPORT decisions (20 ticks = 1s). Much slower than the",
                        "combat cadence on purpose: an idle NPC re-checking heals twice a second is pure waste.")
                .translation(TKEY + "supportOutOfCombatIntervalTicks")
                .defineInRange("supportOutOfCombatIntervalTicks", 100, 20, 2400);
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
        CASTER_MOVEMENT = b
                .comment("Let a pure caster reposition to a range its own spells are eligible at.",
                        "Only ever runs for a mob whose own attack AI is suppressed (a loadout with",
                        "\"native_attack\": \"suppress\"). A mob that still has its own attack goals keeps them and",
                        "this does nothing, so it can never end up in a tug-of-war with a melee goal that is",
                        "pathing inward. The range comes from the loadout's own min_range/max_range.")
                .translation(TKEY + "casterMovement")
                .define("casterMovement", true);
        CASTER_MOVEMENT_SPEED = b
                .comment("Movement speed multiplier a repositioning caster uses.")
                .translation(TKEY + "casterMovementSpeed")
                .defineInRange("casterMovementSpeed", 1.0D, 0.1D, 5.0D);
        RANK_LEVEL_PER_RANK = b
                .comment("Spell levels a progression NPC gains per rank (e.g. a Villager Recruit's XP level).",
                        "0.25 means every fourth rank adds a level. The loadout's own 'level' is a FLOOR that",
                        "this raises; it can never lower it, so a pack author still sets the baseline.",
                        "Set to 0 to keep rank affecting only the mana pool, as it did before 0.6.3.")
                .translation(TKEY + "rankLevelPerRank")
                .defineInRange("rankLevelPerRank", 0.25D, 0.0D, 10.0D);
        RANK_LEVEL_MAX_BONUS = b
                .comment("Hard cap on how many levels rank may add above the loadout's own 'level'.",
                        "Without a cap a long-lived NPC would eventually outrun whatever balance a pack",
                        "intended. The spell's own maximum level still applies on top of this.")
                .translation(TKEY + "rankLevelMaxBonus")
                .defineInRange("rankLevelMaxBonus", 2, 0, 10);
        b.pop();

        b.push("targeting");
        REQUIRE_LINE_OF_SIGHT = b
                .comment("Require an unobstructed line of sight to the target before an NPC casts an attack spell.")
                .translation(TKEY + "requireLineOfSight")
                .define("requireLineOfSight", true);
        CAST_WINDUP_TICKS = b
                .comment("Default wind-up (ticks) a caster spends tracking its target before an attack spell fires.",
                        "It re-checks line of sight/range each tick and only casts if the target is still valid.",
                        "0 = cast instantly (legacy). A loadout entry's 'windup' overrides this per spell.")
                .translation(TKEY + "castWindupTicks")
                .defineInRange("castWindupTicks", 6, 0, 100);
        PROTECT_BYSTANDERS = b
                .comment("Treat nearby non-combatants (villagers, iron golems, tamed pets) as protected",
                        "so an NPC will not catch them in an attack spell's line of fire / blast radius.")
                .translation(TKEY + "protectBystanders")
                .define("protectBystanders", true);
        PROTECT_TARGETED_PLAYERS = b
                .comment("Also treat players as protected bystanders. Off by default since 0.6.0: with it on, a",
                        "hostile caster fighting one player silently never casts while any other player stands",
                        "near the firing line. The caster's own target is never treated as a bystander either way.")
                .translation(TKEY + "protectBystanderPlayers")
                .define("protectBystanderPlayers", false);
        PROTECT_OWNERS = b
                .comment("Enable the generic owner/team friendly-fire adapter (vanilla OwnableEntity + scoreboard teams).",
                        "Protects companion/pet/follower NPCs and their owner without a mod-specific adapter.")
                .translation(TKEY + "protectOwners")
                .define("protectOwners", true);
        PROTECT_RAID_ALLIES = b
                .comment("Never let a raider catch another raider from the same raid in its line of fire.",
                        "Independent of protectOwners: raid membership is not ownership, and a pillager wave",
                        "that blows itself apart is not the advertised behaviour.")
                .translation(TKEY + "protectRaidAllies")
                .define("protectRaidAllies", true);
        SITTING_PETS_MAY_CAST = b
                .comment("Allow a tamed companion that has been ordered to sit to keep casting.",
                        "Default false: 'sit' means stop, and a sitting pet that keeps flinging spells is a",
                        "companion the player cannot switch off. Applies to any vanilla TamableAnimal, so it",
                        "covers companion mods built on one.")
                .translation(TKEY + "sittingPetsMayCast")
                .define("sittingPetsMayCast", false);
        b.pop();

        b.push("equipment");
        REQUIRE_SPELL_FOCUS = b
                .comment("Require a spellcasting NPC to hold an item in the 'magicnpcs:spell_focuses' item tag to cast.",
                        "Default off; populate that tag (e.g. with Iron's staves/spellbooks) to use it.")
                .translation(TKEY + "requireSpellFocus")
                .define("requireSpellFocus", false);
        SPAWN_WITH_GEAR_CHANCE = b
                .comment("Chance [0..1] that a spellcasting NPC spawns holding a random item from 'magicnpcs:spell_focuses'.",
                        "Default 0 (off). Has no effect if that tag is empty. Rolled once per NPC and persisted.")
                .translation(TKEY + "spawnWithGearChance")
                .defineInRange("spawnWithGearChance", 0.0D, 0.0D, 1.0D);
        b.pop();

        b.push("reactive");
        b.comment("Per-spell reactive conditions: a loadout spell may carry a 'condition' block (self/target HP,",
                "nearby-enemy count, recently-hurt) so casters use the right tool at the right moment.");
        REACTIVE_CASTING_ENABLED = b
                .comment("Master switch for per-spell reactive conditions. When false, a spell's 'condition' is ignored",
                        "(it stays eligible by role/range/line-of-sight, as in 0.3.x).")
                .translation(TKEY + "reactive.enabled")
                .define("enabled", true);
        MATCHED_CONDITION_WEIGHT_BONUS = b
                .comment("Selection-weight multiplier applied to a conditioned spell while its condition is satisfied",
                        "(e.g. favour an AoE when swarmed). 1.0 = no bias; only affects spells that declare a condition.")
                .translation(TKEY + "reactive.matchedConditionWeightBonus")
                .defineInRange("matchedConditionWeightBonus", 1.0D, 1.0D, 100.0D);
        b.pop();

        b.push("feedback");
        b.comment("Cosmetic combat readability. Telegraphs play during a caster's wind-up so its attacks can be",
                "seen coming; they are server-spawned vanilla particles/sounds (safe on dedicated servers).");
        FEEDBACK_TELEGRAPHS = b
                .comment("Play a brief 'tell' (particles + sound) when a caster begins an attack wind-up.",
                        "Applies whenever a cast has a wind-up. Note that a LONG (channelled) spell always winds up for",
                        "its own Iron's cast time, so those still telegraph even with castWindupTicks = 0.",
                        "Out-of-combat self-heals are never telegraphed.")
                .translation(TKEY + "feedback.telegraphs")
                .define("telegraphs", true);
        FEEDBACK_SCHOOL_PARTICLES = b
                .comment("Tint telegraph particles by the spell's Iron's school colour (vs a neutral colour).")
                .translation(TKEY + "feedback.schoolParticles")
                .define("schoolParticles", true);
        FEEDBACK_TELEGRAPH_GLOW = b
                .comment("Briefly outline-glow the caster during its wind-up.")
                .translation(TKEY + "feedback.telegraphGlow")
                .define("telegraphGlow", false);
        FEEDBACK_TELEGRAPH_VOLUME = b
                .comment("Volume [0..1] of the telegraph/charge sound (0 = silent).")
                .translation(TKEY + "feedback.telegraphVolume")
                .defineInRange("telegraphVolume", 0.5D, 0.0D, 1.0D);
        FEEDBACK_MIN_DANGER_TIER = b
                .comment("Only telegraph spells at or above this danger tier (0..4, from rarity + AoE size). 0 = telegraph all.")
                .translation(TKEY + "feedback.minDangerTier")
                .defineInRange("minDangerTier", 0, 0, 4);
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
        ALLOW_UNVERIFIED_SPELLS = b
                .comment("Let NPCs cast spells whose mob-casting behaviour Magic NPCs has not verified.",
                        "Magic NPCs ships a manifest of every Iron's spell it has checked against a real mob",
                        "cast lifecycle. A spell outside it - from an Iron's add-on, or a newer Iron's than",
                        "this build was tested against - has no known cast-data strategy, so casting it may do",
                        "nothing while still spending mana. Default false: unverified spells are skipped and",
                        "reported by /magicnpcs validate rather than mis-fired.")
                .translation(TKEY + "allowUnverifiedSpells")
                .define("allowUnverifiedSpells", false);
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
        b.pop();

        b.push("easynpc");
        b.comment("Easy NPC integration. Separate from [compat].easynpc, which only decides whether datapack",
                "loadouts naming an 'easy_npc:' entity type apply at all; these govern the adapter itself.",
                "Off by default: an Easy NPC is a hand-authored character, and giving one spells should be a",
                "deliberate act. Owner and faction protection stay active either way — turning the integration",
                "off stops Easy NPCs casting, it never removes the rules about who they may not cast at.");
        EASYNPC_INTEGRATION_ENABLED = b
                .comment("Enable the Easy NPC adapter (level-scaled mana + owner/faction-aware targeting).")
                .translation(TKEY + "easynpc.enabled")
                .define("enabled", false);
        EASYNPC_MANA_PER_LEVEL = b
                .comment("Extra max-mana fraction per Easy NPC experience level (e.g. 0.05 = +5% per level).",
                        "Easy NPC progression runs to level 60, so this compounds much further than the Recruits",
                        "equivalent — the default is deliberately a fifth of it.")
                .translation(TKEY + "easynpc.manaPerLevel")
                .defineInRange("manaPerLevel", 0.02D, 0.0D, 100.0D);
        EASYNPC_USE_OBJECTIVE = b
                .comment("Register 'magicnpcs:cast_spell' as an Easy NPC custom objective, so casting can be",
                        "attached to an NPC through Easy NPC's own objective system (presets, commands) rather",
                        "than only by a datapack loadout. Turning this off does not stop loadouts working.")
                .translation(TKEY + "easynpc.useObjective")
                .define("useObjective", true);
        EASYNPC_RESPECT_FACTIONS = b
                .comment("Route target selection through Easy NPC's own faction rules, so an NPC never casts at",
                        "something its faction is not hostile to. Turn off only if you want Easy NPCs to use the",
                        "generic owner/team rules alone.")
                .translation(TKEY + "easynpc.respectFactions")
                .define("respectFactions", true);
        b.pop();

        b.push("builtinLoadouts");
        b.comment("Per-loadout switches for the spellcaster loadouts Magic NPCs itself ships.",
                "Each shipped loadout targets an OPTIONAL NPC mod and is already inert when that mod is",
                "absent; these let you keep the mod and drop our spells for it without writing a datapack.",
                "Turning one off is exactly equivalent to a datapack stub with enabled = false.");
        BUILTIN_LOADOUTS = defineBuiltinLoadouts(b);
        b.pop();

        b.push("compat");
        b.comment("DEPRECATED as of 0.6.0 — these moved to config/magicnpcs-common.toml, which applies to",
                "every world instead of being per-save. They are still read for one release: a toggle is on",
                "if EITHER file enables it. This block is removed in 0.8.0.");
        LEGACY_NAMESPACE_TOGGLES = defineCompatToggles(b, true);
        b.pop();

        b.push("schools");
        b.comment("Per-individual magic-school assignment. Each eligible recruit/villager is assigned one",
                "Iron's school; its spell pool is built dynamically from that school's enabled spells.",
                "Run /magicnpcs school pool to see which spells survive these filters, and why.");
        SCHOOLS_ENABLED = b
                .comment("Master switch for the school-assignment system (recruits + villagers).")
                .translation(TKEY + "schools.enableSchools")
                .define("enableSchools", true);
        SCHOOLS_ALLOWED = b
                .comment("School ids NPCs may be assigned. Default: all nine Iron's schools.")
                .translation(TKEY + "schools.allowedSchools")
                .defineListAllowEmpty("allowedSchools", () -> DEFAULT_SCHOOLS, MagicNpcsConfig::isResourceId);
        SCHOOLS_MAX_RARITY = b
                .comment("Highest spell rarity an NPC may be given: COMMON, UNCOMMON, RARE, EPIC, LEGENDARY.",
                        "A school whose spells are all above this yields no caster — see /magicnpcs school pool.")
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
                        "Anything matching heal/cure/blessing/regen/haste/shield/ward/fortify is also support.",
                        "The defensive self-buffs below matter most for schools with no healing spell at all:",
                        "fire, ice, lightning, ender and nature match none of those keywords, so a caster that can",
                        "only self-cast (a villager without selfDefense) would otherwise have nothing to use.")
                .translation(TKEY + "schools.supportSpellIds")
                .defineListAllowEmpty("supportSpellIds", () -> List.of(
                        // Healing / restorative
                        "irons_spellbooks:heal", "irons_spellbooks:greater_heal",
                        "irons_spellbooks:blessing_of_life", "irons_spellbooks:healing_circle",
                        "irons_spellbooks:cloud_of_regeneration", "irons_spellbooks:cleanse",
                        // Buffs
                        "irons_spellbooks:haste", "irons_spellbooks:angel_wing",
                        "irons_spellbooks:fortify", "irons_spellbooks:heartstop",
                        // Defensive self-buffs, one per otherwise support-less school
                        "irons_spellbooks:oakskin", "irons_spellbooks:spider_aspect",
                        "irons_spellbooks:evasion", "irons_spellbooks:invisibility",
                        "irons_spellbooks:ice_block", "irons_spellbooks:frost_step",
                        "irons_spellbooks:shield", "irons_spellbooks:fang_ward"),
                        MagicNpcsConfig::isResourceId);
        SCHOOLS_ALLOWED_CAST_TYPES = b
                .comment("Iron's cast types a generated school pool may include: INSTANT, LONG, CONTINUOUS.",
                        "LONG was excluded before 0.6.0 even though the casting goal channels long casts",
                        "correctly — that alone emptied several schools. CONTINUOUS stays out by default:",
                        "nothing drives a channel loop for a mob.")
                .translation(TKEY + "schools.allowedCastTypes")
                .defineListAllowEmpty("allowedCastTypes", () -> List.of("INSTANT", "LONG"),
                        MagicNpcsConfig::isCastType);
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

        b.push("easynpc");
        SCHOOLS_EASYNPC_ENABLED = b
                .comment("Assign schools to Easy NPCs automatically.",
                        "Off by default, unlike recruits and villagers: an Easy NPC is authored deliberately, and",
                        "a pack author who has hand-built a character does not expect it to roll a random school.")
                .translation(TKEY + "schools.easynpc.enabled")
                .define("enabled", false);
        SCHOOLS_EASYNPC_CASTER_CHANCE = b
                .comment("Chance [0..1] a spawned Easy NPC becomes a school caster (rolled once, persisted).")
                .translation(TKEY + "schools.easynpc.casterChance")
                .defineInRange("casterChance", 0.25D, 0.0D, 1.0D);
        SCHOOLS_EASYNPC_MODE = b
                .comment("School assignment: RANDOM (from allowedSchools), BY_TYPE (typeSchools map), BY_RANK.")
                .translation(TKEY + "schools.easynpc.assignmentMode")
                .define("assignmentMode", "RANDOM", MagicNpcsConfig::isAssignmentMode);
        SCHOOLS_EASYNPC_TYPE_SCHOOLS = b
                .comment("BY_TYPE map: 'entityType=school[,school]', e.g. 'easy_npc:humanoid=irons_spellbooks:fire'.")
                .translation(TKEY + "schools.easynpc.typeSchools")
                .defineListAllowEmpty("typeSchools", () -> List.<String>of(), MagicNpcsConfig::isPairMapping);
        SCHOOLS_EASYNPC_MIN_LEVEL = b
                .comment("Minimum Easy NPC experience level to be eligible for a school (progression runs 0-60).")
                .translation(TKEY + "schools.easynpc.minLevelToCast")
                .defineInRange("minLevelToCast", 0, 0, 100);
        b.pop();

        b.push("villagers");
        SCHOOLS_VILLAGERS_ENABLED = b
                .comment("Assign schools to villagers (vanilla + profession mods extending Villager).",
                        "Note: a magic villager only actually casts when it has a target (raids, guard mods, MCA",
                        "guards) — or, since 0.6.0, when it is wounded and has a support spell.")
                .translation(TKEY + "schools.villagers.enabled")
                .define("enabled", true);
        SCHOOLS_VILLAGERS_CASTER_CHANCE = b
                .comment("Chance [0..1] an eligible villager becomes a school caster (rolled once, persisted).")
                .translation(TKEY + "schools.villagers.casterChance")
                .defineInRange("casterChance", 0.5D, 0.0D, 1.0D);
        SCHOOLS_VILLAGERS_PROFESSION_SCHOOLS = b
                .comment("Profession→school map: 'profession=school[,school]'. Only listed professions cast by default.",
                        "A villager with no profession yet (minecraft:none) is left alone and re-checked once it",
                        "takes a job — it is no longer permanently marked a non-caster.")
                .translation(TKEY + "schools.villagers.professionSchools")
                .defineListAllowEmpty("professionSchools", () -> List.of(
                        "minecraft:cleric=irons_spellbooks:holy",
                        "minecraft:librarian=irons_spellbooks:evocation",
                        "minecraft:weaponsmith=irons_spellbooks:fire",
                        "minecraft:toolsmith=irons_spellbooks:lightning",
                        "minecraft:fletcher=irons_spellbooks:ender",
                        "minecraft:farmer=irons_spellbooks:nature"),
                        MagicNpcsConfig::isPairMapping);
        SCHOOLS_VILLAGERS_SELF_DEFENSE = b
                .comment("Let a school-assigned villager fight back when attacked, so it can use its ATTACK spells.",
                        "A vanilla villager has no targeting AI at all and nothing — raids included — ever gives it",
                        "a target, so without this a magic villager can only ever self-heal. Off by default: turning",
                        "it on changes vanilla villager behaviour, and only for villagers that got a school.")
                .translation(TKEY + "schools.villagers.selfDefense")
                .define("selfDefense", false);
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

        // --- COMMON spec: installation-level facts, one file for every world ---
        ForgeConfigSpec.Builder c = new ForgeConfigSpec.Builder();
        c.comment("Magic NPCs — installation-level settings. Unlike magicnpcs-server.toml (which is per-world,",
                "under saves/<world>/serverconfig/), this file lives in config/ and applies to every world.",
                "Modpack authors: ship this file directly; no defaultconfigs/ copy is needed.");
        c.push("general");
        COMMON_DEBUG_LOGGING = c
                .comment("Log each NPC cast (spell + mana before/after), goal injection, and skip reasons at INFO level.")
                .translation(TKEY + "debugLogging")
                .define("debugLogging", false);
        c.pop();
        c.push("compat");
        c.comment("Per-mod toggles gating whether datapack loadouts targeting that mod's entity types apply.",
                "Conservative: default OFF. Enabling a toggle with the mod absent simply has no effect.",
                "Magic NPCs logs a warning at load time if a mod is installed while its toggle is off.");
        NAMESPACE_TOGGLES = defineCompatToggles(c, false);
        c.pop();
        COMMON_SPEC = c.build();
    }

    /**
     * Define the seven {@code [compat]} namespace toggles on a builder. Called twice — once for the
     * COMMON spec (the real home since 0.6.0) and once for the SERVER spec (the legacy fallback) — so
     * the two can never drift apart.
     */
    private static Map<String, ForgeConfigSpec.BooleanValue> defineCompatToggles(
            ForgeConfigSpec.Builder b, boolean legacy) {
        Map<String, ForgeConfigSpec.BooleanValue> map = new java.util.LinkedHashMap<>();
        for (String[] entry : COMPAT_NAMESPACES) {
            String namespace = entry[0];
            String key = entry[1];
            String label = entry[2];
            String caution = entry[3];
            List<String> comment = new ArrayList<>(3);
            if (legacy) {
                comment.add("DEPRECATED (see above).");
            }
            comment.add("Allow loadouts targeting " + label + " entity types (namespace '" + namespace + "').");
            if (!caution.isEmpty()) {
                comment.add(caution);
            }
            ForgeConfigSpec.BooleanValue value = b
                    .comment(comment.toArray(String[]::new))
                    .translation(TKEY + "compat." + key)
                    .define(key, false);
            map.put(namespace, value);
        }
        return Map.copyOf(map);
    }

    /** Define one boolean per shipped loadout, so the set can never drift from the data generator. */
    private static Map<String, ForgeConfigSpec.BooleanValue> defineBuiltinLoadouts(ForgeConfigSpec.Builder b) {
        Map<String, ForgeConfigSpec.BooleanValue> map = new java.util.LinkedHashMap<>();
        for (String[] entry : BUILTIN_LOADOUT_FILES) {
            String file = entry[0];
            map.put(file, b
                    .comment("Apply the shipped loadout for " + entry[2] + " (" + entry[1] + ").")
                    .translation(TKEY + "builtinLoadouts." + file)
                    .define(file, true));
        }
        return Map.copyOf(map);
    }

    private MagicNpcsConfig() {}

    /**
     * @return whether an unknown key in a loadout file rejects it. Safe before the spec loads (the
     *         data generator parses loadouts with no config present), where it reports the default.
     */
    public static boolean strictLoadoutSchema() {
        return SPEC.isLoaded() && STRICT_LOADOUT_SCHEMA.get();
    }

    /** @return whether a suppressed-native caster may reposition. Safe before the spec loads. */
    public static boolean casterMovementEnabled() {
        return !SPEC.isLoaded() || CASTER_MOVEMENT.get();
    }

    /** @return the speed multiplier a repositioning caster uses. Safe before the spec loads. */
    public static double casterMovementSpeed() {
        return SPEC.isLoaded() ? CASTER_MOVEMENT_SPEED.get() : 1.0;
    }

    /** @return spell levels gained per adapter rank. Safe before the spec loads. */
    public static double rankLevelPerRank() {
        return SPEC.isLoaded() ? RANK_LEVEL_PER_RANK.get() : 0.25;
    }

    /** @return the cap on levels rank may add above a loadout's own level. Safe before the spec loads. */
    public static int rankLevelMaxBonus() {
        return SPEC.isLoaded() ? RANK_LEVEL_MAX_BONUS.get() : 2;
    }

    /** @return how many loaded mobs a reload reconciles per tick. Safe before the spec loads. */
    public static int reconcileBatchSize() {
        return SPEC.isLoaded() ? RECONCILE_BATCH_SIZE.get() : 200;
    }

    /** @return whether spells with no verified mob-cast strategy may be cast. Safe before load. */
    public static boolean allowUnverifiedSpells() {
        return SPEC.isLoaded() && ALLOW_UNVERIFIED_SPELLS.get();
    }

    /** @return whether same-raid allies are protected from a raider's line of fire. Safe before load. */
    public static boolean protectRaidAllies() {
        return !SPEC.isLoaded() || PROTECT_RAID_ALLIES.get();
    }

    /** @return whether a tamed pet ordered to sit may still cast. Safe before load. */
    public static boolean sittingPetsMayCast() {
        return SPEC.isLoaded() && SITTING_PETS_MAY_CAST.get();
    }

    /**
     * @return whether the shipped loadout in {@code magicnpcs:<name>} should apply. A loadout that is
     *         not one of ours is never gated here.
     */
    public static boolean isBuiltinLoadoutEnabled(ResourceLocation source) {
        if (source == null || !"magicnpcs".equals(source.getNamespace())) {
            return true;
        }
        ForgeConfigSpec.BooleanValue toggle = BUILTIN_LOADOUTS.get(source.getPath());
        return toggle == null || !SPEC.isLoaded() || toggle.get();
    }

    /** The shipped-loadout file names, in declaration order - for /magicnpcs config. */
    public static List<String> builtinLoadoutNames() {
        return List.copyOf(BUILTIN_LOADOUTS.keySet());
    }

    /**
     * Warn once, after both specs are loaded, about any {@code [compat]} key or {@code debugLogging}
     * still set in the deprecated server-side location, naming the file it should move to. Called from
     * the mod entrypoint's config-load listener.
     */
    public static void warnOnLegacyKeys() {
        if (!SPEC.isLoaded()) {
            return;
        }
        List<String> stale = new ArrayList<>();
        if (LEGACY_DEBUG_LOGGING.get()) {
            stale.add("general.debugLogging");
        }
        LEGACY_NAMESPACE_TOGGLES.forEach((namespace, value) -> {
            if (value.get()) {
                stale.add("compat." + namespace);
            }
        });
        if (!stale.isEmpty()) {
            com.otectus.magicnpcs.MagicNpcs.LOGGER.warn(
                    "magicnpcs-server.toml still sets {} in the deprecated location(s) [{}]. They are honoured "
                            + "for this release, but move them to config/magicnpcs-common.toml — the server-side "
                            + "copies are removed in 0.8.0.",
                    stale.size(), String.join(", ", stale));
        }
    }

    /**
     * @return whether debug logging is on. Reads both specs (the key moved to COMMON in 0.6.0) and is
     *         safe before either is loaded, so it can be called from any lifecycle point.
     */
    public static boolean debugLogging() {
        return (COMMON_SPEC.isLoaded() && COMMON_DEBUG_LOGGING.get())
                || (SPEC.isLoaded() && LEGACY_DEBUG_LOGGING.get());
    }

    /** @return whether a spell id passes the whitelist/blacklist filters. */
    public static boolean isAllowed(String spellId) {
        if (!SPEC.isLoaded()) {
            return true;
        }
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
     *         gated; a namespace owned by an optional NPC mod requires its toggle
     *         enabled in {@code magicnpcs-common.toml} (or, for one release, in the
     *         deprecated server-side copy).
     */
    public static boolean isLoadoutEnabledFor(ResourceLocation entityType) {
        String ns = entityType.getNamespace();
        ForgeConfigSpec.BooleanValue toggle = NAMESPACE_TOGGLES.get(ns);
        if (toggle == null) {
            return true; // magicnpcs / minecraft / recruits / any namespace without a managed toggle
        }
        if (COMMON_SPEC.isLoaded() && toggle.get()) {
            return true;
        }
        ForgeConfigSpec.BooleanValue legacy = LEGACY_NAMESPACE_TOGGLES.get(ns);
        return SPEC.isLoaded() && legacy != null && legacy.get();
    }

    /**
     * @return the {@code GoalSelector} priority to inject casting goals at. Safe before the spec loads
     *         (GameTests construct goals directly), where it reports the default.
     */
    public static int castingGoalPriority() {
        return SPEC.isLoaded() ? CASTING_GOAL_PRIORITY.get() : 2;
    }

    /**
     * @return whether the casting goal should claim {@code Goal.Flag.LOOK}. Default false since 0.6.0
     *         (ADR 0002); safe before the spec loads.
     */
    public static boolean castingGoalUsesLookFlag() {
        return SPEC.isLoaded() && CASTING_GOAL_USES_LOOK_FLAG.get();
    }

    /** @return true when {@code entityType} is listed in {@code general.disabledEntityTypes}. */
    public static boolean isEntityTypeDisabled(ResourceLocation entityType) {
        return SPEC.isLoaded() && DISABLED_ENTITY_TYPES.get().contains(entityType.toString());
    }

    /** @return true if the optional mod that owns this loadout's namespace is actually installed (for logging). */
    public static boolean ownerModLoaded(ResourceLocation entityType) {
        return NAMESPACE_TOGGLES.containsKey(entityType.getNamespace())
                && ModList.get() != null && ModList.get().isLoaded(entityType.getNamespace());
    }

    /** Simple class names treated as a mob's own attack goal (vanilla list + the configured extras). */
    public static List<String> attackGoalClassNames() {
        if (!SPEC.isLoaded()) {
            return DEFAULT_ATTACK_GOALS;
        }
        List<String> out = new ArrayList<>(DEFAULT_ATTACK_GOALS);
        out.addAll(SUPPRESSIBLE_ATTACK_GOALS.get());
        return out;
    }

    /** The Iron's cast types a generated school pool may include, upper-cased. */
    public static List<String> allowedCastTypes() {
        if (!SPEC.isLoaded()) {
            return List.of("INSTANT", "LONG");
        }
        List<String> out = new ArrayList<>();
        for (String s : SCHOOLS_ALLOWED_CAST_TYPES.get()) {
            out.add(s.toUpperCase(Locale.ROOT));
        }
        return out.isEmpty() ? List.of("INSTANT") : out;
    }

    private static boolean isResourceId(Object o) {
        return o instanceof String s && ResourceLocation.tryParse(s) != null;
    }

    private static boolean isRarity(Object o) {
        if (!(o instanceof String s)) return false;
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY" -> true;
            default -> false;
        };
    }

    private static boolean isCastType(Object o) {
        if (!(o instanceof String s)) return false;
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "INSTANT", "LONG", "CONTINUOUS" -> true;
            default -> false;
        };
    }

    private static boolean isWeightingMode(Object o) {
        return o instanceof String s
                && (s.equalsIgnoreCase("UNIFORM") || s.equalsIgnoreCase("INVERSE_RARITY"));
    }

    private static boolean isAssignmentMode(Object o) {
        if (!(o instanceof String s)) return false;
        return switch (s.toUpperCase(Locale.ROOT)) {
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
            List<ResourceLocation> values = new ArrayList<>();
            for (String v : s.substring(eq + 1).split(",")) {
                ResourceLocation id = ResourceLocation.tryParse(v.trim());
                if (id != null) values.add(id);
            }
            if (!values.isEmpty()) map.put(key, values);
        }
        return map;
    }

    private static List<ResourceLocation> toIds(List<? extends String> raw) {
        List<ResourceLocation> ids = new ArrayList<>();
        for (String s : raw) {
            ResourceLocation id = ResourceLocation.tryParse(s);
            if (id != null) ids.add(id);
        }
        return ids;
    }
}
