package com.otectus.magicnpcs.compat.customnpcs;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import noppes.npcs.api.NpcAPI;
import noppes.npcs.api.entity.ICustomNpc;
import noppes.npcs.api.entity.data.INPCInventory;

/**
 * Puts an item in a CustomNPC's hand the way CustomNPCs itself does.
 *
 * <p>A CustomNPC's held items live in its own {@link INPCInventory}, and that inventory is what gets
 * written back onto the entity and synced to clients. {@code Mob.setItemInHand} therefore does not
 * stick: the grant is visible for a moment and then overwritten by the NPC's own data, so a loadout's
 * {@code equipment} block appeared to do nothing on a CustomNPC.
 *
 * <p>Public {@code INPCInventory} only — {@code setRightHand}/{@code setLeftHand} plus
 * {@link ICustomNpc#updateClient()} to push the change out. No internals, no reflection.
 */
public final class CustomNpcsInventoryBridge {

    private CustomNpcsInventoryBridge() {}

    /**
     * @param npc   the NPC to equip
     * @param hand  which hand — CustomNPCs calls them right and left
     * @param stack what to put there
     * @return true if the item was placed; false if CustomNPCs refused, in which case the caller
     *         falls back to the vanilla {@code setItemInHand} rather than leaving the hand empty
     */
    public static boolean setHeldItem(ICustomNpc<?> npc, InteractionHand hand, ItemStack stack) {
        try {
            INPCInventory inventory = npc.getInventory();
            if (inventory == null) {
                return false;
            }
            var wrapped = NpcAPI.Instance().getIItemStack(stack);
            if (hand == InteractionHand.OFF_HAND) {
                inventory.setLeftHand(wrapped);
            } else {
                inventory.setRightHand(wrapped);
            }
            npc.updateClient();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
