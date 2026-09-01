package com.otectus.magicnpcs.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.registry.MagicNpcsItems;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraftforge.common.crafting.ConditionalRecipe;
import net.minecraftforge.common.crafting.conditions.AndCondition;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.common.crafting.conditions.ItemExistsCondition;
import net.minecraftforge.common.crafting.conditions.ModLoadedCondition;
import net.minecraftforge.common.crafting.conditions.NotCondition;

import java.util.function.Consumer;

/**
 * Crafting recipes for the School Tome.
 *
 * <p>The Tome shipped with no recipe and no loot source of any kind, so it was creative-only — an
 * advertised item a survival player had no way to obtain.
 *
 * <p>Two recipes are emitted under mutually exclusive Forge conditions, so exactly one ever loads: a
 * thematic one using Iron's own reagents where Iron's Spellbooks is installed, and a vanilla-only
 * fallback where it is not. The condition tests that the reagent <em>items</em> exist rather than
 * just that the mod is loaded, so a future Iron's that renames them degrades to the vanilla recipe
 * instead of leaving the Tome uncraftable.
 *
 * <p>The Iron's ingredients are written as raw ids through a small hand-built {@link FinishedRecipe}
 * instead of being looked up in the item registry: {@code runData} runs without Iron's on the
 * classpath, and the repo requires that {@code data/} never references Iron's classes.
 */
public final class SchoolTomeRecipeProvider extends RecipeProvider {

    private static final String IRONS = "irons_spellbooks";
    /** Both verified present in Iron's 1.20.1-3.15.2. */
    private static final String ARCANE_ESSENCE = "arcane_essence";
    private static final String BLANK_RUNE = "blank_rune";

    public SchoolTomeRecipeProvider(PackOutput output) {
        super(output);
    }

    @Override
    protected void buildRecipes(Consumer<FinishedRecipe> consumer) {
        ICondition ironsAvailable = new AndCondition(
                new ModLoadedCondition(IRONS),
                new ItemExistsCondition(IRONS, ARCANE_ESSENCE),
                new ItemExistsCondition(IRONS, BLANK_RUNE));

        ConditionalRecipe.builder()
                .addCondition(ironsAvailable)
                .addRecipe(ironsVariant())
                .build(consumer, id("school_tome_irons"));

        ConditionalRecipe.builder()
                .addCondition(new NotCondition(ironsAvailable))
                .addRecipe(vanillaVariant())
                .build(consumer, id("school_tome"));
    }

    /** Book ringed by arcane essence and blank runes. */
    private static FinishedRecipe ironsVariant() {
        JsonObject json = new JsonObject();
        JsonArray pattern = new JsonArray();
        pattern.add(" e ");
        pattern.add("rbr");
        pattern.add(" e ");
        json.add("pattern", pattern);

        JsonObject key = new JsonObject();
        key.add("e", ingredient(IRONS + ":" + ARCANE_ESSENCE));
        key.add("r", ingredient(IRONS + ":" + BLANK_RUNE));
        key.add("b", ingredient("minecraft:book"));
        json.add("key", key);

        JsonObject result = new JsonObject();
        result.addProperty("item", MagicNpcsItems.SCHOOL_TOME.getId().toString());
        json.add("result", result);

        return new RawRecipe(id("school_tome_irons"), json);
    }

    /** Vanilla fallback: book ringed by amethyst and lapis — obtainable in any world. */
    private static Consumer<Consumer<FinishedRecipe>> vanillaVariant() {
        return consumer -> ShapedRecipeBuilder
                .shaped(RecipeCategory.TOOLS, MagicNpcsItems.SCHOOL_TOME.get())
                .pattern(" a ")
                .pattern("lbl")
                .pattern(" a ")
                .define('a', Items.AMETHYST_SHARD)
                .define('l', Items.LAPIS_LAZULI)
                .define('b', Items.BOOK)
                .unlockedBy("has_amethyst_shard",
                        InventoryChangeTrigger.TriggerInstance.hasItems(Items.AMETHYST_SHARD))
                .save(consumer, id("school_tome_vanilla"));
    }

    private static JsonObject ingredient(String itemId) {
        JsonObject o = new JsonObject();
        o.addProperty("item", itemId);
        return o;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(MagicNpcs.MODID, path);
    }

    /**
     * A {@link FinishedRecipe} over pre-built JSON, for ingredients whose classes may be absent at
     * datagen time. {@code serializeRecipe()} adds the {@code "type"} itself from {@link #getType()},
     * so the body here carries only pattern/key/result.
     */
    private record RawRecipe(ResourceLocation id, JsonObject json) implements FinishedRecipe {
        @Override
        public void serializeRecipeData(JsonObject target) {
            json.entrySet().forEach(e -> target.add(e.getKey(), e.getValue()));
        }

        @Override
        public ResourceLocation getId() {
            return id;
        }

        @Override
        public RecipeSerializer<?> getType() {
            return RecipeSerializer.SHAPED_RECIPE;
        }

        @Override
        public JsonObject serializeAdvancement() {
            return null; // no unlock advancement; the Tome is an admin/utility item
        }

        @Override
        public ResourceLocation getAdvancementId() {
            return null;
        }
    }
}
