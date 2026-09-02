package com.otectus.magicnpcs.compat.easynpc;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.integration.irons.CasterReconciler;
import de.markusbordihn.easynpc.api.condition.ConditionEvaluator;
import de.markusbordihn.easynpc.api.condition.ConditionRegistry;
import de.markusbordihn.easynpc.data.condition.ConditionDataEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Dialog conditions describing an NPC's magical state, so an Easy NPC dialog can offer an option only
 * when it makes sense — "teach me a spell" from an NPC that actually has a school, a healing offer
 * from one with the mana to pay for it.
 *
 * <p>All three are server-only. {@link ConditionEvaluator#isAvailableOnClient()} defaults to
 * {@code false}, which is correct here: none of these can be answered from the client, and Easy NPC's
 * documented behaviour for that case is to send a lock signal rather than show the option as available
 * and fail when it is clicked.
 */
public final class EasyNpcConditions {

    /** True when the NPC has a magic school assigned; {@code customData} optionally names which. */
    public static final ResourceLocation HAS_SCHOOL = new ResourceLocation(MagicNpcs.MODID, "has_school");

    /** True when the NPC currently has a working casting goal. */
    public static final ResourceLocation CAN_CAST = new ResourceLocation(MagicNpcs.MODID, "can_cast");

    /** True when the NPC's current mana is at least {@code value} (default 1). */
    public static final ResourceLocation HAS_MANA = new ResourceLocation(MagicNpcs.MODID, "has_mana");

    private EasyNpcConditions() {}

    static void register() {
        ConditionRegistry.register(HAS_SCHOOL, new HasSchool());
        ConditionRegistry.register(CAN_CAST, new CanCast());
        ConditionRegistry.register(HAS_MANA, new HasMana());
    }

    /**
     * {@code magicnpcs:has_school} — the NPC has a school. Set the condition's custom data to a school
     * id to require that specific one, or leave it empty to accept any.
     */
    private static final class HasSchool implements ConditionEvaluator {
        @Override
        public boolean evaluate(ConditionDataEntry conditionDataEntry, ServerPlayer serverPlayer,
                                LivingEntity livingEntity) {
            if (!(livingEntity instanceof Mob mob)) {
                return false;
            }
            ResourceLocation school = SchoolData.getSchool(mob);
            if (school == null) {
                return false;
            }
            String wanted = conditionDataEntry == null ? null : conditionDataEntry.customData();
            return wanted == null || wanted.isBlank() || school.toString().equals(wanted.trim());
        }
    }

    /**
     * {@code magicnpcs:can_cast} — the NPC has a casting goal installed <em>and</em> nothing is
     * currently suppressing it. Both halves matter: a goal that exists but is blocked by a pause or a
     * disabled integration would otherwise offer the player an option the NPC cannot honour.
     */
    private static final class CanCast implements ConditionEvaluator {
        @Override
        public boolean evaluate(ConditionDataEntry conditionDataEntry, ServerPlayer serverPlayer,
                                LivingEntity livingEntity) {
            return livingEntity instanceof Mob mob
                    && CasterReconciler.findSpellGoal(mob) != null
                    && NpcAdapters.resolve(mob).canCastNow(mob);
        }
    }

    /**
     * {@code magicnpcs:has_mana} — the NPC's current mana is at least the condition's value, or at
     * least 1 when no value is set.
     */
    private static final class HasMana implements ConditionEvaluator {
        @Override
        public boolean evaluate(ConditionDataEntry conditionDataEntry, ServerPlayer serverPlayer,
                                LivingEntity livingEntity) {
            if (!(livingEntity instanceof Mob mob)) {
                return false;
            }
            int required = conditionDataEntry == null ? 1 : Math.max(1, conditionDataEntry.value());
            return com.otectus.magicnpcs.integration.irons.IronsBridge.currentMana(mob) >= required;
        }
    }
}
