package com.otectus.magicnpcs.registry;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.item.SchoolTomeItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Item registry. Currently just the School Tome (manual per-NPC school assignment).
 * Registered unconditionally (vanilla-only); the item's effect is gated on Iron's at
 * interaction time, so it is harmless when Iron's is absent.
 */
public final class MagicNpcsItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MagicNpcs.MODID);

    public static final RegistryObject<Item> SCHOOL_TOME = ITEMS.register("school_tome",
            () -> new SchoolTomeItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

    private MagicNpcsItems() {}

    public static void init(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(MagicNpcsItems::addToTabs);
    }

    private static void addToTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(SCHOOL_TOME);
        }
    }
}
