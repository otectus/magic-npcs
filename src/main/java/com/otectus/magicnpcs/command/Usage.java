package com.otectus.magicnpcs.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Usage printing for the command tree's intermediate nodes.
 *
 * <p>The public project page advertised {@code /magicnpcs loadout}, {@code /magicnpcs school} and
 * {@code /magicnpcs config} as if they were commands. The first two are headings — they need a
 * subcommand and arguments — and the third was not registered at all, so a player copying the page got
 * Brigadier's bare red "Unknown or incomplete command" from most of it while {@code /magicnpcs
 * validate}, the one complete line, worked. That asymmetry is the reported bug (audit CMD-001).
 *
 * <p>Every intermediate literal now executes: it prints the forms below it, with copy-paste selector
 * examples, and returns a normal command result. Nothing in the documented tree terminates in a syntax
 * error any more.
 */
public final class Usage {

    /** A selector that resolves to the nearest single mob, used in every example. */
    public static final String NEAREST = "@e[type=minecraft:skeleton,sort=nearest,limit=1]";

    private Usage() {}

    /** One usage line: the command form, then what it does. */
    public record Line(String form, String description) {}

    /**
     * Attach a usage handler to an intermediate literal.
     *
     * @param heading what this branch is for, in one line
     * @param lines   the executable forms below it — full command lines, never bare roots
     */
    public static <T extends LiteralArgumentBuilder<CommandSourceStack>> T on(T node, String heading,
                                                                             List<Line> lines) {
        node.executes(ctx -> {
            print(ctx.getSource(), heading, lines);
            return Command.SINGLE_SUCCESS;
        });
        return node;
    }

    /** Print a usage block. Angle brackets are placeholders and are labelled as such. */
    public static void print(CommandSourceStack src, String heading, List<Line> lines) {
        src.sendSuccess(() -> Component.literal(heading).withStyle(ChatFormatting.GOLD), false);
        for (Line line : lines) {
            src.sendSuccess(() -> Component.literal("  " + line.form()).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("  " + line.description()).withStyle(ChatFormatting.GRAY)), false);
        }
        src.sendSuccess(() -> Component.literal("  <angle brackets> are placeholders — replace them, "
                        + "do not type the brackets.").withStyle(ChatFormatting.DARK_GRAY), false);
    }

    /** A ready-to-paste example line, shown under a usage block that needs a selector. */
    public static void example(CommandSourceStack src, String command) {
        src.sendSuccess(() -> Component.literal("  example: ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(command).withStyle(ChatFormatting.AQUA)), false);
    }
}
