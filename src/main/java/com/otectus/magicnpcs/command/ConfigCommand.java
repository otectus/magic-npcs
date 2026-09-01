package com.otectus.magicnpcs.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.otectus.magicnpcs.compat.IronsCompat;
import com.otectus.magicnpcs.compat.RecruitsCompat;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import com.otectus.magicnpcs.core.loadout.LoadoutCatalog;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.integration.irons.IronsSpellcasterHandler;
import com.otectus.magicnpcs.integration.irons.SpellManifest;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Locale;

/**
 * {@code /magicnpcs config} — the effective configuration, where it is read from, and whether the
 * world has been reconciled against it.
 *
 * <p>Present in 0.6.0, missing from the 0.6.1 binary while still advertised on the project page, so the
 * one command people were told to run to check their settings always produced a syntax error (audit
 * CMD-002). Restored here and extended with the things 0.6.1 made it possible to get wrong: the catalog
 * generation, how many mobs are still queued for reconciliation, the built-in loadout toggles, and how
 * many spells the mob-cast manifest actually covers.
 *
 * <p>It also prints the <em>real</em> config paths. Forge's {@code SERVER} config is per-world, under
 * {@code <world>/serverconfig/}, not {@code config/} — describing it as the latter sent people editing
 * a file that does not exist.
 */
public final class ConfigCommand {

    private ConfigCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("config")
                .requires(src -> src.hasPermission(2))
                .executes(ConfigCommand::show);
    }

    private static int show(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        LoadoutCatalog catalog = LoadoutManager.catalog();
        LoadoutCatalog.Counts counts = catalog.counts();

        header(src, "Magic NPCs configuration");

        // --- where the settings live -----------------------------------------------------------
        header(src, "files");
        detail(src, "server (per world, auto-synced to clients): "
                + "<world>/serverconfig/magicnpcs-server.toml");
        detail(src, "common (one file for every world):          config/magicnpcs-common.toml");
        detail(src, "a dedicated server's <world> is the level directory named by level-name in "
                + "server.properties");

        // --- runtime state ---------------------------------------------------------------------
        header(src, "runtime");
        boolean master = MagicNpcsConfig.ENABLE_SPELLCASTING.get();
        row(src, "general.enableSpellcasting", String.valueOf(master),
                master ? ChatFormatting.GREEN : ChatFormatting.RED);
        row(src, "loadout catalog generation", String.valueOf(catalog.generation()), ChatFormatting.WHITE);
        row(src, "discovered / active / shadowed / suppressed / rejected",
                counts.discovered() + " / " + counts.active() + " / " + counts.shadowed()
                        + " / " + counts.suppressed() + " / " + counts.rejected(),
                counts.rejected() > 0 ? ChatFormatting.RED : ChatFormatting.WHITE);
        int pending = IronsSpellcasterHandler.pendingReconciles();
        row(src, "mobs queued for reconciliation", String.valueOf(pending),
                pending > 0 ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
        row(src, "managed casters tracked in memory", String.valueOf(ManagedCasterState.trackedCount()),
                ChatFormatting.WHITE);
        if (counts.rejected() > 0) {
            warn(src, "  " + counts.rejected() + " loadout file(s) failed to load. Run /magicnpcs validate.");
        }
        if (pending > 0) {
            warn(src, "  reconciliation is still running; some mobs may not yet reflect the current data.");
        }

        // --- dependencies ----------------------------------------------------------------------
        header(src, "dependencies");
        row(src, "Iron's Spells 'n Spellbooks", IronsCompat.isLoaded() ? "present" : "ABSENT",
                IronsCompat.isLoaded() ? ChatFormatting.GREEN : ChatFormatting.RED);
        row(src, "Villager Recruits", RecruitsCompat.isLoaded() ? "present" : "absent", ChatFormatting.WHITE);
        row(src, "mob-cast manifest", SpellManifest.size() + " spells verified against Iron's "
                + SpellManifest.VERIFIED_AGAINST, ChatFormatting.WHITE);
        row(src, "spells.allowUnverifiedSpells", String.valueOf(MagicNpcsConfig.allowUnverifiedSpells()),
                MagicNpcsConfig.allowUnverifiedSpells() ? ChatFormatting.YELLOW : ChatFormatting.GREEN);

        // --- casting policy --------------------------------------------------------------------
        header(src, "casting policy");
        row(src, "general.castingGoalPriority", String.valueOf(MagicNpcsConfig.castingGoalPriority()),
                ChatFormatting.WHITE);
        row(src, "general.castingGoalUsesLookFlag",
                String.valueOf(MagicNpcsConfig.castingGoalUsesLookFlag()), ChatFormatting.WHITE);
        row(src, "general.strictLoadoutSchema", String.valueOf(MagicNpcsConfig.strictLoadoutSchema()),
                ChatFormatting.WHITE);
        row(src, "general.reconcileBatchSize", String.valueOf(MagicNpcsConfig.reconcileBatchSize()),
                ChatFormatting.WHITE);
        row(src, "balance.decisionIntervalTicks",
                String.valueOf(MagicNpcsConfig.DECISION_INTERVAL_TICKS.get()), ChatFormatting.WHITE);
        row(src, "balance.cooldownMultiplier / minCooldownTicks",
                MagicNpcsConfig.COOLDOWN_MULTIPLIER.get() + " / " + MagicNpcsConfig.MIN_COOLDOWN_TICKS.get(),
                ChatFormatting.WHITE);
        row(src, "balance.manaMultiplier / regenMultiplier",
                MagicNpcsConfig.MANA_MULTIPLIER.get() + " / " + MagicNpcsConfig.REGEN_MULTIPLIER.get(),
                ChatFormatting.WHITE);
        row(src, "balance.casterMovement / casterMovementSpeed",
                MagicNpcsConfig.casterMovementEnabled() + " / " + MagicNpcsConfig.casterMovementSpeed(),
                ChatFormatting.WHITE);
        row(src, "balance.rankLevelPerRank / rankLevelMaxBonus",
                MagicNpcsConfig.rankLevelPerRank() + " / " + MagicNpcsConfig.rankLevelMaxBonus(),
                ChatFormatting.WHITE);
        row(src, "balance.peacefulDisablesCasting",
                String.valueOf(MagicNpcsConfig.PEACEFUL_DISABLES_CASTING.get()), ChatFormatting.WHITE);
        row(src, "general.disabledEntityTypes",
                describeList(MagicNpcsConfig.DISABLED_ENTITY_TYPES.get()), ChatFormatting.WHITE);

        // --- safety ----------------------------------------------------------------------------
        header(src, "friendly fire and safety");
        row(src, "balance.friendlyFireCheck", String.valueOf(MagicNpcsConfig.FRIENDLY_FIRE_CHECK.get()),
                ChatFormatting.WHITE);
        row(src, "targeting.protectBystanders / protectBystanderPlayers",
                MagicNpcsConfig.PROTECT_BYSTANDERS.get() + " / "
                        + MagicNpcsConfig.PROTECT_TARGETED_PLAYERS.get(), ChatFormatting.WHITE);
        row(src, "targeting.protectOwners", String.valueOf(MagicNpcsConfig.PROTECT_OWNERS.get()),
                ChatFormatting.WHITE);
        row(src, "targeting.protectRaidAllies", String.valueOf(MagicNpcsConfig.protectRaidAllies()),
                ChatFormatting.WHITE);
        row(src, "targeting.sittingPetsMayCast", String.valueOf(MagicNpcsConfig.sittingPetsMayCast()),
                MagicNpcsConfig.sittingPetsMayCast() ? ChatFormatting.YELLOW : ChatFormatting.WHITE);
        row(src, "targeting.requireLineOfSight", String.valueOf(MagicNpcsConfig.REQUIRE_LINE_OF_SIGHT.get()),
                ChatFormatting.WHITE);

        // --- spell filters ---------------------------------------------------------------------
        header(src, "spell filters");
        row(src, "spells.spellBlacklist", MagicNpcsConfig.SPELL_BLACKLIST.get().size() + " entries",
                ChatFormatting.WHITE);
        row(src, "spells.spellWhitelist", MagicNpcsConfig.SPELL_WHITELIST.get().isEmpty()
                ? "empty (everything allowed)"
                : MagicNpcsConfig.SPELL_WHITELIST.get().size() + " entries (ONLY these are allowed)",
                ChatFormatting.WHITE);

        // --- shipped loadouts ------------------------------------------------------------------
        header(src, "builtinLoadouts (shipped loadouts)");
        for (String name : MagicNpcsConfig.builtinLoadoutNames()) {
            boolean on = MagicNpcsConfig.isBuiltinLoadoutEnabled(new ResourceLocation("magicnpcs", name));
            row(src, "  " + name, on ? "on" : "off", on ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY);
        }

        // --- compat toggles --------------------------------------------------------------------
        header(src, "compat (config/magicnpcs-common.toml)");
        for (String namespace : List.of("guardvillagers", "mca", "minecolonies", "easy_npc",
                "humancompanions", "morevillagers", "villagersplus")) {
            ResourceLocation probe = new ResourceLocation(namespace, "probe");
            boolean enabled = MagicNpcsConfig.isLoadoutEnabledFor(probe);
            boolean installed = MagicNpcsConfig.ownerModLoaded(probe);
            String value = (enabled ? "on" : "off") + (installed ? ", mod installed" : ", mod absent");
            row(src, "  " + namespace, value,
                    installed && !enabled ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY);
        }

        // --- schools ---------------------------------------------------------------------------
        header(src, "schools");
        row(src, "schools.enableSchools", String.valueOf(MagicNpcsConfig.SCHOOLS_ENABLED.get()),
                ChatFormatting.WHITE);
        row(src, "schools.allowedSchools", MagicNpcsConfig.allowedSchoolIds().size() + " schools",
                ChatFormatting.WHITE);
        row(src, "schools.maxRarity / maxSpellLevel / spellsPerSchool",
                MagicNpcsConfig.SCHOOLS_MAX_RARITY.get() + " / "
                        + MagicNpcsConfig.SCHOOLS_MAX_SPELL_LEVEL.get() + " / "
                        + MagicNpcsConfig.SCHOOLS_SPELLS_PER_SCHOOL.get(), ChatFormatting.WHITE);
        row(src, "schools.control.commandEnabled / itemEnabled",
                MagicNpcsConfig.SCHOOLS_COMMAND_ENABLED.get() + " / "
                        + MagicNpcsConfig.SCHOOLS_ITEM_ENABLED.get(), ChatFormatting.WHITE);

        // --- recruits --------------------------------------------------------------------------
        header(src, "recruits");
        row(src, "recruits.enabled", String.valueOf(MagicNpcsConfig.RECRUITS_INTEGRATION_ENABLED.get()),
                ChatFormatting.WHITE);

        // --- what needs what -------------------------------------------------------------------
        header(src, "applying changes");
        detail(src, "loadout JSON        -> /reload (every loaded mob is then reconciled automatically)");
        detail(src, "server config       -> /reload, or edit and rejoin; casters reconcile on config load");
        detail(src, "common config       -> restart (compat toggles are read at load time)");
        detail(src, "stuck after a change-> /magicnpcs reconcile");
        return Command.SINGLE_SUCCESS;
    }

    private static String describeList(List<? extends String> values) {
        return values.isEmpty() ? "empty" : String.join(", ", values);
    }

    private static void header(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.AQUA), false);
    }

    private static void row(CommandSourceStack src, String key, String value, ChatFormatting colour) {
        src.sendSuccess(() -> Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(colour)), false);
    }

    private static void detail(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal("  " + text).withStyle(ChatFormatting.DARK_GRAY), false);
    }

    private static void warn(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text.toLowerCase(Locale.ROOT).startsWith("  ") ? text : "  " + text)
                .withStyle(ChatFormatting.YELLOW), false);
    }
}
