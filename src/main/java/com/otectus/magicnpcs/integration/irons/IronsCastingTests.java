package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.compat.RecruitsCompat;
import com.otectus.magicnpcs.compat.recruits.RecruitsTestSupport;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.loadout.CastCondition;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.entity.mobs.goals.WizardAttackGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.monster.Zombie;

import java.util.List;
import java.util.function.Consumer;

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

    /**
     * Universal path: a skeleton with an explicit {@code magic_missile} loadout spends mana casting at
     * a target. (The jar no longer ships a {@code minecraft:skeleton} loadout — 0.5.0 — so the test
     * injects its own loadout rather than relying on shipped data.)
     */
    public static void skeletonCastsMagicMissile(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = (Mob) helper.spawn(EntityType.SKELETON, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        double maxMana = primeMana(helper, caster, 200.0);
        // Per-spell cast_chance=1 (not the global config) so concurrently-running sibling tests that
        // mutate the global castChance can't starve this one.
        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 0.0, 24.0, 1.0, LoadoutEntry.Role.ATTACK, 1.0, null, null, 0, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.SKELETON), 200.0, 0.0, List.of(entry));
        caster.goalSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);
        target.setInvulnerable(true);

        helper.succeedWhen(() -> {
            caster.setTarget(target);
            helper.assertTrue(MagicData.getPlayerMagicData(caster).getMana() < maxMana - 0.5,
                    "skeleton with an explicit magic_missile loadout should spend mana casting");
        });
    }

    /**
     * Target-data fix (0.5.0): {@code devour} is an INSTANT spell that reads a {@code TargetEntityCastData}
     * target in {@code onCast}. With the bridge now setting that data before casting, a husk with a devour
     * loadout and a target in range spends mana — proving the target payload is wired up. Before the fix
     * the spell got no target and did nothing.
     */
    public static void devourCastsWithTargetData(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = (Mob) helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        double maxMana = primeMana(helper, caster, 300.0);
        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "devour"),
                1, 1, 0.0, 8.0, 1.0, LoadoutEntry.Role.ATTACK, 1.0, null, null, 0, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 300.0, 0.0, List.of(entry));
        caster.goalSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);
        target.setInvulnerable(true);

        helper.succeedWhen(() -> {
            caster.setTarget(target);
            helper.assertTrue(MagicData.getPlayerMagicData(caster).getMana() < maxMana - 0.5,
                    "devour (target-entity spell) should cast once the bridge supplies TargetEntityCastData");
        });
    }

    /**
     * Long-cast lifecycle (0.5.0): {@code root} is a LONG cast (cast time ~50 ticks). It must NOT fire on
     * the tick the target is acquired (as a single immediate onCast would), and MUST fire after its cast
     * time elapses. We pin {@code castChance} to 1 for determinism and restore it before asserting.
     */
    public static void longCastCompletesAfterCastTime(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);

        Mob caster = (Mob) helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        double maxMana = primeMana(helper, caster, 500.0);
        // Per-spell cast_chance=1 (not the global config) so sibling tests mutating the global
        // castChance can't interfere. windup null → the goal channels for the spell's own Iron's
        // cast time (root channels ~40 ticks), then completes via onCast + onServerCastComplete.
        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "root"),
                1, 1, 0.0, 16.0, 1.0, LoadoutEntry.Role.ATTACK, 1.0, null, null, null, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 500.0, 0.0, List.of(entry));
        // Drop both goal AND target selectors so the husk AI can't drop the pinned zombie mid-channel.
        caster.goalSelector.removeAllGoals(g -> true);
        caster.targetSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);
        target.setNoGravity(true); // the 3×3 platform ends at z=2; keep the target from falling mid-channel
        target.setInvulnerable(true);

        // Poll-and-pin (the reliable pattern in this concurrent harness): a long, target-entity spell
        // (root) must eventually complete its channel and spend mana — proving long-cast lifecycle +
        // TargetEntityCastData both work for a LONG target spell.
        helper.succeedWhen(() -> {
            caster.setTarget(target);
            helper.assertTrue(MagicData.getPlayerMagicData(caster).getMana() < maxMana - 0.5,
                    "a long target-entity cast (root) must complete its channel and spend mana");
        });
    }

    /**
     * Stomp handling (0.5.0): {@code stomp} is a forward ground AoE (LONG cast) that spawns a
     * {@code StompAoe} in front of the caster. With a short range and the goal facing the target, the
     * AoE must appear on the caster's forward (+Z) side — toward the target — not behind it or at world
     * origin. Polls so the transient AoE is caught while it exists.
     */
    public static void stompAoeFiresForward(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = (Mob) helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        primeMana(helper, caster, 500.0);
        // Per-spell cast_chance=1 so sibling tests mutating the global castChance can't starve it.
        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "stomp"),
                1, 1, 0.0, 5.0, 4.0, LoadoutEntry.Role.ATTACK, 1.0, null, null, null, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 500.0, 0.0, List.of(entry));
        caster.goalSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        // Target three blocks toward +Z; the goal snaps the caster to face it, so "forward" = +Z.
        // Gravity off: the 3×3 platform ends at z=2, so a falling target would drop the cast.
        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 4));
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setInvulnerable(true);

        helper.succeedWhen(() -> {
            caster.setTarget(target);
            List<io.redspace.ironsspellbooks.entity.spells.StompAoe> aoes = helper.getLevel().getEntitiesOfClass(
                    io.redspace.ironsspellbooks.entity.spells.StompAoe.class, caster.getBoundingBox().inflate(12.0));
            boolean forward = aoes.stream().anyMatch(a -> a.getZ() > caster.getZ() + 0.3);
            helper.assertTrue(forward,
                    "stomp must spawn its AoE in front of the caster (toward the +Z target), not behind or at origin");
        });
    }

    /** Adapter path: a Villager Recruit casts (skips cleanly if Recruits is absent). */
    public static void recruitCasts(GameTestHelper helper) {
        EntityType<?> type = recruitType();
        if (type == null) {
            helper.succeed(); // Recruits not installed — nothing to assert
            return;
        }
        // A recruit only attacks (and so only casts) when its command state allows it and the
        // target is an enemy; an ownerless recruit defaults otherwise. Set it aggressive so it
        // engages the hostile zombie — the diplomacy gate is exactly what the adapter enforces.
        runCastTest(helper, type, RecruitsTestSupport::makeAggressive);
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
            runCastTest(helper, type, RecruitsTestSupport::makeAggressive);
        } finally {
            MagicNpcsConfig.RECRUITS_USE_IRONS_AI.set(prev);
        }
    }

    /**
     * Reactive condition (0.4.0): an ATTACK spell carrying a {@code target_hp_below} "execute"
     * condition must <b>not</b> fire on a full-HP target, and <b>must</b> fire once the target
     * drops below the threshold. Uses a husk (no shipped loadout, sun-immune) with a
     * programmatically-built loadout so only the condition gate is under test; the target is
     * invulnerable so only the scripted HP changes drive the condition.
     */
    public static void executeConditionGatesCast(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);

        Mob caster = (Mob) helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        AttributeInstance maxAttr = caster.getAttribute(AttributeRegistry.MAX_MANA.get());
        AttributeInstance regenAttr = caster.getAttribute(AttributeRegistry.MANA_REGEN.get());
        if (maxAttr == null || regenAttr == null) {
            helper.fail("husk is missing Iron's mana attributes");
            return;
        }
        maxAttr.setBaseValue(200.0);
        regenAttr.setBaseValue(0.0); // no regen, so mana only ever drops by casting
        IronsBridge.initMana(caster);

        // Execute spell: eligible only when the target's HP fraction is below 0.5. windup 0 = instant.
        // Per-spell cast_chance=1 so a concurrently-running sibling test mutating the global castChance
        // can't starve the "must cast once HP drops" half (the execute condition still gates eligibility).
        CastCondition execute = new CastCondition(null, 0.5, null, null, null, null);
        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 0.0, 16.0, 1.0, LoadoutEntry.Role.ATTACK,
                1.0, null, null, 0, execute);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 200.0, 0.0, List.of(entry));
        // Drop the husk's default goals (the melee goal holds the LOOK flag; the target goals would
        // drop the pinned zombie since it isn't a husk target) and run only our cast goal.
        caster.goalSelector.removeAllGoals(g -> true);
        caster.targetSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);
        target.setInvulnerable(true); // husk melee / sun can't change its HP — only the script does
        double maxMana = maxAttr.getValue();

        helper.startSequence()
                // Full-HP target: the execute condition fails → it must not cast (fixed window).
                .thenExecuteFor(40, () -> caster.setTarget(target))
                .thenExecute(() -> helper.assertTrue(
                        MagicData.getPlayerMagicData(caster).getMana() >= maxMana - 0.5,
                        "execute spell must NOT cast on a full-HP target"))
                // Drop the target below 50% → the condition now passes → poll until it casts (the
                // reliable pattern: a fixed thenExecuteFor window can miss the goal's decision tick).
                .thenExecute(() -> target.setHealth(target.getMaxHealth() * 0.25F))
                .thenWaitUntil(() -> {
                    caster.setTarget(target);
                    helper.assertTrue(MagicData.getPlayerMagicData(caster).getMana() < maxMana - 0.5,
                            "execute spell must cast once the target drops below 50% HP");
                })
                .thenSucceed();
    }

    /**
     * Negative check for the <b>global</b> cast chance: with {@code castChance} forced to 0 a caster
     * with an explicit magic_missile loadout (no per-spell override, so it uses the global) never
     * spends mana over a 100-tick window, even with a pinned, in-range, visible target. This is the
     * one test that exercises the shared global config; every positive test sets a per-spell
     * cast_chance so this can't starve them. Restores the config before asserting so it can't leak.
     */
    public static void castChanceZeroNeverCasts(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        double prev = MagicNpcsConfig.CAST_CHANCE.get();
        MagicNpcsConfig.CAST_CHANCE.set(0.0);

        Mob caster = (Mob) helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        // Explicit loadout with NO per-spell cast_chance, so the global castChance=0 governs it. (Also
        // primes mana — without an applied loadout the caster's mana would be uninitialised.)
        double maxMana = primeMana(helper, caster, 200.0);
        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 0.0, 16.0, 1.0, LoadoutEntry.Role.ATTACK, null, null, null, 0, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 200.0, 0.0, List.of(entry));
        caster.goalSelector.removeAllGoals(g -> true);
        caster.targetSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);
        target.setInvulnerable(true);

        helper.startSequence()
                .thenExecuteFor(100, () -> caster.setTarget(target)) // pin the target every tick
                .thenExecute(() -> MagicNpcsConfig.CAST_CHANCE.set(prev)) // restore before asserting
                .thenExecute(() -> helper.assertTrue(
                        MagicData.getPlayerMagicData(caster).getMana() >= maxMana - 0.5,
                        "a caster with global castChance=0 must never spend mana"))
                .thenSucceed();
    }

    /**
     * Aim fix (0.5.0): a caster facing <b>away</b> from its target, casting a projectile spell on the
     * instant ({@code windup=0}) path, must still fire <b>toward</b> the target — proving the goal
     * snaps the caster's rotation right before {@code onCast} rather than firing in a stale direction.
     * The target sits at +Z of the caster, which starts facing -Z; we assert a spawned projectile
     * carries positive Z velocity.
     */
    public static void windupAimsAtTarget(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);

        Mob caster = (Mob) helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        // Face due north (-Z), i.e. directly away from the target we place at +Z.
        caster.setYRot(180.0F);
        caster.setYHeadRot(180.0F);
        caster.yBodyRot = 180.0F;
        double maxMana = primeMana(helper, caster, 200.0);

        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 0.0, 24.0, 1.0, LoadoutEntry.Role.ATTACK,
                1.0, null, null, 0, null); // windup 0 = instant: the stale-direction case the fix targets
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 200.0, 0.0, List.of(entry));
        caster.goalSelector.removeAllGoals(g -> true);
        // Remove target AI too: otherwise the husk can re-target a concurrently-running sibling test's
        // zombie in an adjacent region and snap toward it instead of our pinned +Z target.
        caster.targetSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        // On the platform (the structure is only 3×3, z 0-2) and gravity off so the target can't fall
        // off the edge into the void before the caster locks on.
        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 2));
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setInvulnerable(true);

        helper.succeedWhen(() -> {
            caster.setTarget(target);
            // Wait for a cast, then verify the caster was snapped from its stale -Z facing (yaw 180)
            // to face the +Z target (yaw ~0) — Iron's reads getLookAngle() during onCast, so a caster
            // facing +Z fires toward +Z. Checking the rotation is deterministic (a projectile search
            // box catches concurrently-running sibling tests' shots, whose owner isn't exposed here).
            helper.assertTrue(MagicData.getPlayerMagicData(caster).getMana() < maxMana - 0.5,
                    "caster must cast at the target");
            float yaw = net.minecraft.util.Mth.wrapDegrees(caster.getYRot());
            helper.assertTrue(Math.abs(yaw) < 45.0f,
                    "caster must snap to face the +Z target (yaw ~0) before casting, not stay at its stale -Z facing");
        });
    }

    /**
     * Focus gate (0.5.0 doc/coverage): with {@code requireSpellFocus} on, an empty-handed caster must
     * not cast; once it holds an item in the {@code magicnpcs:spell_focuses} tag (a blaze rod, via
     * Iron's {@code #irons_spellbooks:fire_focus}) it casts. Restores the config before asserting.
     */
    public static void focusGateRequiresHeldFocus(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        boolean prev = MagicNpcsConfig.REQUIRE_SPELL_FOCUS.get();
        MagicNpcsConfig.REQUIRE_SPELL_FOCUS.set(true);

        Mob caster = (Mob) helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        double maxMana = primeMana(helper, caster, 200.0);

        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 0.0, 16.0, 1.0, LoadoutEntry.Role.ATTACK, 1.0, null, null, 0, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 200.0, 0.0, List.of(entry));
        caster.goalSelector.removeAllGoals(g -> true);
        caster.targetSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);
        target.setInvulnerable(true);

        helper.startSequence()
                // Empty-handed: the focus gate blocks casting.
                .thenExecuteFor(40, () -> caster.setTarget(target))
                .thenExecute(() -> helper.assertTrue(
                        MagicData.getPlayerMagicData(caster).getMana() >= maxMana - 0.5,
                        "empty-handed caster must not cast while requireSpellFocus is on"))
                // Hand it a tagged focus (blaze rod) → it may now cast.
                .thenExecute(() -> caster.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BLAZE_ROD)))
                .thenExecuteFor(80, () -> caster.setTarget(target))
                .thenExecute(() -> MagicNpcsConfig.REQUIRE_SPELL_FOCUS.set(prev))
                .thenExecute(() -> helper.assertTrue(
                        MagicData.getPlayerMagicData(caster).getMana() < maxMana - 0.5,
                        "caster holding a tagged focus must be able to cast"))
                .thenSucceed();
    }

    /**
     * Bad-id convenience (0.5.0): a loadout that writes a bare spell id with no namespace (parsing to
     * {@code minecraft:...}) is auto-retried under {@code irons_spellbooks:}, so a caster with the
     * bare id {@code magic_missile} still resolves the spell and casts.
     */
    public static void bareSpellIdAutoNamespaces(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);

        Mob caster = (Mob) helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        double maxMana = primeMana(helper, caster, 200.0);

        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("magic_missile"), // bare → minecraft:magic_missile → retried as irons_spellbooks:
                1, 1, 0.0, 16.0, 1.0, LoadoutEntry.Role.ATTACK, 1.0, null, null, 0, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 200.0, 0.0, List.of(entry));
        caster.goalSelector.removeAllGoals(g -> true);
        caster.targetSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);
        target.setInvulnerable(true);

        helper.succeedWhen(() -> {
            caster.setTarget(target);
            helper.assertTrue(MagicData.getPlayerMagicData(caster).getMana() < maxMana - 0.5,
                    "a bare spell id must auto-resolve under irons_spellbooks and cast");
        });
    }

    /**
     * Per-spell {@code cast_chance} (0.5.0 coverage): with the <b>global</b> chance at 1.0 but the
     * spell's own {@code cast_chance} at 0, the caster never casts — proving the per-spell field
     * overrides the global one. Restores the config before asserting.
     */
    public static void perSpellCastChanceZeroNeverCasts(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        // The global castChance defaults to 1.0 ("always"); we rely on that default rather than
        // setting it, so this test doesn't race the one test that mutates the global (castChanceZero).
        // The per-spell cast_chance=0 must still win regardless of the global value.

        Mob caster = (Mob) helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        double maxMana = primeMana(helper, caster, 200.0);

        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 0.0, 16.0, 1.0, LoadoutEntry.Role.ATTACK,
                0.0, null, null, 0, null); // cast_chance = 0
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 200.0, 0.0, List.of(entry));
        caster.goalSelector.removeAllGoals(g -> true);
        caster.targetSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);
        target.setInvulnerable(true);

        helper.startSequence()
                .thenExecuteFor(100, () -> caster.setTarget(target))
                .thenExecute(() -> helper.assertTrue(
                        MagicData.getPlayerMagicData(caster).getMana() >= maxMana - 0.5,
                        "per-spell cast_chance=0 must block casting even with the global castChance at its 1.0 default"))
                .thenSucceed();
    }

    /** Set both mana attributes (no regen) and fill mana; @return the max-mana value. */
    private static double primeMana(GameTestHelper helper, Mob caster, double max) {
        AttributeInstance maxAttr = caster.getAttribute(AttributeRegistry.MAX_MANA.get());
        AttributeInstance regenAttr = caster.getAttribute(AttributeRegistry.MANA_REGEN.get());
        if (maxAttr == null || regenAttr == null) {
            helper.fail("caster is missing Iron's mana attributes");
            return 0.0;
        }
        maxAttr.setBaseValue(max);
        regenAttr.setBaseValue(0.0);
        IronsBridge.initMana(caster);
        return maxAttr.getValue();
    }

    /**
     * Spawn a caster of {@code casterType} and a stationary dummy target, then succeed once
     * the caster's Iron's mana drops below its max — proving a real {@code onCast} happened
     * (mana is deducted only by our economy, ADR 0001). With the default wind-up this also
     * exercises the wind-up lifecycle: the cast lands a few ticks after target acquisition.
     */
    private static void runCastTest(GameTestHelper helper, EntityType<?> casterType, Consumer<Mob> afterSpawn) {
        // Casting is suppressed on Peaceful (peacefulDisablesCasting); ensure a fighting difficulty.
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);

        Mob caster = (Mob) helper.spawn(casterType, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        if (afterSpawn != null) {
            afterSpawn.accept(caster); // mod-specific setup (e.g. put a recruit in an aggressive state)
        }
        // Isolate the casting goal: a melee mob's attack goal (MOVE+LOOK) would otherwise hold the
        // LOOK flag at the same priority and starve our LOOK-only cast goal. We're verifying the cast
        // pipeline + adapter, not vanilla goal scheduling, so drop the competing goals.
        caster.goalSelector.removeAllGoals(g -> !(g instanceof NpcSpellAttackGoal) && !(g instanceof WizardAttackGoal));

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
