package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SpellInfo;
import com.otectus.magicnpcs.core.feedback.TelegraphInfo;
import com.otectus.magicnpcs.core.spell.SpellIdSuggestions;
import com.otectus.magicnpcs.core.spell.SpellSupportResolver;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.Item;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /**
     * {@code path -> namespaces} over the whole spell registry, built once on first use. Backs the
     * "did you mean" suggestions: with add-ons installed, several mods register the same path, so a
     * bare id or a wrong namespace has a plausible correction that the resolver must never take by
     * itself.
     */
    private static volatile Map<String, List<String>> pathIndex;

    private IronsBridge() {}

    /** @return the lazily built {@code path -> namespaces} index of every registered spell. */
    public static Map<String, List<String>> pathIndex() {
        Map<String, List<String>> index = pathIndex;
        if (index != null) {
            return index;
        }
        Map<String, List<String>> built = new LinkedHashMap<>();
        for (AbstractSpell spell : SpellRegistry.REGISTRY.get()) {
            if (spell == null || spell == SpellRegistry.none()) {
                continue;
            }
            ResourceLocation id = spell.getSpellResource();
            if (id == null) {
                continue;
            }
            built.computeIfAbsent(id.getPath(), k -> new ArrayList<>()).add(id.getNamespace());
        }
        index = Map.copyOf(built);
        pathIndex = index;
        return index;
    }

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
        String hint = SpellIdSuggestions.suggest(pathIndex(), spellId.toString())
                .map(s -> " (" + s + ")")
                .orElse("");
        MagicNpcs.LOGGER.warn(
                "Loadout {} ({}): unknown spell id '{}' in a 'spell' field — skipping it.{} "
                        + "Run /magicnpcs spells to list valid ids.",
                source != null ? source : "<in-code>", entityType, spellId, hint);
    }

    /**
     * Snapshot every registered Iron's spell as Iron's-free {@link SpellInfo} rows, sorted by id,
     * for the {@code /magicnpcs spells} command and the generated reference. {@code mobFriendly} is no
     * longer the "is it INSTANT?" heuristic it was through 0.6.1 — it is the reviewed manifest verdict,
     * so the command's tick marks now mean "Magic NPCs has verified a mob gets this spell's designed
     * behaviour" rather than "it happens to resolve in one tick" (audit SPI-002).
     */
    public static List<SpellInfo> listSpells() {
        List<SpellInfo> out = new ArrayList<>();
        for (AbstractSpell spell : SpellRegistry.REGISTRY.get()) {
            if (spell == null || spell == SpellRegistry.none()) {
                continue;
            }
            ResourceLocation spellId = spell.getSpellResource();
            if (spellId == null) {
                continue; // an addon spell with no registry id: skip it rather than NPE the whole list
            }
            SchoolType school = spell.getSchoolType();
            String schoolPath = school != null && school.getId() != null ? school.getId().getPath() : "";
            CastType castType = spell.getCastType();
            out.add(new SpellInfo(
                    spellId.toString(),
                    schoolPath,
                    spell.getRarity(1).name(),
                    spell.getSpellCooldown(),
                    castType != null ? castType.name() : "NONE",
                    SpellCompat.supportOf(spell) == SpellCompat.Support.SUPPORTED,
                    SpellCompat.provenanceOf(spell).name()));
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
            Optional<String> suggestion = SpellIdSuggestions.suggest(pathIndex(), rawId);
            return new com.otectus.magicnpcs.core.SpellDiagnostic(rawId, false, false,
                    "UNKNOWN", "NONE", "UNSUPPORTED", false,
                    "no spell with this id is registered", false, 0,
                    SpellSupportResolver.Provenance.UNVERIFIED.name(), suggestion.orElse(null));
        }
        SpellCompat.Support support = SpellCompat.supportOf(spell);
        CastType castType = spell.getCastType();
        return new com.otectus.magicnpcs.core.SpellDiagnostic(
                spell.getSpellResource().toString(),
                true,
                spell.isEnabled(),
                SpellCompat.categoryName(spell),
                castType != null ? castType.name() : "NONE",
                support.name(),
                SpellCompat.castableByMob(spell),
                support == SpellCompat.Support.SUPPORTED ? null : SpellCompat.unsupportedReason(spell),
                SpellCompat.requiresTargetEntity(spell),
                spell.getSpellCooldown(),
                SpellCompat.provenanceOf(spell).name(),
                support == SpellCompat.Support.SUPPORTED ? null : SpellCompat.fixHint(spell));
    }

    public static boolean canAfford(LivingEntity caster, AbstractSpell spell, int level) {
        return MagicData.getPlayerMagicData(caster).getMana() >= spell.getManaCost(level);
    }

    /**
     * @return Iron's <em>effective</em> cast time (ticks) for a channelled spell on this caster, or 0
     *         for an instant one. Effective, not raw: the raw {@code getCastTime} ignores every
     *         caster-side cast-time modifier Iron's applies, so the mod's wind-up drifted out of step
     *         with the channel it was pacing.
     */
    public static int castTime(AbstractSpell spell, int level, LivingEntity caster) {
        return SpellCompat.effectiveCastTime(spell, level, caster);
    }

    /**
     * The blacklist/whitelist verdict for a <em>resolved</em> spell, which is the only form the cast
     * path checks. Callers that filter loadout entries must use this rather than testing the raw
     * datapack id, or a bare id like {@code magic_missile} passes their filter and is then refused at
     * cast time — leaving the mob to replay its wind-up forever.
     */
    public static boolean isAllowedSpell(AbstractSpell spell) {
        ResourceLocation id = spell.getSpellResource();
        return id == null || MagicNpcsConfig.isAllowed(id.toString());
    }

    /**
     * @return the caster's current mana. Iron's-free callers (commands, the Easy NPC dialog
     *         conditions) need this without importing {@code MagicData}, which is the whole point of
     *         this bridge.
     */
    public static float currentMana(LivingEntity caster) {
        return MagicData.getPlayerMagicData(caster).getMana();
    }

    /** Fill the mob's mana to its max (called on spawn/join). */
    public static void initMana(LivingEntity caster) {
        AttributeInstance max = caster.getAttribute(AttributeRegistry.MAX_MANA.get());
        if (max != null) {
            MagicData.getPlayerMagicData(caster).setMana((float) max.getValue());
        }
    }

    /**
     * Clamp a caster's current mana into {@code [0, maxMana]}.
     *
     * <p>Iron's {@code MagicData.setMana} only clamps inside its {@code serverPlayer != null} branch,
     * so for a mob nothing bounds the value at either end. Lowering {@code manaMultiplier}, switching
     * Hard→Easy, or a recruit losing rank therefore left the mob permanently above its new maximum
     * with no path back down — {@link #tickRegen} returns early once mana is at or above max, so it
     * never corrected itself.
     */
    public static void clampMana(LivingEntity caster, double maxMana) {
        MagicData data = MagicData.getPlayerMagicData(caster);
        float current = data.getMana();
        float clamped = (float) Math.max(0.0, Math.min(maxMana, current));
        if (clamped != current) {
            data.setMana(clamped);
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
