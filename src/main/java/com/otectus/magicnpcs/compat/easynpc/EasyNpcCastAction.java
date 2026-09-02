package com.otectus.magicnpcs.compat.easynpc;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.integration.irons.DetachedCastDriver;
import de.markusbordihn.easynpc.api.action.CustomActionExecutor;
import de.markusbordihn.easynpc.data.action.ActionDataEntry;
import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.Locale;

/**
 * The {@code magicnpcs:cast} custom action, so an Easy NPC dialog button, an interaction trigger or a
 * timed event can make an NPC cast a named spell.
 *
 * <p>Written as a class rather than a lambda on purpose. Easy NPC's {@link CustomActionExecutor}
 * declares the four-argument form as the <em>abstract</em> method and the context-carrying form as a
 * {@code default} that delegates to it, so a lambda can only ever implement the older one. Doing it
 * this way keeps the door open to reading {@code ActionContext} later without changing shape.
 *
 * <p>Usage in an Easy NPC action of type {@code CUSTOM}:
 * <pre>magicnpcs:cast &lt;spell_id&gt; [level] [self|target]</pre>
 * for example {@code magicnpcs:cast irons_spellbooks:heal 2 self}. The spell id may omit its namespace,
 * in which case {@code irons_spellbooks} is assumed.
 */
public final class EasyNpcCastAction implements CustomActionExecutor {

    /** The action id pack authors write. */
    public static final ResourceLocation ID = new ResourceLocation(MagicNpcs.MODID, "cast");

    /** Iron's owns almost every spell anyone will name here; spelling it out each time is noise. */
    private static final String DEFAULT_SPELL_NAMESPACE = "irons_spellbooks";

    /** Where an unqualified cast is aimed. */
    private enum Aim { TARGET, SELF }

    @Override
    public void execute(ActionDataEntry actionDataEntry, EasyNPC<?> easyNPC, ServerPlayer serverPlayer,
                        List<String> arguments) {
        if (easyNPC == null || easyNPC.isClientSideInstance()) {
            return;
        }
        Mob mob = easyNPC.getMob();
        if (mob == null) {
            return;
        }
        if (!MagicNpcsConfig.ENABLE_SPELLCASTING.get()
                || !MagicNpcsConfig.EASYNPC_INTEGRATION_ENABLED.get()) {
            return;
        }
        // The adapter's state gates still apply. A paused NPC, or one the operator has switched off,
        // must not be castable by script either — a scripted route that ignores the rules the AI
        // route obeys is how "I disabled this and it still happened" bug reports are made.
        if (!NpcAdapters.resolve(mob).canCastNow(mob)) {
            return;
        }
        if (arguments == null || arguments.isEmpty()) {
            warn("missing spell id — expected 'magicnpcs:cast <spell_id> [level] [self|target]'", mob);
            return;
        }

        ResourceLocation spellId = parseSpellId(arguments.get(0));
        if (spellId == null) {
            warn("'" + arguments.get(0) + "' is not a valid spell id", mob);
            return;
        }
        int level = parseLevel(arguments, mob);
        Aim aim = parseAim(arguments);
        LivingEntity target = aim == Aim.SELF ? null : resolveTarget(mob, serverPlayer);

        DetachedCastDriver.Result result = DetachedCastDriver.cast(mob, target, spellId, level);
        if (!result.started()) {
            // A refused scripted cast is silent in game but must not be silent in the log: the author
            // wrote an action that does nothing, and the reason is the only way they will find out.
            warn("cast of " + spellId + " refused: " + result.detail(), mob);
        }
    }

    /**
     * Who the cast is aimed at when the action does not say.
     *
     * <p>The NPC's own combat target comes first, because an action fired mid-fight almost always
     * means "cast at what you are fighting". The player who triggered the action is the fallback for
     * the dialog case, where there is no combat target and the only other participant is the person
     * standing in front of the NPC. An ally is never a valid target: the adapter's friendly-fire rule
     * decides that, not this method.
     */
    private static LivingEntity resolveTarget(Mob mob, ServerPlayer serverPlayer) {
        LivingEntity current = mob.getTarget();
        if (current != null && current.isAlive() && NpcAdapters.resolve(mob).canCastAt(mob, current)) {
            return current;
        }
        if (serverPlayer != null && serverPlayer.isAlive()
                && NpcAdapters.resolve(mob).canCastAt(mob, serverPlayer)) {
            return serverPlayer;
        }
        return null;
    }

    private static ResourceLocation parseSpellId(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String qualified = trimmed.indexOf(':') >= 0 ? trimmed : DEFAULT_SPELL_NAMESPACE + ":" + trimmed;
        return ResourceLocation.tryParse(qualified);
    }

    /** Second argument, when it is a number. A malformed level is reported rather than silently ignored. */
    private static int parseLevel(List<String> arguments, Mob mob) {
        if (arguments.size() < 2) {
            return 1;
        }
        String raw = arguments.get(1).trim();
        if (raw.isEmpty() || isAimWord(raw)) {
            return 1; // the caller wrote 'magicnpcs:cast <spell> self' and skipped the level
        }
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException ex) {
            warn("'" + raw + "' is not a spell level; using 1", mob);
            return 1;
        }
    }

    private static Aim parseAim(List<String> arguments) {
        for (String argument : arguments) {
            if ("self".equalsIgnoreCase(argument.trim())) {
                return Aim.SELF;
            }
        }
        return Aim.TARGET;
    }

    private static boolean isAimWord(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return "self".equals(lower) || "target".equals(lower);
    }

    private static void warn(String message, Mob mob) {
        MagicNpcs.LOGGER.warn("[magicnpcs:cast] {} ({}): {}",
                net.minecraft.world.entity.EntityType.getKey(mob.getType()), mob.getUUID(), message);
    }
}
