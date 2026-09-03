package com.otectus.magicnpcs.compat.customnpcs;

import com.otectus.magicnpcs.registry.MagicNpcsItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;

/**
 * Puts the School Tome in CustomNPCs' own creative tab, next to the tools it belongs with.
 *
 * <p>Someone building a spellcasting NPC has the CustomNPCs tab open; making them close it and go
 * looking under Tools and Utilities for the item that assigns the school is a small piece of friction
 * in exactly the workflow this integration exists to support. The tome stays in its vanilla tab too —
 * this adds a second home, it does not move it.
 *
 * <p>Vanilla types only: the tab is matched by {@link ResourceLocation}, so nothing here imports
 * CustomNPCs and a missing tab simply never matches. Registered on the MOD bus.
 */
public final class CustomNpcsCreativeTab {

    private static final ResourceKey<CreativeModeTab> CUSTOMNPCS_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, new ResourceLocation("customnpcs", "cnpcs"));

    private CustomNpcsCreativeTab() {}

    public static void onBuildContents(BuildCreativeModeTabContentsEvent event) {
        if (CUSTOMNPCS_TAB.equals(event.getTabKey())) {
            event.accept(MagicNpcsItems.SCHOOL_TOME);
        }
    }
}
