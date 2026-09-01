package com.otectus.magicnpcs.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.otectus.magicnpcs.core.diag.DiagnosticReport;
import com.otectus.magicnpcs.integration.irons.CasterDiagnostics;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * {@code /magicnpcs why <targets>} — explain, for a live mob, exactly why it is or is not casting right
 * now: goal injection, the full goal environment (priority | class | flags | running) with the blocking
 * goal named, the state gates, target and line of sight, mana, and a per-spell table with the first
 * blocker for each entry.
 *
 * <p>Op-only and genuinely read-only — the underlying resolution writes no entity NBT and draws nothing
 * from the mob's RNG, so running a diagnostic cannot change the world it is describing.
 *
 * <p>Iron's-free: {@link CasterDiagnostics} produces plain {@link DiagnosticReport} lines and this class
 * only colours them. Registered only when Iron's is present.
 */
public final class WhyCommand {
    /** Guard against a wildcard selector spamming a player's chat with thousands of lines. */
    private static final int MAX_TARGETS = 5;

    private WhyCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("why")
                .requires(src -> src.hasPermission(2));
        // Bare `/magicnpcs why` used to be a syntax error. It is the command support most often asks
        // people to run, so it explains itself instead.
        node.executes(ctx -> {
            Usage.print(ctx.getSource(), "/magicnpcs why needs a target selector:", java.util.List.of(
                    new Usage.Line("/magicnpcs why <targets>",
                            "explain why these live mobs are or are not casting (up to "
                                    + MAX_TARGETS + " shown)")));
            Usage.example(ctx.getSource(), "/magicnpcs why " + Usage.NEAREST);
            return com.mojang.brigadier.Command.SINGLE_SUCCESS;
        });
        return node.then(Commands.argument("targets", EntityArgument.entities())
                .executes(WhyCommand::why));
    }

    private static int why(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        int shown = 0;
        for (Entity entity : EntityArgument.getEntities(ctx, "targets")) {
            if (!(entity instanceof Mob mob)) {
                continue;
            }
            if (shown >= MAX_TARGETS) {
                src.sendSuccess(() -> Component.literal("… more targets matched; showing the first "
                        + MAX_TARGETS + ".").withStyle(ChatFormatting.DARK_GRAY), false);
                break;
            }
            shown++;
            print(src, CasterDiagnostics.describe(mob));
        }
        if (shown == 0) {
            src.sendFailure(Component.literal("No mobs selected."));
        }
        return shown;
    }

    private static void print(CommandSourceStack src, DiagnosticReport report) {
        for (DiagnosticReport.Line line : report.lines()) {
            src.sendSuccess(() -> Component.literal(line.text()).withStyle(colour(line.level())), false);
        }
    }

    private static ChatFormatting colour(DiagnosticReport.Level level) {
        return switch (level) {
            case HEADER -> ChatFormatting.AQUA;
            case GOOD -> ChatFormatting.GREEN;
            case WARN -> ChatFormatting.YELLOW;
            case BAD -> ChatFormatting.RED;
            case DETAIL -> ChatFormatting.DARK_GRAY;
            case INFO -> ChatFormatting.GRAY;
        };
    }
}
