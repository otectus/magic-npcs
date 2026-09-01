package com.otectus.magicnpcs.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.otectus.magicnpcs.core.caster.ReconcileReason;
import com.otectus.magicnpcs.core.caster.ReconcileResult;
import com.otectus.magicnpcs.integration.irons.CasterReconciler;
import com.otectus.magicnpcs.integration.irons.IronsSpellcasterHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * {@code /magicnpcs reconcile [targets]} — re-evaluate managed casting state against the current
 * catalog and config.
 *
 * <p>An explicit repair command, because reconciliation is now the operation everything else is built
 * on: joins, reloads, config changes and manual assignments all run it. Having a way to invoke it
 * directly means a support answer can be "run this" instead of "unload and reload the chunk", and it
 * makes the mechanism testable from a command block.
 */
public final class ReconcileCommand {

    private ReconcileCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("reconcile")
                .requires(src -> src.hasPermission(2))
                .executes(ReconcileCommand::all);
        node.then(Commands.argument("targets", EntityArgument.entities())
                .executes(ReconcileCommand::targets));
        return node;
    }

    /** Queue every loaded mob, the same path a {@code /reload} takes. */
    private static int all(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        IronsSpellcasterHandler.queueAllLoadedMobs(src.getServer(), ReconcileReason.ADMIN_COMMAND);
        int queued = IronsSpellcasterHandler.pendingReconciles();
        src.sendSuccess(() -> Component.literal("Queued " + queued + " loaded mob(s) for reconciliation. "
                        + "Progress is logged; run /magicnpcs config to see what is left.")
                .withStyle(ChatFormatting.GREEN), false);
        return queued;
    }

    /** Reconcile the selected mobs immediately and report each outcome. */
    private static int targets(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        int changed = 0;
        int seen = 0;
        for (Entity entity : EntityArgument.getEntities(ctx, "targets")) {
            if (!(entity instanceof Mob mob)) {
                continue;
            }
            seen++;
            ReconcileResult result = CasterReconciler.reconcile(mob, ReconcileReason.ADMIN_COMMAND);
            if (result.outcome().changed()) {
                changed++;
            }
            src.sendSuccess(() -> Component.literal(mob.getName().getString() + ": " + result.describe())
                    .withStyle(colour(result)), false);
        }
        if (seen == 0) {
            Usage.print(src, "/magicnpcs reconcile needs mobs, or no argument at all:", List.of(
                    new Usage.Line("/magicnpcs reconcile", "queue every loaded mob"),
                    new Usage.Line("/magicnpcs reconcile <targets>", "reconcile the selected mobs now")));
            Usage.example(src, "/magicnpcs reconcile " + Usage.NEAREST);
            return 0;
        }
        return changed;
    }

    private static ChatFormatting colour(ReconcileResult result) {
        return switch (result.outcome()) {
            case INSTALLED, UPDATED -> ChatFormatting.GREEN;
            case REMOVED -> ChatFormatting.YELLOW;
            case FAILED -> ChatFormatting.RED;
            default -> ChatFormatting.GRAY;
        };
    }
}
