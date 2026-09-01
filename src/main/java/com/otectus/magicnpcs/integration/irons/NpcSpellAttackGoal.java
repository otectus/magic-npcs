package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import com.otectus.magicnpcs.core.feedback.Telegraphs;
import com.otectus.magicnpcs.core.loadout.CastCondition;
import com.otectus.magicnpcs.core.loadout.CooldownResolver;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.loadout.NativeAttackPolicy;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import com.otectus.magicnpcs.core.util.AttackGoals;
import com.otectus.magicnpcs.core.util.LineOfFire;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Universal NPC casting goal: drives spell selection from a datapack {@link SpellcasterLoadout}. Per
 * ADR 0001 we own the mana economy and cooldowns — Iron's does not tick a foreign mob's
 * {@code PlayerCooldowns}. Mana lives on Iron's {@code MagicData}.
 *
 * <p>ATTACK spells are aimed at the hostile target. SUPPORT spells self-cast when the mob's health
 * drops below the configured threshold — since 0.6.0 that includes <b>out of combat</b>, on a much
 * slower cadence, so a support NPC no longer has to be attacked before it will heal (ADR 0005).
 * ATTACK spells are never selectable without a target.
 *
 * <p><b>Scheduling.</b> The goal declares no {@link Flag} by default, so it runs <em>alongside</em> a
 * mob's own attack AI rather than fighting it for the LOOK lock — the fix for both "the witch never
 * casts" and "the skeleton casts instead of shooting" (ADR 0002). It does not need LOOK: it snaps its
 * own rotation in {@link #snapFacing} at cast time, because {@code LookControl} applies too late.
 *
 * <p><b>Combat state is not owned by this object.</b> Cooldowns and the decision deadline live in
 * {@link ManagedCasterState}, keyed by entity rather than by goal instance. Through 0.6.1 they were
 * fields here, so every {@code /reload} — which removes and recreates the goal — silently cleared every
 * cooldown and reset the cadence, on top of refilling mana (audit RCN-002).
 *
 * <p><b>Casting is a session, not a call.</b> {@link MobCastSession} runs Iron's real
 * initiate/pre-cast/tick/complete lifecycle, so LONG and CONTINUOUS spells behave as designed instead
 * of having {@code onCast} invoked once and their whole channel skipped (audit SPI-001).
 */
public class NpcSpellAttackGoal extends Goal {
    /**
     * How often the mob's adapter is re-resolved. Before 0.6.0 it was resolved once in the constructor,
     * so a mob tamed or put on a team after spawn kept the no-op default adapter for life and
     * friendly-fire protection silently never engaged (backlog B9).
     */
    private static final int ADAPTER_REFRESH_TICKS = 100;

    private final Mob mob;
    private final SpellcasterLoadout loadout;
    private final List<Resolved> spells = new ArrayList<>();
    private final boolean hasSupportSpell;
    /** The catalog generation this goal's loadout came from; a newer one means it is stale. */
    private final int builtForGeneration;

    private NpcAdapter adapter;
    private int adapterResolvedAt = Integer.MIN_VALUE;
    private int windupRemaining;
    private boolean glowApplied;
    private Resolved chosen;
    private LivingEntity target;
    private MobCastSession session;

    public NpcSpellAttackGoal(Mob mob, SpellcasterLoadout loadout) {
        this.mob = mob;
        this.loadout = loadout;
        this.builtForGeneration = LoadoutManager.generation();
        boolean support = false;
        for (LoadoutEntry entry : loadout.spells()) {
            AbstractSpell spell = IronsBridge.getSpell(entry.spell());
            if (spell == null) {
                IronsBridge.warnUnknownSpell(loadout.source(), loadout.entityType(), entry.spell());
                continue;
            }
            // Filter on the RESOLVED id, not the raw one. IronsBridge.getSpell auto-namespaces a bare
            // id, but the cast path enforces the list against spell.getSpellResource() — so a bare-id
            // entry for a blacklisted spell used to survive this filter, get selected, play a full
            // wind-up, and then be refused at cast time, replaying that telegraph forever.
            if (!IronsBridge.isAllowedSpell(spell)) {
                continue;
            }
            if (!SpellCompat.castableByMob(spell)) {
                MagicNpcs.LOGGER.warn("Loadout {} ({}): spell {} is not cast by mobs — {}; skipping it.",
                        loadout.source(), loadout.entityType(), entry.spell(),
                        SpellCompat.unsupportedReason(spell));
                continue;
            }
            support |= entry.role() == LoadoutEntry.Role.SUPPORT;
            spells.add(new Resolved(entry, spell));
        }
        this.hasSupportSpell = support;
        // No control flags by default (ADR 0002): claiming LOOK makes an equal-or-better-priority
        // native ranged goal starve this one, and makes this one preempt a lower-priority bow goal.
        setFlags(MagicNpcsConfig.castingGoalUsesLookFlag() ? EnumSet.of(Flag.LOOK) : EnumSet.noneOf(Flag.class));
    }

    private ManagedCasterState state() {
        return ManagedCasterState.of(mob);
    }

    @Override
    public boolean canUse() {
        if (spells.isEmpty()) {
            return false;
        }
        ManagedCasterState state = state();
        LivingEntity t = mob.getTarget();
        if (t != null && !t.isAlive()) {
            t = null;
        }
        boolean outOfCombat = t == null;
        // Entering combat must not be served at the idle cadence. The idle branch below schedules the
        // next decision *before* choosing, and scheduleNextDecision only ever moves the deadline later
        // — so a caster that idled on the tick before it acquired a target was locked out for the whole
        // idle window (default 5 s, up to 2 minutes at the config maximum), which reads as "my mob just
        // stands there when a fight starts". Pull the deadline back to the combat cadence instead.
        if (state.idleScheduled() && !outOfCombat) {
            state.pullDecisionForward(mob.tickCount + combatInterval());
        }
        if (mob.tickCount < state.nextDecisionTick() || !canCastInCurrentState(outOfCombat)) {
            return false;
        }
        if (outOfCombat) {
            // Out of combat only SUPPORT is eligible, and only when the feature is on. Space the next
            // idle evaluation up front so a caster with nothing to do costs one check per cadence
            // rather than one per goal tick.
            if (!hasSupportSpell || !MagicNpcsConfig.SUPPORT_OUT_OF_COMBAT.get()) {
                return false;
            }
            scheduleNextDecision(idleInterval());
        } else if (loadout.nativeAttack() == NativeAttackPolicy.YIELD
                && AttackGoals.anyNativeAttackRunning(mob)) {
            scheduleNextDecision(combatInterval()); // this loadout defers to the mob's own attack AI
            return false;
        }
        Resolved pick = choose(t);
        if (pick == null) {
            // A spent decision that produced nothing still counts as a decision. Without this the
            // whole selection scan — including LineOfFire.scan and any enemies_within count — re-ran
            // on the raw goal cadence (every other tick) for every caster whose spells were all on
            // cooldown, out of range, or blocked.
            scheduleNextDecision(outOfCombat ? idleInterval() : combatInterval());
            return false;
        }
        // Per-spell cast chance: hesitate (skip this decision) with the chosen spell's probability,
        // then space the next attempt by the decision interval so it reads as a deliberate pause.
        if (mob.getRandom().nextDouble() >= resolveCastChance(pick.entry())) {
            scheduleNextDecision(outOfCombat ? idleInterval() : combatInterval());
            return false;
        }
        this.target = t;
        this.chosen = pick;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (chosen == null) {
            return false;
        }
        if (session != null && session.isRunning()) {
            return true; // the channel decides when it is finished; tick() cancels it if it must
        }
        return windupRemaining > 0 && windupTargetValid() == null;
    }

    /**
     * Gate casting on the mob being in a valid, casting-capable state. Vanilla invalid states
     * (dead/dying, removed/despawning, sleeping, AI disabled) are always blocked; Peaceful difficulty
     * is blocked when configured; a mod-specific busy/command state is deferred to the adapter; and an
     * optional held-focus requirement is enforced last.
     */
    private boolean canCastInCurrentState(boolean supportOnly) {
        return stateBlocker(supportOnly) == null;
    }

    /**
     * @return a short reason the mob cannot cast right now, or {@code null} if every state gate passes.
     *         Shared by {@link #canUse()} and {@code /magicnpcs why}, so the diagnostic can never
     *         disagree with the behaviour it is explaining.
     */
    String stateBlocker() {
        return stateBlocker(false);
    }

    /**
     * @param supportOnly true when the only candidates are self-cast SUPPORT spells (the mob has no
     *                    target). A mod-specific "do not fight" command state blocks attacking, not
     *                    healing — a recruit ordered to retreat and recover could not heal itself,
     *                    contradicting the out-of-combat support behaviour it was told to have.
     */
    String stateBlocker(boolean supportOnly) {
        // The master switch, checked live. 0.6.1 only consulted it when injecting a goal and in the
        // mana tick, so flipping general.enableSpellcasting to false left every already-installed goal
        // happily casting until the next chunk reload (audit CFG-001).
        if (!MagicNpcsConfig.ENABLE_SPELLCASTING.get()) {
            return "general.enableSpellcasting is false (master switch)";
        }
        if (!mob.isAlive() || mob.isRemoved() || mob.isDeadOrDying()) {
            return "the mob is dead or being removed";
        }
        if (mob.isSleeping()) {
            return "the mob is sleeping";
        }
        if (mob.isNoAi()) {
            return "the mob has NoAI set";
        }
        if (MagicNpcsConfig.PEACEFUL_DISABLES_CASTING.get() && mob.level().getDifficulty() == Difficulty.PEACEFUL) {
            return "difficulty is Peaceful and balance.peacefulDisablesCasting is on";
        }
        boolean adapterAllows = supportOnly ? adapter().canSupportCastNow(mob) : adapter().canCastNow(mob);
        if (!adapterAllows) {
            return "the " + AttackGoals.simpleName(adapter().getClass())
                    + " adapter is blocking casting (e.g. a passive/hold/sitting state)";
        }
        if (!MagicNpcsConfig.REQUIRE_SPELL_FOCUS.get() || IronsBridge.holdsSpellFocus(mob)) {
            return null;
        }
        // School-aware focus: a school-assigned caster may instead hold a focus for its own
        // Iron's school (the per-school irons_spellbooks:<school>_focus tag).
        if (MagicNpcsConfig.SCHOOLS_SCHOOL_AWARE_FOCUS.get()) {
            ResourceLocation school = SchoolData.getSchool(mob);
            if (school != null && IronsBridge.holdsSchoolFocus(mob, school)) {
                return null;
            }
        }
        return "equipment.requireSpellFocus is on and the mob holds no item in #magicnpcs:spell_focuses";
    }

    /**
     * @return true if this goal was built from an older catalog than the one currently published, so
     *         a diagnostic can say "this mob predates the last /reload" rather than describing stale
     *         data as current (audit VAL-002).
     */
    public boolean isStale() {
        return builtForGeneration != LoadoutManager.generation();
    }

    public int builtForGeneration() {
        return builtForGeneration;
    }

    @Override
    public void start() {
        int windup = resolveWindup(chosen);
        if (windup <= 0) {
            beginCast(); // wind-up disabled — hand straight to Iron's
            return;
        }
        if (chosen.entry().role() == LoadoutEntry.Role.ATTACK && target != null) {
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        // +1 because GoalSelector.tick() ends with tickRunningGoals(true): a goal started during this
        // tick is also ticked during it, so the first decrement lands on the start tick. Without the
        // adjustment a configured wind-up of N produced N-1 ticks of delay and `windup: 1` was
        // indistinguishable from `windup: 0`.
        windupRemaining = windup + 1;
        // Telegraph the wind-up so the cast can be seen coming (server-spawned vanilla particles/sound).
        // Never for an out-of-combat self-heal: an idle NPC topping itself up should not play a combat
        // tell (ADR 0005).
        if (target != null) {
            glowApplied = Telegraphs.play(mob,
                    IronsBridge.telegraphFor(chosen.spell(), effectiveLevel(chosen), chosen.entry().safetyRadius()),
                    chosen.entry().safetyRadius());
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true; // the wind-up counts down, the channel ticks, and both re-aim every tick
    }

    @Override
    public void tick() {
        if (chosen == null) {
            return;
        }
        if (session != null) {
            tickSession();
            return;
        }
        if (chosen.entry().role() == LoadoutEntry.Role.ATTACK && target != null) {
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F); // continuous aim during the wind-up
        }
        if (--windupRemaining <= 0) {
            beginCast();
        }
    }

    /** Drive one tick of a live Iron's cast session, cancelling it if the cast is no longer valid. */
    private void tickSession() {
        String blocker = stateBlocker(target == null);
        if (blocker != null) {
            session.cancel(MobCastSession.CancelReason.CASTER_UNAVAILABLE);
            endAttempt();
            return;
        }
        MobCastSession.CancelReason invalid = windupTargetValid();
        if (invalid != null) {
            session.cancel(invalid);
            endAttempt();
            return;
        }
        if (session.spellWantsToStop()) {
            session.cancel(MobCastSession.CancelReason.SPELL_ASKED_TO_STOP);
            endAttempt();
            return;
        }
        session.retarget(target);
        if (!session.tick()) {
            endAttempt(); // completed
        }
    }

    @Override
    public void stop() {
        if (session != null && session.isRunning()) {
            // An interrupted channel must still run Iron's completion hook, or anything its pre-cast
            // created (target-area entities, synced state) is stranded.
            session.cancel(MobCastSession.CancelReason.GOAL_STOPPED);
        } else if (MagicNpcsConfig.debugLogging() && chosen != null && windupRemaining > 0) {
            MagicNpcs.LOGGER.info("[windup] {} interrupted casting {} with {} ticks left",
                    EntityType.getKey(mob.getType()), chosen.entry().spell(), windupRemaining);
        }
        endAttempt();
    }

    /**
     * Hand the cast to Iron's. Mana and cooldown are charged inside the session at the moment Iron's
     * accepts the cast, so a refusal here costs nothing.
     */
    private void beginCast() {
        boolean wasOutOfCombat = target == null;
        if (chosen.entry().role() == LoadoutEntry.Role.ATTACK && target != null) {
            // LookControl only applies its rotation later in the tick (after the goal runs), so a
            // setLookAt here is stale at cast time — Iron's projectile spells read getLookAngle()
            // during onCast, and its target-seeking pre-cast helpers raycast along it. Snap the mob's
            // rotation at the target NOW so the spell fires on-aim.
            snapFacing(target);
        }
        MobCastSession.Start start =
                MobCastSession.begin(mob, target, chosen.spell(), effectiveLevel(chosen));
        if (!start.started()) {
            // Space the next decision regardless, so a spell that keeps refusing doesn't spam.
            scheduleNextDecision(wasOutOfCombat ? idleInterval() : combatInterval());
            endAttempt();
            return;
        }
        session = start.session();
        mob.swing(InteractionHand.MAIN_HAND);
        // Cooldown starts with the cast, not with its completion: a channel that is interrupted must
        // not be immediately retryable, or an ATTACK caster whose target keeps ducking behind cover
        // replays its telegraph on every decision (backlog B13).
        state().startCooldown(chosen.entry().spell(), mob.tickCount + resolveCooldown(chosen));
        scheduleNextDecision(wasOutOfCombat ? idleInterval() : combatInterval());
        // An INSTANT spell resolves on its first session tick; drive it now so windup=0 still fires
        // in the tick the goal started, as it did before 0.6.2.
        tickSession();
    }

    private void endAttempt() {
        if (glowApplied) {
            Telegraphs.clearGlow(mob);
            glowApplied = false;
        }
        if (chosen != null) {
            scheduleNextDecision(target == null ? idleInterval() : combatInterval());
        }
        this.session = null;
        this.chosen = null;
        this.target = null;
        this.windupRemaining = 0;
    }

    /**
     * Force the caster's yaw/pitch (head + body) straight at the target's eyes immediately, so the
     * look vector Iron's reads in {@code onCast} points at the target this very tick.
     * {@code LookControl} defers its rotation until after the goal tick, so it cannot be relied on for
     * the cast frame; we set the rotations directly (and the {@code *O} previous-frame values to avoid
     * an interpolation artifact).
     */
    private void snapFacing(LivingEntity t) {
        double dx = t.getX() - mob.getX();
        double dz = t.getZ() - mob.getZ();
        double dy = t.getEyeY() - mob.getEyeY();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        mob.setYRot(yaw);
        mob.yRotO = yaw;
        mob.yBodyRot = yaw;
        mob.yBodyRotO = yaw;
        mob.setYHeadRot(yaw);
        mob.yHeadRotO = yaw;
        mob.setXRot(pitch);
        mob.xRotO = pitch;
        if (MagicNpcsConfig.debugLogging()) {
            MagicNpcs.LOGGER.info("[aim] {} snapped to yaw={} pitch={} before casting {}",
                    EntityType.getKey(mob.getType()), String.format("%.1f", yaw), String.format("%.1f", pitch),
                    chosen.entry().spell());
        }
    }

    /**
     * Re-validate an in-flight wind-up or channel.
     *
     * @return the reason it is no longer valid, or {@code null} while it still is
     */
    private MobCastSession.CancelReason windupTargetValid() {
        if (!mob.isAlive() || mob.isNoAi()) {
            return MobCastSession.CancelReason.CASTER_UNAVAILABLE;
        }
        if (chosen.entry().role() != LoadoutEntry.Role.ATTACK) {
            return null; // SUPPORT self-cast: no aim/LOS/range gating
        }
        if (target == null || !target.isAlive() || target.isRemoved()) {
            return MobCastSession.CancelReason.TARGET_LOST;
        }
        if (!adapter().canCastAt(mob, target)) {
            return MobCastSession.CancelReason.TARGET_LOST;
        }
        LoadoutEntry e = chosen.entry();
        if (mob.distanceToSqr(target) > e.maxRange() * e.maxRange()) {
            return MobCastSession.CancelReason.TARGET_OUT_OF_RANGE; // minRange intentionally not re-checked
        }
        if (MagicNpcsConfig.REQUIRE_LINE_OF_SIGHT.get() && !mob.getSensing().hasLineOfSight(target)) {
            return MobCastSession.CancelReason.TARGET_NOT_VISIBLE;
        }
        return null;
    }

    /**
     * Weighted-random pick among castable spells. With a target: ATTACK entries in range,
     * line-of-sight-clear and friendly-fire-clear, plus SUPPORT entries when hurt. With no target
     * (out of combat): SUPPORT entries only.
     *
     * <p>A spell may carry an optional reactive {@link CastCondition} (self/target HP, nearby-enemy
     * count, recently-hurt) that further gates its eligibility; for SUPPORT a condition replaces the
     * default "when hurt" gate — except out of combat, where a condition with no self-health term must
     * still pass the hurt gate so an idle caster cannot loop forever (ADR 0005). A spell whose condition
     * is currently satisfied may get a configurable selection-weight bonus so the right tool is favoured.
     *
     * <p>The friendly-fire corridor is scanned <b>once</b> per decision at the widest safety radius among
     * the surviving candidates, then each spell is tested against that snapshot (backlog B12).
     */
    private Resolved choose(LivingEntity t) {
        boolean outOfCombat = t == null;
        double distSqr = outOfCombat ? 0.0 : mob.distanceToSqr(t);
        boolean hurt = mob.getHealth() < mob.getMaxHealth() * MagicNpcsConfig.SUPPORT_HEALTH_THRESHOLD.get();
        NpcAdapter npcAdapter = adapter();
        boolean canAttackTarget = !outOfCombat && npcAdapter.canCastAt(mob, t);
        boolean hasLineOfSight = !outOfCombat && mob.getSensing().hasLineOfSight(t);
        boolean reactive = MagicNpcsConfig.REACTIVE_CASTING_ENABLED.get();
        ManagedCasterState state = state();

        // Pass A: everything except the friendly-fire corridor, which is expensive and shared.
        List<Resolved> candidates = new ArrayList<>();
        List<Boolean> matched = new ArrayList<>();
        double maxSafety = 0.0;
        boolean anyAttack = false;
        for (Resolved r : spells) {
            LoadoutEntry e = r.entry();
            if (state.cooldownRemaining(e.spell(), mob.tickCount) > 0) {
                continue;
            }
            if (!IronsBridge.canAfford(mob, r.spell(), effectiveLevel(r))) {
                continue;
            }
            if (!holdsRequiredItem(e)) {
                continue; // per-spell require_held_item
            }
            CastCondition cond = reactive ? e.condition() : null;
            boolean hasCond = cond != null && !cond.isEmpty();
            boolean condMatched = false;
            if (e.role() == LoadoutEntry.Role.SUPPORT) {
                if (hasCond) {
                    if (!cond.evaluate(mob, t, npcAdapter)) {
                        continue; // reactive condition replaces the default "when hurt" gate
                    }
                    if (outOfCombat && !cond.hasSelfHealthGate() && !hurt) {
                        continue; // anti-loop floor: idle support still requires being hurt
                    }
                    condMatched = true;
                } else if (!hurt) {
                    continue; // self-cast support only when threatened
                }
            } else { // ATTACK — never selectable without a target
                if (outOfCombat || !canAttackTarget) {
                    continue;
                }
                if (distSqr < e.minRange() * e.minRange() || distSqr > e.maxRange() * e.maxRange()) {
                    continue; // target out of range
                }
                if (MagicNpcsConfig.REQUIRE_LINE_OF_SIGHT.get() && !hasLineOfSight) {
                    continue; // can't see the target through blocks
                }
                if (hasCond) {
                    if (!cond.evaluate(mob, t, npcAdapter)) {
                        continue; // reactive condition (e.g. execute below target HP, AoE when swarmed)
                    }
                    condMatched = true;
                }
                anyAttack = true;
                maxSafety = Math.max(maxSafety, e.safetyRadius());
            }
            candidates.add(r);
            matched.add(condMatched);
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // Pass B: one corridor scan (only if an ATTACK candidate survived and protection is on), then
        // weight what is left.
        LineOfFire.Scan scan = LineOfFire.CLEAR;
        if (anyAttack && MagicNpcsConfig.FRIENDLY_FIRE_CHECK.get()
                && (npcAdapter.tracksAllies() || MagicNpcsConfig.PROTECT_BYSTANDERS.get())) {
            scan = LineOfFire.scan(mob, t, maxSafety, npcAdapter);
        }
        List<Resolved> castable = new ArrayList<>(candidates.size());
        List<Long> weights = new ArrayList<>(candidates.size());
        long totalWeight = 0L;
        for (int i = 0; i < candidates.size(); i++) {
            Resolved r = candidates.get(i);
            LoadoutEntry e = r.entry();
            if (e.role() == LoadoutEntry.Role.ATTACK
                    && !scan.clearAt(e.safetyRadius(), SpellCompat.geometryOf(r.spell()))) {
                continue; // an ally or protected bystander is in the line of fire / blast radius
            }
            // Saturating arithmetic, deliberately. Math.round returns a long that was being narrowed
            // to int, and totalWeight was an unguarded int sum — so a large datapack weight, or a
            // moderate one multiplied by matchedConditionWeightBonus (which allows up to 100), wrapped
            // negative and made RandomSource.nextInt(negative) throw straight out of canUse() into
            // Mob#serverAiStep: a "Ticking entity" crash from an over-enthusiastic weight in a JSON file.
            long weight = Math.max(1, e.weight());
            if (Boolean.TRUE.equals(matched.get(i))) {
                weight = Math.max(1L, Math.round(weight * MagicNpcsConfig.MATCHED_CONDITION_WEIGHT_BONUS.get()));
            }
            castable.add(r);
            weights.add(weight);
            totalWeight = saturatingAdd(totalWeight, weight);
        }
        if (castable.isEmpty()) {
            return null;
        }
        long roll = Math.floorMod(mob.getRandom().nextLong(), totalWeight);
        for (int i = 0; i < castable.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) {
                return castable.get(i);
            }
        }
        return castable.get(castable.size() - 1);
    }

    /**
     * The per-spell held-item requirement ({@code require_held_item} / {@code required_items} /
     * {@code required_hand}), restored in 0.6.2 (audit REL-002).
     *
     * <p>Distinct from the global {@code equipment.requireSpellFocus}, which gates <em>all</em>
     * casting: this gates one spell, so a caster can be given a staff-only nuke alongside spells it
     * can cast bare-handed. An empty {@code required_items} falls back to the spell-focus tag.
     */
    boolean holdsRequiredItem(LoadoutEntry entry) {
        if (!entry.requireHeldItem()) {
            return true;
        }
        return switch (entry.requiredHand()) {
            case MAIN -> matchesRequirement(mob.getItemInHand(InteractionHand.MAIN_HAND), entry);
            case OFF -> matchesRequirement(mob.getItemInHand(InteractionHand.OFF_HAND), entry);
            case EITHER -> matchesRequirement(mob.getItemInHand(InteractionHand.MAIN_HAND), entry)
                    || matchesRequirement(mob.getItemInHand(InteractionHand.OFF_HAND), entry);
        };
    }

    private boolean matchesRequirement(ItemStack stack, LoadoutEntry entry) {
        if (stack.isEmpty()) {
            return false;
        }
        if (entry.requiredItems().isEmpty()) {
            return stack.is(IronsBridge.SPELL_FOCUSES);
        }
        for (String ref : entry.requiredItems()) {
            if (ref.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(ref.substring(1));
                if (tagId != null && stack.is(TagKey.create(Registries.ITEM, tagId))) {
                    return true;
                }
            } else {
                ResourceLocation itemId = ResourceLocation.tryParse(ref);
                Item item = itemId == null ? null : BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
                if (item != null && stack.is(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Add without overflowing: an absurd datapack weight should skew selection, never crash the server. */
    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /**
     * The level this spell is actually cast at: the loadout's own {@code level} as a floor, raised by
     * the caster's progression rank where its adapter has one (a Villager Recruit's XP level).
     *
     * <p><b>Every</b> read of a spell's level goes through here — the telegraph, the affordability
     * check, the cast itself, and the diagnostic table. They have to agree: checking whether the mob
     * can afford level 1 and then asking Iron's to cast level 3 makes {@code MobCastSession.begin}
     * refuse with {@code INSUFFICIENT_MANA} on every attempt, and the caster silently never casts.
     *
     * <p>Computed per decision rather than cached on {@link Resolved}, so a rank-up takes effect
     * without needing the mob reconciled. {@code adapter().level(mob)} is a synched-data read and the
     * adapter is already re-resolved on a slow cadence, so this is cheap.
     */
    int effectiveLevel(Resolved r) {
        int floor = r.entry().level();
        double perRank = MagicNpcsConfig.rankLevelPerRank();
        if (perRank <= 0.0) {
            return floor;
        }
        int rank = Math.max(0, adapter().level(mob));
        if (rank == 0) {
            return floor;
        }
        int ceiling = Math.min(r.spell().getMaxLevel(), floor + MagicNpcsConfig.rankLevelMaxBonus());
        int ranked = floor + (int) (rank * perRank);
        return Math.max(floor, Math.min(ranked, ceiling));
    }

    /** @return the level {@code entry} would be cast at right now, for the diagnostic table. */
    int effectiveLevel(LoadoutEntry entry) {
        for (Resolved r : spells) {
            if (r.entry() == entry) {
                return effectiveLevel(r);
            }
        }
        return entry.level();
    }

    private double resolveCastChance(LoadoutEntry e) {
        return e.castChance() != null ? e.castChance() : MagicNpcsConfig.CAST_CHANCE.get();
    }

    /**
     * Wind-up ticks the caster spends telegraphing <em>before</em> handing the cast to Iron's.
     *
     * <p>An explicit per-spell {@code windup} wins. Otherwise it is the global wind-up: a channelled
     * spell's own cast time is now run by {@link MobCastSession} as a real Iron's channel, so adding it
     * here as well would double the delay. Through 0.6.1 the mod simulated the channel itself with a
     * wind-up equal to the raw cast time and then fired {@code onCast} once at the end, which is
     * exactly the behaviour {@link MobCastSession} replaces.
     */
    private int resolveWindup(Resolved r) {
        Integer w = r.entry().windupTicks();
        return w != null ? w : MagicNpcsConfig.CAST_WINDUP_TICKS.get();
    }

    /** Precedence: explicit per-spell ticks > per-spell multiplier > global multiplier; always floored. */
    private int resolveCooldown(Resolved r) {
        LoadoutEntry e = r.entry();
        return CooldownResolver.resolve(
                e.cooldownTicks(),
                e.cooldownMultiplier(),
                MagicNpcsConfig.COOLDOWN_MULTIPLIER.get(),
                r.spell().getSpellCooldown(),
                MagicNpcsConfig.MIN_COOLDOWN_TICKS.get());
    }

    /**
     * Push the next decision out by {@code ticks} <em>game</em> ticks. Only ever moves the deadline
     * later, so a cheap early-out cannot shorten a cooldown another path already imposed.
     */
    private void scheduleNextDecision(int ticks) {
        ManagedCasterState state = state();
        int at = mob.tickCount + Math.max(1, ticks);
        if (at > state.nextDecisionTick()) {
            // Remember whether this deadline came from the (long) idle cadence, so acquiring a target
            // can pull it back in rather than waiting the idle window out. See canUse().
            state.scheduleDecision(at, ticks >= idleInterval() && idleInterval() > combatInterval());
        }
    }

    private int combatInterval() {
        return MagicNpcsConfig.DECISION_INTERVAL_TICKS.get();
    }

    private int idleInterval() {
        return MagicNpcsConfig.SUPPORT_OUT_OF_COMBAT_INTERVAL_TICKS.get();
    }

    /** @return the mob's adapter, re-resolved on a slow cadence so post-spawn taming/teaming is honoured. */
    NpcAdapter adapter() {
        if (adapter == null || mob.tickCount - adapterResolvedAt >= ADAPTER_REFRESH_TICKS) {
            adapter = NpcAdapters.resolve(mob);
            adapterResolvedAt = mob.tickCount;
        }
        return adapter;
    }

    // --- Diagnostics seam (/magicnpcs why) ---

    /** The loadout this goal was built from — the authoritative answer, not a re-resolution. */
    public SpellcasterLoadout loadout() {
        return loadout;
    }

    /** The mob this goal is installed on, so a diagnostic can look up its managed state. */
    public Mob mob() {
        return mob;
    }

    /** The loadout entries that survived construction (blacklist, unknown id, unsupported/unverified). */
    List<LoadoutEntry> castableEntries() {
        List<LoadoutEntry> out = new ArrayList<>(spells.size());
        for (Resolved r : spells) {
            out.add(r.entry());
        }
        return out;
    }

    /** @return ticks until {@code spell} comes off cooldown, or 0 if it is ready. */
    int cooldownRemaining(ResourceLocation spell) {
        return state().cooldownRemaining(spell, mob.tickCount);
    }

    /** @return ticks until this goal will next consider casting, or 0 if it would consider it now. */
    int ticksUntilNextDecision() {
        return Math.max(0, state().nextDecisionTick() - mob.tickCount);
    }

    /** @return the live cast session, or {@code null} when the caster is not channelling. */
    MobCastSession session() {
        return session;
    }

    /** @return the resolved cooldown (ticks) this goal would apply after casting {@code entry}. */
    int plannedCooldown(LoadoutEntry entry) {
        for (Resolved r : spells) {
            if (r.entry() == entry) {
                return resolveCooldown(r);
            }
        }
        return 0;
    }

    /** @return the danger shape of {@code entry}'s spell, for the friendly-fire diagnostic. */
    LineOfFire.Geometry geometryOf(LoadoutEntry entry) {
        for (Resolved r : spells) {
            if (r.entry() == entry) {
                return SpellCompat.geometryOf(r.spell());
            }
        }
        return LineOfFire.Geometry.CORRIDOR;
    }

    /** @return the wind-up (ticks) this goal would use for {@code entry}. */
    int plannedWindup(LoadoutEntry entry) {
        for (Resolved r : spells) {
            if (r.entry() == entry) {
                return resolveWindup(r);
            }
        }
        return 0;
    }

    /** @return Iron's mana cost of {@code entry} at its configured level, for the diagnostic table. */
    int manaCost(LoadoutEntry entry) {
        for (Resolved r : spells) {
            if (r.entry() == entry) {
                return r.spell().getManaCost(effectiveLevel(r));
            }
        }
        return 0;
    }

    private record Resolved(LoadoutEntry entry, AbstractSpell spell) {}
}
