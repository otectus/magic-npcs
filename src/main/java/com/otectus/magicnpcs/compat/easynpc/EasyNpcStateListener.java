package com.otectus.magicnpcs.compat.easynpc;

import com.otectus.magicnpcs.core.caster.ReconcileReason;
import com.otectus.magicnpcs.integration.irons.CasterReconciler;
import de.markusbordihn.easynpc.api.event.StateEventListener;
import de.markusbordihn.easynpc.data.state.StateEntry;
import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

/**
 * Re-reconciles an Easy NPC when its state changes.
 *
 * <p>The adapter reads Easy NPC state to decide whether an NPC may cast, but state is changed by
 * dialogs, actions and commands that Magic NPCs never sees. Without this, a state change that should
 * start or stop casting would only take effect at the next chunk reload — and "I set the NPC to
 * hostile and it did nothing until I relogged" is indistinguishable from the feature being broken.
 *
 * <p>{@code reconcile} is idempotent, so a state change with no casting consequence costs a goal-list
 * walk and does nothing else.
 *
 * <p>Implements the four-argument {@code onStateChanged}: Easy NPC declares that form as the abstract
 * method and the {@code ActionContext}-carrying form as a {@code default} that delegates to it, so
 * this is the one an implementor must provide. The context is not needed here — who caused the state
 * change does not affect what the NPC should now be running.
 */
@SuppressWarnings("deprecation") // the 4-arg form is the abstract method; see the class javadoc
public final class EasyNpcStateListener implements StateEventListener {

    @Override
    public void onStateChanged(EasyNPC<?> easyNPC, ResourceLocation stateId,
                               StateEntry previousStateEntry, StateEntry currentStateEntry) {
        if (easyNPC == null || easyNPC.isClientSideInstance()) {
            return;
        }
        Mob mob = easyNPC.getMob();
        if (mob != null) {
            CasterReconciler.reconcile(mob, ReconcileReason.EASY_NPC_STATE);
        }
    }
}
