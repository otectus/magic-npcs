package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.Item;

/**
 * The single seam to Iron's Spellbooks for the universal (mod-agnostic) casting
 * path. Every Iron's import in the mod lives in this {@code integration.irons}
 * package, which is only classloaded when Iron's is present (wired by
 * {@link IronsIntegration} behind {@code IronsCompat.isLoaded()}).
 *
 * <p>Per ADR 0001: {@code CastSource.MOB} neither consumes mana nor respects
 * cooldown in Iron's, and Iron's does not regenerate a foreign mob's mana — so
 * this class owns the mana economy (deduct on cast, periodic regen) itself.
 */
public final class IronsBridge {
    /** Item tag of "spell focuses" (staves/spellbooks/etc.) an NPC may need to hold to cast. */
    public static final TagKey<Item> SPELL_FOCUSES =
            TagKey.create(Registries.ITEM, new ResourceLocation("magicnpcs", "spell_focuses"));

    private IronsBridge() {}

    /** @return true if the caster holds an item in the {@link #SPELL_FOCUSES} tag in either hand. */
    public static boolean holdsSpellFocus(LivingEntity caster) {
        return caster.getItemInHand(InteractionHand.MAIN_HAND).is(SPELL_FOCUSES)
                || caster.getItemInHand(InteractionHand.OFF_HAND).is(SPELL_FOCUSES);
    }

    /** Resolve a loadout spell id to an Iron's spell, or {@code null} if unknown/unregistered. */
    public static AbstractSpell getSpell(ResourceLocation id) {
        AbstractSpell spell = SpellRegistry.getSpell(id);
        return (spell == null || spell == SpellRegistry.none()) ? null : spell;
    }

    public static boolean canAfford(LivingEntity caster, AbstractSpell spell, int level) {
        return MagicData.getPlayerMagicData(caster).getMana() >= spell.getManaCost(level);
    }

    /** Apply the spell's effects from {@code caster} and deduct its mana cost. */
    public static void cast(LivingEntity caster, AbstractSpell spell, int level) {
        MagicData data = MagicData.getPlayerMagicData(caster);
        float before = data.getMana();
        spell.onCast(caster.level(), level, caster, CastSource.MOB, data);
        data.addMana(-spell.getManaCost(level));
        if (MagicNpcsConfig.DEBUG_LOGGING.get()) {
            MagicNpcs.LOGGER.info("[cast] {} cast {} (lvl {}): mana {} -> {}",
                    EntityType.getKey(caster.getType()), spell.getSpellName(), level, before, data.getMana());
        }
    }

    /** Fill the mob's mana to its max (called on spawn/join). */
    public static void initMana(LivingEntity caster) {
        AttributeInstance max = caster.getAttribute(AttributeRegistry.MAX_MANA.get());
        if (max != null) {
            MagicData.getPlayerMagicData(caster).setMana((float) max.getValue());
        }
    }

    /**
     * One regen step, replicating Iron's player formula
     * ({@code maxMana * manaRegen * 0.01 * regenMultiplier}). Call every
     * {@code MagicManager.MANA_REGEN_TICKS}; foreign mobs get no Iron's regen.
     */
    public static void tickRegen(LivingEntity caster) {
        AttributeInstance max = caster.getAttribute(AttributeRegistry.MAX_MANA.get());
        AttributeInstance regen = caster.getAttribute(AttributeRegistry.MANA_REGEN.get());
        if (max == null || regen == null) {
            return;
        }
        float maxMana = (float) max.getValue();
        MagicData data = MagicData.getPlayerMagicData(caster);
        if (data.getMana() >= maxMana) {
            return;
        }
        float multiplier = (float) (double) MagicNpcsConfig.REGEN_MULTIPLIER.get();
        float next = data.getMana() + maxMana * (float) regen.getValue() * 0.01f * multiplier;
        data.setMana(Math.min(maxMana, next));
    }
}
