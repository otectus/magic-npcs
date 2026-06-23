package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import com.otectus.magicnpcs.core.util.LineOfFire;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
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
            if (spell != null) {
                spells.add(new Resolved(entry, spell));
            }
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
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        mob.swing(InteractionHand.MAIN_HAND);
        IronsBridge.cast(mob, chosen.spell(), chosen.entry().level());
        cooldowns.put(chosen.entry().spell(), resolveCooldown(chosen));
        decisionTimer = MagicNpcsConfig.DECISION_INTERVAL_TICKS.get();
        endAttempt();
    }

    private void endAttempt() {
        this.chosen = null;
        this.target = null;
        this.windupRemaining = 0;
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

    /** Weighted-random pick among castable spells: ATTACK in range + friendly-fire-clear, or SUPPORT when hurt. */
    private Resolved choose(LivingEntity t) {
        double distSqr = mob.distanceToSqr(t);
        boolean hurt = mob.getHealth() < mob.getMaxHealth() * MagicNpcsConfig.SUPPORT_HEALTH_THRESHOLD.get();
        boolean canAttackTarget = adapter.canCastAt(mob, t);
        boolean hasLineOfSight = mob.getSensing().hasLineOfSight(t);
        // Friendly-fire scan runs when the adapter tracks allies OR generic bystander
        // protection is on (so even an adapter-less skeleton won't blast the townsfolk).
        boolean friendlyFire = MagicNpcsConfig.FRIENDLY_FIRE_CHECK.get()
                && (adapter.tracksAllies() || MagicNpcsConfig.PROTECT_BYSTANDERS.get());

        List<Resolved> castable = new ArrayList<>();
        int totalWeight = 0;
        for (Resolved r : spells) {
            LoadoutEntry e = r.entry();
            if (cooldowns.getOrDefault(e.spell(), 0) > 0) {
                continue;
            }
            if (!IronsBridge.canAfford(mob, r.spell(), e.level())) {
                continue;
            }
            if (e.role() == LoadoutEntry.Role.SUPPORT) {
                if (!hurt) {
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
            }
            castable.add(r);
            totalWeight += Math.max(1, e.weight());
        }
        if (castable.isEmpty()) {
            return null;
        }
        int roll = mob.getRandom().nextInt(totalWeight);
        for (Resolved r : castable) {
            roll -= Math.max(1, r.entry().weight());
            if (roll < 0) {
                return r;
            }
        }
        return castable.get(castable.size() - 1);
    }

    private double resolveCastChance(LoadoutEntry e) {
        return e.castChance() != null ? e.castChance() : MagicNpcsConfig.CAST_CHANCE.get();
    }

    private int resolveWindup(Resolved r) {
        Integer w = r.entry().windupTicks();
        return w != null ? w : MagicNpcsConfig.CAST_WINDUP_TICKS.get();
    }

    /** Precedence: explicit per-spell ticks > per-spell multiplier > global multiplier; always floored. */
    private int resolveCooldown(Resolved r) {
        int floor = MagicNpcsConfig.MIN_COOLDOWN_TICKS.get();
        LoadoutEntry e = r.entry();
        if (e.cooldownTicks() != null) {
            return Math.max(e.cooldownTicks(), floor);
        }
        double mult = e.cooldownMultiplier() != null
                ? e.cooldownMultiplier()
                : MagicNpcsConfig.COOLDOWN_MULTIPLIER.get();
        return Math.max((int) (r.spell().getSpellCooldown() * mult), floor);
    }

    private void tickCooldowns() {
        if (!cooldowns.isEmpty()) {
            cooldowns.replaceAll((id, cd) -> cd > 0 ? cd - 1 : 0);
        }
    }

    private record Resolved(LoadoutEntry entry, AbstractSpell spell) {}
}
