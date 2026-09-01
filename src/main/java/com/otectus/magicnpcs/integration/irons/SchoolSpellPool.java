package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.diag.SchoolPoolReport;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import com.otectus.magicnpcs.core.util.Weights;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds a {@link SpellcasterLoadout} dynamically from an Iron's {@link SchoolType}:
 * the spells are whatever that school currently offers (so addon-added spells are
 * picked up automatically), filtered and weighted per {@link MagicNpcsConfig}. The
 * resulting loadout feeds the unchanged {@link NpcSpellAttackGoal}.
 *
 * <p>{@link #report(SchoolType)} runs the same filter chain but records <em>why</em> each spell was
 * dropped, so {@code /magicnpcs school pool} and the reload summary can explain an empty school instead
 * of a command silently reporting "0 NPCs" (W4).
 *
 * <p>Iron's-side: lives in {@code integration.irons}, classloaded only when Iron's is present.
 */
public final class SchoolSpellPool {
    private static final String[] SUPPORT_KEYWORDS =
            {"heal", "cure", "blessing", "regen", "haste", "shield", "ward", "fortify"};

    private SchoolSpellPool() {}

    /** @return a synthesized loadout for {@code school}, or {@code null} if the school yields no castable spells. */
    public static SpellcasterLoadout buildLoadout(SchoolType school, Mob mob) {
        List<Candidate> candidates = candidates(school, null);
        if (candidates.isEmpty()) {
            return null;
        }
        double maxRange = MagicNpcsConfig.SCHOOLS_ATTACK_MAX_RANGE.get();
        int want = Math.min(MagicNpcsConfig.SCHOOLS_SPELLS_PER_SCHOOL.get(), candidates.size());
        List<Candidate> chosen = weightedSample(candidates, want, mob.getRandom());
        // A caster that can never hold a target can only ever use SUPPORT spells, so a sample that
        // happened to draw four ATTACK spells left it with a casting goal, a regenerating mana pool,
        // and nothing it could ever do. Swap one in rather than shipping a caster that cannot cast.
        if (needsSupport(mob)) {
            ensureSupport(chosen, candidates, mob);
        }

        List<LoadoutEntry> spells = new ArrayList<>(chosen.size());
        for (Candidate c : chosen) {
            double range = c.role == LoadoutEntry.Role.ATTACK ? maxRange : 0.0;
            spells.add(new LoadoutEntry(c.id, c.level, c.weight, 0.0, range, 1.5, c.role));
        }

        double baseMana = MagicNpcsConfig.SCHOOLS_BASE_MAX_MANA.get();
        double regen = MagicNpcsConfig.SCHOOLS_BASE_MANA_REGEN.get();
        return new SpellcasterLoadout(EntityType.getKey(mob.getType()), baseMana, regen, spells);
    }

    /**
     * Run the filter chain for {@code school} and record every decision, for diagnostics. Never
     * consumes randomness and never touches an entity, so it is safe to call from a command or at
     * reload time.
     */
    public static SchoolPoolReport report(SchoolType school) {
        List<SchoolPoolReport.Drop> drops = new ArrayList<>();
        List<Candidate> survivors = candidates(school, drops);
        List<SchoolPoolReport.Survivor> rows = new ArrayList<>(survivors.size());
        for (Candidate c : survivors) {
            rows.add(new SchoolPoolReport.Survivor(c.id.toString(), c.level, c.rarity,
                    c.role.name().toLowerCase(Locale.ROOT), c.weight));
        }
        rows.sort(Comparator.comparing(SchoolPoolReport.Survivor::spellId));
        drops.sort(Comparator.comparing(SchoolPoolReport.Drop::spellId));
        return new SchoolPoolReport(school.getId(), rows.size() + drops.size(), List.copyOf(rows), List.copyOf(drops));
    }

    /** Every allowed school's pool composition, for the once-per-reload summary. */
    public static List<SchoolPoolReport> reportAll() {
        List<SchoolPoolReport> out = new ArrayList<>();
        for (ResourceLocation id : MagicNpcsConfig.allowedSchoolIds()) {
            SchoolType school = SchoolRegistry.getSchool(id);
            if (school != null) {
                out.add(report(school));
            }
        }
        return out;
    }

    /**
     * The single filter chain, shared by {@link #buildLoadout} and {@link #report} so the diagnostic can
     * never drift from the behaviour. When {@code drops} is non-null, each rejected spell is recorded
     * with the first filter that rejected it.
     *
     * <p>Note on cast types: {@code CONTINUOUS} spells are excluded by default because nothing drives a
     * channel loop for a mob, but {@code LONG} spells are now included — 0.5.0 taught the goal to
     * channel them ({@code resolveWindup} → Iron's cast time, then {@code onServerCastComplete}), while
     * this filter still rejected them, which on its own emptied several schools (W4).
     */
    private static List<Candidate> candidates(SchoolType school, List<SchoolPoolReport.Drop> drops) {
        SpellRarity rarityCap = parseRarity(MagicNpcsConfig.SCHOOLS_MAX_RARITY.get());
        int levelCap = MagicNpcsConfig.SCHOOLS_MAX_SPELL_LEVEL.get();
        boolean includeSupport = MagicNpcsConfig.SCHOOLS_INCLUDE_SUPPORT.get();
        boolean inverseRarity = MagicNpcsConfig.SCHOOLS_WEIGHTING_MODE.get().equalsIgnoreCase("INVERSE_RARITY");
        List<String> allowedCastTypes = MagicNpcsConfig.allowedCastTypes();

        List<Candidate> candidates = new ArrayList<>();
        // getSpellsForSchool returns every registered spell in the school, disabled ones included
        // (verified against Iron's 3.15.2 — it is a plain registry filter), so isEnabled() is required.
        for (AbstractSpell spell : SpellRegistry.getSpellsForSchool(school)) {
            ResourceLocation id = spell.getSpellResource();
            String spellId = id == null ? spell.getSpellName() : id.toString();
            if (!spell.isEnabled()) {
                drop(drops, spellId, "disabled in Iron's own spell config");
                continue;
            }
            CastType castType = spell.getCastType();
            String castTypeName = castType == null ? "NONE" : castType.name();
            if (!allowedCastTypes.contains(castTypeName)) {
                drop(drops, spellId, castTypeName + " cast type is not in schools.allowedCastTypes "
                        + allowedCastTypes);
                continue;
            }
            if (id == null) {
                drop(drops, spellId, "the spell has no registry id");
                continue;
            }
            if (!MagicNpcsConfig.isAllowed(id.toString())) {
                drop(drops, spellId, "excluded by spells.spellBlacklist / spellWhitelist");
                continue;
            }
            if (!SpellCompat.castableByMob(spell)) {
                drop(drops, spellId, SpellCompat.unsupportedReason(spell));
                continue;
            }
            int level = Math.max(spell.getMinLevel(), Math.min(spell.getMaxLevel(), levelCap));
            SpellRarity rarity = spell.getRarity(level);
            if (rarity.getValue() > rarityCap.getValue()) {
                drop(drops, spellId, rarity.name() + " at level " + level
                        + " is above schools.maxRarity=" + rarityCap.name());
                continue;
            }
            boolean support = isSupport(id, spell);
            if (support && !includeSupport) {
                drop(drops, spellId, "support spell, and schools.includeSupportSpells is false");
                continue;
            }
            int weight = inverseRarity
                    ? Math.max(1, (SpellRarity.LEGENDARY.getValue() + 1) - rarity.getValue())
                    : 1;
            candidates.add(new Candidate(id, level, weight,
                    support ? LoadoutEntry.Role.SUPPORT : LoadoutEntry.Role.ATTACK, rarity.name()));
        }
        return candidates;
    }

    /**
     * @return true if this mob can only ever use SUPPORT spells, so a pool without one is useless to
     *         it. A vanilla villager has an empty {@code GoalSelector} and nothing ever calls
     *         {@code setTarget} on it — not even a raid — so it never has a target, and the casting
     *         goal only considers ATTACK entries when it does. Enabling the self-defence goal gives it
     *         a way to acquire one, at which point ATTACK spells become useful again.
     */
    public static boolean needsSupport(Mob mob) {
        return mob instanceof net.minecraft.world.entity.npc.Villager
                && !MagicNpcsConfig.SCHOOLS_VILLAGERS_SELF_DEFENSE.get();
    }

    /** @return true if this school currently yields at least one SUPPORT spell a mob could self-cast. */
    public static boolean hasSupportSpell(SchoolType school) {
        for (Candidate c : candidates(school, null)) {
            if (c.role == LoadoutEntry.Role.SUPPORT) {
                return true;
            }
        }
        return false;
    }

    /** Replace one sampled ATTACK spell with a SUPPORT spell, if the school has one to give. */
    private static void ensureSupport(List<Candidate> chosen, List<Candidate> pool, Mob mob) {
        for (Candidate c : chosen) {
            if (c.role == LoadoutEntry.Role.SUPPORT) {
                return;
            }
        }
        List<Candidate> support = new ArrayList<>();
        for (Candidate c : pool) {
            if (c.role == LoadoutEntry.Role.SUPPORT) {
                support.add(c);
            }
        }
        if (support.isEmpty()) {
            return; // nothing to offer; the caller's school choice should have avoided this school
        }
        Candidate pick = support.get(mob.getRandom().nextInt(support.size()));
        if (chosen.isEmpty()) {
            chosen.add(pick);
        } else {
            chosen.set(chosen.size() - 1, pick);
        }
    }

    private static void drop(List<SchoolPoolReport.Drop> drops, String spellId, String reason) {
        if (drops != null) {
            drops.add(new SchoolPoolReport.Drop(spellId, reason));
        }
    }

    /**
     * Decide whether a generated-pool spell is a self-cast SUPPORT entry.
     *
     * <p>The configured {@code schools.supportSpellIds} list is authoritative. Failing that, a spell
     * whose reviewed capability says it needs a hostile <em>target entity</em> can never be a self-cast,
     * so it is an ATTACK entry regardless of what it is called — that check comes first, because it is
     * a targeting fact rather than a guess.
     *
     * <p>Only then does the name-keyword heuristic run, and only as a last resort. It was the whole
     * classification through 0.6.1, which misfiled any add-on spell whose name happened to contain
     * "shield" or "ward" and any healing spell that did not (audit "School pool classification").
     */
    private static boolean isSupport(ResourceLocation id, AbstractSpell spell) {
        if (MagicNpcsConfig.isSupportSpellId(id.toString())) {
            return true;
        }
        if (SpellCompat.requiresTargetEntity(spell)) {
            return false; // needs someone to point at; a self-cast cannot supply that
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
            long total = 0L;
            for (Candidate c : remaining) {
                total = Weights.saturatingAdd(total, Weights.normalize(c.weight));
            }
            long roll = Weights.roll(total, random);
            int idx = 0;
            for (; idx < remaining.size(); idx++) {
                roll -= Weights.normalize(remaining.get(idx).weight);
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

    private record Candidate(ResourceLocation id, int level, int weight, LoadoutEntry.Role role, String rarity) {}
}
