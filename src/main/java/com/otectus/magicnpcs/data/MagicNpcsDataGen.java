package com.otectus.magicnpcs.data;

import com.otectus.magicnpcs.MagicNpcs;
import net.minecraft.data.PackOutput;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Data-generation entry point (Forge {@link GatherDataEvent}). Subscribed on the MOD
 * bus; this event fires only during the {@code data} run ({@code runData}), never at
 * game runtime. Vanilla-only — no Iron's references — so generation succeeds with or
 * without Iron's on the classpath.
 */
@Mod.EventBusSubscriber(modid = MagicNpcs.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MagicNpcsDataGen {
    private MagicNpcsDataGen() {}

    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        PackOutput output = event.getGenerator().getPackOutput();
        event.getGenerator().addProvider(event.includeServer(), new SpellcasterLoadoutProvider(output));
        event.getGenerator().addProvider(event.includeServer(), new SchoolTomeRecipeProvider(output));
        event.getGenerator().addProvider(event.includeServer(),
                new MagicNpcsItemTagsProvider(output, event.getLookupProvider(), event.getExistingFileHelper()));
    }
}
