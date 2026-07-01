package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.feedback.Telegraphs;
import com.otectus.magicnpcs.core.loadout.CastCondition;
import com.otectus.magicnpcs.core.loadout.CooldownResolver;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import com.otectus.magicnpcs.core.util.LineOfFire;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Universal NPC casting goal: drives spell selection from a datapack
 * {@link SpellcasterLoadout}. Per ADR 0001 we own the mana economy and cooldowns
 * — cooldowns are a self-managed per-spell map (Iron's does not tick foreign
 * mobs' {@code PlayerCooldowns}). Mana lives on Iron's {@code MagicData}.
 *
 * <p>ATTACK spells are aimed at the hostile target; SUPPORT spells self-cast when
 * the mob's health drops below the configured threshold. A per-mob
 * {@link NpcAdapter} (e.g. Recruits) gates targeting via {@code canCastAt} and
 * supplies allies for the {@link LineOfFire} friendly-fire check. Balance knobs
 * come from {@link MagicNpcsConfig}.
 */
public class NpcSpellAttackGoal extends Goal {
    private final Mob mob;
    private final NpcAdapter adapter;
    private final List<Resolved> spells = new ArrayList<>();
    private final Map<ResourceLocation, Integer> cooldowns = new HashMap<>();
    private int decisionTimer;
    private int windupRemaining;
    private Resolved chosen;
    private LivingEntity target;

    public NpcSpellAttackGoal(Mob mob, SpellcasterLoadout loadout) {
        this.mob = mob;
        this.adapter = NpcAdapters.resolve(mob);
        for (LoadoutEntry entry : loadout.spells()) {
            if (!MagicNpcsConfig.isAllowed(entry.spell().toString())) {
                continue;
            }
            AbstractSpell spell = IronsBridge.getSpell(entry.spell());
            if (spell == null) {
                IronsBridge.warnUnknownSpell(loadout.source(), loadout.entityType(), entry.spell());
                continue;
            }
            SpellCompat.Category category = SpellCompat.categoryOf(spell);
            if (!SpellCompat.supportedForMob(category)) {
                com.otectus.magicnpcs.MagicNpcs.LOGGER.warn(
                        "Loadout {} ({}): spell {} is not castable by a mob — {}; skipping it.",
                        loadout.source(), loadout.entityType(), entry.spell(),
                        SpellCompat.unsupportedReason(category));
                continue;
            }
            spells.add(new Resolved(entry, spell));
        }
        setFlags(EnumSet.of(Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        tickCooldowns();
        if (spells.isEmpty() || !canCastInCurrentState()) {
            return false;
        }
        if (decisionTimer > 0) {
            decisionTimer--;
            return false;
        }
        LivingEntity t = mob.getTarget();
        if (t == null || !t.isAlive()) {
            return false;
        }
        Resolved pick = choose(t);
        if (pick == null) {
            return false;
        }
        // Per-spell cast chance: hesitate (skip this decision) with the chosen spell's probability,
        // then space the next attempt by the decision interval so it reads as a deliberate pause.
        if (mob.getRandom().nextDouble() >= resolveCastChance(pick.entry())) {
            decisionTimer = MagicNpcsConfig.DECISION_INTERVAL_TICKS.get();
            return false;
        }
        this.target = t;
        this.chosen = pick;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        // Stay active only while a wind-up is pending and the re-validated target still holds.
        return chosen != null && windupRemaining > 0 && windupTargetValid();
    }

    /**
     * Gate casting on the mob being in a valid, casting-capable state. Vanilla
     * invalid states (dead/dying, removed/despawning, sleeping, AI disabled) are
     * always blocked; Peaceful difficulty is blocked when configured; a mod-specific
     * busy/command state is deferred to the adapter; and an optional held-focus
     * requirement is enforced last.
     */
    private boolean canCastInCurrentState() {
        if (!mob.isAlive() || mob.isRemoved() || mob.isDeadOrDying() || mob.isSleeping() || mob.isNoAi()) {
            return false;
        }
        if (MagicNpcsConfig.PEACEFUL_DISABLES_CASTING.get() && mob.level().getDifficulty() == Difficulty.PEACEFUL) {
            return false;
        }
        if (!adapter.canCastNow(mob)) {
            return false;
        }
        if (!MagicNpcsConfig.REQUIRE_SPELL_FOCUS.get() || IronsBridge.holdsSpellFocus(mob)) {
            return true;
        }
        // School-aware focus: a school-assigned caster may instead hold a focus for its own
        // Iron's school (the per-school irons_spellbooks:<school>_focus tag).
        if (MagicNpcsConfig.SCHOOLS_SCHOOL_AWARE_FOCUS.get()) {
            ResourceLocation school = SchoolData.getSchool(mob);
            return school != null && IronsBridge.holdsSchoolFocus(mob, school);
        }
        return false;
    }

    @Override
    public void start() {
        int windup = resolveWindup(chosen);
        if (windup <= 0) {
            fire(); // wind-up disabled → instant cast (legacy behaviour)
            return;
        }
        if (chosen.entry().role() == LoadoutEntry.Role.ATTACK) {
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        windupRemaining = windup;
        // Telegraph the wind-up so the cast can be seen coming (server-spawned vanilla particles/sound).
        Telegraphs.play(mob,
                IronsBridge.telegraphFor(chosen.spell(), chosen.entry().level(), chosen.entry().safetyRadius()),
                chosen.entry().safetyRadius());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true; // the wind-up counts down and re-aims every tick
    }

    @Override
    public void tick() {
        if (chosen == null) {
            return;
        }
        if (chosen.entry().role() == LoadoutEntry.Role.ATTACK && target != null) {
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F); // continuous aim during the wind-up
        }
        if (--windupRemaining <= 0) {
            fire();
        }
    }

    @Override
    public void stop() {
        if (MagicNpcsConfig.DEBUG_LOGGING.get() && chosen != null && windupRemaining > 0) {
            com.otectus.magicnpcs.MagicNpcs.LOGGER.info("[windup] {} interrupted casting {} with {} ticks left",
                    EntityType.getKey(mob.getType()), chosen.entry().spell(), windupRemaining);
        }
        // Reached on a clean finish (fire() already cleared state) or an interrupted wind-up
        // (target lost): in the latter case no cast happened, so no cooldown is consumed.
        endAttempt();
    }

    /** Cast now: face + swing, apply the spell (mana deducted by the bridge), set cooldown, space next decision. */
    private void fire() {
        if (!IronsBridge.canAfford(mob, chosen.spell(), chosen.entry().level())) {
            endAttempt(); // mana drained during the wind-up — abort without setting a cooldown
            return;
        }
        if (chosen.entry().role() == LoadoutEntry.Role.ATTACK && target != null) {
            // LookControl only applies its rotation later in the tick (after the goal runs), so a
            // setLookAt here is stale at cast time — Iron's projectile spells read getLookAngle()
            // during onCast. Snap the mob's rotation at the target NOW so the spell fires on-aim,
            // even on the windup=0 instant path.
            snapFacing(target);
        }
        // Pass the target so target-locked spells (root/devour/wisp) get their TargetEntityCastData.
        boolean cast = IronsBridge.cast(mob, target, chosen.spell(), chosen.entry().level());
        if (cast) {
            mob.swing(InteractionHand.MAIN_HAND);
            cooldowns.put(chosen.entry().spell(), resolveCooldown(chosen));
        }
        // Space the next decision regardless, so a spell that skips (e.g. unmet pre-cast) doesn't spam.
        decisionTimer = MagicNpcsConfig.DECISION_INTERVAL_TICKS.get();
        endAttempt();
    }

    private void endAttempt() {
        Telegraphs.clearGlow(mob);
        this.chosen = null;
        this.target = null;
        this.windupRemaining = 0;
    }

    /**
     * Force the caster's yaw/pitch (head + body) straight at the target's eyes immediately, so the
     * look vector Iron's reads in {@code onCast} points at the target this very tick. {@code LookControl}
     * defers its rotation until after the goal tick, so it can't be relied on for the cast frame; we
     * set the rotations directly (and the {@code *O} previous-frame values to avoid an interpolation
     * artifact). Vertical aim (pitch) and horizontal aim (yaw, head + body) are all covered.
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
        if (MagicNpcsConfig.DEBUG_LOGGING.get()) {
            com.otectus.magicnpcs.MagicNpcs.LOGGER.info(
                    "[aim] {} snapped to yaw={} pitch={} before casting {}",
                    EntityType.getKey(mob.getType()), String.format("%.1f", yaw), String.format("%.1f", pitch),
                    chosen.entry().spell());
        }
    }

    /** Re-validate an in-flight wind-up: ATTACK casts need a live, allowed, reachable, visible target. */
    private boolean windupTargetValid() {
        if (!mob.isAlive() || mob.isNoAi()) {
            return false;
        }
        if (chosen.entry().role() != LoadoutEntry.Role.ATTACK) {
            return true; // SUPPORT self-cast: no aim/LOS/range gating
        }
        if (target == null || !target.isAlive() || target.isRemoved()) {
            return false;
        }
        if (!adapter.canCastAt(mob, target)) {
            return false;
        }
        LoadoutEntry e = chosen.entry();
        if (mob.distanceToSqr(target) > e.maxRange() * e.maxRange()) {
            return false; // target fled out of range mid-wind-up (minRange intentionally not re-checked)
        }
        return !MagicNpcsConfig.REQUIRE_LINE_OF_SIGHT.get() || mob.getSensing().hasLineOfSight(target);
    }

    /**
     * Weighted-random pick among castable spells: ATTACK in range + friendly-fire-clear, or SUPPORT
     * when hurt. A spell may carry an optional reactive {@link CastCondition} (self/target HP,
     * nearby-enemy count, recently-hurt) that further gates its eligibility; for SUPPORT a condition
     * replaces the default "when hurt" gate. A spell whose condition is currently satisfied may get a
     * configurable selection-weight bonus so the right tool is favoured (e.g. an AoE when swarmed).
     */
    private Resolved choose(LivingEntity t) {
        double distSqr = mob.distanceToSqr(t);
        boolean hurt = mob.getHealth() < mob.getMaxHealth() * MagicNpcsConfig.SUPPORT_HEALTH_THRESHOLD.get();
        boolean canAttackTarget = adapter.canCastAt(mob, t);
        boolean hasLineOfSight = mob.getSensing().hasLineOfSight(t);
        // Friendly-fire scan runs when the adapter tracks allies OR generic bystander
        // protection is on (so even an adapter-less skeleton won't blast the townsfolk).
        boolean friendlyFire = MagicNpcsConfig.FRIENDLY_FIRE_CHECK.get()
                && (adapter.tracksAllies() || MagicNpcsConfig.PROTECT_BYSTANDERS.get());
        boolean reactive = MagicNpcsConfig.REACTIVE_CASTING_ENABLED.get();

        List<Resolved> castable = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        int totalWeight = 0;
        for (Resolved r : spells) {
            LoadoutEntry e = r.entry();
            if (cooldowns.getOrDefault(e.spell(), 0) > 0) {
                continue;
            }
            if (!IronsBridge.canAfford(mob, r.spell(), e.level())) {
                continue;
            }
            CastCondition cond = reactive ? e.condition() : null;
            boolean hasCond = cond != null && !cond.isEmpty();
            boolean condMatched = false;
            if (e.role() == LoadoutEntry.Role.SUPPORT) {
                if (hasCond) {
                    if (!cond.evaluate(mob, null, adapter)) {
                        continue; // reactive condition replaces the default "when hurt" gate
                    }
                    condMatched = true;
                } else if (!hurt) {
                    continue; // self-cast support only when threatened
                }
            } else { // ATTACK
                if (!canAttackTarget) {
                    continue; // adapter forbids attacking this target (e.g. ally/neutral)
                }
                if (distSqr < e.minRange() * e.minRange() || distSqr > e.maxRange() * e.maxRange()) {
                    continue; // target out of range
                }
                if (MagicNpcsConfig.REQUIRE_LINE_OF_SIGHT.get() && !hasLineOfSight) {
                    continue; // can't see the target through blocks
                }
                if (friendlyFire && !LineOfFire.clear(mob, t, e.safetyRadius(), adapter)) {
                    continue; // an ally or protected bystander is in the line of fire / blast radius
                }
                if (hasCond) {
                    if (!cond.evaluate(mob, t, adapter)) {
                        continue; // reactive condition (e.g. execute below target HP, AoE when swarmed)
                    }
                    condMatched = true;
                }
            }
            int weight = Math.max(1, e.weight());
            if (condMatched) {
                weight = (int) Math.max(1L, Math.round(weight * MagicNpcsConfig.MATCHED_CONDITION_WEIGHT_BONUS.get()));
            }
            castable.add(r);
            weights.add(weight);
            totalWeight += weight;
        }
        if (castable.isEmpty()) {
            return null;
        }
        int roll = mob.getRandom().nextInt(totalWeight);
        for (int i = 0; i < castable.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) {
                return castable.get(i);
            }
        }
        return castable.get(castable.size() - 1);
    }

    private double resolveCastChance(LoadoutEntry e) {
        return e.castChance() != null ? e.castChance() : MagicNpcsConfig.CAST_CHANCE.get();
    }

    /**
     * Wind-up ticks before the cast lands. An explicit per-spell {@code windup} wins; otherwise a
     * long (channelled) spell channels for its full Iron's cast time (so root/wisp/stomp complete
     * after their cast time rather than firing instantly), and instant spells use the global wind-up.
     */
    private int resolveWindup(Resolved r) {
        Integer w = r.entry().windupTicks();
        if (w != null) {
            return w;
        }
        int castTime = IronsBridge.castTime(r.spell(), r.entry().level());
        return castTime > 0 ? castTime : MagicNpcsConfig.CAST_WINDUP_TICKS.get();
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

    private void tickCooldowns() {
        if (!cooldowns.isEmpty()) {
            cooldowns.replaceAll((id, cd) -> cd > 0 ? cd - 1 : 0);
        }
    }

    private record Resolved(LoadoutEntry entry, AbstractSpell spell) {}
}
