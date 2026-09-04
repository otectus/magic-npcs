package com.otectus.magicnpcs.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.otectus.magicnpcs.integration.irons.IronsBridge;
import com.otectus.magicnpcs.integration.irons.SpellAuditRun;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * {@code /magicnpcs audit spells [namespace] [cast]} — run every registered spell past a pair of
 * disposable dummies and write down what happened.
 *
 * <p>With a shelf of Iron's add-ons installed, "is this spell supported" is answered by a manifest, a
 * trusted namespace, or nothing at all — all of them claims, none of them evidence. This command is the
 * evidence: RESOLVE mode proves each spell resolves, inspects and passes Iron's pre-cast check; the
 * opt-in {@code cast} literal additionally runs the real cast lifecycle on the dummy. The second one
 * has real, permanent side effects, so it must be asked for by name and it warns before it starts.
 *
 * <p>Op-only, one run per server, and everything it needs lives in
 * {@link SpellAuditRun}; this class only parses, warns and reports. Registered only when Iron's is
 * present, like the rest of the tree.
 */
public final class AuditCommand {

    /** Namespaces that have at least one registered spell, for the tab-completion of the filter. */
    private static final SuggestionProvider<CommandSourceStack> NAMESPACES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(namespaces(), builder);

    private AuditCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        LiteralArgumentBuilder<CommandSourceStack> spells = Commands.literal("spells")
                .executes(ctx -> start(ctx, null, false));
        spells.then(Commands.literal("cast").executes(ctx -> start(ctx, null, true)));
        spells.then(Commands.argument("namespace", StringArgumentType.word())
                .suggests(NAMESPACES)
                .executes(ctx -> start(ctx, StringArgumentType.getString(ctx, "namespace"), false))
                .then(Commands.literal("cast").executes(ctx ->
                        start(ctx, StringArgumentType.getString(ctx, "namespace"), true))));

        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("audit")
                .requires(src -> src.hasPermission(2));
        Usage.on(node, "/magicnpcs audit exercises every registered spell and writes a report:",
                List.of(
                        new Usage.Line("/magicnpcs audit spells [<namespace>]",
                                "resolve and pre-cast check every spell (no effects run)"),
                        new Usage.Line("/magicnpcs audit spells [<namespace>] cast",
                                "additionally cast every spell for real — disposable worlds only"),
                        new Usage.Line("/magicnpcs audit status", "how far the running audit has got"),
                        new Usage.Line("/magicnpcs audit cancel",
                                "stop the running audit and write what it has so far")));
        node.then(spells);
        node.then(Commands.literal("status").executes(AuditCommand::status));
        node.then(Commands.literal("cancel").executes(AuditCommand::cancel));
        return node;
    }

    private static int start(CommandContext<CommandSourceStack> ctx, String namespace, boolean cast) {
        CommandSourceStack src = ctx.getSource();
        if (SpellAuditRun.active().isPresent()) {
            src.sendFailure(Component.literal("An audit is already running ("
                    + SpellAuditRun.active().get().progress()
                    + "). Run /magicnpcs audit cancel first."));
            return 0;
        }
        ServerLevel level = src.getLevel();
        BlockPos origin = BlockPos.containing(src.getPosition());
        if (cast) {
            src.sendSuccess(() -> Component.literal("CAST mode runs every spell for real: summons, "
                    + "projectiles, and block changes will happen. Use a disposable world.")
                    .withStyle(ChatFormatting.RED), true);
        }
        Optional<SpellAuditRun> run = SpellAuditRun.start(level, origin, namespace, cast, src);
        if (run.isEmpty()) {
            src.sendFailure(Component.literal(namespace == null
                    ? "Could not start the audit: no spells to audit, or the dummies could not spawn."
                    : "Could not start the audit: no registered spells in namespace '" + namespace
                            + "', or the dummies could not spawn."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Audit started in " + run.get().mode() + " mode"
                + (namespace == null ? "" : " for namespace " + namespace)
                + ". Progress is reported here; the report path is printed when it finishes.")
                .withStyle(ChatFormatting.GREEN), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<SpellAuditRun> run = SpellAuditRun.active();
        if (run.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No audit is running.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        SpellAuditRun active = run.get();
        src.sendSuccess(() -> Component.literal("Audit " + active.progress() + " in "
                + active.mode() + " mode"
                + (active.namespaceFilter() == null ? "" : " for " + active.namespaceFilter()) + ".")
                .withStyle(ChatFormatting.AQUA), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Optional<SpellAuditRun> run = SpellAuditRun.active();
        if (run.isEmpty()) {
            src.sendFailure(Component.literal("No audit is running."));
            return 0;
        }
        run.get().cancel();
        return Command.SINGLE_SUCCESS;
    }

    /** Every namespace that registers a spell, sorted — the only values the filter can usefully take. */
    private static List<String> namespaces() {
        TreeSet<String> out = new TreeSet<>();
        IronsBridge.pathIndex().values().forEach(out::addAll);
        return List.copyOf(out);
    }
}
