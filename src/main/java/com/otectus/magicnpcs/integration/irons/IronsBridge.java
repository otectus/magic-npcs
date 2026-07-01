package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SpellInfo;
import com.otectus.magicnpcs.core.feedback.TelegraphInfo;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastSource;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import io.redspace.ironsspellbooks.capabilities.magic.TargetEntityCastData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.Item;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * @return true if the caster holds, in either hand, an item that is a focus for the
     *         given Iron's school (Iron's {@code SchoolType.isFocus}, backed by per-school
     *         {@code irons_spellbooks:<school>_focus} item tags). Backs the optional
     *         {@code schools.schoolAwareFocus} relaxation of the held-focus requirement.
     */
    public static boolean holdsSchoolFocus(LivingEntity caster, ResourceLocation schoolId) {
        SchoolType school = SchoolRegistry.getSchool(schoolId);
        if (school == null) {
            return false;
        }
        return school.isFocus(caster.getItemInHand(InteractionHand.MAIN_HAND))
                || school.isFocus(caster.getItemInHand(InteractionHand.OFF_HAND));
    }

    /** @return the Iron's per-school focus item tag for {@code schoolId}, or {@code null} if unknown. */
    public static TagKey<Item> schoolFocusTag(ResourceLocation schoolId) {
        SchoolType school = SchoolRegistry.getSchool(schoolId);
        return school == null ? null : school.getFocus();
    }

    /**
     * Build a vanilla-only {@link TelegraphInfo} for a spell: the caster's wind-up "tell" is
     * tinted by the spell's Iron's school colour and given a danger tier from rarity + AoE size.
     * The only place Iron's school colour/sound are read; returns plain data so the spawner stays
     * Iron's-free.
     */
    public static TelegraphInfo telegraphFor(AbstractSpell spell, int level, double safetyRadius) {
        SchoolType school = spell.getSchoolType();
        Vector3f color = school != null ? school.getTargetingColor() : null;
        float r = color != null ? color.x() : 0.8f;
        float g = color != null ? color.y() : 0.8f;
        float b = color != null ? color.z() : 1.0f;
        SoundEvent castSound = school != null ? school.getCastSound() : null;
        if (castSound == null) {
            castSound = SoundEvents.EVOKER_PREPARE_ATTACK;
        }
        ResourceLocation soundId = BuiltInRegistries.SOUND_EVENT.getKey(castSound);
        int tier = Math.min(4, spell.getRarity(level).getValue() + (safetyRadius >= 3.0 ? 1 : 0));
        return new TelegraphInfo(r, g, b, soundId, tier);
    }

    /**
     * Resolve a loadout spell id to an Iron's spell, or {@code null} if unknown/unregistered. A bare
     * id (no namespace) parses to the {@code minecraft} namespace, which is never a spell; as a
     * convenience we retry such ids under {@code irons_spellbooks} so e.g. {@code devour} resolves
     * to {@code irons_spellbooks:devour}.
     */
    public static AbstractSpell getSpell(ResourceLocation id) {
        AbstractSpell spell = SpellRegistry.getSpell(id);
        if ((spell == null || spell == SpellRegistry.none()) && "minecraft".equals(id.getNamespace())) {
            spell = SpellRegistry.getSpell(new ResourceLocation("irons_spellbooks", id.getPath()));
        }
        return (spell == null || spell == SpellRegistry.none()) ? null : spell;
    }

    /**
     * Log a single clear warning that a loadout referenced a spell id that doesn't resolve to any
     * registered Iron's spell — naming the datapack file, entity type, the {@code spell} field, and
     * the bad id, with a hint to run {@code /magicnpcs spells}. Common cause: a missing
     * {@code irons_spellbooks:} namespace or a wrong path (e.g. {@code fangward} vs {@code fang_ward}).
     */
    public static void warnUnknownSpell(ResourceLocation source, ResourceLocation entityType, ResourceLocation spellId) {
        String hint = "minecraft".equals(spellId.getNamespace())
                ? " (did you mean irons_spellbooks:" + spellId.getPath() + "?)"
                : "";
        MagicNpcs.LOGGER.warn(
                "Loadout {} ({}): unknown spell id '{}' in a 'spell' field — skipping it.{} "
                        + "Run /magicnpcs spells to list valid ids.",
                source != null ? source : "<in-code>", entityType, spellId, hint);
    }

    /**
     * Snapshot every registered Iron's spell as Iron's-free {@link SpellInfo} rows, sorted by id,
     * for the {@code /magicnpcs spells} command and the generated reference. {@code mobFriendly} is a
     * heuristic: an {@code INSTANT} cast is reliable for a generic mob (target-aimed, no channel).
     */
    public static List<SpellInfo> listSpells() {
        List<SpellInfo> out = new ArrayList<>();
        for (AbstractSpell spell : SpellRegistry.REGISTRY.get()) {
            if (spell == null || spell == SpellRegistry.none()) {
                continue;
            }
            SchoolType school = spell.getSchoolType();
            String schoolPath = school != null && school.getId() != null ? school.getId().getPath() : "";
            CastType castType = spell.getCastType();
            out.add(new SpellInfo(
                    spell.getSpellResource().toString(),
                    schoolPath,
                    spell.getRarity(1).name(),
                    spell.getSpellCooldown(),
                    castType != null ? castType.name() : "NONE",
                    castType == CastType.INSTANT));
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    /**
     * Resolve a loadout spell id to an Iron's-free {@link com.otectus.magicnpcs.core.SpellDiagnostic}
     * for the {@code /magicnpcs loadout} and {@code /magicnpcs validate} commands: whether the id
     * exists/is enabled, its mob-cast category and cast type, and whether it needs a target. Bare ids
     * are auto-namespaced exactly as {@link #getSpell} does.
     */
    public static com.otectus.magicnpcs.core.SpellDiagnostic diagnose(String rawId) {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        AbstractSpell spell = id == null ? null : getSpell(id);
        if (spell == null) {
            return new com.otectus.magicnpcs.core.SpellDiagnostic(rawId, false, false,
                    "UNKNOWN", "NONE", false, false, 0);
        }
        SpellCompat.Category category = SpellCompat.categoryOf(spell);
        CastType castType = spell.getCastType();
        return new com.otectus.magicnpcs.core.SpellDiagnostic(
                spell.getSpellResource().toString(),
                true,
                spell.isEnabled(),
                category.name(),
                castType != null ? castType.name() : "NONE",
                SpellCompat.supportedForMob(category),
                SpellCompat.requiresTargetEntity(category),
                spell.getSpellCooldown());
    }

    public static boolean canAfford(LivingEntity caster, AbstractSpell spell, int level) {
        return MagicData.getPlayerMagicData(caster).getMana() >= spell.getManaCost(level);
    }

    /** @return Iron's cast time (ticks) for a long/channelled spell, or 0 for an instant one. */
    public static int castTime(AbstractSpell spell, int level) {
        return SpellCompat.isLongCast(spell) ? Math.max(0, spell.getCastTime(level)) : 0;
    }

    /** Apply a spell with no explicit target (self/forward/aimed spells). */
    public static void cast(LivingEntity caster, AbstractSpell spell, int level) {
        cast(caster, null, spell, level);
    }

    /**
     * Apply {@code spell} from {@code caster} at {@code target}, wiring up the cast data the spell
     * needs for a non-player mob: a {@link TargetEntityCastData} for target-locked spells (root,
     * devour, wisp), Iron's pre-cast check after that data is present, then {@code onCast} plus
     * {@code onServerCastComplete} for long casts. Deducts the spell's mana cost on a real cast.
     * Spells that can't be supplied their required data (multi-target / player-only) are skipped with
     * a debug reason rather than mis-fired. {@code target} may be {@code null} for self/forward spells.
     *
     * @return true if the spell actually cast (so the caller can gate cooldown/swing on it)
     */
    public static boolean cast(LivingEntity caster, LivingEntity target, AbstractSpell spell, int level) {
        SpellCompat.Category category = SpellCompat.categoryOf(spell);
        if (!SpellCompat.supportedForMob(category)) {
            if (MagicNpcsConfig.DEBUG_LOGGING.get()) {
                MagicNpcs.LOGGER.info("[cast] skipping {} for {}: {}", spell.getSpellName(),
                        EntityType.getKey(caster.getType()), SpellCompat.unsupportedReason(category));
            }
            return false;
        }
        MagicData data = MagicData.getPlayerMagicData(caster);
        boolean targetData = false;
        if (SpellCompat.requiresTargetEntity(category)) {
            if (target == null) {
                if (MagicNpcsConfig.DEBUG_LOGGING.get()) {
                    MagicNpcs.LOGGER.info("[cast] skipping {} for {}: requires a target but none was supplied",
                            spell.getSpellName(), EntityType.getKey(caster.getType()));
                }
                return false;
            }
            // Set the target BEFORE the pre-cast check — root/devour/wisp read it from there.
            data.setAdditionalCastData(new TargetEntityCastData(target));
            targetData = true;
            if (!spell.checkPreCastConditions(caster.level(), level, caster, data)) {
                data.resetAdditionalCastData();
                if (MagicNpcsConfig.DEBUG_LOGGING.get()) {
                    MagicNpcs.LOGGER.info("[cast] skipping {} for {}: pre-cast conditions not met",
                            spell.getSpellName(), EntityType.getKey(caster.getType()));
                }
                return false;
            }
        }
        float before = data.getMana();
        spell.onCast(caster.level(), level, caster, CastSource.MOB, data);
        // Long (channelled) spells finalize through onServerCastComplete; instant ones don't need it
        // (and the existing instant path is proven without it, so don't change that behaviour).
        if (SpellCompat.isLongCast(spell)) {
            spell.onServerCastComplete(caster.level(), level, caster, data, false);
        }
        data.addMana(-spell.getManaCost(level));
        if (targetData) {
            data.resetAdditionalCastData(); // don't leak the target into the next cast
        }
        if (MagicNpcsConfig.DEBUG_LOGGING.get()) {
            MagicNpcs.LOGGER.info("[cast] {} cast {} (lvl {}): mana {} -> {}",
                    EntityType.getKey(caster.getType()), spell.getSpellName(), level, before, data.getMana());
        }
        return true;
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
