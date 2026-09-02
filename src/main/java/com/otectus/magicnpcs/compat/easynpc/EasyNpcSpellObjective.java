package com.otectus.magicnpcs.compat.easynpc;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.caster.ReconcileReason;
import com.otectus.magicnpcs.integration.irons.CasterReconciler;
import com.otectus.magicnpcs.integration.irons.NpcSpellAttackGoal;
import de.markusbordihn.easynpc.data.objective.ObjectiveDataEntry;
import de.markusbordihn.easynpc.data.objective.ObjectiveGoalFactory;
import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import de.markusbordihn.easynpc.entity.easynpc.data.ObjectiveDataCapable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Exposes Magic NPCs casting as the Easy NPC custom objective {@code magicnpcs:cast_spell}, so a pack
 * author can turn an Easy NPC into a caster through Easy NPC's own objective system — and, because
 * Easy NPC persists objectives in the NPC's data, have that choice travel with the NPC's preset
 * instead of living only in a datapack keyed on entity type.
 *
 * <p><b>It does not build a second casting goal.</b> {@link CasterReconciler} is the single owner of
 * what a mob should be running (ADR 0008), and a factory that constructed its own goal would be a
 * parallel path that could disagree with it about mana, equipment, cooldowns and the native-attack
 * policy. Instead {@link #createGoal} runs the ordinary reconcile and hands Easy NPC the goal that
 * produced. Easy NPC then removes and re-adds that same instance at the objective's priority, which
 * is harmless — it is one object, in one goal selector, found by the same
 * {@code CasterReconciler.findSpellGoal} as always, so reconciliation still sees it as installed and
 * never duplicates it.
 *
 * <p>Imports both Easy NPC and the Iron's-side casting package, so it is classloaded only when
 * <em>both</em> mods are present — see {@link EasyNpcCastingIntegration}.
 */
public final class EasyNpcSpellObjective implements ObjectiveGoalFactory {

    /** The objective id pack authors write. */
    public static final ResourceLocation ID = new ResourceLocation(MagicNpcs.MODID, "cast_spell");

    /**
     * Guards against re-entering {@link #createGoal} from our own goal-change notification.
     *
     * <p>The cycle without it is real and immediate: {@code createGoal} reconciles, the reconciler
     * fires {@link com.otectus.magicnpcs.core.caster.CasterGoalListeners}, our listener rebuilds the
     * objective so Easy NPC drops its now-stale goal reference, and rebuilding calls {@code getGoal},
     * which calls {@code createGoal} again. The rebuild is only ever needed for a goal change that
     * happened <em>outside</em> this call, so suppressing it while we are inside one is exactly right.
     */
    private static final ThreadLocal<Boolean> BUILDING = ThreadLocal.withInitial(() -> false);

    /**
     * Give Easy NPC the casting goal the reconciler installed, or {@code null} when this NPC has
     * nothing to cast.
     *
     * <p>Returning {@code null} is a supported answer: Easy NPC skips the add, logs once that the
     * objective could not be created, and retries later — which is the behaviour we want for an NPC
     * that has the objective but has not been given a school or a loadout yet. It is a true statement
     * about that NPC's configuration, and it self-corrects the moment the NPC does get spells.
     */
    @Override
    public Goal createGoal(ObjectiveDataEntry objectiveDataEntry, EasyNPC<?> easyNPC) {
        if (easyNPC == null || easyNPC.isClientSideInstance()) {
            return null;
        }
        Mob mob = easyNPC.getMob();
        if (mob == null) {
            return null;
        }
        BUILDING.set(Boolean.TRUE);
        try {
            CasterReconciler.reconcile(mob, ReconcileReason.EASY_NPC_OBJECTIVE);
            return CasterReconciler.findSpellGoal(mob);
        } finally {
            BUILDING.set(Boolean.FALSE);
        }
    }

    /**
     * Whether this objective can ever produce a goal here. Answering "yes" for an NPC with no spells
     * is deliberate — Easy NPC's retry loop is how such an NPC starts casting once it is given some.
     * Answering "no" would mark the objective permanently registered and it would never be revisited.
     */
    @Override
    public boolean isCompatible(EasyNPC<?> easyNPC) {
        return easyNPC != null
                && easyNPC.getMob() != null
                && MagicNpcsConfig.ENABLE_SPELLCASTING.get();
    }

    @Override
    public int getDefaultPriority() {
        return MagicNpcsConfig.castingGoalPriority();
    }

    /**
     * The casting goal declares no control flags and does its own movement through
     * {@code CasterMovementGoal} (ADR 0002/0009), so Easy NPC must not also treat it as something that
     * needs travel handling — that would layer a second opinion about where the NPC stands on top of
     * the one the movement policy already resolved.
     */
    @Override
    public boolean hasTravelObjective() {
        return false;
    }

    /**
     * Drop Easy NPC's cached reference to a goal the reconciler has just replaced or removed, so the
     * next objective refresh rebuilds from the live one instead of resurrecting a dead object.
     */
    static void onCastingGoalChanged(Mob mob) {
        if (Boolean.TRUE.equals(BUILDING.get()) || !(mob instanceof EasyNPC<?> easyNPC)) {
            return;
        }
        ObjectiveDataCapable<?> objectiveData = easyNPC.getEasyNPCObjectiveData();
        if (objectiveData == null || easyNPC.isClientSideInstance()) {
            return;
        }
        ObjectiveDataEntry entry = findEntry(objectiveData);
        if (entry != null) {
            objectiveData.rebuildCustomObjective(entry);
        }
    }

    /**
     * Find our objective entry on an NPC.
     *
     * <p>Looked up by string id rather than by {@code ObjectiveType}: every third-party objective
     * shares {@code ObjectiveType.CUSTOM}, so {@code getObjective(CUSTOM)} would return whichever one
     * happened to be first. A custom entry's id is its {@code ResourceLocation.toString()}, which is
     * unique per objective by construction.
     */
    private static ObjectiveDataEntry findEntry(ObjectiveDataCapable<?> objectiveData) {
        return objectiveData.getObjectiveDataSet() == null
                ? null : objectiveData.getObjectiveDataSet().getObjective(ID.toString());
    }

    /** @return true if {@code mob} carries our casting objective. Used by the diagnostics. */
    public static boolean hasCastingObjective(Mob mob) {
        if (!(mob instanceof EasyNPC<?> easyNPC)) {
            return false;
        }
        ObjectiveDataCapable<?> objectiveData = easyNPC.getEasyNPCObjectiveData();
        return objectiveData != null && findEntry(objectiveData) != null;
    }

    /** @return the casting goal Easy NPC currently has registered for this mob, or null. */
    public static NpcSpellAttackGoal installedGoal(Mob mob) {
        return CasterReconciler.findSpellGoal(mob);
    }
}
