package com.otectus.magicnpcs.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * School Tome — right-click an NPC to inspect its casting setup, sneak-right-click to cycle its
 * assigned magic school (and, past the last school, to stop it casting).
 *
 * <p>The item itself is deliberately inert: it is vanilla-only so it registers without Iron's, and it
 * carries no interaction logic at all. That logic lives in
 * {@code integration.irons.SchoolTomeInteraction}, a {@code PlayerInteractEvent.EntityInteract}
 * handler.
 *
 * <p><b>Do not move the behaviour back into {@code interactLivingEntity}.</b> Forge's patched
 * {@code Player#interactOn} calls {@code entity.interact(...)} <em>before</em> the item callback and
 * returns early if it consumed the click — and both documented targets consume it: Recruits opens its
 * own GUI or cycles follow state, and a vanilla villager opens its trade screen. The item callback was
 * therefore dead code on every villager and every recruit.
 */
public class SchoolTomeItem extends Item {
    public SchoolTomeItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.magicnpcs.school_tome.desc").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
