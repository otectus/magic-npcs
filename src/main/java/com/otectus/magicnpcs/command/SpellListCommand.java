package com.otectus.magicnpcs.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.otectus.magicnpcs.core.SpellInfo;
import com.otectus.magicnpcs.integration.irons.IronsBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * {@code /magicnpcs spells [filter]} — list the valid Iron's spell registry ids (with school,
 * rarity, default cooldown and a mob-friendly hint) so datapack authors can discover the exact
 * {@code spell} ids to put in a loadout. The {@code [filter]} is a case-insensitive substring match
 * against the id. Iron's data is fetched through {@link IronsBridge} so this class stays Iron's-free
 * and is only registered when Iron's is present (alongside {@code /magicnpcs school}).
 */
public final class SpellListCommand {
    private static final int MAX_ROWS = 60;

    private SpellListCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("spells")
                // Op-gated like every other diagnostic. README documents all of them as op-only;
                // leaving this one open put the whole Iron's spell registry in every player's
                // tab-completion on a multiplayer server.
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> list(ctx, ""))
                .then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(ctx -> list(ctx, StringArgumentType.getString(ctx, "filter"))));
    }

    private static int list(CommandContext<CommandSourceStack> ctx, String filter) {
        CommandSourceStack src = ctx.getSource();
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        List<SpellInfo> all = IronsBridge.listSpells();
        List<SpellInfo> matched = all.stream()
                .filter(s -> needle.isEmpty() || s.id().toLowerCase(Locale.ROOT).contains(needle))
                .toList();

        if (matched.isEmpty()) {
            src.sendSuccess(() -> Component.literal(
                    "No Iron's spells match '" + filter + "'.").withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }

        src.sendSuccess(() -> Component.literal(
                matched.size() + " spell(s)" + (needle.isEmpty() ? "" : " matching '" + filter + "'")
                        + "  (id — school · rarity · cooldown · cast):").withStyle(ChatFormatting.AQUA), false);
        int shown = Math.min(matched.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            SpellInfo s = matched.get(i);
            ChatFormatting idColor = s.mobFriendly() ? ChatFormatting.GREEN : ChatFormatting.GRAY;
            String detail = String.format(Locale.ROOT, "  —  %s · %s · %dt (%.1fs) · %s%s",
                    s.school().isEmpty() ? "—" : s.school(), s.rarity().toLowerCase(Locale.ROOT),
                    s.cooldownTicks(), s.cooldownTicks() / 20.0, s.castType().toLowerCase(Locale.ROOT),
                    s.mobFriendly() ? "" : " (not mob-friendly)");
            src.sendSuccess(() -> Component.literal(s.id()).withStyle(idColor)
                    .append(Component.literal(detail).withStyle(ChatFormatting.DARK_GRAY)), false);
        }
        if (matched.size() > MAX_ROWS) {
            src.sendSuccess(() -> Component.literal(
                    "…and " + (matched.size() - MAX_ROWS) + " more — narrow with /magicnpcs spells <text>.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return matched.size();
    }
}
