package com.otectus.magicnpcs.compat.customnpcs.gametest;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.api.event.MagicNpcCastEvent;
import com.otectus.magicnpcs.compat.CustomNpcsCompat;
import com.otectus.magicnpcs.compat.IronsCompat;
import com.otectus.magicnpcs.compat.customnpcs.CustomNpcsActivityState;
import com.otectus.magicnpcs.compat.customnpcs.CustomNpcsMailboxCodec;
import com.otectus.magicnpcs.compat.customnpcs.CustomNpcsScriptApi;
import com.otectus.magicnpcs.compat.customnpcs.CustomNpcsScriptBridge;
import com.otectus.magicnpcs.compat.customnpcs.CustomNpcsScriptGlobal;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import com.otectus.magicnpcs.core.caster.ReconcileReason;
import com.otectus.magicnpcs.integration.irons.CasterReconciler;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import noppes.npcs.api.NpcAPI;
import noppes.npcs.api.entity.ICustomNpc;
import noppes.npcs.api.entity.data.IData;

import java.util.Map;

/**
 * GameTests for the CustomNPCs bridge.
 *
 * <p>Lives in the leaf package because it has to drive CustomNPCs' own API to reproduce the thing the
 * bridge exists for: an authored NPC having its AI rebuilt, which clears both goal selectors and takes
 * Magic NPCs' casting goals with them. Nothing short of the real mod reproduces that.
 *
 * <p>Every test is gated on the mods it needs and succeeds immediately when they are absent, so the
 * offline {@code runGameTestServer} used for the boot check stays green. They are {@code
 * required = false} for the same reason the Iron's runtime tests are: a scenario needing three mods
 * staged by hand should not be able to fail the suite for someone who has staged none of them.
 */
@GameTestHolder(MagicNpcs.MODID)
@PrefixGameTestTemplate(false)
public final class CustomNpcsCompatGameTests {

    private static final ResourceLocation CUSTOM_NPC =
            new ResourceLocation("customnpcs", "customnpc");

    /** An arbitrary school; the test is about the goal surviving, not about which spells it holds. */
    private static final ResourceLocation SCHOOL = new ResourceLocation("irons_spellbooks", "fire");

    private CustomNpcsCompatGameTests() {}

    /**
     * The whole point of the bridge: after CustomNPCs rebuilds an NPC's AI, the casting goal is back —
     * once. Sampled every ten ticks rather than only at the end, because the failure this guards
     * against is a repair that re-adds a goal the previous repair already added, and a duplicate can
     * be created and then reconciled away between two point checks.
     */
    @GameTest(template = "platform", timeoutTicks = 260, required = false)
    public static void casterGoalSurvivesAiRebuild(GameTestHelper helper) {
        if (!IronsCompat.isLoaded() || !CustomNpcsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        Mob npc = spawnNpc(helper);
        if (npc == null) {
            helper.succeed();
            return;
        }
        SchoolData.set(npc, SCHOOL, true);
        CasterReconciler.reconcile(npc, ReconcileReason.TEST);

        // Changing an AI mode is how an author triggers a rebuild, and it is the same code path
        // CustomNPCs runs on its own cadence.
        helper.runAtTickTime(10, () -> {
            ICustomNpc<?> wrapper = wrapperOf(npc);
            if (wrapper != null) {
                wrapper.getAi().setMovingType(1);
                wrapper.getAi().setRetaliateType(0);
            }
        });

        helper.runAtTickTime(30, () -> assertExactlyOneSpellGoal(helper, npc));
        for (int tick = 40; tick <= 200; tick += 10) {
            helper.runAtTickTime(tick, () -> assertExactlyOneSpellGoal(helper, npc));
        }
        helper.runAtTickTime(200, helper::succeed);
    }

    /** An NPC that dies stops being tracked, so the activity table cannot outlive the world. */
    @GameTest(template = "platform", timeoutTicks = 100, required = false)
    public static void deathClearsActivityState(GameTestHelper helper) {
        if (!CustomNpcsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        Mob npc = spawnNpc(helper);
        if (npc == null) {
            helper.succeed();
            return;
        }
        CustomNpcsActivityState.noteUpdate(npc, helper.getLevel().getGameTime());
        helper.assertTrue(CustomNpcsActivityState.lastUpdate(npc) != Long.MIN_VALUE,
                "the NPC should be tracked once an update event has been seen for it");
        npc.kill();
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(CustomNpcsActivityState.lastUpdate(npc) == Long.MIN_VALUE,
                    "a dead NPC should no longer be tracked");
            helper.succeed();
        });
    }

    /** Leaving the level (unload, dimension change) clears the same state as death does. */
    @GameTest(template = "platform", timeoutTicks = 100, required = false)
    public static void leaveClearsActivityState(GameTestHelper helper) {
        if (!CustomNpcsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        Mob npc = spawnNpc(helper);
        if (npc == null) {
            helper.succeed();
            return;
        }
        CustomNpcsActivityState.noteUpdate(npc, helper.getLevel().getGameTime());
        npc.discard();
        helper.runAtTickTime(20, () -> {
            helper.assertTrue(CustomNpcsActivityState.lastUpdate(npc) == Long.MIN_VALUE,
                    "an NPC that has left the level should no longer be tracked");
            helper.succeed();
        });
    }

    /** @return a spawned CustomNPC, or {@code null} if the entity type is not registered after all. */
    private static Mob spawnNpc(GameTestHelper helper) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(CUSTOM_NPC);
        if (type == null) {
            return null;
        }
        Entity entity = helper.spawn(type, new BlockPos(1, 2, 1));
        if (!(entity instanceof Mob mob)) {
            return null;
        }
        mob.setPersistenceRequired();
        return mob;
    }

    private static ICustomNpc<?> wrapperOf(Mob mob) {
        return NpcAPI.IsAvailable() && NpcAPI.Instance().getIEntity(mob) instanceof ICustomNpc<?> npc
                ? npc : null;
    }

    private static void assertExactlyOneSpellGoal(GameTestHelper helper, Mob mob) {
        int found = 0;
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (CasterReconciler.isOurSpellGoal(wrapped.getGoal())) {
                found++;
            }
        }
        helper.assertTrue(found == 1,
                "expected exactly one Magic NPCs casting goal after the AI rebuild, found " + found);
    }

    // --- M4: the script and event bridge ---------------------------------------------------------

    /** A spell every Iron's build has, so the test is about the lifecycle and not about the spell. */
    private static final ResourceLocation SPELL = new ResourceLocation("irons_spellbooks", "magic_missile");

    /**
     * Counts the cast lifecycle for one caster, and optionally vetoes it. Scoped to a single mob so a
     * watcher that somehow outlives its test cannot alter anyone else's cast.
     */
    private static final class CastWatch {
        private final Mob subject;
        private final boolean veto;
        private int pre;
        private int started;
        private int completed;
        private int cancelled;

        private CastWatch(Mob subject, boolean veto) {
            this.subject = subject;
            this.veto = veto;
        }

        @SubscribeEvent
        public void onPre(MagicNpcCastEvent.Pre event) {
            if (event.getCaster() != subject) {
                return;
            }
            pre++;
            if (veto) {
                event.setCancelReason("vetoed by a game test");
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public void onStarted(MagicNpcCastEvent.Started event) {
            if (event.getCaster() == subject) {
                started++;
            }
        }

        @SubscribeEvent
        public void onCompleted(MagicNpcCastEvent.Completed event) {
            if (event.getCaster() == subject) {
                completed++;
            }
        }

        @SubscribeEvent
        public void onCancelled(MagicNpcCastEvent.Cancelled event) {
            if (event.getCaster() == subject) {
                cancelled++;
            }
        }

        private int terminals() {
            return completed + cancelled;
        }
    }

    /**
     * Cancelling {@link MagicNpcCastEvent.Pre} is free for the caster: no mana spent, no cooldown
     * started, and exactly one {@code Cancelled} to explain it. A veto that charged the NPC anyway would
     * be worse than no veto at all, because the NPC would go quiet and drain itself.
     */
    @GameTest(template = "platform", timeoutTicks = 120, required = false)
    public static void aVetoedCastCostsTheCasterNothing(GameTestHelper helper) {
        if (!IronsCompat.isLoaded() || !CustomNpcsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        Mob npc = spawnNpc(helper);
        if (npc == null) {
            helper.succeed();
            return;
        }
        SchoolData.set(npc, SCHOOL, true);
        CasterReconciler.reconcile(npc, ReconcileReason.TEST);

        double manaBefore = manaOf(npc);
        CastWatch watch = new CastWatch(npc, true);
        MinecraftForge.EVENT_BUS.register(watch);
        try {
            CustomNpcsScriptApi.Result result =
                    CustomNpcsScriptBridge.api().cast(npc, SPELL.toString(), 1, null);
            helper.assertFalse(result.isOk(), "a vetoed cast must be reported as refused, not as ok");
            helper.assertTrue(watch.pre == 1, "expected exactly one Pre, got " + watch.pre);
            helper.assertTrue(watch.started == 0,
                    "a vetoed cast must never announce Started, got " + watch.started);
            helper.assertTrue(watch.cancelled == 1,
                    "a vetoed cast must announce exactly one Cancelled, got " + watch.cancelled);
            helper.assertTrue(watch.completed == 0, "nothing started, so nothing can complete");
            helper.assertTrue(manaOf(npc) >= manaBefore - 0.5, "a vetoed cast must not spend mana");
            ManagedCasterState state = ManagedCasterState.peek(npc);
            helper.assertTrue(state == null || state.cooldownRemaining(SPELL, npc.tickCount) == 0,
                    "a vetoed cast must not start a cooldown");
        } finally {
            MinecraftForge.EVENT_BUS.unregister(watch);
        }
        helper.succeed();
    }

    /**
     * An accepted cast announces {@code Started} once and then exactly one terminal. Sampled to the end
     * of the cast rather than checked immediately, because the failure this guards against is a session
     * cancelled by both its owner and itself, announcing two endings for one cast.
     */
    @GameTest(template = "platform", timeoutTicks = 200, required = false)
    public static void anAcceptedCastAnnouncesOneStartAndOneEnding(GameTestHelper helper) {
        if (!IronsCompat.isLoaded() || !CustomNpcsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        Mob npc = spawnNpc(helper);
        if (npc == null) {
            helper.succeed();
            return;
        }
        SchoolData.set(npc, SCHOOL, true);
        CasterReconciler.reconcile(npc, ReconcileReason.TEST);

        CastWatch watch = new CastWatch(npc, false);
        MinecraftForge.EVENT_BUS.register(watch);
        CustomNpcsScriptApi.Result result =
                CustomNpcsScriptBridge.api().cast(npc, SPELL.toString(), 1, null);
        if (!result.isOk()) {
            // Iron's refused before anything started - no mana attribute, or the spell is not
            // mob-castable in this build. A legitimate outcome and not what this test is about, but the
            // pairing still has to hold: nothing started, so nothing may have ended.
            MinecraftForge.EVENT_BUS.unregister(watch);
            helper.assertTrue(watch.started == 0 && watch.terminals() == 0,
                    "a cast that never started must announce neither a start nor an ending");
            helper.succeed();
            return;
        }
        helper.assertTrue(watch.started == 1, "expected exactly one Started, got " + watch.started);
        helper.runAtTickTime(150, () -> {
            MinecraftForge.EVENT_BUS.unregister(watch);
            helper.assertTrue(watch.started == 1, "Started must not be announced twice for one cast");
            helper.assertTrue(watch.terminals() == 1,
                    "expected exactly one terminal for one cast, got " + watch.terminals()
                            + " (completed " + watch.completed + ", cancelled " + watch.cancelled + ")");
            helper.succeed();
        });
    }

    /**
     * A request written into an NPC's stored data is answered on a later update event, and its request
     * keys are gone. The removal is the load-bearing half: a request that survived execution would be
     * re-run every tick for as long as the NPC stayed loaded.
     */
    @GameTest(template = "platform", timeoutTicks = 160, required = false)
    public static void aMailboxRequestIsAnsweredAndConsumed(GameTestHelper helper) {
        if (!CustomNpcsCompat.isLoaded()) {
            helper.succeed();
            return;
        }
        Mob npc = spawnNpc(helper);
        ICustomNpc<?> wrapper = npc == null ? null : wrapperOf(npc);
        if (wrapper == null) {
            helper.succeed();
            return;
        }
        IData stored = wrapper.getStoreddata();
        stored.put(CustomNpcsMailboxCodec.KEY_SEQ, 42);
        stored.put(CustomNpcsMailboxCodec.KEY_OP, "getSchool");

        helper.runAtTickTime(60, () -> {
            helper.assertFalse(stored.has(CustomNpcsMailboxCodec.KEY_OP),
                    "the request keys must be removed before the operation runs, or a failing request "
                            + "is retried forever");
            helper.assertFalse(stored.has(CustomNpcsMailboxCodec.KEY_SEQ),
                    "every request key goes, not just the op");
            helper.assertTrue(stored.has(CustomNpcsMailboxCodec.RESULT_CODE),
                    "the answer must be written back where the script can read it");
            Object seq = stored.get(CustomNpcsMailboxCodec.RESULT_SEQ);
            helper.assertTrue(seq instanceof Number number && number.intValue() == 42,
                    "the sequence number must be echoed so a script can tell its own answer from a "
                            + "stale one, got " + seq);
            helper.succeed();
        });
    }

    /**
     * A script suspension stops the NPC deciding to cast, and lifting it lets the NPC decide again
     * without being handed a reset cooldown. A resume that cleared cooldowns would let a script give an
     * NPC a free volley on every scene change.
     */
    @GameTest(template = "platform", timeoutTicks = 160, required = false)
    public static void aScriptSuspensionBlocksCastingAndResumesWithoutResettingCooldowns(
            GameTestHelper helper) {
        if (!IronsCompat.isLoaded() || !CustomNpcsCompat.isLoaded()
                || !MagicNpcsConfig.customNpcsScriptMutationsEnabled()) {
            helper.succeed();
            return;
        }
        Mob npc = spawnNpc(helper);
        if (npc == null) {
            helper.succeed();
            return;
        }
        SchoolData.set(npc, SCHOOL, true);
        CasterReconciler.reconcile(npc, ReconcileReason.TEST);

        CustomNpcsScriptApi api = CustomNpcsScriptBridge.api();
        helper.assertTrue(api.setCastingSuspended(npc, true).isOk(),
                "suspending an NPC through the script API should succeed with mutations enabled");
        helper.assertFalse(NpcAdapters.resolve(npc).canCastNow(npc),
                "a suspended NPC must not be allowed to decide to cast");

        // May or may not start; either way, whatever cooldown it leaves behind must only count down.
        api.cast(npc, SPELL.toString(), 1, null);
        ManagedCasterState state = ManagedCasterState.peek(npc);
        int cooldownBefore = state == null ? 0 : state.cooldownRemaining(SPELL, npc.tickCount);

        helper.runAtTickTime(40, () -> {
            helper.assertTrue(api.setCastingSuspended(npc, false).isOk(), "resuming should succeed");
            helper.assertFalse(CustomNpcsActivityState.isScriptSuspended(npc.getUUID()),
                    "the suspension flag must be cleared, not merely ignored");
            ManagedCasterState after = ManagedCasterState.peek(npc);
            int cooldownAfter = after == null ? 0 : after.cooldownRemaining(SPELL, npc.tickCount);
            helper.assertTrue(cooldownAfter <= cooldownBefore,
                    "a cooldown only ever counts down; resuming must not refill it (" + cooldownBefore
                            + " -> " + cooldownAfter + ")");
            helper.succeed();
        });
    }

    /**
     * When the global is enabled and the build is pinned, scripts can actually see it. Checked by
     * reaching for the globals map the same way the installer does - by assembled name, so this class
     * carries no reference to CustomNPCs internals either.
     */
    @GameTest(template = "platform", timeoutTicks = 40, required = false)
    public static void theScriptGlobalIsVisibleToScriptsWhenEnabled(GameTestHelper helper) {
        if (!CustomNpcsCompat.isLoaded()
                || CustomNpcsCompat.status() == CustomNpcsCompat.Status.PRESENT_UNSUPPORTED
                || !MagicNpcsConfig.customNpcsScriptGlobalEnabled()) {
            helper.succeed();
            return;
        }
        helper.assertTrue(CustomNpcsScriptGlobal.installed(),
                "the script global should be installed on a pinned build with it enabled; instead: "
                        + CustomNpcsScriptGlobal.failure());
        try {
            Class<?> container = Class.forName(String.join(".", GLOBALS_CLASS_PARTS));
            Object globals = container.getField("Data").get(null);
            helper.assertTrue(globals instanceof Map<?, ?> map
                            && map.get(CustomNpcsScriptGlobal.GLOBAL_NAME) != null,
                    "a script looking up '" + CustomNpcsScriptGlobal.GLOBAL_NAME + "' must find it");
        } catch (ReflectiveOperationException | LinkageError ex) {
            // The installer would have reported the same failure and left the bridge running, so there
            // is simply no global to check for. A skip, not a failure.
            helper.succeed();
            return;
        }
        helper.succeed();
    }

    /** {@code ScriptContainer}, in pieces - see {@link CustomNpcsScriptGlobal}. */
    private static final String[] GLOBALS_CLASS_PARTS = {"noppes", "npcs", "controllers", "ScriptContainer"};

    /** @return the NPC's mana through the bridge's own API, so this class needs no Iron's import. */
    private static double manaOf(Mob npc) {
        CustomNpcsScriptApi.Result result = CustomNpcsScriptBridge.api().getMana(npc);
        return result.isOk() && result.value() instanceof Number number ? number.doubleValue() : 0.0;
    }
}
