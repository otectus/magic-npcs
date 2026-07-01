package com.otectus.magicnpcs.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SpellDiagnostic;
import com.otectus.magicnpcs.core.loadout.CooldownResolver;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import com.otectus.magicnpcs.integration.irons.IronsBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /magicnpcs loadout entity <targets>}, {@code /magicnpcs loadout id <entity_type>}, and
 * {@code /magicnpcs validate} — inspect the effective spellcaster loadout(s) and surface why a mob
 * has each spell (pooling, replace overrides, bad/disabled ids, cast-data needs, suspicious ranges).
 * Built for OpenLoader/modpack authors (the Fortuna RPG skeleton case). Iron's-specific facts come
 * through {@link IronsBridge} so this class stays Iron's-free; registered only when Iron's is present.
 */
public final class LoadoutCommand {
    private LoadoutCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("magicnpcs")
                .then(Commands.literal("loadout")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("entity")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(LoadoutCommand::entity)))
                        .then(Commands.literal("id")
                                .then(Commands.argument("entity_type", ResourceLocationArgument.id())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                LoadoutManager.snapshot().keySet().stream().map(ResourceLocation::toString), b))
                                        .executes(LoadoutCommand::byId))))
                .then(Commands.literal("validate")
                        .requires(src -> src.hasPermission(2))
                        .executes(LoadoutCommand::validate)));
    }

    /** Show the resolved loadout (and per-spell skip reasons) for each targeted mob. */
    private static int entity(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        int shown = 0;
        for (Entity e : EntityArgument.getEntities(ctx, "targets")) {
            if (!(e instanceof Mob mob)) {
                continue;
            }
            shown++;
            SpellcasterLoadout loadout = LoadoutManager.resolve(mob);
            ResourceLocation type = EntityType.getKey(mob.getType());
            if (loadout == null) {
                src.sendSuccess(() -> Component.literal(mob.getName().getString() + " (" + type + "): "
                        + "no loadout resolves here (not a spellcaster)").withStyle(ChatFormatting.GRAY), false);
                continue;
            }
            int pool = LoadoutManager.loadoutsFor(type).size();
            src.sendSuccess(() -> Component.literal(mob.getName().getString() + " (" + type + ")")
                    .withStyle(ChatFormatting.AQUA)
                    .append(Component.literal("  source=" + loadout.source()
                            + (loadout.replace() ? " [replace]" : "")
                            + (pool > 1 ? "  (1 of " + pool + " pooled for this type)" : ""))
                            .withStyle(ChatFormatting.DARK_GRAY)), false);
            LivingEntity tgt = mob.getTarget();
            for (LoadoutEntry spell : loadout.spells()) {
                printSpellRow(src, spell, tgt, mob);
            }
        }
        if (shown == 0) {
            src.sendFailure(Component.literal("No mobs selected."));
        }
        return shown;
    }

    /** Show every loadout declared for an entity type (post-override), without live-mob context. */
    private static int byId(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ResourceLocation type = ResourceLocationArgument.getId(ctx, "entity_type");
        List<SpellcasterLoadout> loadouts = LoadoutManager.loadoutsFor(type);
        if (loadouts.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No spellcaster loadout declared for " + type + ".")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal(type + ": " + loadouts.size() + " loadout(s)"
                + (loadouts.size() > 1 ? " — pooled per-NPC by pool_weight unless one sets replace" : ""))
                .withStyle(ChatFormatting.AQUA), false);
        for (SpellcasterLoadout loadout : loadouts) {
            src.sendSuccess(() -> Component.literal("• " + loadout.source()
                    + (loadout.profession() != null ? "  profession=" + loadout.profession() : "")
                    + (loadout.replace() ? "  [replace]" : "")
                    + "  pool_weight=" + loadout.poolWeight()).withStyle(ChatFormatting.GOLD), false);
            for (LoadoutEntry spell : loadout.spells()) {
                printSpellRow(src, spell, null, null);
            }
        }
        return loadouts.size();
    }

    /** Scan all loaded loadouts and report pooling, bad/disabled ids, and suspicious ranges. */
    private static int validate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Map<ResourceLocation, List<SpellcasterLoadout>> all = LoadoutManager.snapshot();
        if (all.isEmpty()) {
            src.sendSuccess(() -> Component.literal("No spellcaster loadouts are loaded.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        int issues = 0;
        for (Map.Entry<ResourceLocation, List<SpellcasterLoadout>> e : all.entrySet()) {
            ResourceLocation type = e.getKey();
            List<SpellcasterLoadout> loadouts = e.getValue();

            // Pooling: 2+ loadouts targeting one type with none set to replace.
            if (loadouts.size() > 1 && loadouts.stream().noneMatch(SpellcasterLoadout::replace)) {
                issues++;
                String sources = loadouts.stream().map(l -> String.valueOf(l.source()))
                        .reduce((a, b) -> a + ", " + b).orElse("");
                src.sendSuccess(() -> Component.literal(type + ": " + loadouts.size()
                        + " loadouts pooled (" + sources + "). Each NPC sticky-picks one. "
                        + "Add \"replace\": true to the one that should win to override instead.")
                        .withStyle(ChatFormatting.YELLOW), false);
            }

            // Per-spell checks across every loadout for this type.
            for (SpellcasterLoadout loadout : loadouts) {
                for (LoadoutEntry spell : loadout.spells()) {
                    SpellDiagnostic d = IronsBridge.diagnose(spell.spell().toString());
                    String problem = spellProblem(d, spell);
                    if (problem != null) {
                        issues++;
                        src.sendSuccess(() -> Component.literal(type + " · " + loadout.source()
                                + ": " + spell.spell() + " — " + problem).withStyle(ChatFormatting.RED), false);
                    }
                }
            }
        }
        final int total = issues;
        if (total == 0) {
            src.sendSuccess(() -> Component.literal("Validate: no issues found across "
                    + all.size() + " entity type(s).").withStyle(ChatFormatting.GREEN), false);
        } else {
            src.sendSuccess(() -> Component.literal("Validate: " + total + " issue(s) found — see above.")
                    .withStyle(ChatFormatting.GOLD), false);
        }
        return total;
    }

    /** @return a short problem string for a loadout spell, or {@code null} if it looks fine. */
    private static String spellProblem(SpellDiagnostic d, LoadoutEntry spell) {
        if (!d.exists()) {
            return "unknown spell id (run /magicnpcs spells)";
        }
        if (!d.enabled()) {
            return "spell is disabled in Iron's config";
        }
        if (!d.supportedForMob()) {
            return "not castable by a mob (" + d.category().toLowerCase(Locale.ROOT) + ")";
        }
        if (d.requiresTarget() && spell.role() == LoadoutEntry.Role.SUPPORT) {
            return "needs a target but is a SUPPORT (self-cast) spell — set role=attack";
        }
        if ("GROUND_AOE_FORWARD".equals(d.category()) && spell.maxRange() > 8.0) {
            return String.format(Locale.ROOT, "forward ground-AoE with max_range=%.1f — recommend ≤5.0 "
                    + "(it lands in front of the caster, not at the target)", spell.maxRange());
        }
        return null;
    }

    /** Print one spell row: the loadout fields, then its resolved Iron's diagnostic + any skip reason. */
    private static void printSpellRow(CommandSourceStack src, LoadoutEntry spell, LivingEntity target, Mob mob) {
        SpellDiagnostic d = IronsBridge.diagnose(spell.spell().toString());
        int cooldown = CooldownResolver.resolve(spell.cooldownTicks(), spell.cooldownMultiplier(),
                MagicNpcsConfig.COOLDOWN_MULTIPLIER.get(), d.baseCooldownTicks(),
                MagicNpcsConfig.MIN_COOLDOWN_TICKS.get());
        String head = String.format(Locale.ROOT, "  %s  lvl%d · %s · w%d · range %.0f-%.0f · safety %.1f",
                spell.spell(), spell.level(), spell.role().name().toLowerCase(Locale.ROOT), spell.weight(),
                spell.minRange(), spell.maxRange(), spell.safetyRadius());
        String detail = String.format(Locale.ROOT, "      %s · %s · cd %dt%s%s",
                d.exists() ? d.category().toLowerCase(Locale.ROOT) : "UNKNOWN",
                d.castType().toLowerCase(Locale.ROOT), cooldown,
                d.exists() && !d.enabled() ? " · DISABLED" : "",
                d.requiresTarget() ? " · needs-target" : "");
        String skip = liveSkipReason(d, spell, target, mob);

        ChatFormatting color = !d.exists() || !d.enabled() || !d.supportedForMob()
                ? ChatFormatting.RED : (skip != null ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
        src.sendSuccess(() -> Component.literal(head).withStyle(color), false);
        src.sendSuccess(() -> Component.literal(detail + (skip != null ? " · skip: " + skip : ""))
                .withStyle(ChatFormatting.DARK_GRAY), false);
    }

    /** Best-effort "why wouldn't this cast right now" for a live mob (vanilla-side checks only). */
    private static String liveSkipReason(SpellDiagnostic d, LoadoutEntry spell, LivingEntity target, Mob mob) {
        if (!d.exists()) {
            return "unknown id";
        }
        if (!d.enabled()) {
            return "disabled";
        }
        if (!d.supportedForMob()) {
            return "unsupported for mobs";
        }
        if (mob == null) {
            return null; // id-mode: no live context
        }
        if (d.requiresTarget() && target == null) {
            return "needs a target but the mob has none";
        }
        if (spell.role() == LoadoutEntry.Role.ATTACK && target != null) {
            double dist = Math.sqrt(mob.distanceToSqr(target));
            if (dist < spell.minRange()) {
                return String.format(Locale.ROOT, "target too close (%.1f < min %.1f)", dist, spell.minRange());
            }
            if (dist > spell.maxRange()) {
                return String.format(Locale.ROOT, "target out of range (%.1f > max %.1f)", dist, spell.maxRange());
            }
        }
        return null;
    }
}
