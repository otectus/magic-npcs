package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.command.MagicNpcsCommands;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolAssignResult;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import com.otectus.magicnpcs.core.caster.ReconcileReason;
import com.otectus.magicnpcs.core.caster.ReconcileResult;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.loadout.LoadoutResolution;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FORGE-bus handler driving the universal casting path: registers the loadout datapack listener, hands
 * every lifecycle event to {@link CasterReconciler}, and ticks our own mana regen (Iron's does not
 * regen foreign mobs — ADR 0001). Max-mana is scaled by the per-mob {@link NpcAdapters adapter} (e.g.
 * Recruits rank) and re-scaled on the regen cadence so it tracks level-ups.
 *
 * <p>Server-side only; gated by {@link MagicNpcsConfig}.
 */
public class IronsSpellcasterHandler {

    /**
     * How often a professionless villager is re-checked for eligibility. Villagers spawn (and are bred)
     * with profession {@code minecraft:none} and take a job later, but goal injection only happened at
     * join — so profession-scoped loadouts and profession→school mappings never applied to naturally
     * spawned villagers (backlog B2/B3). Five seconds is imperceptible and the check is a goal-list walk.
     */
    private static final int VILLAGER_RECHECK_TICKS = 100;

    static final ResourceLocation NO_PROFESSION = new ResourceLocation("minecraft", "none");

    /**
     * Vanilla scoreboard tag stamped on any mob a player manually assigned a school to. It exists so
     * {@code mayHaveSchoolData} can recognise an arbitrary tomed mob (a zombie, a witch) without
     * reading persistent data, which would create it. Doubles as a usable selector:
     * {@code @e[tag=magicnpcs.school]}.
     */
    public static final String MANUAL_SCHOOL_TAG = "magicnpcs.school";

    /**
     * Mobs queued for reconciliation after a reload or config change, drained a bounded number per
     * server tick.
     *
     * <p>A reload must re-evaluate <em>every</em> loaded mob, not just the ones that already have our
     * goal — that precondition is exactly why a skeleton loaded before the datapack was added never
     * became a caster however many times the player ran {@code /reload} (audit RLD-001). Doing that
     * inline would mean an unbounded entity scan on the reload thread, so it is queued instead and
     * reported when it finishes.
     */
    private static final Deque<QueuedReconcile> PENDING = new ArrayDeque<>();
    private static int pendingInstalled;
    private static int pendingRemoved;
    private static int pendingFailed;
    private static int pendingGeneration;

    private record QueuedReconcile(ServerLevel level, UUID entityId, ReconcileReason reason) {}

    @SubscribeEvent
    public void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new LoadoutManager());
        // Report school pool composition once per reload, so a school that can never be assigned is
        // visible in the log without anyone running a command (W4).
        event.addListener(new SchoolPoolReporter());
    }

    /**
     * Reconcile every loaded mob after a datapack reload.
     *
     * <p>Fires on datapack sync (which follows a reload) rather than on the reload listener itself,
     * because the listener runs before the new catalog is published.
     */
    @SubscribeEvent
    public void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) {
            return; // per-player login sync, not a reload — nothing to reconcile
        }
        queueAllLoadedMobs(event.getPlayerList().getServer(), ReconcileReason.DATAPACK_RELOAD);
    }

    /**
     * Queue every loaded mob for reconciliation.
     *
     * <p>Public so the config-reload listener and {@code /magicnpcs reconcile} use the same path: a
     * transition that only some callers apply is a transition that will be wrong somewhere.
     */
    public static void queueAllLoadedMobs(MinecraftServer server, ReconcileReason reason) {
        if (server == null) {
            return;
        }
        PENDING.clear();
        pendingInstalled = 0;
        pendingRemoved = 0;
        pendingFailed = 0;
        pendingGeneration = LoadoutManager.generation();
        int queued = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Mob mob) {
                    PENDING.add(new QueuedReconcile(level, mob.getUUID(), reason));
                    queued++;
                }
            }
        }
        MagicNpcs.LOGGER.info("[magicnpcs] {} — queued {} loaded mob(s) for reconciliation "
                        + "against catalog generation {}",
                reason.name().toLowerCase(Locale.ROOT), queued, pendingGeneration);
    }

    /** Drain the reconciliation queue in bounded batches so a large world does not stall a tick. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING.isEmpty()) {
            return;
        }
        int budget = MagicNpcsConfig.reconcileBatchSize();
        while (budget-- > 0 && !PENDING.isEmpty()) {
            QueuedReconcile queued = PENDING.poll();
            Mob mob;
            try {
                // A queued entry outlives the tick it was made on, so its level may have unloaded and
                // its entity may be gone. Neither is an error worth failing a reconcile pass over.
                Entity entity = queued.level().getEntity(queued.entityId());
                if (!(entity instanceof Mob found) || !found.isAlive()) {
                    continue;
                }
                mob = found;
            } catch (Exception ex) {
                MagicNpcs.LOGGER.debug("[magicnpcs] skipping queued reconcile for {}: {}",
                        queued.entityId(), ex.toString());
                continue;
            }
            ReconcileResult result = CasterReconciler.reconcile(mob, queued.reason());
            switch (result.outcome()) {
                case INSTALLED, UPDATED -> pendingInstalled++;
                case REMOVED -> pendingRemoved++;
                case FAILED -> pendingFailed++;
                default -> { /* UNCHANGED / NOT_APPLICABLE are the common case; do not count them */ }
            }
        }
        if (PENDING.isEmpty() && (pendingInstalled > 0 || pendingRemoved > 0 || pendingFailed > 0)) {
            MagicNpcs.LOGGER.info("[magicnpcs] reconciliation complete (generation {}): "
                            + "{} caster(s) installed or updated, {} removed, {} failed",
                    pendingGeneration, pendingInstalled, pendingRemoved, pendingFailed);
            if (pendingFailed > 0) {
                MagicNpcs.LOGGER.warn("[magicnpcs] {} mob(s) could not be reconciled. Run "
                        + "/magicnpcs why on one of them for the reason.", pendingFailed);
            }
            pendingInstalled = 0;
            pendingRemoved = 0;
            pendingFailed = 0;
        }
    }

    /** @return how many mobs are still queued, for {@code /magicnpcs config}. */
    public static int pendingReconciles() {
        return PENDING.size();
    }

    @SubscribeEvent
    public void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        MagicNpcsCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        // A wind-up that never finished (unload, death, server stop) leaves the persisted glow behind.
        // Joining the level is the first moment we can see and undo that.
        com.otectus.magicnpcs.core.feedback.Telegraphs.clearStrandedGlow(mob);
        CasterReconciler.reconcile(mob, ReconcileReason.ENTITY_JOIN);
    }

    /** Drop a mob's managed state when it leaves the world, so the tracking map cannot grow forever. */
    @SubscribeEvent
    public void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Mob mob) {
            ManagedCasterState.forget(mob);
        }
    }

    /**
     * Assign (or re-assign) a school to a specific mob and rebuild its casting goal. Used by the
     * command and the School Tome item.
     *
     * @return why it succeeded or failed — a boolean could not tell a caller whether the school was
     *         unknown, disallowed, or simply empty under the current caps (W4)
     */
    public static SchoolAssignResult applySchool(Mob mob, ResourceLocation schoolId) {
        if (!MagicNpcsConfig.SCHOOLS_ENABLED.get()) {
            return SchoolAssignResult.SCHOOLS_DISABLED;
        }
        SchoolType school = SchoolRegistry.getSchool(schoolId);
        if (school == null) {
            return SchoolAssignResult.UNKNOWN_SCHOOL;
        }
        if (!MagicNpcsConfig.allowedSchoolIds().contains(schoolId)) {
            return SchoolAssignResult.SCHOOL_NOT_ALLOWED;
        }
        if (SchoolSpellPool.buildLoadout(school, mob) == null) {
            return SchoolAssignResult.NO_CASTABLE_SPELLS;
        }
        SchoolData.set(mob, schoolId, true);
        mob.addTag(MANUAL_SCHOOL_TAG);
        // Reconcile rather than apply directly: one code path decides what a mob should be running,
        // so a manual assignment cannot drift from what a reload or a join would have produced.
        ReconcileResult result = CasterReconciler.reconcile(mob, ReconcileReason.MANUAL_SCHOOL);
        return result.casterInstalled() ? SchoolAssignResult.OK : SchoolAssignResult.NO_CASTABLE_SPELLS;
    }

    /**
     * Mark a mob as a sticky non-caster and remove its casting goal. "Clear" means "stop casting" and
     * must persist — using {@link SchoolData#markNonCaster} (not {@code clear}) keeps it from
     * re-rolling into a caster on the next chunk reload. Re-enable later via {@code set}/{@code reroll}
     * /the Tome, or return it to automatic with {@code /magicnpcs school auto}.
     */
    public static void clearSchool(Mob mob) {
        SchoolData.markNonCaster(mob, true);
        mob.addTag(MANUAL_SCHOOL_TAG);
        CasterReconciler.removeSelfDefense(mob);
        CasterReconciler.reconcile(mob, ReconcileReason.MANUAL_SCHOOL);
    }

    /**
     * Return a mob to automatic assignment, undoing a manual school or a manual clear.
     *
     * <p>0.6.1 had no way back: once a player had used the Tome or the command, the only options were
     * another school or a permanent clear, so "I set that by mistake" was unrecoverable (audit
     * "manual override fallback defect").
     */
    public static void resetSchoolToAuto(Mob mob) {
        SchoolData.returnToAuto(mob);
        mob.removeTag(MANUAL_SCHOOL_TAG);
        CasterReconciler.reconcile(mob, ReconcileReason.MANUAL_SCHOOL);
    }

    /**
     * Mana upkeep for actual casters only.
     *
     * <p>Before 0.6.0 this ran a full {@link LoadoutManager#resolve} — two list allocations plus a biome
     * lookup and a raid query — for <em>every</em> mob in the world, twenty times a second, and read
     * (and thereby created) persistent NBT on each one (backlog B1/B11). It now early-outs on the regen
     * cadence first, then asks the mob's own goal list, which costs a short walk and touches no NBT.
     */
    @SubscribeEvent
    public void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (event.getEntity().level().isClientSide()
                || !(event.getEntity() instanceof Mob mob)
                || !MagicNpcsConfig.ENABLE_SPELLCASTING.get()) {
            return;
        }
        if (mob.tickCount % MagicManager.MANA_REGEN_TICKS != 0) {
            return;
        }
        maybeRecheckVillager(mob);
        NpcSpellAttackGoal goal = CasterReconciler.findSpellGoal(mob);
        if (goal == null) {
            return;
        }
        rescaleMaxMana(mob, goal.loadout().maxMana()); // track adapter changes (e.g. recruit level-ups)
        IronsBridge.tickRegen(mob);
    }

    /**
     * Re-attempt reconciliation for a villager that had no profession when it joined the world. Runs on
     * a slow cadence and only for villagers, so the cost is a goal-list walk every five seconds per
     * unemployed villager.
     */
    private static void maybeRecheckVillager(Mob mob) {
        if (!(mob instanceof Villager villager)
                || mob.tickCount % VILLAGER_RECHECK_TICKS != 0
                || NO_PROFESSION.equals(professionOf(villager))) {
            return;
        }
        // A villager that took a job since it joined needs its first resolution; a villager that
        // *changed* job needs a fresh one. Before 0.6.1 only the former worked, because tryInject
        // no-oped whenever a goal was present — so a farmer who became a cleric kept casting (or not
        // casting) as a farmer for life.
        if (professionChanged(mob)) {
            if (SchoolData.isManual(mob)) {
                return; // a player's explicit choice is not undone by a career change
            }
            SchoolData.clear(mob);
        }
        CasterReconciler.reconcile(mob, ReconcileReason.PROFESSION_CHANGE);
    }

    static ResourceLocation professionOf(Villager villager) {
        return BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession());
    }

    /**
     * @return true if this villager's job differs from the one its assignment was decided under, so a
     *         sticky non-caster mark or an old school must not be treated as final. A villager that has
     *         since become professionless is not "changed" — it is between jobs, and re-rolling it as
     *         {@code minecraft:none} would just discard a valid assignment.
     */
    static boolean professionChanged(Mob mob) {
        if (!(mob instanceof Villager villager)) {
            return false;
        }
        ResourceLocation now = professionOf(villager);
        if (NO_PROFESSION.equals(now)) {
            return false;
        }
        ResourceLocation rolledFor = SchoolData.getRolledProfession(mob);
        return rolledFor != null && !rolledFor.equals(now);
    }

    /** Eligibility + caster-chance roll; persists the outcome. Returns the chosen school or null. */
    static ResourceLocation rollSchool(Mob mob) {
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
            // A villager that has not taken a job yet is NOT rolled and NOT marked: EntityJoinLevelEvent
            // fires while its profession is still minecraft:none, and marking it would sticky-bar it
            // from ever becoming a caster (backlog B2). The tick handler re-checks it once it has a job.
            ResourceLocation profession = professionOf(villager);
            if (NO_PROFESSION.equals(profession)) {
                return null;
            }
            ResourceLocation s = pickVillagerSchool(villager, rng);
            if (s == null) {
                // No school maps to this profession. That is a *config* state, not a verdict about this
                // villager: sticky-marking here meant adding the profession to professionSchools later
                // could never revive any existing villager.
                return null;
            }
            SchoolData.setRolledProfession(mob, profession);
            if (rng.nextDouble() < MagicNpcsConfig.SCHOOLS_VILLAGERS_CASTER_CHANCE.get()) {
                SchoolData.set(mob, s);
                return s;
            }
            // A lost caster-chance roll *is* a decided outcome, so it stays sticky for this profession.
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
        return switch (MagicNpcsConfig.SCHOOLS_RECRUITS_MODE.get().toUpperCase(Locale.ROOT)) {
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
        ResourceLocation profession = professionOf(villager);
        Map<ResourceLocation, List<ResourceLocation>> map =
                MagicNpcsConfig.parsePairMap(MagicNpcsConfig.SCHOOLS_VILLAGERS_PROFESSION_SCHOOLS.get());
        List<ResourceLocation> opts = intersect(map.get(profession), allowed);
        if (opts.isEmpty()) {
            if (!MagicNpcsConfig.SCHOOLS_VILLAGERS_UNMAPPED_RANDOM.get()) {
                return null; // unmapped profession, and random fallback disabled
            }
            opts = allowed;
        }
        // A villager that cannot acquire a target can only self-cast, so a school with no SUPPORT
        // spell produces a caster that can never cast. Prefer one it can actually use.
        if (SchoolSpellPool.needsSupport(villager)) {
            List<ResourceLocation> usable = new ArrayList<>(opts.size());
            for (ResourceLocation id : opts) {
                SchoolType school = SchoolRegistry.getSchool(id);
                if (school != null && SchoolSpellPool.hasSupportSpell(school)) {
                    usable.add(id);
                }
            }
            if (!usable.isEmpty()) {
                opts = usable;
            }
        }
        return opts.get(rng.nextInt(opts.size()));
    }

    private static List<ResourceLocation> intersect(List<ResourceLocation> wanted, List<ResourceLocation> allowed) {
        if (wanted == null) {
            return List.of();
        }
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation r : wanted) {
            if (allowed.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }

    private static void rescaleMaxMana(Mob mob, double baseMana) {
        AttributeInstance maxMana = mob.getAttribute(AttributeRegistry.MAX_MANA.get());
        if (maxMana == null) {
            return;
        }
        double desired = CasterReconciler.desiredMaxMana(mob, baseMana);
        if (Math.abs(maxMana.getBaseValue() - desired) > 0.5) {
            maxMana.setBaseValue(desired);
            // Rescaling down leaves current mana above the new ceiling, and tickRegen early-outs at
            // >= max, so nothing would ever bring it back into range.
            IronsBridge.clampMana(mob, maxMana.getValue());
        }
    }

    /**
     * Iron's-free view of one (or every allowed) school's generated spell pool, for
     * {@code /magicnpcs school pool}. Lives here so {@code command/} needs no Iron's import.
     *
     * @param only a single school to report, or {@code null} for every allowed school
     * @return the reports, or an empty list when the school id is unknown
     */
    public static List<com.otectus.magicnpcs.core.diag.SchoolPoolReport> schoolPools(ResourceLocation only) {
        if (only == null) {
            return SchoolSpellPool.reportAll();
        }
        SchoolType school = SchoolRegistry.getSchool(only);
        return school == null ? List.of() : List.of(SchoolSpellPool.report(school));
    }

    /**
     * @return true if {@code schoolId} would currently produce a castable loadout for {@code mob}.
     *         Used by {@code /magicnpcs school info} so a manual assignment that yields nothing today
     *         is reported as such rather than described as active.
     */
    public static boolean schoolIsUsable(Mob mob, ResourceLocation schoolId) {
        return CasterReconciler.schoolLoadoutFor(mob, schoolId) != null;
    }

    /** Read-only resolution, for the School Tome's inspect message. */
    public static LoadoutResolution peekLoadout(Mob mob) {
        return LoadoutManager.peek(mob);
    }

    /**
     * Run the spawn-time school roll for {@code mob}, for GameTests. Package-private surface rather
     * than a test-only branch inside {@link #rollSchool}, so the tested path is the real one.
     */
    static ResourceLocation rollSchoolForTest(Mob mob) {
        return rollSchool(mob);
    }

    /**
     * Drop {@code mob}'s casting goal and reconcile it, reproducing what a chunk reload does — goals
     * are not persisted, so every reload re-runs reconciliation. Used by GameTests to assert that a
     * manual assignment survives that round trip.
     */
    static void reinjectForTest(Mob mob) {
        CasterReconciler.removeSpellGoals(mob);
        ManagedCasterState.forget(mob);
        CasterReconciler.reconcile(mob, ReconcileReason.TEST);
    }

    /** @return our built-in casting goal on {@code mob}, or {@code null}. */
    public static NpcSpellAttackGoal findSpellGoal(Mob mob) {
        return CasterReconciler.findSpellGoal(mob);
    }

    /** @return the {@link WrappedGoal} wrapping any casting goal we injected, or {@code null}. */
    public static WrappedGoal findWrappedSpellGoal(Mob mob) {
        return CasterReconciler.findWrappedSpellGoal(mob);
    }

    /** Any casting goal we may have injected. */
    public static boolean isOurSpellGoal(Goal goal) {
        return CasterReconciler.isOurSpellGoal(goal);
    }

    /** @return a loadout synthesized from {@code schoolId}, or null if unknown/disallowed/empty. */
    static SpellcasterLoadout schoolLoadoutFor(Mob mob, ResourceLocation schoolId) {
        return CasterReconciler.schoolLoadoutFor(mob, schoolId);
    }
}
