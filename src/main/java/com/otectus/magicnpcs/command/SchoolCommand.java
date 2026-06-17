package com.otectus.magicnpcs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.integration.irons.IronsSpellcasterHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

import java.util.Collection;
import java.util.List;

/**
 * {@code /magicnpcs school <set|info|reroll|clear> <targets> [school]} — manually
 * (re)assign the magic school of targeted NPCs. Registered only when Iron's is present
 * (delegated from the Iron's-gated {@link IronsSpellcasterHandler}); this class touches
 * no Iron's type directly, only our own seam, so the "Iron's imports stay in
 * integration.irons" invariant holds.
 */
public final class SchoolCommand {
    private SchoolCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("magicnpcs")
                .then(Commands.literal("school")
                        .requires(src -> src.hasPermission(MagicNpcsConfig.SCHOOLS_COMMAND_PERMISSION.get()))
                        .then(Commands.literal("set")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("school", StringArgumentType.string())
                                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                        MagicNpcsConfig.allowedSchoolIds().stream().map(ResourceLocation::toString), b))
                                                .executes(SchoolCommand::set))))
                        .then(Commands.literal("info")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(SchoolCommand::info)))
                        .then(Commands.literal("reroll")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(SchoolCommand::reroll)))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(SchoolCommand::clear)))));
    }

    private static int set(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ResourceLocation school = ResourceLocation.tryParse(StringArgumentType.getString(ctx, "school"));
        if (school == null) {
            ctx.getSource().sendFailure(Component.literal("Invalid school id."));
            return 0;
        }
        int n = 0;
        for (Mob mob : mobs(ctx)) {
            if (IronsSpellcasterHandler.applySchool(mob, school)) {
                n++;
            }
        }
        final int count = n;
        ctx.getSource().sendSuccess(() -> Component.literal("Assigned " + school + " to " + count + " NPC(s)."), true);
        return count;
    }

    private static int reroll(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        List<ResourceLocation> allowed = MagicNpcsConfig.allowedSchoolIds();
        if (allowed.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No schools are allowed in the config."));
            return 0;
        }
        int n = 0;
        for (Mob mob : mobs(ctx)) {
            ResourceLocation pick = allowed.get(mob.getRandom().nextInt(allowed.size()));
            if (IronsSpellcasterHandler.applySchool(mob, pick)) {
                n++;
            }
        }
        final int count = n;
        ctx.getSource().sendSuccess(() -> Component.literal("Re-rolled schools for " + count + " NPC(s)."), true);
        return count;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        int n = 0;
        for (Mob mob : mobs(ctx)) {
            IronsSpellcasterHandler.clearSchool(mob);
            n++;
        }
        final int count = n;
        ctx.getSource().sendSuccess(() -> Component.literal("Cleared schools on " + count + " NPC(s)."), true);
        return count;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        for (Mob mob : mobs(ctx)) {
            ResourceLocation school = SchoolData.getSchool(mob);
            String label = school != null ? school.toString()
                    : (SchoolData.hasRolled(mob) ? "none (not a caster)" : "unassigned");
            ctx.getSource().sendSuccess(() -> Component.literal(
                    mob.getName().getString() + ": " + label), false);
        }
        return 1;
    }

    private static List<Mob> mobs(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<? extends Entity> entities = EntityArgument.getEntities(ctx, "targets");
        List<Mob> out = new java.util.ArrayList<>();
        for (Entity e : entities) {
            if (e instanceof Mob mob) {
                out.add(mob);
            }
        }
        return out;
    }
}
