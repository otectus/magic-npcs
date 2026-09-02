package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import com.otectus.magicnpcs.core.caster.ReconcileResult;
import com.otectus.magicnpcs.core.diag.DiagnosticReport;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.loadout.LoadoutResolution;
import com.otectus.magicnpcs.core.loadout.NativeAttackPolicy;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import com.otectus.magicnpcs.core.util.AttackGoals;
import com.otectus.magicnpcs.core.util.GoalContention;
import com.otectus.magicnpcs.core.util.LineOfFire;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.capabilities.magic.MagicManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

import java.util.List;
import java.util.Locale;

/**
 * Answers "why is <em>this</em> mob, right now, not casting?" as a {@link DiagnosticReport}.
 *
 * <p>Four of the six 0.5.0-era bug reports were "X isn't happening and I can't tell why". The mod had
 * good static diagnostics ({@code /magicnpcs spells}, {@code loadout}, {@code validate}) but nothing
 * that inspected a live mob. This does, in the same order the casting goal itself checks, stopping at
 * the first blocker — including the goal-flag/priority conflict that is the W2 answer.
 *
 * <p>Strictly read-only: it uses {@link LoadoutManager#peek} (no NBT writes, no RNG draws) and reads
 * the goal's own state rather than re-deriving it, so the report can never disagree with the behaviour
 * it explains. Iron's-side, so the command package stays Iron's-free.
 */
public final class CasterDiagnostics {
    private CasterDiagnostics() {}

    public static DiagnosticReport describe(Mob mob) {
        DiagnosticReport.Builder out = DiagnosticReport.builder();
        ResourceLocation type = EntityType.getKey(mob.getType());
        out.header(mob.getName().getString() + "  (" + type + ")");

        NpcSpellAttackGoal goal = IronsSpellcasterHandler.findSpellGoal(mob);
        WrappedGoal wrapped = IronsSpellcasterHandler.findWrappedSpellGoal(mob);
        describeInjection(out, mob, type, goal, wrapped);
        describeReconciliation(out, mob, goal);
        describeGoalEnvironment(out, mob, wrapped);
        if (goal == null) {
            return out.build();
        }
        describeState(out, mob, goal);
        LivingEntity target = describeTarget(out, mob, goal);
        describeMana(out, mob);
        describeSpells(out, mob, goal, target);
        return out.build();
    }

    /** Step 1: is a casting goal present, and if not, why not? */
    private static void describeInjection(DiagnosticReport.Builder out, Mob mob, ResourceLocation type,
                                          NpcSpellAttackGoal goal, WrappedGoal wrapped) {
        if (wrapped != null) {
            String cls = AttackGoals.simpleName(wrapped.getGoal().getClass());
            out.good("injection: " + cls + " present at priority " + wrapped.getPriority()
                    + ", flags " + AttackGoals.flags(wrapped.getGoal()));
            if (goal != null) {
                SpellcasterLoadout loadout = goal.loadout();
                out.detail("loadout " + loadout.source() + "  ·  source tier " + loadout.tier().label()
                        + "  ·  native_attack " + loadout.nativeAttack().jsonValue()
                        + (loadout.replace() ? "  ·  [replace]" : ""));
                out.detail(goal.castableEntries().size() + " of " + loadout.spells().size()
                        + " loadout spell(s) resolved to a castable Iron's spell");
            }
            return;
        }
        out.bad("injection: no casting goal on this mob — it can never cast.");
        if (!MagicNpcsConfig.ENABLE_SPELLCASTING.get()) {
            out.detail("general.enableSpellcasting is false (master switch).");
            return;
        }
        LoadoutResolution resolution = LoadoutManager.peek(mob);
        out.detail("loadout: " + resolution.explain(type));
        if (resolution.status() != LoadoutResolution.Status.NO_LOADOUT) {
            return;
        }
        // No explicit loadout — so it would have to be a magic-school caster.
        if (!MagicNpcsConfig.SCHOOLS_ENABLED.get()) {
            out.detail("magic schools: disabled (schools.enableSchools = false).");
            return;
        }
        String raw = SchoolData.getRaw(mob);
        if (raw == null) {
            out.detail("magic schools: this mob has never been rolled — it is not a recruit, and either "
                    + "not a villager or not an eligible profession.");
        } else if (SchoolData.NONE.equals(raw)) {
            out.detail("magic schools: rolled and marked a non-caster (sticky). "
                    + "Use /magicnpcs school set <target> <school> to override.");
        } else {
            out.detail("magic schools: assigned " + raw + ", but it yielded no castable spells. "
                    + "Run /magicnpcs school pool " + raw + ".");
        }
    }

    /**
     * Step 1b: how this mob's managed state compares with the current data.
     *
     * <p>{@code /why} could previously describe a loadout the installed goal had never seen, because it
     * re-resolved rather than reading what was actually running (audit VAL-002). It now reports both,
     * names the reconcile that produced the current state, and says when the two disagree.
     */
    private static void describeReconciliation(DiagnosticReport.Builder out, Mob mob, NpcSpellAttackGoal goal) {
        int generation = LoadoutManager.generation();
        ManagedCasterState state = ManagedCasterState.peek(mob);
        if (state == null) {
            out.detail("reconcile: this mob has never been reconciled in this server session "
                    + "[NEVER_RECONCILED]. Run /magicnpcs reconcile <targets>.");
        } else if (state.lastResult() != null) {
            ReconcileResult last = state.lastResult();
            String line = "reconcile: " + last.describe() + " [" + last.reason().name() + "]";
            if (last.outcome() == ReconcileResult.Outcome.FAILED) {
                out.bad(line);
            } else {
                out.detail(line);
            }
        }
        if (goal == null) {
            return;
        }
        if (goal.isStale()) {
            out.warn("staleness: this goal was built for catalog generation " + goal.builtForGeneration()
                    + " but the current one is " + generation + " [STALE_GENERATION]. It predates the last "
                    + "/reload — run /magicnpcs reconcile <targets>.");
        } else {
            out.good("staleness: running catalog generation " + generation + " (current).");
        }
        LoadoutResolution desired = LoadoutManager.peek(mob);
        if (desired.isPresent()
                && !desired.loadout().contentHash().equals(goal.loadout().contentHash())) {
            out.warn("desired vs installed: this mob would now resolve to " + desired.loadout().source()
                    + " (hash " + desired.loadout().contentHash() + ") but is running "
                    + goal.loadout().source() + " (hash " + goal.loadout().contentHash()
                    + ") [DESIRED_INSTALLED_MISMATCH].");
        }
        MobCastSession session = goal.session();
        if (session != null && session.isRunning()) {
            out.info("cast session: channelling " + session.spell().getSpellName()
                    + ", " + session.remainingTicks() + " tick(s) left"
                    + (session.effectCommitted() ? " (effect already applied at least once)" : ""));
        }
        out.detail("adapters: " + String.join(" + ", NpcAdapters.describe(mob)));
        describeMovement(out, mob, state);
        // Whatever the NPC's own mod knows that we do not — paused, factioned, immovable. Registered
        // behind that mod's compat guard, so this call is a no-op when none is installed.
        com.otectus.magicnpcs.core.diag.DiagnosticContributors.describeAll(mob, out);
    }

    /**
     * Why this caster is or is not repositioning.
     *
     * <p>"It stands there and dies" and "it walks out of my formation" are both things people report,
     * and they have opposite causes, so the report has to name which gate is in play rather than
     * leave the movement goal invisible.
     */
    private static void describeMovement(DiagnosticReport.Builder out, Mob mob, ManagedCasterState state) {
        if (!MagicNpcsConfig.casterMovementEnabled()) {
            out.detail("movement: balance.casterMovement is off [MOVEMENT_DISABLED]");
            return;
        }
        if (state == null || !state.nativeAttackSuppressed()) {
            out.detail("movement: standing down — this mob still has its own attack AI "
                    + "[MOVEMENT_NATIVE_ACTIVE]. Set \"native_attack\": \"suppress\" on the loadout to "
                    + "make it a pure caster that keeps its range.");
            return;
        }
        NpcAdapter.MovementPolicy policy = NpcAdapters.resolve(mob).movementPolicy(mob);
        switch (policy.freedom()) {
            case PINNED -> out.info("movement: pinned by its own mod's orders (hold position, formation, "
                    + "or an active march) [MOVEMENT_PINNED] — it will cast from where it stands.");
            case ANCHORED -> out.good(String.format(Locale.ROOT,
                    "movement: repositioning within %.1f blocks of its anchor [MOVEMENT_ANCHORED].",
                    policy.leash()));
            case FREE -> out.good("movement: free to reposition [MOVEMENT_FREE].");
        }
    }

    /**
     * Step 2: the full goal environment, and the flag/priority conflict check.
     *
     * <p>{@code GoalSelector.tick()} only starts a goal when every flag it declares is free or held by a
     * goal that {@code canBeReplacedBy} it — and that is {@code isInterruptable() && other.priority <
     * this.priority}, strictly. A running goal sharing a flag at an equal or better priority therefore
     * starves the casting goal completely. That is exactly why a witch with a loadout never cast.
     */
    private static void describeGoalEnvironment(DiagnosticReport.Builder out, Mob mob, WrappedGoal ours) {
        out.info("goals (priority | class | flags | running):");
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            Goal g = wrapped.getGoal();
            String marker = wrapped == ours ? " <- casting goal" : "";
            out.detail(String.format(Locale.ROOT, "%d | %s | %s | %s%s",
                    wrapped.getPriority(), AttackGoals.simpleName(g.getClass()), AttackGoals.flags(g),
                    wrapped.isRunning() ? "RUNNING" : "idle", marker));
        }
        if (ours == null) {
            return;
        }
        if (ours.getGoal().getFlags().isEmpty()) {
            out.good("flag contention: the casting goal declares no control flags, so no other goal can "
                    + "block it from starting.");
        } else {
            boolean starved = false;
            for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
                if (wrapped == ours) {
                    continue;
                }
                if (GoalContention.blocks(ours.getPriority(), ours.getGoal().getFlags(),
                        wrapped.getPriority(), wrapped.getGoal().getFlags(),
                        wrapped.isInterruptable(), wrapped.isRunning())) {
                    starved = true;
                    out.bad(String.format(Locale.ROOT,
                            "flag contention: %s at priority %d holds %s and outranks the casting goal "
                                    + "(priority %d), so the casting goal can never start. Set "
                                    + "general.castingGoalUsesLookFlag = false (the 0.6.0 default), or give this "
                                    + "loadout a lower \"goal_priority\" number.",
                            AttackGoals.simpleName(wrapped.getGoal().getClass()), wrapped.getPriority(),
                            AttackGoals.flags(wrapped.getGoal()), ours.getPriority()));
                }
            }
            if (!starved) {
                out.good("flag contention: no running goal is currently blocking the casting goal.");
            }
        }
        if (mob.goalSelector.getAvailableGoals().isEmpty()) {
            out.warn("this mob has no goals at all — it may be driven by a Brain/Behaviour system or a "
                    + "custom tick, in which case goal injection cannot reach it.");
        }
    }

    /** Step 3: the state gates, using the goal's own check so the two can never diverge. */
    private static void describeState(DiagnosticReport.Builder out, Mob mob, NpcSpellAttackGoal goal) {
        String blocker = goal.stateBlocker();
        if (blocker == null) {
            out.good("state: every gate passes (alive, awake, AI on, difficulty, adapter, focus).");
        } else {
            out.bad("state: " + blocker);
        }
        int wait = goal.ticksUntilNextDecision();
        if (wait > 0) {
            out.info(String.format(Locale.ROOT,
                    "cadence: next decision in %d tick(s) (%.1fs).", wait, wait / 20.0));
        } else {
            out.good("cadence: ready to decide now.");
        }
        if (goal.loadout().nativeAttack() == NativeAttackPolicy.YIELD && AttackGoals.anyNativeAttackRunning(mob)) {
            out.bad("native_attack=yield: one of the mob's own attack goals is running, so casting is "
                    + "deferred until it stops.");
        }
    }

    /** Step 4: the current target, distance and line of sight. */
    private static LivingEntity describeTarget(DiagnosticReport.Builder out, Mob mob, NpcSpellAttackGoal goal) {
        LivingEntity target = mob.getTarget();
        if (target != null && !target.isAlive()) {
            target = null;
        }
        if (target == null) {
            boolean supportPossible = MagicNpcsConfig.SUPPORT_OUT_OF_COMBAT.get()
                    && goal.castableEntries().stream().anyMatch(e -> e.role() == LoadoutEntry.Role.SUPPORT);
            if (supportPossible) {
                boolean hurt = mob.getHealth() < mob.getMaxHealth() * MagicNpcsConfig.SUPPORT_HEALTH_THRESHOLD.get();
                out.info("target: none — only SUPPORT spells are eligible out of combat.");
                out.check(hurt, String.format(Locale.ROOT,
                        "health: %.1f / %.1f (%.0f%%); out-of-combat support needs it below %.0f%% "
                                + "(balance.supportHealthThreshold)",
                        mob.getHealth(), mob.getMaxHealth(), 100.0 * mob.getHealth() / mob.getMaxHealth(),
                        100.0 * MagicNpcsConfig.SUPPORT_HEALTH_THRESHOLD.get()));
            } else if (!MagicNpcsConfig.SUPPORT_OUT_OF_COMBAT.get()) {
                out.bad("target: none, and balance.supportOutOfCombat is false — nothing can be cast.");
            } else {
                out.bad("target: none, and this loadout has no SUPPORT spell — ATTACK spells are never "
                        + "cast without a target.");
            }
            return null;
        }
        double distance = Math.sqrt(mob.distanceToSqr(target));
        boolean los = mob.getSensing().hasLineOfSight(target);
        out.info(String.format(Locale.ROOT, "target: %s at %.1f blocks", target.getName().getString(), distance));
        out.check(los || !MagicNpcsConfig.REQUIRE_LINE_OF_SIGHT.get(),
                "line of sight: " + (los ? "clear" : "blocked (targeting.requireLineOfSight is on)"));
        NpcAdapter adapter = goal.adapter();
        out.check(adapter.canCastAt(mob, target),
                "adapter: " + AttackGoals.simpleName(adapter.getClass())
                        + (adapter.canCastAt(mob, target) ? " permits attacking this target"
                                                          : " forbids attacking this target (ally/neutral)"));
        return target;
    }

    /** Step 6 (printed before the table, because the table references it): mana. */
    private static void describeMana(DiagnosticReport.Builder out, Mob mob) {
        AttributeInstance max = mob.getAttribute(AttributeRegistry.MAX_MANA.get());
        AttributeInstance regen = mob.getAttribute(AttributeRegistry.MANA_REGEN.get());
        if (max == null) {
            out.bad("mana: this entity type has no Iron's MAX_MANA attribute.");
            return;
        }
        float mana = MagicData.getPlayerMagicData(mob).getMana();
        out.info(String.format(Locale.ROOT, "mana: %.1f / %.1f  (+%.1f every %d ticks)",
                mana, max.getValue(),
                max.getValue() * (regen == null ? 0.0 : regen.getValue()) * 0.01
                        * MagicNpcsConfig.REGEN_MULTIPLIER.get(),
                MagicManager.MANA_REGEN_TICKS));
    }

    /** Step 5: the per-spell table — cooldown, mana, role, range, LOS, friendly fire, condition. */
    private static void describeSpells(DiagnosticReport.Builder out, Mob mob,
                                       NpcSpellAttackGoal goal, LivingEntity target) {
        List<LoadoutEntry> entries = goal.castableEntries();
        if (entries.isEmpty()) {
            out.bad("spells: no spell in this loadout resolved to a castable Iron's spell "
                    + "(unknown id, blacklisted, unsupported, or unverified for mob casting) "
                    + "[NO_CASTABLE_SPELLS]. Run /magicnpcs validate.");
            return;
        }
        out.info("spells:");
        float mana = MagicData.getPlayerMagicData(mob).getMana();
        boolean reactive = MagicNpcsConfig.REACTIVE_CASTING_ENABLED.get();
        boolean hurt = mob.getHealth() < mob.getMaxHealth() * MagicNpcsConfig.SUPPORT_HEALTH_THRESHOLD.get();
        for (LoadoutEntry entry : entries) {
            int level = goal.effectiveLevel(entry);
            String levelText = level == entry.level()
                    ? String.valueOf(entry.level())
                    : entry.level() + "->" + level; // raised by the caster's rank
            String head = String.format(Locale.ROOT, "%s  %s lvl%s w%d  %s  cd %d/%dt  mana %d  windup %dt",
                    entry.spell(), entry.role().name().toLowerCase(Locale.ROOT), levelText, entry.weight(),
                    describeRange(entry),
                    goal.cooldownRemaining(entry.spell()), goal.plannedCooldown(entry),
                    goal.manaCost(entry), goal.plannedWindup(entry));
            String blocker = spellBlocker(mob, goal, entry, target, mana, reactive, hurt);
            out.detail(head);
            if (blocker == null) {
                out.detail("  -> castable now");
            } else {
                out.detail("  -> blocked: " + blocker);
            }
        }
    }

    /** The first reason this spell is not castable right now, mirroring the goal's own ordering. */
    private static String spellBlocker(Mob mob, NpcSpellAttackGoal goal, LoadoutEntry entry,
                                       LivingEntity target, float mana, boolean reactive, boolean hurt) {
        int cooldown = goal.cooldownRemaining(entry.spell());
        if (cooldown > 0) {
            return String.format(Locale.ROOT, "on cooldown for %d more tick(s) [COOLDOWN]", cooldown);
        }
        if (!goal.holdsRequiredItem(entry)) {
            return "the caster is not holding a required item ("
                    + (entry.requiredItems().isEmpty()
                            ? "#magicnpcs:spell_focuses" : String.join(" / ", entry.requiredItems()))
                    + ", hand " + entry.requiredHand().jsonValue() + ") [REQUIRED_ITEM]";
        }
        int cost = goal.manaCost(entry);
        if (mana < cost) {
            return String.format(Locale.ROOT, "not enough mana (%.1f < %d) [MANA]", mana, cost);
        }
        com.otectus.magicnpcs.core.loadout.CastCondition condition = reactive ? entry.condition() : null;
        if (condition != null && condition.isEmpty()) {
            condition = null; // an "empty" condition imposes nothing — treat it as absent
        }
        if (entry.role() == LoadoutEntry.Role.SUPPORT) {
            if (condition != null) {
                if (!condition.evaluate(mob, target, goal.adapter())) {
                    return "its reactive condition is not satisfied [CONDITION]";
                }
                if (target == null && !condition.hasSelfHealthGate() && !hurt) {
                    return "out of combat, a support condition with no health term still needs the caster "
                            + "to be hurt (balance.supportHealthThreshold) [IDLE_SUPPORT_FLOOR]";
                }
            } else if (!hurt) {
                return String.format(Locale.ROOT,
                        "the caster is not hurt (health is above %.0f%%) [NOT_HURT]",
                        100.0 * MagicNpcsConfig.SUPPORT_HEALTH_THRESHOLD.get());
            }
            return null;
        }
        // ATTACK
        if (target == null) {
            return "no target (ATTACK spells are never cast without one) [NO_TARGET]";
        }
        if (!goal.adapter().canCastAt(mob, target)) {
            return "the adapter forbids attacking this target [RELATIONSHIP]";
        }
        double distSqr = mob.distanceToSqr(target);
        if (distSqr < entry.minRange() * entry.minRange()) {
            return String.format(Locale.ROOT, "target too close (%.1f < min %.1f) [MIN_RANGE]",
                    Math.sqrt(distSqr), entry.minRange());
        }
        if (distSqr > entry.maxRange() * entry.maxRange()) {
            return String.format(Locale.ROOT, "target out of range (%.1f > max %.1f) [MAX_RANGE]",
                    Math.sqrt(distSqr), entry.maxRange());
        }
        if (MagicNpcsConfig.REQUIRE_LINE_OF_SIGHT.get() && !mob.getSensing().hasLineOfSight(target)) {
            return "no line of sight to the target [LINE_OF_SIGHT]";
        }
        if (MagicNpcsConfig.FRIENDLY_FIRE_CHECK.get()
                && (goal.adapter().tracksAllies() || MagicNpcsConfig.PROTECT_BYSTANDERS.get())) {
            LineOfFire.Geometry geometry = goal.geometryOf(entry);
            LivingEntity blocker =
                    LineOfFire.firstBlocker(mob, target, entry.safetyRadius(), goal.adapter(), geometry);
            if (blocker != null) {
                return "friendly fire: " + blocker.getName().getString() + " is within "
                        + String.format(Locale.ROOT, "%.1f", entry.safetyRadius())
                        + " blocks of the " + switch (geometry) {
                            case CORRIDOR -> "firing line";
                            case TARGET_BLAST -> "impact point";
                            case CASTER_AOE -> "caster";
                        }
                        + " (targeting.protectBystanders / friendlyFireCheck) [FRIENDLY_FIRE]";
            }
        }
        if (condition != null && !condition.evaluate(mob, target, goal.adapter())) {
            return "its reactive condition is not satisfied [CONDITION]";
        }
        return null;
    }

    /**
     * A range window for display. SUPPORT spells are self-cast and the goal never range-checks them,
     * so the synthesized {@code 0.0} bounds printed as "range 0-0" and read like a misconfiguration.
     */
    private static String describeRange(LoadoutEntry entry) {
        if (entry.role() == LoadoutEntry.Role.SUPPORT) {
            return "self-cast";
        }
        return String.format(Locale.ROOT, "range %.0f-%.0f", entry.minRange(), entry.maxRange());
    }

}
