package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.api.event.MagicNpcSchoolChangedEvent;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolAssignResult;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.SchoolReroll;
import com.otectus.magicnpcs.core.loadout.LoadoutResolution;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.registry.MagicNpcsItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;

/**
 * School Tome interaction: right-click an NPC to <b>inspect</b> its casting setup, sneak-right-click
 * to <b>cycle</b> its assigned magic school (and, past the last school, to stop it casting).
 *
 * <p><b>Why this is an event handler and not {@code Item#interactLivingEntity}.</b> Forge's patched
 * {@code Player#interactOn} fires {@code ForgeHooks.onInteractEntity} first, then calls
 * {@code entity.interact(...)}, and only reaches {@code stack.interactLivingEntity(...)} if that did
 * not consume the click. Villager Recruits' {@code AbstractRecruitEntity#mobInteract} returns SUCCESS
 * for its owner both when sneaking (opens the recruit's inventory) and when not (cycles follow state),
 * and a vanilla {@code Villager} returns SUCCESS to open its trade screen. So the item callback was
 * unreachable on precisely the two NPC kinds the Tome is documented for — the item did nothing at all
 * on a villager or a recruit, which is exactly how it was reported. {@code EntityInteract} runs before
 * either of them and can cancel the interaction outright.
 *
 * <p>Registered only when Iron's Spellbooks is present (via {@link IronsIntegration}), so the Tome is
 * inert rather than misleading in an Iron's-less install.
 */
public final class SchoolTomeInteraction {

    private SchoolTomeInteraction() {}

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (!event.getItemStack().is(MagicNpcsItems.SCHOOL_TOME.get())
                || !(event.getTarget() instanceof Mob mob)
                || !MagicNpcsConfig.SCHOOLS_ITEM_ENABLED.get()) {
            return;
        }
        List<ResourceLocation> allowed = MagicNpcsConfig.allowedSchoolIds();
        if (allowed.isEmpty()) {
            return; // nothing assignable — let the click fall through to normal behaviour
        }

        // Both sides run this far so the client's prediction matches the server's decision; only the
        // server mutates anything. Returning SUCCESS unconditionally on the client (as the old item
        // callback did) swung the player's arm even on the paths where the server did nothing.
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        if (player.level().isClientSide()) {
            return;
        }

        if (player.isShiftKeyDown()) {
            cycle(player, mob, allowed);
        } else {
            player.displayClientMessage(inspect(mob), false);
        }
    }

    /** Every mutation this class makes was asked for by a player holding the Tome. */
    private static final MagicNpcSchoolChangedEvent.ChangeSource TOME =
            MagicNpcSchoolChangedEvent.ChangeSource.TOME;

    /** Sneak-click: advance to the next school with a usable pool, or clear once past the last. */
    private static void cycle(Player player, Mob mob, List<ResourceLocation> allowed) {
        ResourceLocation current = SchoolData.getSchool(mob);
        SchoolReroll.CycleOutcome outcome =
                SchoolReroll.cycle(allowed, current,
                        school -> IronsSpellcasterHandler.applySchool(mob, school, TOME));

        if (outcome.assigned() != null) {
            say(player, mob, Component.literal(mob.getName().getString() + " → " + outcome.assigned())
                    .withStyle(ChatFormatting.AQUA), true);
        } else if (outcome.cleared()) {
            IronsSpellcasterHandler.clearSchool(mob, TOME);
            say(player, mob, Component.literal(mob.getName().getString() + " → no school (stopped casting)")
                    .withStyle(ChatFormatting.GRAY), true);
        } else {
            // Every remaining school failed. Name them and why, rather than clicking into silence.
            say(player, mob, Component.literal("No school could be assigned: " + outcome.describeFailures())
                    .withStyle(ChatFormatting.RED), false);
        }
    }

    /**
     * Plain click: a read-only report. This is the only way a survival player can find out what an NPC
     * is set to — {@code /magicnpcs school info} needs op, and the school-coloured particles only show
     * during a cast wind-up.
     *
     * <p>Reports the <em>effective</em> source, not just the stored school, because those can disagree:
     * an explicit datapack loadout outranks an automatically rolled school, so a mob could read as
     * "fire" while actually casting a loadout's spells.
     */
    private static Component inspect(Mob mob) {
        String name = mob.getName().getString();
        ResourceLocation school = SchoolData.getSchool(mob);
        boolean manual = SchoolData.isManual(mob);

        if (school != null && manual) {
            return Component.literal(name + ": " + school + " (set by hand)").withStyle(ChatFormatting.AQUA);
        }
        LoadoutResolution resolution = LoadoutManager.peek(mob);
        if (resolution.isPresent()) {
            return Component.literal(name + ": casting " + resolution.loadout().source() + " (datapack loadout)")
                    .withStyle(ChatFormatting.GOLD);
        }
        if (school != null) {
            return Component.literal(name + ": " + school + " (assigned automatically)")
                    .withStyle(ChatFormatting.AQUA);
        }
        if (manual) {
            return Component.literal(name + ": not a caster (cleared by hand)").withStyle(ChatFormatting.GRAY);
        }
        return Component.literal(name + (SchoolData.hasRolled(mob) ? ": not a caster" : ": no school assigned"))
                .withStyle(ChatFormatting.GRAY);
    }

    /** Feedback plus, only when something actually changed, a click. NEUTRAL: allies telegraph too. */
    private static void say(Player player, Mob mob, Component message, boolean changed) {
        player.displayClientMessage(message, true);
        if (changed) {
            player.level().playSound(null, mob.blockPosition(),
                    SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.NEUTRAL, 0.6F, 1.2F);
        }
    }
}
