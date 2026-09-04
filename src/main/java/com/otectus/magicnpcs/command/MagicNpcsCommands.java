package com.otectus.magicnpcs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * The single place the {@code /magicnpcs} command tree is built.
 *
 * <p>Through 0.6.1 five classes each called {@code dispatcher.register(Commands.literal("magicnpcs")…)}
 * with their own branch. Brigadier merges same-named roots, so it worked — but nothing owned the tree,
 * no one place listed what existed, and the help text was written by hand against a shape nobody could
 * see in one file. That is how the published help came to describe {@code /magicnpcs school
 * set|reroll|clear <targets> [school]} as one form when only {@code set} takes a school, and how
 * {@code /magicnpcs config} came to be advertised while not being registered at all (audit CMD-001,
 * CMD-002, CMD-003).
 *
 * <p>Registering once also means every intermediate literal can be given a usage handler in one pass,
 * so no documented node terminates in Brigadier's bare syntax error.
 */
public final class MagicNpcsCommands {

    private MagicNpcsCommands() {}

    /** Build and register the whole tree. Called from the Iron's-gated handler on RegisterCommands. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("magicnpcs")
                .requires(src -> src.hasPermission(0)) // the index itself is harmless; children gate themselves
                .executes(HelpCommand::index);

        root.then(HelpCommand.node());
        root.then(WhyCommand.node());
        root.then(AuditCommand.node());
        root.then(LoadoutCommand.loadoutNode());
        root.then(LoadoutCommand.validateNode());
        root.then(SpellListCommand.node());
        root.then(ConfigCommand.node());
        root.then(ReconcileCommand.node());
        // The school toggle is about stopping players *changing* schools. `school pool` only reads, and
        // the docs point at it as the first stop for "why can this school never be assigned", so
        // disabling the command must not take the diagnostic with it.
        //
        // RegisterCommandsEvent can fire before the server config is loaded (notably in the gametest
        // dev runtime), where reading a config value throws. Default to the full tree when the config
        // is not loaded yet — the option defaults to enabled anyway.
        boolean full = !MagicNpcsConfig.SPEC.isLoaded() || MagicNpcsConfig.SCHOOLS_COMMAND_ENABLED.get();
        root.then(full ? SchoolCommand.node() : SchoolCommand.poolOnlyNode());

        dispatcher.register(root);
    }
}
