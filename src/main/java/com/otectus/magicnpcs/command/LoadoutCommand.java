package com.otectus.magicnpcs.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SpellDiagnostic;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import com.otectus.magicnpcs.core.loadout.CooldownResolver;
import com.otectus.magicnpcs.core.loadout.LoadoutCatalog;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.loadout.LoadoutProblem;
import com.otectus.magicnpcs.core.loadout.LoadoutRecord;
import com.otectus.magicnpcs.core.loadout.LoadoutResolution;
import com.otectus.magicnpcs.core.loadout.NativeAttackPolicy;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import com.otectus.magicnpcs.integration.irons.IronsBridge;
import com.otectus.magicnpcs.integration.irons.IronsSpellcasterHandler;
import com.otectus.magicnpcs.integration.irons.NpcSpellAttackGoal;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.Locale;

/**
 * {@code /magicnpcs loadout …} and {@code /magicnpcs validate …} — inspect the effective spellcaster
 * loadouts and every discovered loadout file.
 *
 * <p><b>Validation is the headline fix here (audit VAL-001).</b> Through 0.6.1 this command read
 * {@code LoadoutManager.snapshot()}, which contained only successfully parsed, post-override loadouts.
 * A file that failed to parse had already been logged and discarded, and a file in the wrong folder was
 * never discovered at all — so the one file most likely to be broken was the one file validation could
 * not see, and "no issues found" was reported over a rejected skeleton loadout. It now reads the whole
 * {@link LoadoutCatalog}, reports every discovered resource with its status and problems, and says
 * explicitly what it cannot know.
 *
 * <p>{@code loadout entity} also compares <em>desired</em> with <em>installed</em>: what would resolve
 * now versus what the mob's goal is actually running (audit VAL-002).
 */
public final class LoadoutCommand {

    /** Cap the per-record problem list so one bad file cannot flood a player's chat. */
    private static final int MAX_PROBLEM_ROWS = 12;

    private LoadoutCommand() {}

    // --- tree ----------------------------------------------------------------------------------

    /** {@code /magicnpcs loadout …} */
    public static LiteralArgumentBuilder<CommandSourceStack> loadoutNode() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("loadout")
                .requires(src -> src.hasPermission(2));
        Usage.on(node, "/magicnpcs loadout needs a subcommand:", List.of(
                new Usage.Line("/magicnpcs loadout entity <targets>",
                        "what a live mob resolved to, and what it is running"),
                new Usage.Line("/magicnpcs loadout id <entity_type>",
                        "every loadout declared for an entity type")));

        LiteralArgumentBuilder<CommandSourceStack> entity = Commands.literal("entity");
        Usage.on(entity, "/magicnpcs loadout entity needs a target selector:", List.of(
                new Usage.Line("/magicnpcs loadout entity <targets>", "one or more live mobs")));
        entity.then(Commands.argument("targets", EntityArgument.entities())
                .executes(LoadoutCommand::entity));

        LiteralArgumentBuilder<CommandSourceStack> byId = Commands.literal("id");
        Usage.on(byId, "/magicnpcs loadout id needs an entity type:", List.of(
                new Usage.Line("/magicnpcs loadout id <entity_type>", "e.g. minecraft:skeleton")));
        byId.then(Commands.argument("entity_type", ResourceLocationArgument.id())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                        LoadoutManager.snapshot().keySet().stream().map(ResourceLocation::toString), b))
                .executes(LoadoutCommand::byId));

        return node.then(entity).then(byId);
    }

    /** {@code /magicnpcs validate …} */
    public static LiteralArgumentBuilder<CommandSourceStack> validateNode() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("validate")
                .requires(src -> src.hasPermission(2))
                .executes(LoadoutCommand::validateAll);

        LiteralArgumentBuilder<CommandSourceStack> resource = Commands.literal("resource");
        Usage.on(resource, "/magicnpcs validate resource needs a loadout resource id:", List.of(
                new Usage.Line("/magicnpcs validate resource <resource_id>",
                        "e.g. my_magic:skeleton — the file name without .json")));
        resource.then(Commands.argument("resource_id", ResourceLocationArgument.id())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                        LoadoutManager.catalog().records().stream()
                                .map(r -> r.resourceId().toString()), b))
                .executes(LoadoutCommand::validateResource));

        LiteralArgumentBuilder<CommandSourceStack> byType = Commands.literal("id");
        Usage.on(byType, "/magicnpcs validate id needs an entity type:", List.of(
                new Usage.Line("/magicnpcs validate id <entity_type>", "e.g. minecraft:skeleton")));
        byType.then(Commands.argument("entity_type", ResourceLocationArgument.id())
                .executes(LoadoutCommand::validateType));

        return node.then(resource).then(byType);
    }

    // --- loadout entity / id ---------------------------------------------------------------------

    /** Show the resolved loadout (and per-spell skip reasons) for each targeted mob. */
    private static int entity(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        int shown = 0;
        for (Entity e : EntityArgument.getEntities(ctx, "targets")) {
            if (!(e instanceof Mob mob)) {
                continue;
            }
            shown++;
            ResourceLocation type = EntityType.getKey(mob.getType());
            // peek(), not resolve(): this command is documented as read-only, and 0.5.0's resolve()
            // wrote entity NBT and consumed the mob's RNG when a pool had more than one member (B5).
            LoadoutResolution resolution = LoadoutManager.peek(mob);
            SpellcasterLoadout desired = resolution.loadout();
            NpcSpellAttackGoal goal = IronsSpellcasterHandler.findSpellGoal(mob);
            SpellcasterLoadout installed = goal == null ? null : goal.loadout();

            src.sendSuccess(() -> Component.literal(mob.getName().getString() + " (" + type + ")")
                    .withStyle(ChatFormatting.AQUA), false);
            describeDesiredVsInstalled(src, type, resolution, desired, installed, goal);

            SpellcasterLoadout show = installed != null ? installed : desired;
            if (show == null) {
                continue;
            }
            LivingEntity tgt = mob.getTarget();
            for (LoadoutEntry spell : show.spells()) {
                printSpellRow(src, spell, tgt, mob);
            }
        }
        if (shown == 0) {
            src.sendFailure(Component.literal("No mobs selected."));
        }
        return shown;
    }

    /**
     * Print what the mob <em>should</em> be running and what it <em>is</em> running, and say so when
     * those differ.
     *
     * <p>0.6.1 printed only the re-resolution, so after a data or context change it confidently
     * described a loadout the mob's installed goal had never seen (audit VAL-002).
     */
    private static void describeDesiredVsInstalled(CommandSourceStack src, ResourceLocation type,
                                                   LoadoutResolution resolution,
                                                   SpellcasterLoadout desired, SpellcasterLoadout installed,
                                                   NpcSpellAttackGoal goal) {
        int generation = LoadoutManager.generation();
        if (desired == null) {
            src.sendSuccess(() -> Component.literal("  Desired:   nothing — " + resolution.explain(type))
                    .withStyle(ChatFormatting.GRAY), false);
        } else {
            src.sendSuccess(() -> Component.literal("  Desired:   " + desired.source()
                            + " [" + desired.tier().label() + "]"
                            + (desired.replace() ? " [replace]" : "")
                            + "  (catalog generation " + generation + ", hash " + desired.contentHash() + ")"
                            + (resolution.pool().size() > 1
                                    ? "  — 1 of " + resolution.pool().size() + " pooled for this mob" : ""))
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        if (installed == null) {
            src.sendSuccess(() -> Component.literal("  Installed: no casting goal on this mob")
                    .withStyle(desired == null ? ChatFormatting.DARK_GRAY : ChatFormatting.RED), false);
        } else {
            src.sendSuccess(() -> Component.literal("  Installed: " + installed.source()
                            + "  (built for generation " + goal.builtForGeneration()
                            + ", hash " + installed.contentHash() + ")")
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
        boolean stale = installed != null && desired != null
                && (!installed.contentHash().equals(desired.contentHash()) || goal.isStale());
        if (stale) {
            src.sendSuccess(() -> Component.literal("  Status:    STALE — this mob predates the current "
                            + "data. Run /magicnpcs reconcile " + Usage.NEAREST)
                    .withStyle(ChatFormatting.YELLOW), false);
        } else if (installed != null && desired != null) {
            src.sendSuccess(() -> Component.literal("  Status:    current").withStyle(ChatFormatting.GREEN), false);
        } else if (installed != null) {
            src.sendSuccess(() -> Component.literal("  Status:    running a loadout that no longer resolves "
                    + "— run /magicnpcs reconcile").withStyle(ChatFormatting.YELLOW), false);
        }
        ManagedCasterState state = goal == null ? null : ManagedCasterState.peek(goal.mob());
        if (state != null && state.lastResult() != null) {
            src.sendSuccess(() -> Component.literal("  Last reconcile: " + state.lastResult().describe())
                    .withStyle(ChatFormatting.DARK_GRAY), false);
        }
    }

    /** Show every loadout declared for an entity type (post-override), without live-mob context. */
    private static int byId(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ResourceLocation type = ResourceLocationArgument.getId(ctx, "entity_type");
        List<SpellcasterLoadout> loadouts = LoadoutManager.loadoutsFor(type);
        List<LoadoutRecord> records = LoadoutManager.catalog().recordsFor(type);

        if (loadouts.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No active spellcaster loadout for " + type + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
            if (records.isEmpty()) {
                src.sendSuccess(() -> Component.literal("  No loadout file declares it either. Files must be at "
                                + "data/<namespace>/spellcasters/<name>.json inside a loaded datapack.")
                        .withStyle(ChatFormatting.DARK_GRAY), false);
            } else {
                // The case the reporter hit: a file exists, but it never made it into the runtime map.
                src.sendSuccess(() -> Component.literal("  " + records.size()
                                + " file(s) declare it but none is active:").withStyle(ChatFormatting.RED), false);
                records.forEach(r -> printRecord(src, r, true));
            }
            return 0;
        }
        src.sendSuccess(() -> Component.literal(type + ": " + loadouts.size() + " active loadout(s)"
                + (loadouts.size() > 1 ? " — pooled per-NPC by pool_weight unless one sets replace" : ""))
                .withStyle(ChatFormatting.AQUA), false);
        if (!MagicNpcsConfig.isLoadoutEnabledFor(type)) {
            src.sendSuccess(() -> Component.literal("  ! the [compat] toggle for namespace '"
                            + type.getNamespace() + "' is off, so these are inert. "
                            + "Set compat." + type.getNamespace() + " = true in config/magicnpcs-common.toml.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        if (MagicNpcsConfig.isEntityTypeDisabled(type)) {
            src.sendSuccess(() -> Component.literal("  ! " + type
                            + " is listed in general.disabledEntityTypes, so these are inert.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        for (SpellcasterLoadout loadout : loadouts) {
            src.sendSuccess(() -> Component.literal("• " + loadout.source()
                    + "  [" + loadout.tier().label() + "]"
                    + (loadout.profession() != null ? "  profession=" + loadout.profession() : "")
                    + (loadout.replace() ? "  [replace]" : "")
                    + (loadout.nativeAttack() != NativeAttackPolicy.COEXIST
                            ? "  native_attack=" + loadout.nativeAttack().jsonValue() : "")
                    + (loadout.goalPriority() != null ? "  goal_priority=" + loadout.goalPriority() : "")
                    + (loadout.casterChance() != null ? "  caster_chance=" + loadout.casterChance() : "")
                    + "  pool_weight=" + loadout.poolWeight()).withStyle(ChatFormatting.GOLD), false);
            for (LoadoutEntry spell : loadout.spells()) {
                printSpellRow(src, spell, null, null);
            }
        }
        // Anything declaring this type that is NOT active is exactly what a confused author needs.
        records.stream().filter(r -> r.status() != LoadoutRecord.Status.ACTIVE)
                .forEach(r -> printRecord(src, r, true));
        return loadouts.size();
    }

    // --- validate --------------------------------------------------------------------------------

    /** Report the whole catalog: counts, then every record that has something to say. */
    private static int validateAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        LoadoutCatalog catalog = LoadoutManager.catalog();
        LoadoutCatalog.Counts counts = catalog.counts();

        src.sendSuccess(() -> Component.literal("Magic NPCs validation (catalog generation "
                + catalog.generation() + ")").withStyle(ChatFormatting.AQUA), false);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "Discovered: %d  Parsed: %d  Active: %d  Shadowed: %d  Suppressed: %d  Rejected: %d",
                        counts.discovered(), counts.parsed(), counts.active(), counts.shadowed(),
                        counts.suppressed(), counts.rejected()))
                .withStyle(counts.rejected() > 0 ? ChatFormatting.RED : ChatFormatting.WHITE), false);

        int errors = 0;
        int warnings = 0;
        for (LoadoutRecord record : catalog.records()) {
            printRecord(src, record, false);
            for (LoadoutProblem p : record.problems()) {
                if (p.severity() == LoadoutProblem.Severity.ERROR) {
                    errors++;
                } else if (p.severity() == LoadoutProblem.Severity.WARNING) {
                    warnings++;
                }
            }
        }
        // Spell-level checks need Iron's, so they are not part of the parse record.
        int spellIssues = validateSpells(src, catalog);
        errors += spellIssues;

        final int totalErrors = errors;
        final int totalWarnings = warnings;
        if (totalErrors == 0 && totalWarnings == 0) {
            src.sendSuccess(() -> Component.literal("Result: OK — no problems in "
                    + counts.discovered() + " discovered loadout file(s).").withStyle(ChatFormatting.GREEN), false);
        } else {
            src.sendSuccess(() -> Component.literal("Result: " + (totalErrors > 0 ? "FAILED" : "WARNINGS")
                            + " (" + totalErrors + " error(s), " + totalWarnings + " warning(s))")
                    .withStyle(totalErrors > 0 ? ChatFormatting.RED : ChatFormatting.GOLD), false);
        }
        printScopeCaveat(src);
        return totalErrors;
    }

    /**
     * State plainly what validation cannot see.
     *
     * <p>The 0.6.1 success message read "no issues found across N entity types", which a reasonable
     * person takes to mean "your pack is fine". It could not mean that: a file in the wrong folder is
     * never handed to the loader at all, and nothing about a live mob is checked here.
     */
    private static void printScopeCaveat(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal("This checks loadout FILES, not live mobs.")
                .withStyle(ChatFormatting.GRAY), false);
        src.sendSuccess(() -> Component.literal("  • A file outside data/<namespace>/spellcasters/ is never "
                        + "handed to Magic NPCs and cannot appear above at all — check the folder name "
                        + "(spellcasters, plural) and that it is directly under data/<namespace>/.")
                .withStyle(ChatFormatting.DARK_GRAY), false);
        src.sendSuccess(() -> Component.literal("  • Whether a particular mob is casting is a separate "
                        + "question: /magicnpcs why " + Usage.NEAREST)
                .withStyle(ChatFormatting.DARK_GRAY), false);
    }

    /** One focused file. */
    private static int validateResource(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "resource_id");
        LoadoutRecord record = LoadoutManager.catalog().record(id);
        if (record == null) {
            src.sendFailure(Component.literal("No loadout resource '" + id + "' was discovered. "
                    + "The id is the file path without .json, e.g. my_magic:skeleton for "
                    + "data/my_magic/spellcasters/skeleton.json."));
            return 0;
        }
        printRecord(src, record, true);
        return record.hasErrors() ? 0 : 1;
    }

    /** Every file targeting one entity type, whatever its status. */
    private static int validateType(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ResourceLocation type = ResourceLocationArgument.getId(ctx, "entity_type");
        List<LoadoutRecord> records = LoadoutManager.catalog().recordsFor(type);
        if (records.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No discovered loadout file declares " + type + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
            printScopeCaveat(src);
            return 0;
        }
        src.sendSuccess(() -> Component.literal(type + ": " + records.size() + " loadout file(s)")
                .withStyle(ChatFormatting.AQUA), false);
        records.forEach(r -> printRecord(src, r, true));
        return records.size();
    }

    /**
     * Print one record's status and problems.
     *
     * @param always print even when the record is clean and active
     * @return the number of problem lines printed
     */
    private static int printRecord(CommandSourceStack src, LoadoutRecord record, boolean always) {
        boolean interesting = always || !record.problems().isEmpty()
                || record.status() == LoadoutRecord.Status.REJECTED;
        if (!interesting) {
            return 0;
        }
        ChatFormatting colour = switch (record.status()) {
            case ACTIVE -> ChatFormatting.GREEN;
            case SHADOWED -> ChatFormatting.GRAY;
            case SUPPRESSED -> ChatFormatting.DARK_GRAY;
            case REJECTED -> ChatFormatting.RED;
        };
        src.sendSuccess(() -> Component.literal(record.status() + "  " + record.describeSource()
                        + (record.entityType() == null ? "" : "  → " + record.effectiveKey()))
                .withStyle(colour), false);
        int printed = 0;
        for (LoadoutProblem p : record.problems()) {
            if (printed >= MAX_PROBLEM_ROWS) {
                src.sendSuccess(() -> Component.literal("      … more problems; see latest.log")
                        .withStyle(ChatFormatting.DARK_GRAY), false);
                break;
            }
            printed++;
            src.sendSuccess(() -> Component.literal("      " + p.describe())
                    .withStyle(switch (p.severity()) {
                        case ERROR -> ChatFormatting.RED;
                        case WARNING -> ChatFormatting.YELLOW;
                        case INFO -> ChatFormatting.DARK_GRAY;
                    }), false);
        }
        return printed;
    }

    /** Iron's-side checks over the active loadouts: unknown/disabled/unsupported spells and ranges. */
    private static int validateSpells(CommandSourceStack src, LoadoutCatalog catalog) {
        int issues = 0;
        for (LoadoutRecord record : catalog.records()) {
            if (record.status() != LoadoutRecord.Status.ACTIVE || record.loadout() == null) {
                continue;
            }
            for (LoadoutEntry spell : record.loadout().spells()) {
                SpellDiagnostic d = IronsBridge.diagnose(spell.spell().toString());
                String problem = spellProblem(d, spell);
                if (problem == null) {
                    continue;
                }
                issues++;
                boolean fatal = !d.exists() || !d.enabled() || !d.willCast();
                src.sendSuccess(() -> Component.literal((fatal ? "ERROR " : "WARN  ")
                                + record.resourceId() + " (pack " + record.packId() + ") /spells: "
                                + spell.spell() + " — " + problem)
                        .withStyle(fatal ? ChatFormatting.RED : ChatFormatting.YELLOW), false);
            }
        }
        return issues;
    }

    /** @return a short problem string for a loadout spell, or {@code null} if it looks fine. */
    private static String spellProblem(SpellDiagnostic d, LoadoutEntry spell) {
        if (!d.exists()) {
            return "unknown spell id (run /magicnpcs spells)"
                    + (spell.spell().getNamespace().equals("minecraft")
                            ? " — did you mean irons_spellbooks:" + spell.spell().getPath() + "?" : "");
        }
        if (!d.enabled()) {
            return "spell is disabled in Iron's config";
        }
        if (!d.willCast()) {
            return (d.unverified() ? "UNVERIFIED for mob casting: " : "not castable by a mob: ")
                    + d.unsupportedReason();
        }
        if (d.requiresTarget() && spell.role() == LoadoutEntry.Role.SUPPORT) {
            return "needs a target but is a SUPPORT (self-cast) spell — set role=attack";
        }
        if (spell.minRange() > spell.maxRange()) {
            return String.format(Locale.ROOT,
                    "min_range=%.1f is greater than max_range=%.1f — no distance can ever satisfy both, "
                            + "so this spell is never selectable",
                    spell.minRange(), spell.maxRange());
        }
        if ("GROUND_AOE_FORWARD".equals(d.category()) && spell.maxRange() > 8.0) {
            return String.format(Locale.ROOT, "forward ground-AoE with max_range=%.1f — recommend ≤5.0 "
                    + "(it lands in front of the caster, not at the target)", spell.maxRange());
        }
        return null;
    }

    // --- shared rows -----------------------------------------------------------------------------

    /** Print one spell row: the loadout fields, then its resolved Iron's diagnostic + any skip reason. */
    private static void printSpellRow(CommandSourceStack src, LoadoutEntry spell, LivingEntity target, Mob mob) {
        SpellDiagnostic d = IronsBridge.diagnose(spell.spell().toString());
        int cooldown = CooldownResolver.resolve(spell.cooldownTicks(), spell.cooldownMultiplier(),
                MagicNpcsConfig.COOLDOWN_MULTIPLIER.get(), d.baseCooldownTicks(),
                MagicNpcsConfig.MIN_COOLDOWN_TICKS.get());
        String head = String.format(Locale.ROOT, "  %s  lvl%d · %s · w%d · %s · safety %.1f%s",
                spell.spell(), spell.level(), spell.role().name().toLowerCase(Locale.ROOT), spell.weight(),
                describeRange(spell), spell.safetyRadius(),
                spell.requireHeldItem() ? " · needs-held-item" : "");
        String detail = String.format(Locale.ROOT, "      %s · %s · %s · cd %dt%s%s",
                d.exists() ? d.category().toLowerCase(Locale.ROOT) : "UNKNOWN",
                d.castType().toLowerCase(Locale.ROOT), d.support().toLowerCase(Locale.ROOT), cooldown,
                d.exists() && !d.enabled() ? " · DISABLED" : "",
                d.requiresTarget() ? " · needs-target" : "");
        String skip = liveSkipReason(d, spell, target, mob);

        ChatFormatting color = !d.exists() || !d.enabled() || !d.willCast()
                ? ChatFormatting.RED : (skip != null ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
        src.sendSuccess(() -> Component.literal(head).withStyle(color), false);
        src.sendSuccess(() -> Component.literal(detail + (skip != null ? " · skip: " + skip : ""))
                .withStyle(ChatFormatting.DARK_GRAY), false);
    }

    /** Best-effort "why wouldn't this cast right now" for a live mob (vanilla-side checks only). */
    private static String liveSkipReason(SpellDiagnostic d, LoadoutEntry spell, LivingEntity target, Mob mob) {
        if (!d.exists()) {
            return "unknown id";
        }
        if (!d.enabled()) {
            return "disabled";
        }
        if (!d.willCast()) {
            return d.unverified() ? "unverified for mob casting" : "unsupported for mobs";
        }
        if (mob == null) {
            return null; // id-mode: no live context
        }
        if (d.requiresTarget() && target == null) {
            return "needs a target but the mob has none";
        }
        if (spell.role() == LoadoutEntry.Role.ATTACK && target != null) {
            double dist = Math.sqrt(mob.distanceToSqr(target));
            if (dist < spell.minRange()) {
                return String.format(Locale.ROOT, "target too close (%.1f < min %.1f)", dist, spell.minRange());
            }
            if (dist > spell.maxRange()) {
                return String.format(Locale.ROOT, "target out of range (%.1f > max %.1f)", dist, spell.maxRange());
            }
        }
        return null;
    }

    /**
     * A range window for display. SUPPORT spells are self-cast and the goal never range-checks them,
     * so the synthesized {@code 0.0} bounds printed as "range 0-0" and read like a misconfiguration.
     */
    private static String describeRange(LoadoutEntry entry) {
        if (entry.role() == LoadoutEntry.Role.SUPPORT) {
            return "self-cast";
        }
        return String.format(Locale.ROOT, "range %.0f-%.0f", entry.minRange(), entry.maxRange());
    }
}
