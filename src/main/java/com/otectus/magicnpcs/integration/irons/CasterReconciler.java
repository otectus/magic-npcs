package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.LoadoutData;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.caster.CasterGoalListeners;
import com.otectus.magicnpcs.core.caster.CasterMovementGoal;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import com.otectus.magicnpcs.core.caster.ReconcileReason;
import com.otectus.magicnpcs.core.caster.ReconcileResult;
import com.otectus.magicnpcs.core.loadout.LoadoutEquipment;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.loadout.LoadoutResolution;
import com.otectus.magicnpcs.core.loadout.NativeAttackPolicy;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import com.otectus.magicnpcs.core.util.AttackGoals;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SchoolRegistry;
import io.redspace.ironsspellbooks.api.spells.SchoolType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one idempotent way a mob's managed casting state is brought in line with what it <em>should</em>
 * be right now.
 *
 * <p>0.6.1 had four separate callers each doing their own thing — entity join called {@code tryInject},
 * which no-oped whenever a goal was already present; the school command removed and re-added; the
 * villager re-check removed and re-added; and the reload handler iterated loaded entities with
 * {@code if (!hasSpellGoal(mob)) continue;}. That last precondition is the reported bug: a skeleton
 * that existed <em>before</em> the datapack was added has no Magic NPCs goal, so the reload skipped it
 * and it never became a caster, however many times the player ran {@code /reload} (audit RLD-001).
 *
 * <p>{@link #reconcile} computes the desired state from the catalog, config, manual assignment and
 * profession, compares it with what is installed, and applies only the difference. It is safe to call
 * on any mob at any time: for a mob that is already running the right loadout it does nothing at all,
 * which is what lets a reload preserve mana, cooldowns, and decision cadence (audit RCN-002).
 */
public final class CasterReconciler {

    private CasterReconciler() {}

    /**
     * Bring {@code mob}'s managed casting state in line with the current catalog and config.
     *
     * @return what was actually done — the caller must use this for its counts, rather than assuming
     *         success because it made the call (audit RCN-005)
     */
    public static ReconcileResult reconcile(Mob mob, ReconcileReason reason) {
        if (mob.level().isClientSide()) {
            return ReconcileResult.of(ReconcileResult.Outcome.NOT_APPLICABLE,
                    ReconcileResult.ReasonCode.CLIENT_SIDE);
        }
        ManagedCasterState state = ManagedCasterState.peek(mob);
        Desired desired = desiredFor(mob);
        NpcSpellAttackGoal installed = findSpellGoal(mob);
        boolean hasAnyGoal = findWrappedSpellGoal(mob) != null;

        ReconcileResult result;
        if (desired.loadout() == null) {
            result = hasAnyGoal
                    ? remove(mob, desired.reason(), desired.detail())
                    : ReconcileResult.of(ReconcileResult.Outcome.NOT_APPLICABLE, desired.reason(),
                            desired.detail());
        } else if (installed != null && state != null
                && state.matches(desired.loadout().source(), desired.loadout().contentHash())
                && state.catalogGeneration() == LoadoutManager.generation()) {
            // Already running exactly this. Do not replace the goal: that would refill mana, clear
            // cooldowns, and reset the decision cadence for no reason at all.
            //
            // The suppression wrappers are checked even so. They live in the same goal selector as
            // the casting goal, so anything that rewrites that selector wholesale — CustomNPCs
            // rebuilds an NPC's AI on a timer — can strip them while leaving our goal (re-added by a
            // repair pass) in place. Left unrepaired the mob would cast and swing, which is exactly
            // what native_attack=suppress exists to prevent.
            reapplySuppressionIfLost(mob, state);
            result = ReconcileResult.of(ReconcileResult.Outcome.UNCHANGED, ReconcileResult.ReasonCode.UNCHANGED);
        } else {
            result = install(mob, desired.loadout(), hasAnyGoal);
        }

        // Record the outcome only for mobs that are, or were, managed. A reload reconciles every
        // loaded entity, so calling ManagedCasterState.of() unconditionally would create an entry for
        // every cow and bat in the world and hold it until that entity unloaded.
        if (state != null || result.outcome() != ReconcileResult.Outcome.NOT_APPLICABLE) {
            ManagedCasterState.of(mob).recordResult(result);
        }
        if (MagicNpcsConfig.debugLogging() && result.outcome() != ReconcileResult.Outcome.NOT_APPLICABLE) {
            MagicNpcs.LOGGER.info("[reconcile:{}] {} — {}", reason.name().toLowerCase(java.util.Locale.ROOT),
                    EntityType.getKey(mob.getType()), result.describe());
        }
        return result;
    }

    /** The loadout a mob should be running right now, or the reason it should not be casting. */
    private record Desired(SpellcasterLoadout loadout, ReconcileResult.ReasonCode reason, String detail) {
        static Desired none(ReconcileResult.ReasonCode reason) {
            return new Desired(null, reason, null);
        }

        static Desired none(ReconcileResult.ReasonCode reason, String detail) {
            return new Desired(null, reason, detail);
        }

        static Desired of(SpellcasterLoadout loadout) {
            return new Desired(loadout, ReconcileResult.ReasonCode.OK, null);
        }
    }

    /**
     * Compute the desired casting state, in the documented precedence order:
     * master switch → manual assignment → datapack loadout → magic school.
     */
    private static Desired desiredFor(Mob mob) {
        if (!MagicNpcsConfig.ENABLE_SPELLCASTING.get()) {
            return Desired.none(ReconcileResult.ReasonCode.MASTER_SWITCH_OFF);
        }
        // A player's own choice outranks everything, including a datapack loadout. Goals are not
        // persisted, so without this an explicit loadout re-resolved on the next chunk load silently
        // overwrote a Tome/command assignment. Only consulted for mobs that may carry our data, so the
        // read cannot force ForgeData into existence for every mob in the world (see mayHaveSchoolData).
        if (mayHaveSchoolData(mob)) {
            switch (SchoolData.mode(mob)) {
                case MANUAL_DISABLED -> {
                    return Desired.none(ReconcileResult.ReasonCode.MANUAL_CLEARED);
                }
                case MANUAL_SCHOOL -> {
                    SpellcasterLoadout manual = schoolLoadoutFor(mob, SchoolData.getSchool(mob));
                    if (manual != null) {
                        return Desired.of(manual);
                    }
                    // 0.6.1 fell through to the datapack loadout here, silently replacing the player's
                    // choice with a pack's. A manual school now installs that school or nothing, and
                    // the reason is visible in /magicnpcs why (audit "manual override fallback").
                    return Desired.none(ReconcileResult.ReasonCode.MANUAL_SCHOOL_UNUSABLE,
                            "school " + SchoolData.getSchool(mob)
                                    + " — run /magicnpcs school pool " + SchoolData.getSchool(mob));
                }
                default -> { /* AUTO — fall through to the datapack/school path below */ }
            }
        }
        LoadoutResolution resolution = LoadoutManager.assign(mob);
        SpellcasterLoadout loadout = resolution.loadout();
        if (loadout != null) {
            if (!MagicNpcsConfig.isBuiltinLoadoutEnabled(loadout.source())) {
                return Desired.none(ReconcileResult.ReasonCode.SUPPRESSED,
                        "builtinLoadouts." + loadout.source().getPath() + " is off");
            }
            return Desired.of(loadout);
        }
        if (resolution.status() == LoadoutResolution.Status.NOT_A_CASTER) {
            return Desired.none(ReconcileResult.ReasonCode.NOT_A_CASTER, resolution.detail());
        }
        if (isDeliberatelySuppressed(resolution.status())) {
            // A loadout exists for this type but the operator switched it off. Do NOT fall through to
            // magic-school assignment — that would hand the mob spells anyway and make the kill switch
            // look broken.
            return Desired.none(ReconcileResult.ReasonCode.SUPPRESSED,
                    resolution.explain(EntityType.getKey(mob.getType())));
        }
        SpellcasterLoadout school = trySchoolLoadout(mob);
        return school == null ? Desired.none(ReconcileResult.ReasonCode.NO_LOADOUT) : Desired.of(school);
    }

    /**
     * @return true when a loadout exists for this mob but was deliberately switched off, as opposed to
     *         simply not applying. "No loadout declares this type", "none matches this villager's
     *         profession", and "the context conditions don't hold here" all still fall through to
     *         magic-school assignment; a compat toggle, a {@code disabledEntityTypes} entry, or
     *         {@code "enabled": false} does not.
     */
    private static boolean isDeliberatelySuppressed(LoadoutResolution.Status status) {
        return switch (status) {
            case TYPE_DISABLED_BY_CONFIG, COMPAT_TOGGLE_OFF, ALL_LOADOUTS_DISABLED -> true;
            default -> false;
        };
    }

    // --- applying ------------------------------------------------------------------------------

    /**
     * Install (or replace) the casting goal for {@code loadout}, preserving everything that should
     * survive a loadout change.
     */
    private static ReconcileResult install(Mob mob, SpellcasterLoadout loadout, boolean replacing) {
        AttributeInstance maxMana = mob.getAttribute(AttributeRegistry.MAX_MANA.get());
        AttributeInstance manaRegen = mob.getAttribute(AttributeRegistry.MANA_REGEN.get());
        if (maxMana == null || manaRegen == null) {
            // 0.6.1 returned early here but still reported success to the caller, so a reload could
            // claim it had rebuilt casters that had never been given a goal (audit RCN-005).
            return ReconcileResult.of(ReconcileResult.Outcome.FAILED,
                    ReconcileResult.ReasonCode.NO_MANA_ATTRIBUTES,
                    EntityType.getKey(mob.getType()) + " has no Iron's mana attributes");
        }
        ManagedCasterState state = ManagedCasterState.of(mob);
        if (replacing) {
            removeSpellGoals(mob);
        }

        double desiredMax = desiredMaxMana(mob, loadout.maxMana());
        maxMana.setBaseValue(desiredMax);
        manaRegen.setBaseValue(loadout.manaRegen());
        // setBaseValue silently clamps to the attribute's range, so an attribute mod can leave a
        // caster with a fraction of the mana its loadout asked for and nothing anywhere says so.
        // /magicnpcs why reports it as [MANA_CLAMPED]; this is the same fact in the log, once.
        reportClamp(mob, "max_mana", desiredMax, maxMana.getBaseValue());
        reportClamp(mob, "mana_regen", loadout.manaRegen(), manaRegen.getBaseValue());
        if (state.claimManaInitialisation()) {
            IronsBridge.initMana(mob); // first activation only — a reload is not free healing
        } else {
            IronsBridge.clampMana(mob, maxMana.getValue()); // a lower ceiling must still bind
        }
        // Keep cooldowns for spells the new loadout still has; drop the rest.
        Set<ResourceLocation> present = new HashSet<>();
        loadout.spells().forEach(e -> present.add(e.spell()));
        state.retainCooldownsFor(present);

        applyEquipment(mob, loadout, state);
        applyNativeAttackPolicy(mob, loadout, state);

        int priority = loadout.goalPriority() != null
                ? loadout.goalPriority()
                : MagicNpcsConfig.castingGoalPriority();
        NpcSpellAttackGoal goal = new NpcSpellAttackGoal(mob, loadout);
        if (goal.castableEntries().isEmpty()) {
            // Nothing survived filtering (unknown ids, blacklist, unverified spells). Installing an
            // inert goal would make /why report a healthy caster that can never cast.
            releaseNativeAttackPolicy(mob, state);
            return ReconcileResult.of(ReconcileResult.Outcome.FAILED,
                    ReconcileResult.ReasonCode.NO_CASTABLE_SPELLS,
                    "loadout " + loadout.source() + " has " + loadout.spells().size()
                            + " spell(s), none castable — run /magicnpcs validate");
        }
        mob.goalSelector.addGoal(priority, goal);
        // Stamp the heartbeat now so a caster that was reconciled this tick is not reported stale by
        // /magicnpcs why before the goal selector has had its first pass at it.
        state.heartbeat(mob.tickCount);
        // One priority below the casting goal: it only ever runs while the mob's own attack AI is
        // suppressed, so there is nothing at that priority left to contend with, and keeping it
        // distinct makes the /magicnpcs why goal dump readable.
        mob.goalSelector.addGoal(priority + 1,
                new CasterMovementGoal(mob, goal.castableEntries(), MagicNpcsConfig.casterMovementSpeed()));
        maybeGrantSelfDefense(mob, state);
        state.adopt(loadout.source(), loadout.contentHash(), LoadoutManager.generation());

        if (MagicNpcsConfig.debugLogging()) {
            MagicNpcs.LOGGER.info("[magicnpcs] {} casting goal at priority {} (loadout {}, {}, {})",
                    replacing ? "replaced" : "installed", priority, loadout.source(),
                    loadout.tier().label(), loadout.nativeAttack().jsonValue());
        }
        // Anything holding a reference to the previous goal object has just been left with a dead
        // one; tell it before it can re-add it (see CasterGoalListeners).
        CasterGoalListeners.fireGoalChanged(mob);
        return ReconcileResult.of(replacing ? ReconcileResult.Outcome.UPDATED
                : ReconcileResult.Outcome.INSTALLED, ReconcileResult.ReasonCode.OK);
    }

    /** Remove every Magic NPCs-owned behaviour and release everything it was holding. */
    private static ReconcileResult remove(Mob mob, ReconcileResult.ReasonCode reason, String detail) {
        removeSpellGoals(mob);
        ManagedCasterState state = ManagedCasterState.peek(mob);
        if (state != null) {
            releaseNativeAttackPolicy(mob, state);
            if (state.selfDefenseGranted()) {
                // Undo the retaliation goal we added, so "stop casting" also stops the behaviour
                // change that came with casting.
                removeSelfDefense(mob);
                state.setSelfDefenseGranted(false);
            }
            state.adopt(null, null, LoadoutManager.generation());
        }
        // A wind-up that was running when the goal went away leaves its glow behind.
        com.otectus.magicnpcs.core.feedback.Telegraphs.clearStrandedGlow(mob);
        CasterGoalListeners.fireGoalChanged(mob);
        return ReconcileResult.of(ReconcileResult.Outcome.REMOVED, reason, detail);
    }

    /**
     * Apply the loadout's {@code native_attack} policy, and — just as importantly — <em>undo</em> it
     * when the policy no longer says to suppress. 0.6.1 could only ever suppress (audit RCN-003).
     */
    private static void applyNativeAttackPolicy(Mob mob, SpellcasterLoadout loadout, ManagedCasterState state) {
        if (loadout.nativeAttack() == NativeAttackPolicy.SUPPRESS) {
            if (state.nativeAttackSuppressed() && AttackGoals.hasSuppressedGoals(mob)) {
                return; // already held: re-suppressing would wrap our own wrapper
            }
            List<String> suppressed = AttackGoals.suppressNativeAttackGoals(mob);
            state.setNativeAttackSuppressed(true);
            if (!suppressed.isEmpty()) {
                // Log exactly what we took over: silently rewriting another mod's AI is how "my guard
                // stopped swinging its sword" becomes an unanswerable bug report.
                MagicNpcs.LOGGER.info("[magicnpcs] {} ({}): native_attack=suppress is holding {} inert",
                        EntityType.getKey(mob.getType()), loadout.source(), String.join(", ", suppressed));
            }
            return;
        }
        releaseNativeAttackPolicy(mob, state);
    }

    /**
     * Re-take the native-attack lease when our own state says we hold it but the wrappers are gone.
     *
     * <p>Only ever <em>adds</em> suppression back: a mob whose state does not claim suppression is
     * left entirely alone, so this can never take over a mob we were not already holding.
     */
    private static void reapplySuppressionIfLost(Mob mob, ManagedCasterState state) {
        if (state == null || !state.nativeAttackSuppressed() || AttackGoals.hasSuppressedGoals(mob)) {
            return;
        }
        List<String> suppressed = AttackGoals.suppressNativeAttackGoals(mob);
        if (!suppressed.isEmpty()) {
            MagicNpcs.LOGGER.info("[magicnpcs] {}: native attack suppression was lost and has been "
                            + "re-applied to {}", EntityType.getKey(mob.getType()),
                    String.join(", ", suppressed));
        }
    }

    /**
     * @return true when everything Magic NPCs injected into {@code mob} is still exactly where it was
     *         put: one casting goal, its companion movement goal, and the native-attack suppression
     *         wrappers if and only if this caster's state claims them.
     *
     * <p>The question a compat bridge asks before requesting a repair, so a framework that rewrites
     * goal selectors does not cost a full reconcile every time it does so. A mob that was never a
     * managed caster answers true — there is nothing of ours to be missing.
     *
     * <p>The movement goal is expected whenever a casting goal is installed, because the install path
     * adds the two together unconditionally; {@link CasterMovementGoal} decides for itself whether to
     * actually run.
     */
    public static boolean ownedGoalsIntact(Mob mob) {
        ManagedCasterState state = ManagedCasterState.peek(mob);
        int spellGoals = 0;
        int movementGoals = 0;
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            Goal goal = wrapped.getGoal();
            if (isOurSpellGoal(goal)) {
                spellGoals++;
            } else if (goal instanceof CasterMovementGoal) {
                movementGoals++;
            }
        }
        if (spellGoals == 0 && movementGoals == 0) {
            // Not a caster right now. Only a mob whose state still claims a loadout is missing
            // something; anything else legitimately has none of our goals.
            return state == null || state.loadoutHash() == null;
        }
        if (spellGoals != 1 || movementGoals != 1) {
            return false;
        }
        return state == null || state.nativeAttackSuppressed() == AttackGoals.hasSuppressedGoals(mob);
    }

    private static void releaseNativeAttackPolicy(Mob mob, ManagedCasterState state) {
        if (!state.nativeAttackSuppressed() && !AttackGoals.hasSuppressedGoals(mob)) {
            return;
        }
        List<String> restored = AttackGoals.releaseNativeAttackGoals(mob);
        state.setNativeAttackSuppressed(false);
        if (!restored.isEmpty() && MagicNpcsConfig.debugLogging()) {
            MagicNpcs.LOGGER.info("[magicnpcs] {}: released native attack goals {}",
                    EntityType.getKey(mob.getType()), String.join(", ", restored));
        }
    }

    /**
     * Remove every casting goal we injected, stopping any that is running.
     *
     * <p>Must not use {@code GoalSelector#removeAllGoals}: vanilla implements it as a plain
     * {@code removeIf} that never calls {@link Goal#stop()}. A running goal dropped that way keeps its
     * {@code lockedFlags} entry forever — {@code GoalSelector} only releases a flag when its holder
     * reports {@code !isRunning()}, and an unregistered goal is never ticked again — so re-schooling a
     * mob mid-cast permanently starved every other goal wanting those flags. {@code removeGoal} stops
     * first, which also runs the goal's own cast-cancel and telegraph cleanup.
     */
    static void removeSpellGoals(Mob mob) {
        List<Goal> ours = new ArrayList<>(2);
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            Goal goal = wrapped.getGoal();
            // The movement goal is ours too: leaving it behind would keep repositioning a mob that is
            // no longer a caster, which is exactly the kind of stranded owned behaviour audit RCN-003
            // was about.
            if (isOurSpellGoal(goal) || goal instanceof CasterMovementGoal) {
                ours.add(goal);
            }
        }
        for (Goal goal : ours) {
            mob.goalSelector.removeGoal(goal);
        }
    }

    // --- schools -------------------------------------------------------------------------------

    /**
     * Resolve (or roll, once) this mob's assigned school and synthesize a loadout from it. Returns
     * null when schools are disabled, the mob is not eligible, it rolled a non-caster, or the school
     * yields no castable spells.
     */
    private static SpellcasterLoadout trySchoolLoadout(Mob mob) {
        if (!MagicNpcsConfig.SCHOOLS_ENABLED.get() || !mayHaveSchoolData(mob)) {
            return null;
        }
        ResourceLocation schoolId = SchoolData.getSchool(mob);
        if (schoolId == null) {
            if (SchoolData.hasRolled(mob) && !IronsSpellcasterHandler.professionChanged(mob)) {
                return null; // sticky non-caster
            }
            schoolId = IronsSpellcasterHandler.rollSchool(mob);
            if (schoolId == null) {
                return null;
            }
        }
        SpellcasterLoadout loadout = schoolLoadoutFor(mob, schoolId);
        if (loadout == null && MagicNpcsConfig.debugLogging()) {
            // Deliberately NOT sticky-marked: buildLoadout returns null for purely *config* reasons
            // too (maxRarity, maxSpellLevel, allowedCastTypes, the spell allow/deny list), so relaxing
            // the config afterwards must still be able to revive the NPC.
            MagicNpcs.LOGGER.info("[schools] {} has school {} but it yields no castable spells right now "
                            + "(see /magicnpcs school pool {})",
                    EntityType.getKey(mob.getType()), schoolId, schoolId);
        }
        return loadout;
    }

    /** @return a loadout synthesized from {@code schoolId}, or null if unknown/disallowed/empty. */
    static SpellcasterLoadout schoolLoadoutFor(Mob mob, ResourceLocation schoolId) {
        if (schoolId == null || !MagicNpcsConfig.SCHOOLS_ENABLED.get()) {
            return null;
        }
        SchoolType school = SchoolRegistry.getSchool(schoolId);
        return school == null ? null : SchoolSpellPool.buildLoadout(school, mob);
    }

    /**
     * Could this mob be carrying our persistent data? Reading it cannot be the first question asked,
     * because {@code Entity#getPersistentData()} <em>creates</em> the tag on read and Forge writes
     * {@code ForgeData} to disk whenever it is non-null — even when empty. Asking unconditionally
     * therefore stamped an empty compound onto every cow, bat and zombie in the world, permanently
     * (backlog B1).
     */
    static boolean mayHaveSchoolData(Mob mob) {
        return mob instanceof Villager
                || mob.getTags().contains(IronsSpellcasterHandler.MANUAL_SCHOOL_TAG)
                || NpcAdapters.resolve(mob).schoolAssignable(mob);
    }

    // --- equipment -----------------------------------------------------------------------------

    /**
     * Grant starting gear — <b>once per NPC per loadout</b>.
     *
     * <p>Goals are not persisted, so every chunk reload re-runs this. Before 0.6.0 that re-ran the
     * equipment roll too, so {@code only_if_empty: false} replaced the held item on every reload and
     * {@code spawnWithGearChance} re-rolled until it eventually won (backlog B10). 0.6.0's fix was a
     * permanent boolean latch, which then meant a <em>changed</em> loadout could never grant its new
     * gear (audit RCN-004). The mark is now the loadout's identity, so a genuine loadout change
     * applies once and only once.
     */
    private static void applyEquipment(Mob mob, SpellcasterLoadout loadout, ManagedCasterState state) {
        boolean globalGear = loadout.equipment() == null && MagicNpcsConfig.SPAWN_WITH_GEAR_CHANCE.get() > 0.0;
        if (loadout.equipment() == null && !globalGear) {
            return; // nothing to grant — don't touch persistent data
        }
        String mark = equipmentMark(loadout);
        String applied = LoadoutData.getEquippedFor(mob);
        if (mark.equals(applied)
                || (LoadoutData.LEGACY_EQUIPMENT_MARK.equals(applied) && loadout.equipment() == null)) {
            return; // already equipped for this loadout (or by a pre-0.6.2 build, for global gear)
        }
        LoadoutData.setEquippedFor(mob, mark);
        if (loadout.equipment() != null) {
            applyWeightedEquipment(mob, loadout.equipment());
        } else {
            maybeGiveSpellFocus(mob);
        }
    }

    /** The identity managed equipment is recorded against: the source plus the equipment block. */
    private static String equipmentMark(SpellcasterLoadout loadout) {
        return loadout.source() + "#" + (loadout.equipment() == null
                ? "global" : Integer.toHexString(loadout.equipment().hashCode()));
    }

    /** Per-hand weighted equipment from a loadout's {@code equipment} block. */
    private static void applyWeightedEquipment(Mob mob, LoadoutEquipment equipment) {
        if (equipment.chance() <= 0.0 || mob.getRandom().nextDouble() >= equipment.chance()) {
            return;
        }
        equipHand(mob, InteractionHand.MAIN_HAND, equipment.mainhand(), equipment.onlyIfEmpty());
        equipHand(mob, InteractionHand.OFF_HAND, equipment.offhand(), equipment.onlyIfEmpty());
    }

    private static void equipHand(Mob mob, InteractionHand hand,
                                  List<LoadoutEquipment.WeightedItem> candidates, boolean onlyIfEmpty) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        if (onlyIfEmpty && !mob.getItemInHand(hand).isEmpty()) {
            return;
        }
        ResourceLocation itemId = LoadoutEquipment.pick(candidates, mob.getRandom());
        if (itemId == null) {
            return;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null) {
            MagicNpcs.LOGGER.warn("Loadout equipment references unknown item '{}' — skipping", itemId);
            return;
        }
        setHeldItem(mob, hand, new ItemStack(item));
    }

    /**
     * Put an item in a hand the way the mob's own mod wants it done, falling back to vanilla.
     *
     * <p>Some NPC mods keep held equipment in their own inventory object and copy it onto the entity
     * every tick, so a bare {@code setItemInHand} is overwritten and the loadout's {@code equipment}
     * block appears to do nothing on those mobs.
     */
    private static void setHeldItem(Mob mob, InteractionHand hand, ItemStack stack) {
        if (!NpcAdapters.resolve(mob).setHeldItem(mob, hand, stack)) {
            mob.setItemInHand(hand, stack);
        }
    }

    /** With the configured chance, equip a random spell-focus item so a held-focus rule can be met. */
    private static void maybeGiveSpellFocus(Mob mob) {
        double chance = MagicNpcsConfig.SPAWN_WITH_GEAR_CHANCE.get();
        if (chance <= 0.0 || !mob.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
            return;
        }
        if (mob.getRandom().nextDouble() >= chance) {
            return;
        }
        BuiltInRegistries.ITEM.getTag(preferredFocusTag(mob)).ifPresent(holders -> {
            if (holders.size() == 0) {
                return;
            }
            Item item = holders.get(mob.getRandom().nextInt(holders.size())).value();
            setHeldItem(mob, InteractionHand.MAIN_HAND, new ItemStack(item));
        });
    }

    /** Prefer the caster's school focus tag when school-aware focus is on and it has items. */
    private static TagKey<Item> preferredFocusTag(Mob mob) {
        if (MagicNpcsConfig.SCHOOLS_SCHOOL_AWARE_FOCUS.get()) {
            ResourceLocation school = SchoolData.getSchool(mob);
            TagKey<Item> schoolTag = school == null ? null : IronsBridge.schoolFocusTag(school);
            if (schoolTag != null
                    && BuiltInRegistries.ITEM.getTag(schoolTag).map(h -> h.size() > 0).orElse(false)) {
                return schoolTag;
            }
        }
        return IronsBridge.SPELL_FOCUSES;
    }

    // --- mana scaling --------------------------------------------------------------------------

    /**
     * Caster UUIDs already warned about a clamped mana attribute. Bounded, and cleared wholesale
     * rather than pruned: it exists only to keep a per-tick reconcile from repeating one debug line.
     */
    private static final Set<UUID> CLAMP_REPORTED = new HashSet<>();

    /** Log once per caster when an attribute mod's range cap ate the value we asked for. */
    private static void reportClamp(Mob mob, String attribute, double wanted, double actual) {
        if (Math.abs(wanted - actual) <= 0.5) {
            return;
        }
        if (CLAMP_REPORTED.size() > 512) {
            CLAMP_REPORTED.clear();
        }
        if (!CLAMP_REPORTED.add(mob.getUUID())) {
            return;
        }
        MagicNpcs.LOGGER.debug("[magicnpcs] {} ({}): {} clamped to {} (wanted {}) — an attribute range "
                        + "cap applies; see /magicnpcs why [MANA_CLAMPED]",
                mob.getName().getString(), EntityType.getKey(mob.getType()), attribute, actual, wanted);
    }

    static double desiredMaxMana(Mob mob, double baseMana) {
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
        return switch (mob.level().getDifficulty()) {
            case EASY -> 0.85;
            case HARD -> 1.2;
            default -> 1.0; // NORMAL / PEACEFUL
        };
    }

    /**
     * Give a casting villager a way to acquire a target, when the operator has asked for it.
     *
     * <p>A vanilla villager is brain-driven with a completely empty {@code GoalSelector}, and nothing
     * in vanilla ever calls {@code setTarget} on one — not even a raid, where villagers only hide. So
     * {@code getTarget()} is permanently null and a "magic villager" could do nothing but heal itself.
     * Off by default, because retaliating is a real change to vanilla villager behaviour.
     */
    private static void maybeGrantSelfDefense(Mob mob, ManagedCasterState state) {
        if (!(mob instanceof Villager)) {
            return;
        }
        if (!MagicNpcsConfig.SCHOOLS_VILLAGERS_SELF_DEFENSE.get()) {
            // The option can be turned off while villagers are already retaliating; a reconcile is
            // where that has to take effect.
            if (state.selfDefenseGranted()) {
                removeSelfDefense(mob);
                state.setSelfDefenseGranted(false);
            }
            return;
        }
        for (WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof HurtByTargetGoal) {
                state.setSelfDefenseGranted(true); // already present (e.g. a chunk-reload re-injection)
                return;
            }
        }
        mob.targetSelector.addGoal(1, new HurtByTargetGoal((PathfinderMob) mob));
        state.setSelfDefenseGranted(true);
    }

    /**
     * Remove the self-defence goal we granted, so turning the option off (or clearing a villager's
     * school) actually returns it to vanilla behaviour. 0.6.1 added the goal and never tracked it.
     */
    static void removeSelfDefense(Mob mob) {
        if (!(mob instanceof Villager)) {
            return;
        }
        List<Goal> ours = new ArrayList<>(1);
        for (WrappedGoal wrapped : mob.targetSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof HurtByTargetGoal) {
                ours.add(wrapped.getGoal());
            }
        }
        ours.forEach(mob.targetSelector::removeGoal);
    }

    // --- goal lookup ---------------------------------------------------------------------------

    /** @return our built-in casting goal on {@code mob}, or {@code null}. */
    public static NpcSpellAttackGoal findSpellGoal(Mob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof NpcSpellAttackGoal goal) {
                return goal;
            }
        }
        return null;
    }

    /**
     * Cut short whatever {@code mob} is casting because a player started talking to it.
     *
     * <p>Exposed as a plain {@code Mob} method so a compat leaf can hand it over as a
     * {@code Consumer<Mob>} method reference without naming any Iron's type of its own — the same
     * shape the CustomNPCs bridge uses for goal repair.
     *
     * <p>Cooldowns are deliberately untouched: the cast is abandoned, not refunded, so an NPC cannot
     * be made to skip its cooldown by opening and closing a dialog on it.
     */
    public static void cancelCastForDialog(Mob mob) {
        NpcSpellAttackGoal goal = findSpellGoal(mob);
        MobCastSession session = goal == null ? null : goal.session();
        if (session != null && session.isRunning()) {
            session.cancel(MobCastSession.CancelReason.DIALOG_OPENED);
        }
    }

    /** @return the {@link WrappedGoal} wrapping any casting goal we injected, or {@code null}. */
    public static WrappedGoal findWrappedSpellGoal(Mob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (isOurSpellGoal(wrapped.getGoal())) {
                return wrapped;
            }
        }
        return null;
    }

    /** Any casting goal we may have injected. */
    public static boolean isOurSpellGoal(Goal goal) {
        return goal instanceof NpcSpellAttackGoal;
    }

    /** Unused difficulty import guard — kept so the enum switch above stays exhaustive-checked. */
    @SuppressWarnings("unused")
    private static final Difficulty[] DIFFICULTIES = Difficulty.values();
}
