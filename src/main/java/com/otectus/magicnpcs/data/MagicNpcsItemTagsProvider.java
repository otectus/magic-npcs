package com.otectus.magicnpcs.data;

import com.otectus.magicnpcs.MagicNpcs;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.data.ExistingFileHelper;

import java.util.concurrent.CompletableFuture;

/**
 * Generates the {@code magicnpcs:spell_focuses} item tag (used by the
 * {@code equipment.requireSpellFocus} gate). It optionally pulls in Iron's
 * {@code #irons_spellbooks:school_focus} ("all focuses") tag, so the gate works
 * out-of-the-box when Iron's is installed and resolves to nothing when it is not.
 * Vanilla-only — no Iron's classes referenced.
 */
public final class MagicNpcsItemTagsProvider extends ItemTagsProvider {
    private static final TagKey<Item> SPELL_FOCUSES =
            TagKey.create(Registries.ITEM, new ResourceLocation(MagicNpcs.MODID, "spell_focuses"));
    private static final ResourceLocation IRONS_SCHOOL_FOCUS =
            new ResourceLocation("irons_spellbooks", "school_focus");

    public MagicNpcsItemTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookup,
                                     ExistingFileHelper existingFileHelper) {
        super(output, lookup, CompletableFuture.completedFuture(TagsProvider.TagLookup.empty()),
                MagicNpcs.MODID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // Optional: present only if Iron's ships it; harmless otherwise. Pack authors can
        // also add their own staves/spellbooks to magicnpcs:spell_focuses in their datapack.
        tag(SPELL_FOCUSES).addOptionalTag(IRONS_SCHOOL_FOCUS);
    }
}
