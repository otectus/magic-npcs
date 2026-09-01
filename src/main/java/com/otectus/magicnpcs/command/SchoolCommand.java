package com.otectus.magicnpcs.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolAssignResult;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.SchoolReroll;
import com.otectus.magicnpcs.core.diag.SchoolPoolReport;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.loadout.LoadoutResolution;
import com.otectus.magicnpcs.integration.irons.IronsSpellcasterHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /magicnpcs school <set|info|reroll|clear|pool>} — manually (re)assign the magic school of
 * targeted NPCs, and inspect what a school's generated spell pool actually contains.
 *
 * <p>Registered only when Iron's is present (delegated from the Iron's-gated
 * {@link IronsSpellcasterHandler}); this class touches no Iron's type directly, only our own seam, so
 * the "Iron's imports stay in integration.irons" invariant holds.
 *
 * <p>Since 0.6.0 every outcome is reported with a reason. {@code reroll} walks the whole allowed pool
 * rather than trying one random school and silently giving up, and {@code pool} explains exactly which
 * filter removed each spell — the two halves of the "re-rolled schools for 0 NPCs" report (W4).
 */
public final class SchoolCommand {
    /** Cap the per-spell drop list so one command can't flood chat with a whole registry. */
    private static final int MAX_DROP_ROWS = 20;

    private SchoolCommand() {}

    /** Usage lines for the {@code school} branch — one executable form per line, never a bare root. */
    private static final List<Usage.Line> USAGE = List.of(
            new Usage.Line("/magicnpcs school info <targets>", "what each NPC is set to, and what is driving it"),
            new Usage.Line("/magicnpcs school set <targets> <school>", "assign by hand; overrides any loadout"),
            new Usage.Line("/magicnpcs school reroll <targets>", "pick a different school"),
            new Usage.Line("/magicnpcs school clear <targets>", "stop these NPCs casting, permanently"),
            new Usage.Line("/magicnpcs school auto <targets>", "undo a manual assignment; back to automatic"),
            new Usage.Line("/magicnpcs school pool [school]", "what a school's generated pool contains"));

    /** The full {@code /magicnpcs school …} branch. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("school")
                .requires(src -> src.hasPermission(MagicNpcsConfig.SCHOOLS_COMMAND_PERMISSION.get()));
        Usage.on(node, "/magicnpcs school needs a subcommand:", USAGE);

        node.then(withTargets(Commands.literal("set"),
                        "/magicnpcs school set needs targets and a school:",
                        "/magicnpcs school set <targets> <school>")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("school", StringArgumentType.string())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        MagicNpcsConfig.allowedSchoolIds().stream()
                                                .map(ResourceLocation::toString), b))
                                .executes(SchoolCommand::set))));
        node.then(simpleTargetNode("info", SchoolCommand::info));
        node.then(simpleTargetNode("reroll", SchoolCommand::reroll));
        node.then(simpleTargetNode("clear", SchoolCommand::clear));
        node.then(simpleTargetNode("auto", SchoolCommand::auto));
        node.then(poolNode());
        return node;
    }

    /**
     * The read-only {@code pool} branch on its own, for when {@code schools.control.commandEnabled} is
     * off. That toggle is about stopping players <em>changing</em> schools; {@code pool} only reads,
     * and the docs point at it as the first stop for "why can this school never be assigned", so
     * disabling the command must not take the diagnostic with it.
     */
    public static LiteralArgumentBuilder<CommandSourceStack> poolOnlyNode() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("school")
                .requires(src -> src.hasPermission(MagicNpcsConfig.SCHOOLS_COMMAND_PERMISSION.get()));
        Usage.on(node, "/magicnpcs school is read-only here (schools.control.commandEnabled is false):",
                List.of(USAGE.get(USAGE.size() - 1)));
        return node.then(poolNode());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> poolNode() {
        return Commands.literal("pool")
                .executes(ctx -> pool(ctx, null))
                .then(Commands.argument("school", StringArgumentType.string())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                MagicNpcsConfig.allowedSchoolIds().stream()
                                        .map(ResourceLocation::toString), b))
                        .executes(SchoolCommand::poolForNamedSchool));
    }

    /** A {@code <subcommand> <targets>} branch whose bare form prints usage instead of a syntax error. */
    private static LiteralArgumentBuilder<CommandSourceStack> simpleTargetNode(
            String name, com.mojang.brigadier.Command<CommandSourceStack> action) {
        LiteralArgumentBuilder<CommandSourceStack> node = withTargets(Commands.literal(name),
                "/magicnpcs school " + name + " needs a target selector:",
                "/magicnpcs school " + name + " <targets>");
        return node.then(Commands.argument("targets", EntityArgument.entities()).executes(action));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> withTargets(
            LiteralArgumentBuilder<CommandSourceStack> node, String heading, String form) {
        node.executes(ctx -> {
            Usage.print(ctx.getSource(), heading, List.of(new Usage.Line(form, "one or more live mobs")));
            Usage.example(ctx.getSource(), form.replace("<targets>", Usage.NEAREST)
                    .replace("<school>", "irons_spellbooks:fire"));
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        });
        return node;
    }

    private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ResourceLocation school = ResourceLocation.tryParse(StringArgumentType.getString(ctx, "school"));
        if (school == null) {
            src.sendFailure(Component.literal("Invalid school id."));
            return 0;
        }
        int assigned = 0;
        for (Mob mob : mobs(ctx)) {
            SchoolAssignResult result = IronsSpellcasterHandler.applySchool(mob, school);
            if (result.ok()) {
                assigned++;
            } else {
                // Naming the mob AND the reason is the whole point: "0 NPCs" with no explanation is
                // what sent a pack author hunting through configs for a problem that wasn't theirs.
                src.sendSuccess(() -> Component.literal(mob.getName().getString() + ": "
                        + result.describe(school)).withStyle(ChatFormatting.YELLOW), false);
            }
        }
        final int count = assigned;
        src.sendSuccess(() -> Component.literal("Assigned " + school + " to " + count + " NPC(s).")
                .withStyle(count > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return count;
    }

    private static int reroll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        List<ResourceLocation> allowed = MagicNpcsConfig.allowedSchoolIds();
        if (allowed.isEmpty()) {
            src.sendFailure(Component.literal(
                    "No schools are allowed — schools.allowedSchools in magicnpcs-server.toml is empty."));
            return 0;
        }
        int assigned = 0;
        for (Mob mob : mobs(ctx)) {
            SchoolReroll.Outcome outcome = SchoolReroll.reroll(allowed, SchoolData.getSchool(mob),
                    mob.getRandom(), school -> IronsSpellcasterHandler.applySchool(mob, school));
            if (outcome.ok()) {
                assigned++;
                src.sendSuccess(() -> Component.literal(mob.getName().getString() + " -> "
                        + outcome.assigned()).withStyle(ChatFormatting.GREEN), false);
            } else {
                src.sendSuccess(() -> Component.literal(mob.getName().getString()
                        + ": no allowed school could be assigned. Tried: " + outcome.describeFailures())
                        .withStyle(ChatFormatting.RED), false);
            }
        }
        final int count = assigned;
        src.sendSuccess(() -> Component.literal("Re-rolled schools for " + count + " NPC(s).")
                .withStyle(count > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return count;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int n = 0;
        for (Mob mob : mobs(ctx)) {
            IronsSpellcasterHandler.clearSchool(mob);
            n++;
        }
        final int count = n;
        ctx.getSource().sendSuccess(() -> Component.literal("Cleared schools on " + count + " NPC(s)."), true);
        return count;
    }

    /**
     * Return NPCs to automatic assignment.
     *
     * <p>0.6.1 had no way back from a manual assignment: {@code set} and {@code clear} both stamped the
     * "a player chose this" flag, and nothing removed it, so "I tomed that villager by mistake" was
     * unrecoverable short of killing it.
     */
    private static int auto(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int n = 0;
        for (Mob mob : mobs(ctx)) {
            IronsSpellcasterHandler.resetSchoolToAuto(mob);
            n++;
        }
        final int count = n;
        ctx.getSource().sendSuccess(() -> Component.literal("Returned " + count
                + " NPC(s) to automatic assignment; their datapack loadout or spawn roll applies again."), true);
        return count;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        List<Mob> mobs = mobs(ctx);
        if (mobs.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No mobs selected."));
            return 0;
        }
        for (Mob mob : mobs) {
            ResourceLocation school = SchoolData.getSchool(mob);
            String label = school != null ? school.toString()
                    : (SchoolData.hasRolled(mob) ? "none (rolled a non-caster)" : "unassigned (never rolled)");
            // Report what is actually driving the mob, not only what is stored. An explicit datapack
            // loadout outranks an automatically assigned school, so this used to name a school the mob
            // was demonstrably not using — the diagnostic and the behaviour disagreed with each other.
            String effective = effectiveSource(mob, school);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    mob.getName().getString() + ": " + label + effective), false);
        }
        return mobs.size();
    }

    /** " — casting <source>" describing which of the three sources actually wins for this mob. */
    private static String effectiveSource(Mob mob, ResourceLocation school) {
        switch (SchoolData.mode(mob)) {
            case MANUAL_SCHOOL -> {
                // A manual school installs that school or nothing at all. It never falls back to a
                // datapack loadout, which is what 0.6.1 did whenever the school happened to yield
                // nothing today — silently replacing the player's choice with a pack's.
                return IronsSpellcasterHandler.schoolIsUsable(mob, school)
                        ? " — set by hand [MANUAL_SCHOOL], overrides any loadout"
                        : " — set by hand [MANUAL_SCHOOL] but currently yields no castable spells, so this "
                                + "NPC is not casting; run /magicnpcs school pool " + school;
            }
            case MANUAL_DISABLED -> {
                return " — cleared by hand [MANUAL_DISABLED]; /magicnpcs school auto undoes this";
            }
            default -> { /* AUTO — fall through */ }
        }
        LoadoutResolution resolution = LoadoutManager.peek(mob);
        if (resolution.isPresent()) {
            return " — casting datapack loadout " + resolution.loadout().source()
                    + (school != null ? " (which outranks the school above)" : "");
        }
        return school != null ? " — casting this school" : "";
    }

    /** Parse the school argument first, so a typo reports "invalid id" instead of listing everything. */
    private static int poolForNamedSchool(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "school");
        ResourceLocation school = ResourceLocation.tryParse(raw);
        if (school == null) {
            ctx.getSource().sendFailure(Component.literal("'" + raw + "' is not a valid resource id."));
            return 0;
        }
        return pool(ctx, school);
    }

    /**
     * {@code /magicnpcs school pool [school]} — per-school survivor counts and, for a named school, the
     * reason every dropped spell was dropped. The direct answer to "is there something I'm missing in
     * the configs?"
     */
    private static int pool(CommandContext<CommandSourceStack> ctx, ResourceLocation only) {
        CommandSourceStack src = ctx.getSource();
        if (!MagicNpcsConfig.SCHOOLS_ENABLED.get()) {
            src.sendFailure(Component.literal(
                    "Magic schools are disabled (schools.enableSchools = false)."));
            return 0;
        }
        List<SchoolPoolReport> reports = IronsSpellcasterHandler.schoolPools(only);
        if (reports.isEmpty()) {
            src.sendFailure(Component.literal(only == null
                    ? "No allowed school resolves to a registered Iron's school."
                    : "'" + only + "' is not a registered Iron's school in this install."));
            return 0;
        }
        for (SchoolPoolReport report : reports) {
            src.sendSuccess(() -> Component.literal(report.summary())
                    .withStyle(report.isEmpty() ? ChatFormatting.RED : ChatFormatting.AQUA), false);
            for (SchoolPoolReport.Survivor s : report.survivors()) {
                src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                                "  %s  lvl%d · %s · %s · weight %d",
                                s.spellId(), s.level(), s.rarity().toLowerCase(Locale.ROOT), s.role(), s.weight()))
                        .withStyle(ChatFormatting.GREEN), false);
            }
            if (only == null) {
                continue; // the whole-pool view stays to one line per school
            }
            int shown = 0;
            for (SchoolPoolReport.Drop d : report.drops()) {
                if (shown++ >= MAX_DROP_ROWS) {
                    int remaining = report.drops().size() - MAX_DROP_ROWS;
                    src.sendSuccess(() -> Component.literal("  … and " + remaining + " more dropped spell(s)")
                            .withStyle(ChatFormatting.DARK_GRAY), false);
                    break;
                }
                src.sendSuccess(() -> Component.literal("  " + d.spellId() + " — " + d.reason())
                        .withStyle(ChatFormatting.GRAY), false);
            }
        }
        return reports.size();
    }

    private static List<Mob> mobs(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<? extends Entity> entities = EntityArgument.getEntities(ctx, "targets");
        List<Mob> out = new ArrayList<>();
        for (Entity e : entities) {
            if (e instanceof Mob mob) {
                out.add(mob);
            }
        }
        return out;
    }
}
