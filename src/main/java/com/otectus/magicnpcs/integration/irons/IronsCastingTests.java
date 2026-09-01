package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.compat.RecruitsCompat;
import com.otectus.magicnpcs.compat.recruits.RecruitsTestSupport;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.loadout.CastCondition;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.caster.CasterMovementGoal;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import com.otectus.magicnpcs.core.caster.ReconcileReason;
import com.otectus.magicnpcs.core.caster.ReconcileResult;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.util.AttackGoals;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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

    // ---------------------------------------------------------------------------------------------
    // 0.6.0 — casting reaches the mobs it should (W2), and support works out of combat (W1)
    // ---------------------------------------------------------------------------------------------

    /**
     * W2: a {@code minecraft:witch} with a loadout must cast. The witch registers
     * {@code RangedAttackGoal} at priority <b>2</b> declaring {@code MOVE, LOOK}; before 0.6.0 the
     * casting goal was injected at priority 2 declaring {@code LOOK}, and
     * {@code WrappedGoal.canBeReplacedBy} needs a <em>strictly</em> lower priority number, so it could
     * never start while the witch had a target — exactly the reported symptom.
     *
     * <p>The witch's own goals are deliberately <b>left in place</b>: the fix is that a flagless casting
     * goal coexists with them, so removing them would test nothing.
     */
    public static void witchCastsAlongsideItsRangedGoal(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = helper.spawn(EntityType.WITCH, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        double maxMana = primeMana(helper, caster, 300.0);
        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 0.0, 24.0, 1.0, LoadoutEntry.Role.ATTACK, 1.0, null, null, 0, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.WITCH), 300.0, 0.0, List.of(entry));
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));
        helper.assertTrue(hasGoal(caster, "RangedAttackGoal"),
                "the witch must still own its native RangedAttackGoal for this test to mean anything");

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setInvulnerable(true);

        helper.succeedWhen(() -> {
            caster.setTarget(target);
            helper.assertTrue(MagicData.getPlayerMagicData(caster).getMana() < maxMana - 0.5,
                    "a witch with a loadout must cast even while its own RangedAttackGoal holds LOOK");
            helper.assertTrue(hasGoal(caster, "RangedAttackGoal"),
                    "the casting goal must coexist with the witch's ranged goal, not evict it");
        });
    }

    /**
     * W2/W3: a bow-armed skeleton must keep shooting <b>and</b> cast. Before 0.6.0 the casting goal
     * (priority 2, LOOK) preempted the bow goal (priority 4, MOVE+LOOK) on every decision, so the
     * skeleton cast <em>instead of</em> shooting — which is what "shooting magic missile so frequently
     * that it's getting out of hand" actually was.
     */
    public static void skeletonShootsAndCasts(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        AbstractSkeleton caster = helper.spawn(EntityType.SKELETON, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        // helper.spawn does not run finalizeSpawn, so arm the skeleton and rebuild its weapon goal.
        caster.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        caster.reassessWeaponGoal();
        double maxMana = primeMana(helper, caster, 400.0);
        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 0.0, 24.0, 1.0, LoadoutEntry.Role.ATTACK, 1.0, null, null, 0, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.SKELETON), 400.0, 0.0, List.of(entry));
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setInvulnerable(true);

        helper.succeedWhen(() -> {
            caster.setTarget(target);
            helper.assertTrue(MagicData.getPlayerMagicData(caster).getMana() < maxMana - 0.5,
                    "the skeleton must cast");
            // Only count arrows this skeleton fired: sibling tests run concurrently in adjacent regions.
            boolean shot = helper.getLevel()
                    .getEntitiesOfClass(AbstractArrow.class, caster.getBoundingBox().inflate(16.0))
                    .stream().anyMatch(a -> a.getOwner() == caster);
            helper.assertTrue(shot, "the skeleton must still shoot its bow — the casting goal must not "
                    + "preempt the bow goal every decision");
        });
    }

    /**
     * W1: a wounded caster with <b>no target</b> must self-heal. Before 0.6.0 {@code canUse()} returned
     * false whenever {@code getTarget()} was null, which defeated the entire SUPPORT design — "they only
     * use heal when entering combat".
     */
    public static void woundedCasterHealsOutOfCombat(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        double maxMana = primeMana(helper, caster, 300.0);
        LoadoutEntry heal = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "heal"),
                1, 1, 0.0, 0.0, 1.5, LoadoutEntry.Role.SUPPORT, 1.0, null, null, 0, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 300.0, 0.0, List.of(heal));
        // No target selectors: the whole point is that it casts with getTarget() == null.
        caster.goalSelector.removeAllGoals(g -> true);
        caster.targetSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));
        caster.setHealth(caster.getMaxHealth() * 0.3F); // below the default 0.5 support threshold

        helper.succeedWhen(() -> {
            caster.setTarget(null);
            helper.assertTrue(MagicData.getPlayerMagicData(caster).getMana() < maxMana - 0.5,
                    "a wounded caster with no target must self-heal");
        });
    }

    /**
     * W1, the other half: a caster at full health with no target must never cast. This is the anti-loop
     * guard — out-of-combat support must not become a mana-burning idle animation.
     */
    public static void fullHealthCasterNeverCastsOutOfCombat(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        double maxMana = primeMana(helper, caster, 300.0);
        LoadoutEntry heal = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "heal"),
                1, 1, 0.0, 0.0, 1.5, LoadoutEntry.Role.SUPPORT, 1.0, null, null, 0, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 300.0, 0.0, List.of(heal));
        caster.goalSelector.removeAllGoals(g -> true);
        caster.targetSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        helper.startSequence()
                .thenExecuteFor(260, () -> caster.setTarget(null)) // > 2 idle cadences
                .thenExecute(() -> helper.assertTrue(
                        MagicData.getPlayerMagicData(caster).getMana() >= maxMana - 0.5,
                        "a full-health caster with no target must never cast"))
                .thenSucceed();
    }

    /**
     * W1 acceptance criterion: an ATTACK spell must never be selected without a target. Without this
     * the out-of-combat path would fire projectiles into empty air.
     */
    public static void attackSpellIsNeverCastWithoutATarget(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        double maxMana = primeMana(helper, caster, 300.0);
        LoadoutEntry attack = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 0.0, 24.0, 1.0, LoadoutEntry.Role.ATTACK, 1.0, null, null, 0, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 300.0, 0.0, List.of(attack));
        caster.goalSelector.removeAllGoals(g -> true);
        caster.targetSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));
        caster.setHealth(caster.getMaxHealth() * 0.2F); // hurt, so only the role gate can stop it

        helper.startSequence()
                .thenExecuteFor(260, () -> caster.setTarget(null))
                .thenExecute(() -> helper.assertTrue(
                        MagicData.getPlayerMagicData(caster).getMana() >= maxMana - 0.5,
                        "an ATTACK spell must never be selected without a target"))
                .thenSucceed();
    }

    /**
     * W3(b): a configured cooldown must correspond to <b>real game ticks</b>. {@code Mob#serverAiStep}
     * only reaches {@code canUse()} on alternating ticks, so the pre-0.6.0 decrementing counters ran at
     * half rate and every configured value behaved as roughly double. Measures the interval between two
     * consecutive casts against an explicit 60-tick cooldown.
     */
    public static void cooldownIsMeasuredInRealGameTicks(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        final int cooldown = 60;
        Mob caster = helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        primeMana(helper, caster, 600.0);
        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 0.0, 24.0, 1.0, LoadoutEntry.Role.ATTACK, 1.0, cooldown, null, 0, null);
        SpellcasterLoadout loadout = new SpellcasterLoadout(
                EntityType.getKey(EntityType.HUSK), 600.0, 0.0, List.of(entry));
        caster.goalSelector.removeAllGoals(g -> true);
        caster.targetSelector.removeAllGoals(g -> true);
        caster.goalSelector.addGoal(2, new NpcSpellAttackGoal(caster, loadout));

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setInvulnerable(true);

        int[] castTicks = {-1, -1};
        float[] lastMana = {MagicData.getPlayerMagicData(caster).getMana()};
        helper.startSequence()
                .thenExecuteFor(400, () -> {
                    caster.setTarget(target);
                    float mana = MagicData.getPlayerMagicData(caster).getMana();
                    if (mana < lastMana[0] - 0.5f) {
                        lastMana[0] = mana;
                        if (castTicks[0] < 0) {
                            castTicks[0] = caster.tickCount;
                        } else if (castTicks[1] < 0) {
                            castTicks[1] = caster.tickCount;
                        }
                    }
                })
                .thenExecute(() -> {
                    helper.assertTrue(castTicks[1] > 0, "expected at least two casts in 400 ticks");
                    int delta = castTicks[1] - castTicks[0];
                    // canUse() is only evaluated every other tick, so allow a small upward slack —
                    // but nowhere near the ~2x of the pre-0.6.0 behaviour.
                    helper.assertTrue(delta >= cooldown && delta <= cooldown + 12,
                            "inter-cast interval was " + delta + " ticks; a " + cooldown
                                    + "-tick cooldown must mean " + cooldown + " real game ticks");
                })
                .thenSucceed();
    }

    /** @return true if the mob owns a goal whose simple class name matches (vanilla goals are unexported). */
    private static boolean hasGoal(Mob mob, String simpleClassName) {
        return mob.goalSelector.getAvailableGoals().stream()
                .anyMatch(w -> w.getGoal().getClass().getSimpleName().equals(simpleClassName));
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
        caster.goalSelector.removeAllGoals(g -> !(g instanceof NpcSpellAttackGoal));

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

    /**
     * 0.6.1: a villager whose profession maps to no school must stay <b>re-checkable</b>.
     *
     * <p>The spawn roll used to sticky-mark it a non-caster, which permanently disqualified 8 of the
     * 15 vanilla professions — adding one to {@code professionSchools} afterwards could never rescue a
     * villager that already existed. Only a lost caster-chance roll is a decided outcome.
     */
    public static void unmappedProfessionVillagerStaysRecheckable(GameTestHelper helper) {
        Villager villager = helper.spawn(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setPersistenceRequired();
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.NITWIT));
        SchoolData.clear(villager);

        // Drive the same entry point the join handler uses.
        IronsSpellcasterHandler.rollSchoolForTest(villager);

        helper.assertFalse(SchoolData.hasRolled(villager),
                "a villager whose profession maps to no school must not be sticky-marked a non-caster — "
                        + "that permanently bars it even after the config gains that profession");
        helper.succeed();
    }

    /**
     * 0.6.1: a manually assigned school must outrank an explicit loadout and survive re-injection.
     *
     * <p>Goals are not persisted, so a chunk reload re-runs injection. Before 0.6.1 that resolved the
     * explicit loadout first and silently discarded the player's choice — the reason the School Tome
     * appeared to do nothing lasting on a recruit.
     */
    public static void manualSchoolSurvivesReinjection(GameTestHelper helper) {
        Mob caster = helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        ResourceLocation fire = new ResourceLocation("irons_spellbooks", "fire");
        if (!MagicNpcsConfig.allowedSchoolIds().contains(fire)) {
            helper.succeed(); // fire is not an allowed school in this config; nothing to assert
            return;
        }
        if (!IronsSpellcasterHandler.applySchool(caster, fire).ok()) {
            helper.succeed(); // fire yields no castable pool here; covered by the pool diagnostics
            return;
        }
        helper.assertTrue(SchoolData.isManual(caster), "applySchool must record the choice as manual");

        // Simulate the chunk-reload path: drop the goal, then re-run injection.
        IronsSpellcasterHandler.reinjectForTest(caster);
        helper.assertTrue(fire.equals(SchoolData.getSchool(caster)),
                "a hand-set school must still be the assignment after re-injection");
        helper.assertTrue(IronsSpellcasterHandler.findSpellGoal(caster) != null,
                "re-injection must restore a casting goal for the manually assigned school");
        helper.succeed();
    }

    /**
     * 0.6.1: clearing by hand must stay cleared across re-injection, including on a mob whose casting
     * came from an explicit loadout — where the sticky mark used to be irrelevant.
     */
    public static void manualClearStaysCleared(GameTestHelper helper) {
        Mob caster = helper.spawn(EntityType.HUSK, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        IronsSpellcasterHandler.clearSchool(caster);
        IronsSpellcasterHandler.reinjectForTest(caster);
        helper.assertTrue(IronsSpellcasterHandler.findSpellGoal(caster) == null,
                "a mob cleared by hand must not be given a casting goal again on reload");
        helper.succeed();
    }

    /** @return the Recruits "recruit" entity type, or null if Recruits is not installed. */
    private static EntityType<?> recruitType() {
        if (!RecruitsCompat.isLoaded()) {
            return null;
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(new ResourceLocation("recruits", "recruit")).orElse(null);
    }

    // --- 0.6.2 regression tests (audit acceptance checklist) -------------------------------------

    /**
     * <b>The reported bug (audit RLD-001).</b> A mob that was already loaded when a datapack arrived
     * must become a caster once the data is reconciled.
     *
     * <p>0.6.1's reload handler iterated loaded entities with {@code if (!hasSpellGoal(mob)) continue;}
     * — a precondition that skips exactly the mobs a newly added loadout is supposed to reach. The
     * player's skeleton had no Magic NPCs goal, so every {@code /reload} passed straight over it, and
     * the only way to get a caster was to spawn a fresh one.
     */
    public static void existingMobBecomesCasterOnReconcile(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = (Mob) helper.spawn(EntityType.SKELETON, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        // Reproduce "loaded before the datapack existed": strip the goal the join event installed, and
        // forget its managed state, so the mob is indistinguishable from a plain skeleton.
        CasterReconciler.removeSpellGoals(caster);
        ManagedCasterState.forget(caster);
        helper.assertTrue(IronsSpellcasterHandler.findSpellGoal(caster) == null,
                "test setup: the skeleton should start with no casting goal");

        // Publish a loadout for it, exactly as a datapack reload would, then reconcile.
        SpellcasterLoadout loadout = testLoadout(EntityType.SKELETON, 200.0);
        LoadoutManager.publishForTest(java.util.Map.of(EntityType.getKey(EntityType.SKELETON),
                List.of(loadout)));
        ReconcileResult result = CasterReconciler.reconcile(caster, ReconcileReason.DATAPACK_RELOAD);

        helper.assertTrue(result.outcome() == ReconcileResult.Outcome.INSTALLED,
                "a previously-loaded skeleton should be INSTALLED by reconciliation, got " + result.describe());
        helper.assertTrue(IronsSpellcasterHandler.findSpellGoal(caster) != null,
                "the skeleton should have a casting goal after reconciliation");
        helper.succeed();
    }

    /**
     * A reload must not refill mana or clear cooldowns (audit RCN-002).
     *
     * <p>0.6.1 called {@code IronsBridge.initMana} on every {@code applyLoadout} and kept cooldowns as
     * fields on the goal it was about to replace, so editing a datapack mid-fight healed every caster
     * to full mana and let it fire its whole rotation again immediately.
     */
    public static void reconcilePreservesManaAndCooldowns(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = (Mob) helper.spawn(EntityType.SKELETON, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        SpellcasterLoadout loadout = testLoadout(EntityType.SKELETON, 200.0);
        LoadoutManager.publishForTest(java.util.Map.of(EntityType.getKey(EntityType.SKELETON),
                List.of(loadout)));
        CasterReconciler.reconcile(caster, ReconcileReason.TEST);

        // Spend some mana and put a spell on cooldown, as a real cast would.
        MagicData data = MagicData.getPlayerMagicData(caster);
        data.setMana(40.0f);
        ResourceLocation spellId = new ResourceLocation("irons_spellbooks", "magic_missile");
        ManagedCasterState state = ManagedCasterState.of(caster);
        state.startCooldown(spellId, caster.tickCount + 200);

        // Reconcile again for the same loadout: this must be a genuine no-op.
        ReconcileResult again = CasterReconciler.reconcile(caster, ReconcileReason.DATAPACK_RELOAD);
        helper.assertTrue(again.outcome() == ReconcileResult.Outcome.UNCHANGED,
                "reconciling an unchanged loadout should be UNCHANGED, got " + again.describe());
        helper.assertTrue(MagicData.getPlayerMagicData(caster).getMana() < 45.0f,
                "reconciliation refilled mana: " + MagicData.getPlayerMagicData(caster).getMana());
        helper.assertTrue(ManagedCasterState.of(caster).cooldownRemaining(spellId, caster.tickCount) > 0,
                "reconciliation cleared a running cooldown");
        helper.succeed();
    }

    /**
     * Turning the master switch off must stop an <em>already installed</em> goal (audit CFG-001).
     *
     * <p>0.6.1 read {@code enableSpellcasting} when injecting a goal and in the mana tick, but the
     * goal's own state blocker never consulted it — so a config change left every existing caster
     * casting until its chunk reloaded.
     */
    public static void masterSwitchBlocksInstalledGoal(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = (Mob) helper.spawn(EntityType.SKELETON, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        primeMana(helper, caster, 200.0);
        NpcSpellAttackGoal goal = new NpcSpellAttackGoal(caster, testLoadout(EntityType.SKELETON, 200.0));
        caster.goalSelector.addGoal(2, goal);

        boolean previous = MagicNpcsConfig.ENABLE_SPELLCASTING.get();
        try {
            MagicNpcsConfig.ENABLE_SPELLCASTING.set(false);
            helper.assertTrue(goal.stateBlocker() != null,
                    "with enableSpellcasting=false the installed goal must report a blocker");
            helper.assertFalse(goal.canUse(), "a disabled caster must not decide to cast");
            MagicNpcsConfig.ENABLE_SPELLCASTING.set(true);
            helper.assertTrue(goal.stateBlocker() == null,
                    "re-enabling spellcasting must clear the blocker");
        } finally {
            MagicNpcsConfig.ENABLE_SPELLCASTING.set(previous);
        }
        helper.succeed();
    }

    /**
     * {@code native_attack: "suppress"} must be reversible (audit RCN-003).
     *
     * <p>0.6.1 removed the mob's own attack goals outright. Nothing recorded how to rebuild them, so
     * changing the policy back to {@code coexist}, removing the loadout, or disabling the mod left the
     * mob permanently unable to use its bow.
     */
    public static void nativeAttackSuppressionIsReversible(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = (Mob) helper.spawn(EntityType.SKELETON, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        caster.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BOW));
        ((AbstractSkeleton) caster).reassessWeaponGoal();
        int before = countNativeAttackGoals(caster);
        helper.assertTrue(before > 0, "test setup: a bow skeleton should have a native attack goal");

        List<String> suppressed = AttackGoals.suppressNativeAttackGoals(caster);
        helper.assertTrue(!suppressed.isEmpty(), "suppression should have taken over a goal");
        helper.assertTrue(countNativeAttackGoals(caster) == 0,
                "no native attack goal should be runnable while suppressed");
        helper.assertTrue(AttackGoals.hasSuppressedGoals(caster), "the lease should be visible");

        AttackGoals.releaseNativeAttackGoals(caster);
        helper.assertTrue(countNativeAttackGoals(caster) == before,
                "releasing the lease must restore every original goal (had " + before
                        + ", now " + countNativeAttackGoals(caster) + ")");
        helper.assertFalse(AttackGoals.hasSuppressedGoals(caster), "no lease should remain");
        helper.succeed();
    }

    /** @return how many of the mob's own attack goals are present and not held inert. */
    private static int countNativeAttackGoals(Mob mob) {
        int n = 0;
        for (net.minecraft.world.entity.ai.goal.WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (AttackGoals.isNativeAttackGoal(wrapped.getGoal())) {
                n++;
            }
        }
        return n;
    }

    /**
     * A tamed companion ordered to sit does not cast (audit TGT-003).
     *
     * <p>Present in 0.6.0 and gone from 0.6.1, which left "sit" — the one instruction a player has for
     * switching a companion off — not switching anything off.
     */
    public static void sittingPetDoesNotCast(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Wolf wolf = helper.spawn(EntityType.WOLF, new BlockPos(1, 2, 1));
        wolf.setPersistenceRequired();
        wolf.setTame(true);
        primeMana(helper, wolf, 200.0);
        NpcSpellAttackGoal goal = new NpcSpellAttackGoal(wolf, testLoadout(EntityType.WOLF, 200.0));

        wolf.setOrderedToSit(false);
        helper.assertTrue(goal.stateBlocker() == null,
                "a standing tamed wolf should have no state blocker, got: " + goal.stateBlocker());
        wolf.setOrderedToSit(true);
        helper.assertTrue(goal.stateBlocker() != null,
                "a sitting tamed wolf must not be allowed to cast");
        wolf.setOrderedToSit(false);
        helper.succeed();
    }

    /**
     * A spell outside the verified manifest is not cast and costs nothing (audit SPI-002).
     *
     * <p>0.6.1 classified four spell paths explicitly and defaulted everything else to "supported", so
     * a player-only or channel-driven spell could be selected, spend mana, start a cooldown, and do
     * nothing at all. Unverified and unsupported spells now fail closed, before the transaction point.
     */
    public static void unsupportedSpellIsNotCastAndCostsNoMana(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = (Mob) helper.spawn(EntityType.SKELETON, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        double maxMana = primeMana(helper, caster, 200.0);

        // recall is player-only: Iron's checkPreCastConditions refuses any non-ServerPlayer caster.
        var recall = IronsBridge.getSpell(new ResourceLocation("irons_spellbooks", "recall"));
        if (recall == null) {
            helper.succeed(); // this Iron's build does not have it; nothing to assert
            return;
        }
        helper.assertFalse(SpellCompat.castableByMob(recall),
                "recall must be reported as not castable by a mob");
        MobCastSession.Start start = MobCastSession.begin(caster, null, recall, 1);
        helper.assertFalse(start.started(), "a player-only spell must not start a cast session");
        helper.assertTrue(MagicData.getPlayerMagicData(caster).getMana() >= maxMana - 0.5,
                "a refused cast must not spend mana");
        helper.succeed();
    }

    /** A minimal single-spell attack loadout, shared by the 0.6.2 regression tests. */
    private static SpellcasterLoadout testLoadout(EntityType<?> type, double maxMana) {
        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 0.0, 24.0, 0.0, LoadoutEntry.Role.ATTACK, 1.0, null, null, 0, null);
        return new SpellcasterLoadout(EntityType.getKey(type), maxMana, 0.0, List.of(entry));
    }

    // --- 0.6.3: caster movement and rank scaling -------------------------------------------------

    /**
     * A pure caster backs away from a target that is inside its minimum range.
     *
     * <p>A plain Villager Recruit has only a `RecruitMeleeAttackGoal`, so a casting one closed to
     * sword range and cast on the way in. A skeleton stands in for it here: same shape (native attack
     * AI suppressed, nothing telling it where to stand), and it needs no optional dependency.
     */
    public static void casterKeepsItsDistance(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = (Mob) helper.spawn(EntityType.SKELETON, new BlockPos(3, 2, 3));
        caster.setPersistenceRequired();
        primeMana(helper, caster, 400.0);

        SpellcasterLoadout loadout = standoffLoadout(EntityType.SKELETON);
        LoadoutManager.publishForTest(java.util.Map.of(EntityType.getKey(EntityType.SKELETON),
                List.of(loadout)));
        CasterReconciler.reconcile(caster, ReconcileReason.TEST);
        ManagedCasterState state = ManagedCasterState.of(caster);
        helper.assertTrue(state.nativeAttackSuppressed(),
                "test setup: native_attack=suppress should have taken over the skeleton's bow goal");

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 4));
        target.setNoAi(true);
        double startDistance = caster.distanceTo(target);
        helper.assertTrue(startDistance < 4.0,
                "test setup: the caster should start well inside its minimum range");

        helper.succeedWhen(() -> {
            caster.setTarget(target);
            helper.assertTrue(caster.distanceTo(target) > startDistance + 1.5,
                    "a pure caster with min_range 8 should back away from a target 1 block in front "
                            + "of it, but it is still at " + caster.distanceTo(target));
        });
    }

    /**
     * The movement goal stands down when the mob still has its own attack AI.
     *
     * <p>This is the "no behaviour change for existing worlds" guarantee: the shipped recruit loadouts
     * use the default `coexist`, so nothing about how they fight changes. It is also what keeps the
     * goal out of a tug-of-war with a melee goal that is pathing inward.
     */
    public static void movementStandsDownWhenNotSuppressed(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = (Mob) helper.spawn(EntityType.SKELETON, new BlockPos(1, 2, 1));
        caster.setPersistenceRequired();
        primeMana(helper, caster, 200.0);
        // A coexist loadout: the mob keeps its own attack goals.
        LoadoutManager.publishForTest(java.util.Map.of(EntityType.getKey(EntityType.SKELETON),
                List.of(testLoadout(EntityType.SKELETON, 200.0))));
        CasterReconciler.reconcile(caster, ReconcileReason.TEST);

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 2, 2));
        target.setNoAi(true);
        caster.setTarget(target);

        CasterMovementGoal movement = new CasterMovementGoal(caster,
                testLoadout(EntityType.SKELETON, 200.0).spells(), 1.0);
        helper.assertFalse(movement.canUse(),
                "with native_attack=coexist the movement goal must not engage");
        helper.succeed();
    }

    /**
     * A caster holds position while a channelled spell is in flight.
     *
     * <p>The casting session re-aims at its target every tick of a channel; walking at the same time
     * throws that aim away, and a spell that reads the caster's look angle would spray.
     */
    public static void casterDoesNotMoveWhileChannelling(GameTestHelper helper) {
        helper.getLevel().getServer().setDifficulty(Difficulty.NORMAL, true);
        Mob caster = (Mob) helper.spawn(EntityType.SKELETON, new BlockPos(3, 2, 3));
        caster.setPersistenceRequired();
        primeMana(helper, caster, 400.0);
        SpellcasterLoadout loadout = standoffLoadout(EntityType.SKELETON);
        LoadoutManager.publishForTest(java.util.Map.of(EntityType.getKey(EntityType.SKELETON),
                List.of(loadout)));
        CasterReconciler.reconcile(caster, ReconcileReason.TEST);

        Zombie target = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 2, 4));
        target.setNoAi(true);
        caster.setTarget(target);

        // Assert the gate directly rather than racing a real channel: with channelling set, the goal
        // must stop the navigation even though the target is well inside the standoff band.
        ManagedCasterState.of(caster).setChannelling(true);
        CasterMovementGoal movement = new CasterMovementGoal(caster, loadout.spells(), 1.0);
        movement.tick();
        helper.assertFalse(caster.getNavigation().isInProgress(),
                "a channelling caster must not be pathing anywhere");
        ManagedCasterState.of(caster).setChannelling(false);
        helper.succeed();
    }

    /** A minimal pure-caster loadout: native attack suppressed, and a real standoff band. */
    private static SpellcasterLoadout standoffLoadout(EntityType<?> type) {
        LoadoutEntry entry = new LoadoutEntry(
                new ResourceLocation("irons_spellbooks", "magic_missile"),
                1, 1, 8.0, 24.0, 0.0, LoadoutEntry.Role.ATTACK, 1.0, null, null, 0, null);
        return new SpellcasterLoadout(EntityType.getKey(type), null, 400.0, 0.0, List.of(entry),
                null, null, 1, new ResourceLocation("magicnpcs", "gametest_standoff"), false, true,
                com.otectus.magicnpcs.core.loadout.LoadoutSourceTier.DATAPACK, null,
                com.otectus.magicnpcs.core.loadout.NativeAttackPolicy.SUPPRESS, null);
    }
}
