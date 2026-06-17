package com.otectus.magicnpcs.item;

import com.otectus.magicnpcs.compat.IronsCompat;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.integration.irons.IronsSpellcasterHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * School Tome — right-click an NPC to cycle its assigned magic school (sneak-right-click
 * to clear). A manual per-individual override on top of automatic assignment. The
 * school-applying effect runs server-side and is gated on Iron's being present and on
 * {@code schools.item}; the item itself is vanilla-only so it registers without Iron's.
 *
 * <p>References to the Iron's-side {@link IronsSpellcasterHandler} only execute inside
 * the {@link IronsCompat#isLoaded()} guard, so the class never force-loads Iron's.
 */
public class SchoolTomeItem extends Item {
    public SchoolTomeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (player.level().isClientSide) {
            return InteractionResult.sidedSuccess(true); // swing on the client; logic is server-side
        }
        if (!MagicNpcsConfig.SCHOOLS_ITEM_ENABLED.get() || !IronsCompat.isLoaded() || !(target instanceof Mob mob)) {
            return InteractionResult.PASS;
        }
        List<ResourceLocation> allowed = MagicNpcsConfig.allowedSchoolIds();
        if (allowed.isEmpty()) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) {
            IronsSpellcasterHandler.clearSchool(mob);
            player.displayClientMessage(Component.literal("Cleared " + mob.getName().getString() + "'s school."), true);
        } else {
            ResourceLocation current = SchoolData.getSchool(mob);
            int idx = current == null ? -1 : allowed.indexOf(current);
            ResourceLocation next = allowed.get((idx + 1) % allowed.size());
            if (IronsSpellcasterHandler.applySchool(mob, next)) {
                player.displayClientMessage(Component.literal(mob.getName().getString() + " → " + next), true);
            } else {
                player.displayClientMessage(Component.literal("Could not assign " + next + " (no castable spells)."), true);
            }
        }
        player.level().playSound(null, mob.blockPosition(),
                SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.NEUTRAL, 0.6F, 1.2F);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.magicnpcs.school_tome.desc").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
