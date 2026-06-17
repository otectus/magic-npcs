package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.command.SchoolCommand;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import io.redspace.ironsspellbooks.api.entity.IMagicEntity;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import io.redspace.ironsspellbooks.entity.mobs.goals.WizardAttackGoal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Map;

/**
 * FORGE-bus handler driving the universal casting path: registers the loadout
 * datapack listener, primes mana + injects the casting goal for any mob whose
 * type has a loadout, and ticks our own mana regen (Iron's does not regen foreign
 * mobs — ADR 0001). Max-mana is scaled by the per-mob {@link NpcAdapters adapter}
 * (e.g. Recruits rank) and re-scaled on the regen cadence so it tracks level-ups.
 *
 * <p>Goal choice: when {@code recruits.useIronsAI} is on and the mob is
 * {@link IMagicEntity} (the Recruits mixin applied), inject Iron's own
 * {@code WizardAttackGoal}; otherwise the built-in {@link NpcSpellAttackGoal}.
 * Server-side only; gated by {@link MagicNpcsConfig}.
 */
public class IronsSpellcasterHandler {

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new LoadoutManager());
    }

    @SubscribeEvent
    public void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        if (MagicNpcsConfig.SCHOOLS_COMMAND_ENABLED.get()) {
            SchoolCommand.register(event.getDispatcher());
        }
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || !MagicNpcsConfig.ENABLE_SPELLCASTING.get()
                || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (hasSpellGoal(mob)) {
            return; // already injected (e.g. chunk reload)
        }
        ResourceLocation typeId = EntityType.getKey(mob.getType());
        SpellcasterLoadout loadout = LoadoutManager.get(typeId);
        if (loadout != null) {
            if (!MagicNpcsConfig.isLoadoutEnabledFor(typeId)) {
                if (MagicNpcsConfig.DEBUG_LOGGING.get() && MagicNpcsConfig.ownerModLoaded(typeId)) {
                    MagicNpcs.LOGGER.info("[compat] loadout for {} skipped — its compat toggle is disabled", typeId);
                }
                return; // an optional NPC mod's loadout whose compat toggle is off
            }
        } else {
            loadout = trySchoolLoadout(mob); // recruits/villagers assigned a magic school
            if (loadout == null) {
                return; // not a spellcaster
            }
        }
        applyLoadout(mob, loadout);
    }

    /** Set mana attributes from a loadout's base values and inject the casting goal. */
    private static void applyLoadout(Mob mob, SpellcasterLoadout loadout) {
        AttributeInstance maxMana = mob.getAttribute(AttributeRegistry.MAX_MANA.get());
        AttributeInstance manaRegen = mob.getAttribute(AttributeRegistry.MANA_REGEN.get());
        if (maxMana == null || manaRegen == null) {
            return; // type lacks mana attributes (shouldn't happen — added in IronsAttributeHandler)
        }
        maxMana.setBaseValue(desiredMaxMana(mob, loadout.maxMana()));
        manaRegen.setBaseValue(loadout.manaRegen());
        IronsBridge.initMana(mob);
        maybeGiveSpellFocus(mob);

        if (useIronsGoal(mob)) {
            mob.goalSelector.addGoal(2, IronsGoalFactory.wizardGoal(
                    mob, loadout,
                    MagicNpcsConfig.RECRUITS_IRONS_AI_SPEED.get(),
                    MagicNpcsConfig.RECRUITS_IRONS_AI_INTERVAL.get(),
                    spellQuality(mob)));
        } else {
            mob.goalSelector.addGoal(2, new NpcSpellAttackGoal(mob, loadout));
        }
    }

    /**
     * Resolve (or roll, once) this mob's assigned school and synthesize a loadout from
     * it. Returns null when schools are disabled, the mob is not eligible, it rolled a
     * non-caster, or the school yields no castable spells.
     */
    private static SpellcasterLoadout trySchoolLoadout(Mob mob) {
        if (!MagicNpcsConfig.SCHOOLS_ENABLED.get()) {
            return null;
        }
        ResourceLocation schoolId = SchoolData.getSchool(mob);
        if (schoolId == null) {
            if (SchoolData.hasRolled(mob)) {
                return null; // sticky non-caster
            }
            schoolId = rollSchool(mob);
            if (schoolId == null) {
                return null;
            }
        }
        SchoolType school = SchoolRegistry.getSchool(schoolId);
        if (school == null) {
            return null; // school not present in this Iron's build
        }
        return SchoolSpellPool.buildLoadout(school, mob);
    }

    /** Eligibility + caster-chance roll; persists the outcome. Returns the chosen school or null. */
    private static ResourceLocation rollSchool(Mob mob) {
        NpcAdapter adapter = NpcAdapters.resolve(mob);
        RandomSource rng = mob.getRandom();

        // Recruit-style progression NPC.
        if (adapter.schoolAssignable(mob) && MagicNpcsConfig.SCHOOLS_RECRUITS_ENABLED.get()
                && adapter.level(mob) >= MagicNpcsConfig.SCHOOLS_RECRUITS_MIN_RANK.get()) {
            if (rng.nextDouble() < MagicNpcsConfig.SCHOOLS_RECRUITS_CASTER_CHANCE.get()) {
                ResourceLocation s = pickRecruitSchool(mob, adapter, rng);
                if (s != null) {
                    SchoolData.set(mob, s);
                    return s;
                }
            }
            SchoolData.markNonCaster(mob);
            return null;
        }

        // Villager (vanilla + profession mods extending Villager).
        if (mob instanceof Villager villager && MagicNpcsConfig.SCHOOLS_VILLAGERS_ENABLED.get()) {
            ResourceLocation s = pickVillagerSchool(villager, rng);
            if (s != null && rng.nextDouble() < MagicNpcsConfig.SCHOOLS_VILLAGERS_CASTER_CHANCE.get()) {
                SchoolData.set(mob, s);
                return s;
            }
            SchoolData.markNonCaster(mob);
            return null;
        }

        // Not eligible — leave persistent data untouched (don't tag every mob in the world).
        return null;
    }

    private static ResourceLocation pickRecruitSchool(Mob mob, NpcAdapter adapter, RandomSource rng) {
        List<ResourceLocation> allowed = MagicNpcsConfig.allowedSchoolIds();
        if (allowed.isEmpty()) {
            return null;
        }
        return switch (MagicNpcsConfig.SCHOOLS_RECRUITS_MODE.get().toUpperCase(java.util.Locale.ROOT)) {
            case "BY_TYPE" -> {
                Map<ResourceLocation, List<ResourceLocation>> map =
                        MagicNpcsConfig.parsePairMap(MagicNpcsConfig.SCHOOLS_RECRUITS_TYPE_SCHOOLS.get());
                List<ResourceLocation> opts = intersect(map.get(EntityType.getKey(mob.getType())), allowed);
                yield (opts.isEmpty() ? allowed : opts).get(rng.nextInt(opts.isEmpty() ? allowed.size() : opts.size()));
            }
            case "BY_RANK" -> allowed.get(Math.floorMod(adapter.level(mob), allowed.size()));
            default -> allowed.get(rng.nextInt(allowed.size())); // RANDOM
        };
    }

    private static ResourceLocation pickVillagerSchool(Villager villager, RandomSource rng) {
        List<ResourceLocation> allowed = MagicNpcsConfig.allowedSchoolIds();
        if (allowed.isEmpty()) {
            return null;
        }
        ResourceLocation profession = BuiltInRegistries.VILLAGER_PROFESSION.getKey(
                villager.getVillagerData().getProfession());
        Map<ResourceLocation, List<ResourceLocation>> map =
                MagicNpcsConfig.parsePairMap(MagicNpcsConfig.SCHOOLS_VILLAGERS_PROFESSION_SCHOOLS.get());
        List<ResourceLocation> opts = intersect(map.get(profession), allowed);
        if (opts.isEmpty()) {
            if (!MagicNpcsConfig.SCHOOLS_VILLAGERS_UNMAPPED_RANDOM.get()) {
                return null; // unmapped profession, and random fallback disabled
            }
            opts = allowed;
        }
        return opts.get(rng.nextInt(opts.size()));
    }

    private static List<ResourceLocation> intersect(List<ResourceLocation> wanted, List<ResourceLocation> allowed) {
        if (wanted == null) {
            return List.of();
        }
        List<ResourceLocation> out = new java.util.ArrayList<>();
        for (ResourceLocation r : wanted) {
            if (allowed.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Assign (or re-assign) a school to a specific mob and rebuild its casting goal.
     * Used by the command and the School Tome item. @return true on success.
     */
    public static boolean applySchool(Mob mob, ResourceLocation schoolId) {
        SchoolType school = SchoolRegistry.getSchool(schoolId);
        if (school == null) {
            return false;
        }
        SpellcasterLoadout loadout = SchoolSpellPool.buildLoadout(school, mob);
        if (loadout == null) {
            return false;
        }
        SchoolData.set(mob, schoolId);
        mob.goalSelector.removeAllGoals(g -> g instanceof NpcSpellAttackGoal);
        applyLoadout(mob, loadout);
        return true;
    }

    /** Clear a mob's assigned school and remove its casting goal. */
    public static void clearSchool(Mob mob) {
        SchoolData.clear(mob);
        mob.goalSelector.removeAllGoals(g -> g instanceof NpcSpellAttackGoal);
    }

    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level().isClientSide()
                || !MagicNpcsConfig.ENABLE_SPELLCASTING.get()
                || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        SpellcasterLoadout loadout = LoadoutManager.get(EntityType.getKey(mob.getType()));
        boolean schoolCaster = loadout == null
                && MagicNpcsConfig.SCHOOLS_ENABLED.get()
                && SchoolData.getSchool(mob) != null;
        if (loadout == null && !schoolCaster) {
            return;
        }
        if (mob.tickCount % MagicManager.MANA_REGEN_TICKS == 0) {
            double base = loadout != null ? loadout.maxMana() : MagicNpcsConfig.SCHOOLS_BASE_MAX_MANA.get();
            rescaleMaxMana(mob, base); // track adapter changes (e.g. recruit level-ups)
            IronsBridge.tickRegen(mob);
        }
    }

    /** Use Iron's own goal only when opted-in AND the mixin actually made this mob {@link IMagicEntity}. */
    private static boolean useIronsGoal(Mob mob) {
        return MagicNpcsConfig.RECRUITS_USE_IRONS_AI.get() && mob instanceof IMagicEntity;
    }

    /** Iron's spell quality (0..1) driven by the mob's adapter (recruit rank → manaScale). */
    private static float spellQuality(Mob mob) {
        return (float) Math.min(1.0, 0.25 * NpcAdapters.resolve(mob).manaScale(mob));
    }

    private static double desiredMaxMana(Mob mob, double baseMana) {
        return baseMana
                * MagicNpcsConfig.MANA_MULTIPLIER.get()
                * NpcAdapters.resolve(mob).manaScale(mob)
                * difficultyFactor(mob);
    }

    /** Modest mana scaling by world difficulty (off when {@code difficultyScaling} is false). */
    private static double difficultyFactor(Mob mob) {
        if (!MagicNpcsConfig.DIFFICULTY_SCALING.get()) {
            return 1.0;
        }
        Difficulty d = mob.level().getDifficulty();
        return switch (d) {
            case EASY -> 0.85;
            case HARD -> 1.2;
            default -> 1.0; // NORMAL / PEACEFUL
        };
    }

    /** With the configured chance, equip a random spell-focus item so the held-focus requirement can be met. */
    private static void maybeGiveSpellFocus(Mob mob) {
        double chance = MagicNpcsConfig.SPAWN_WITH_GEAR_CHANCE.get();
        if (chance <= 0.0 || !mob.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
            return;
        }
        if (mob.getRandom().nextDouble() >= chance) {
            return;
        }
        BuiltInRegistries.ITEM.getTag(IronsBridge.SPELL_FOCUSES).ifPresent(holders -> {
            if (holders.size() == 0) {
                return;
            }
            Item item = holders.get(mob.getRandom().nextInt(holders.size())).value();
            mob.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        });
    }

    private static void rescaleMaxMana(Mob mob, double baseMana) {
        AttributeInstance maxMana = mob.getAttribute(AttributeRegistry.MAX_MANA.get());
        if (maxMana == null) {
            return;
        }
        double desired = desiredMaxMana(mob, baseMana);
        if (Math.abs(maxMana.getBaseValue() - desired) > 0.5) {
            maxMana.setBaseValue(desired);
        }
    }

    private static boolean hasSpellGoal(Mob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof NpcSpellAttackGoal || wrapped.getGoal() instanceof WizardAttackGoal) {
                return true;
            }
        }
        return false;
    }
}
