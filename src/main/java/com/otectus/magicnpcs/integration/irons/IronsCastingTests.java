package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.compat.RecruitsCompat;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.monster.Zombie;

/**
 * Iron's-side bodies for the runtime casting GameTests. Classloaded only from the
 * {@code IronsCompat.isLoaded()} guard in the (Iron's-free) GameTest holder, so the
 * offline boot test never touches Iron's. These need the full runtime (Iron's +
 * companions, and Recruits for the recruit cases) and are <b>not</b> exercised in the
 * bare dev environment — see {@code docs/dev-runtime.md}. Treat their spawn positions
 * and target choice as runtime-tunable.
 */
public final class IronsCastingTests {
    private IronsCastingTests() {}

    /** Universal path: a skeleton (shipped loadout) spends mana casting at a target. */
    public static void skeletonCastsMagicMissile(GameTestHelper helper) {
        runCastTest(helper, EntityType.SKELETON);
    }

    /** Adapter path: a Villager Recruit casts (skips cleanly if Recruits is absent). */
    public static void recruitCasts(GameTestHelper helper) {
        EntityType<?> type = recruitType();
        if (type == null) {
            helper.succeed(); // Recruits not installed — nothing to assert
            return;
        }
        runCastTest(helper, type);
    }

    /** Iron's-AI path: a recruit driven by Iron's WizardAttackGoal (via the mixin) casts. */
    public static void recruitCastsWithIronsAi(GameTestHelper helper) {
        EntityType<?> type = recruitType();
        if (type == null) {
            helper.succeed();
            return;
        }
        boolean prev = MagicNpcsConfig.RECRUITS_USE_IRONS_AI.get();
        // The goal type is chosen at spawn (applyLoadout), so set the flag before spawning;
        // restoring it right after is safe — the WizardAttackGoal is already injected.
        MagicNpcsConfig.RECRUITS_USE_IRONS_AI.set(true);
        try {
            runCastTest(helper, type);
        } finally {
            MagicNpcsConfig.RECRUITS_USE_IRONS_AI.set(prev);
        }
    }

    /**
     * Negative check for per-spell cast chance: with the global {@code castChance} forced to 0 a
     * skeleton (shipped loadout) never spends mana over a 100-tick window, even with a pinned,
     * in-range, visible target. Restores the config before asserting so a failure can't leak it.
     */
    public static void castChanceZeroNeverCasts(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        double prev = MagicNpcsConfig.CAST_CHANCE.get();
        MagicNpcsConfig.CAST_CHANCE.set(0.0);

        Mob caster = (Mob) helper.spawn(EntityType.SKELETON, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);

        AttributeInstance maxAttr = caster.getAttribute(AttributeRegistry.MAX_MANA.get());
        double maxMana = maxAttr != null ? maxAttr.getValue() : 0.0;

        helper.startSequence()
                .thenExecuteFor(100, () -> caster.setTarget(target)) // pin the target every tick
                .thenExecute(() -> MagicNpcsConfig.CAST_CHANCE.set(prev)) // restore before asserting
                .thenExecute(() -> helper.assertTrue(
                        MagicData.getPlayerMagicData(caster).getMana() >= maxMana - 0.5,
                        "skeleton with castChance=0 must never spend mana"))
                .thenSucceed();
    }

    /**
     * Spawn a caster of {@code casterType} and a stationary dummy target, then succeed once
     * the caster's Iron's mana drops below its max — proving a real {@code onCast} happened
     * (mana is deducted only by our economy, ADR 0001). With the default wind-up this also
     * exercises the wind-up lifecycle: the cast lands a few ticks after target acquisition.
     */
    private static void runCastTest(GameTestHelper helper, EntityType<?> casterType) {
        // Casting is suppressed on Peaceful (peacefulDisablesCasting); ensure a fighting difficulty.
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);

        Mob caster = (Mob) helper.spawn(casterType, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true); // a still dummy in range and line of sight

        AttributeInstance maxAttr = caster.getAttribute(AttributeRegistry.MAX_MANA.get());
        double maxMana = maxAttr != null ? maxAttr.getValue() : 0.0;

        helper.succeedWhen(() -> {
            caster.setTarget(target); // pin the target each tick so vanilla AI can't drop it
            double mana = MagicData.getPlayerMagicData(caster).getMana();
            helper.assertTrue(mana < maxMana - 0.5, casterType.getDescriptionId() + " should have spent mana casting");
        });
    }

    /** @return the Recruits "recruit" entity type, or null if Recruits is not installed. */
    private static EntityType<?> recruitType() {
        if (!RecruitsCompat.isLoaded()) {
            return null;
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(new ResourceLocation("recruits", "recruit")).orElse(null);
    }
}
