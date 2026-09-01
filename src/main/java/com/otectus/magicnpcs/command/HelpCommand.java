package com.otectus.magicnpcs.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.otectus.magicnpcs.compat.IronsCompat;
import com.otectus.magicnpcs.compat.RecruitsCompat;
import com.otectus.magicnpcs.core.loadout.LoadoutCatalog;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /magicnpcs} and {@code /magicnpcs help} — the command index.
 *
 * <p>Every line printed here is a <b>complete, executable command</b>. 0.6.1's index printed
 * {@code /magicnpcs school set|reroll|clear <targets> [school]} as one combined form, which is not a
 * command the parser accepts and which implies {@code reroll} and {@code clear} take a school; and it
 * described {@code /why} as "stopping at the first blocker" when the implementation prints several
 * sections (audit CMD-003).
 */
public final class HelpCommand {

    private HelpCommand() {}

    /** {@code /magicnpcs help} — the same index the bare root prints, for discoverability. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("help").executes(HelpCommand::index);
    }

    /** Print the index. Also the bare {@code /magicnpcs} handler. */
    public static int index(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        LoadoutCatalog catalog = LoadoutManager.catalog();
        LoadoutCatalog.Counts counts = catalog.counts();

        src.sendSuccess(() -> Component.literal("Magic NPCs — diagnostics and school control")
                .withStyle(ChatFormatting.GOLD), false);
        src.sendSuccess(() -> Component.literal("  Iron's Spellbooks: " + present(IronsCompat.isLoaded())
                        + "   Villager Recruits: " + present(RecruitsCompat.isLoaded())
                        + "   loadouts: " + counts.active() + " active"
                        + (counts.rejected() > 0 ? ", " + counts.rejected() + " REJECTED" : "")
                        + "   (catalog generation " + catalog.generation() + ")")
                .withStyle(counts.rejected() > 0 ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY), false);

        line(src, "/magicnpcs why <targets>",
                "why this live mob is or is not casting: goal, gates, target, mana, per-spell blockers");
        line(src, "/magicnpcs loadout entity <targets>",
                "which loadout a mob resolved to, and whether that is what it is actually running");
        line(src, "/magicnpcs loadout id <entity_type>",
                "every loadout declared for a type, plus compat/disable warnings");
        line(src, "/magicnpcs validate",
                "every discovered loadout file and its status — including ones that failed to load");
        line(src, "/magicnpcs validate resource <resource_id>", "one loadout file in full");
        line(src, "/magicnpcs validate id <entity_type>", "every loadout file targeting one entity type");
        line(src, "/magicnpcs config", "effective settings, config file paths, and reconciliation state");
        line(src, "/magicnpcs reconcile", "re-evaluate every loaded mob against the current data");
        line(src, "/magicnpcs school info <targets>", "each NPC's school and which source is driving it");
        line(src, "/magicnpcs school set <targets> <school>", "assign by hand — overrides any loadout");
        line(src, "/magicnpcs school reroll <targets>", "pick a different school for these NPCs");
        line(src, "/magicnpcs school clear <targets>", "stop these NPCs casting, permanently");
        line(src, "/magicnpcs school auto <targets>", "undo a manual assignment; go back to automatic");
        line(src, "/magicnpcs school pool [school]", "what a school's generated pool contains, and why");
        line(src, "/magicnpcs spells [filter]", "the valid Iron's spell ids and their mob-cast support");

        src.sendSuccess(() -> Component.literal("  <angle brackets> are placeholders. A single nearby mob is "
                        + Usage.NEAREST).withStyle(ChatFormatting.DARK_GRAY), false);
        return Command.SINGLE_SUCCESS;
    }

    private static String present(boolean loaded) {
        return loaded ? "present" : "absent";
    }

    private static void line(CommandSourceStack src, String usage, String description) {
        src.sendSuccess(() -> Component.literal(usage).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" — " + description).withStyle(ChatFormatting.GRAY)), false);
    }
}
