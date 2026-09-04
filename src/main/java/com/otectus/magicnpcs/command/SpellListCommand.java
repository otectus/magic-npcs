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

    /** One-letter provenance column: which layer decided this spell's mob-cast capability. */
    private static String provenanceLetter(String provenance) {
        return switch (provenance == null ? "" : provenance) {
            case "VERIFIED" -> "V";
            case "OVERRIDE" -> "O";
            case "MANIFEST" -> "M";
            case "NAMESPACE_TRUSTED" -> "T";
            default -> "U";
        };
    }

    /**
     * "(not mob-friendly)" named a symptom and left the operator nowhere to go. A spell no layer has
     * spoken for is the one case they can actually fix, so that row says how.
     */
    private static String unverifiedHint(SpellInfo spell) {
        return "U".equals(provenanceLetter(spell.provenance()))
                ? " — unverified: trust the namespace or add a manifest row "
                        + "(docs/compat/irons-addons.md)"
                : "";
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
                        + "  (id — provenance · school · rarity · cooldown · cast):")
                .withStyle(ChatFormatting.AQUA), false);
        int shown = Math.min(matched.size(), MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            SpellInfo s = matched.get(i);
            ChatFormatting idColor = s.mobFriendly() ? ChatFormatting.GREEN : ChatFormatting.GRAY;
            String detail = String.format(Locale.ROOT, "  —  %s · %s · %s · %dt (%.1fs) · %s%s",
                    provenanceLetter(s.provenance()),
                    s.school().isEmpty() ? "—" : s.school(), s.rarity().toLowerCase(Locale.ROOT),
                    s.cooldownTicks(), s.cooldownTicks() / 20.0, s.castType().toLowerCase(Locale.ROOT),
                    unverifiedHint(s));
            src.sendSuccess(() -> Component.literal(s.id()).withStyle(idColor)
                    .append(Component.literal(detail).withStyle(ChatFormatting.DARK_GRAY)), false);
        }
        src.sendSuccess(() -> Component.literal(
                "provenance: V verified · O config override · M datapack manifest · T namespace-trusted "
                        + "· U unverified").withStyle(ChatFormatting.DARK_GRAY), false);
        if (matched.size() > MAX_ROWS) {
            src.sendSuccess(() -> Component.literal(
                    "…and " + (matched.size() - MAX_ROWS)
                            + " more — narrow with /magicnpcs spells <text or namespace:>.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return matched.size();
    }
}
