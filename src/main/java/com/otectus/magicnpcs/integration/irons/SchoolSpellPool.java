package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import io.redspace.ironsspellbooks.api.spells.SpellRarity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds a {@link SpellcasterLoadout} dynamically from an Iron's {@link SchoolType}:
 * the spells are whatever that school currently offers (so addon-added spells are
 * picked up automatically), filtered and weighted per {@link MagicNpcsConfig}. The
 * resulting loadout feeds the unchanged {@link NpcSpellAttackGoal}.
 *
 * <p>Iron's-side: lives in {@code integration.irons}, classloaded only when Iron's
 * is present.
 */
public final class SchoolSpellPool {
    private static final String[] SUPPORT_KEYWORDS =
            {"heal", "cure", "blessing", "regen", "haste", "shield", "ward", "fortify"};

    private SchoolSpellPool() {}

    /** @return a synthesized loadout for {@code school}, or {@code null} if the school yields no castable spells. */
    public static SpellcasterLoadout buildLoadout(SchoolType school, Mob mob) {
        SpellRarity rarityCap = parseRarity(MagicNpcsConfig.SCHOOLS_MAX_RARITY.get());
        int levelCap = MagicNpcsConfig.SCHOOLS_MAX_SPELL_LEVEL.get();
        boolean includeSupport = MagicNpcsConfig.SCHOOLS_INCLUDE_SUPPORT.get();
        boolean inverseRarity = MagicNpcsConfig.SCHOOLS_WEIGHTING_MODE.get().equalsIgnoreCase("INVERSE_RARITY");
        double maxRange = MagicNpcsConfig.SCHOOLS_ATTACK_MAX_RANGE.get();

        List<Candidate> candidates = new ArrayList<>();
        for (AbstractSpell spell : SpellRegistry.getSpellsForSchool(school)) {
            if (!spell.isEnabled() || spell.getCastType() != CastType.INSTANT) {
                continue; // the goal only drives one-shot INSTANT spells
            }
            ResourceLocation id = spell.getSpellResource();
            if (!MagicNpcsConfig.isAllowed(id.toString())) {
                continue;
            }
            int level = Math.max(spell.getMinLevel(), Math.min(spell.getMaxLevel(), levelCap));
            if (spell.getRarity(level).getValue() > rarityCap.getValue()) {
                continue; // too rare/powerful for the configured cap
            }
            boolean support = isSupport(id, spell);
            if (support && !includeSupport) {
                continue;
            }
            int weight = inverseRarity
                    ? Math.max(1, (SpellRarity.LEGENDARY.getValue() + 1) - spell.getRarity(level).getValue())
                    : 1;
            candidates.add(new Candidate(id, level, weight,
                    support ? LoadoutEntry.Role.SUPPORT : LoadoutEntry.Role.ATTACK));
        }
        if (candidates.isEmpty()) {
            return null;
        }

        int want = Math.min(MagicNpcsConfig.SCHOOLS_SPELLS_PER_SCHOOL.get(), candidates.size());
        List<Candidate> chosen = weightedSample(candidates, want, mob.getRandom());

        List<LoadoutEntry> spells = new ArrayList<>(chosen.size());
        for (Candidate c : chosen) {
            double range = c.role == LoadoutEntry.Role.ATTACK ? maxRange : 0.0;
            spells.add(new LoadoutEntry(c.id, c.level, c.weight, 0.0, range, 1.5, c.role));
        }

        double baseMana = MagicNpcsConfig.SCHOOLS_BASE_MAX_MANA.get();
        double regen = MagicNpcsConfig.SCHOOLS_BASE_MANA_REGEN.get();
        return new SpellcasterLoadout(EntityType.getKey(mob.getType()), baseMana, regen, spells);
    }

    private static boolean isSupport(ResourceLocation id, AbstractSpell spell) {
        if (MagicNpcsConfig.isSupportSpellId(id.toString())) {
            return true;
        }
        String name = spell.getSpellName().toLowerCase(Locale.ROOT);
        for (String kw : SUPPORT_KEYWORDS) {
            if (name.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** Pick {@code count} distinct candidates with probability proportional to weight. */
    private static List<Candidate> weightedSample(List<Candidate> pool, int count, RandomSource random) {
        List<Candidate> remaining = new ArrayList<>(pool);
        List<Candidate> out = new ArrayList<>(count);
        while (out.size() < count && !remaining.isEmpty()) {
            int total = 0;
            for (Candidate c : remaining) {
                total += c.weight;
            }
            int roll = random.nextInt(total);
            int idx = 0;
            for (; idx < remaining.size(); idx++) {
                roll -= remaining.get(idx).weight;
                if (roll < 0) {
                    break;
                }
            }
            out.add(remaining.remove(Math.min(idx, remaining.size() - 1)));
        }
        return out;
    }

    private static SpellRarity parseRarity(String s) {
        try {
            return SpellRarity.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return SpellRarity.RARE;
        }
    }

    private record Candidate(ResourceLocation id, int level, int weight, LoadoutEntry.Role role) {}
}
