package com.otectus.magicnpcs.compat.customnpcs;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.MagicNpcSignal;
import net.minecraft.world.entity.Mob;
import noppes.npcs.api.entity.ICustomNpc;
import noppes.npcs.api.entity.data.IData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where a Magic NPCs signal becomes something a CustomNPCs script can see, and where a script's request
 * becomes a Magic NPCs call.
 *
 * <p>Two directions, both driven from events the bridge already receives:
 *
 * <ul>
 *   <li><b>out</b> — {@link #emit}: {@code core.caster.MagicNpcEvents} publishes to the adapter, the
 *       adapter calls here, and this fires the NPC's script trigger. That is the only path; nothing in
 *       this package listens for cast events on the Forge bus, so a script is run once per cast;</li>
 *   <li><b>in</b> — {@link #processMailbox}: the NPC's update event reads a request out of its stored
 *       data, executes it, and writes the answer back. See {@link CustomNpcsMailboxCodec}.</li>
 * </ul>
 *
 * <p><b>The in-flight flag is not optional.</b> A trigger runs the NPC's script synchronously, inside
 * our own casting path. A script that answers a {@code cast_started} trigger by writing a
 * {@code cast} request would otherwise have that request executed by the mailbox on the same tick,
 * re-entering the casting code from inside itself. So while an NPC's trigger is running, its mailbox is
 * closed, and the request waits for the next update event — one tick later, from a clean stack.
 */
public final class CustomNpcsScriptBridge {

    /**
     * The protocol version handed to the script as the second trigger argument, so a script written
     * against a later argument list can refuse to run rather than misread the arguments it is given.
     */
    private static final String PROTOCOL = "magicnpcs:v1";

    /** Temp data a script writes to veto a cast it was told about. Temp, so it cannot outlive the tick. */
    private static final String KEY_CANCEL = "magicnpcs.cancel.v1";

    // The signal payload keys, in the order they are passed to the script trigger.
    private static final String KEY_SPELL = "spell";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_TARGET = "target";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_REASON = "reason";
    private static final String KEY_OLD_SCHOOL = "old_school";
    private static final String KEY_NEW_SCHOOL = "new_school";
    private static final String KEY_MODE = "mode";

    /** NPCs whose script is running right now. Their mailboxes are closed until it returns. */
    private static final java.util.Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** The last signal each NPC was told about, for {@code /magicnpcs why}. */
    private static final Map<UUID, String> LAST_SIGNAL = new ConcurrentHashMap<>();

    private static final AtomicInteger SIGNALS_EMITTED = new AtomicInteger();
    private static final AtomicInteger MAILBOX_REQUESTS = new AtomicInteger();

    private static volatile CustomNpcsScriptApi api = CustomNpcsScriptApi.inactive();
    private static volatile String lastResultCode = "none";

    private CustomNpcsScriptBridge() {}

    /**
     * Hand the bridge the API implementation to execute mailbox requests against. Called once from
     * {@link CustomNpcsIntegration}: the Iron's-backed one inside the Iron's guard, the null object
     * outside it.
     */
    static void setApi(CustomNpcsScriptApi implementation) {
        api = implementation == null ? CustomNpcsScriptApi.inactive() : implementation;
    }

    /**
     * @return the API the mailbox and the script facade execute against — the null object until
     *         {@link #setApi} has been called, never {@code null}.
     */
    public static CustomNpcsScriptApi api() {
        return api;
    }

    // --- out: signal to script trigger ---------------------------------------------------------

    /**
     * Tell an NPC's script that something happened to its casting.
     *
     * @return true to veto the cast — only ever for {@link MagicNpcSignal#CAST_PRE}, only when
     *         {@code scriptCancelHandshakeEnabled} is on, and only when the script actually answered.
     *         Every other signal returns false, because nothing after the transaction point can be
     *         un-spent and a script that "vetoes" a completed cast would be silently ignored anyway.
     */
    public static boolean emit(ICustomNpc<?> npc, MagicNpcSignal signal) {
        if (npc == null || signal == null || !MagicNpcsConfig.customNpcsEmitScriptTriggers()) {
            return false;
        }
        Mob mob = npc.getMCEntity();
        if (mob == null || mob.level().isClientSide()) {
            return false;
        }
        UUID id = mob.getUUID();
        if (!IN_FLIGHT.add(id)) {
            // This NPC's script is already on the stack. Running its trigger again from inside itself
            // is how a one-line script becomes an unbounded recursion, so the signal is dropped.
            return false;
        }
        try {
            npc.trigger(MagicNpcsConfig.customNpcsScriptTriggerId(),
                    npc, PROTOCOL, signal.name(),
                    text(signal, KEY_SPELL), text(signal, KEY_LEVEL), text(signal, KEY_TARGET),
                    text(signal, KEY_SOURCE), text(signal, KEY_REASON),
                    text(signal, KEY_OLD_SCHOOL), text(signal, KEY_NEW_SCHOOL), text(signal, KEY_MODE));
            SIGNALS_EMITTED.incrementAndGet();
            LAST_SIGNAL.put(id, signal.name());
            return MagicNpcSignal.CAST_PRE.equals(signal.name()) && readVeto(npc);
        } catch (LinkageError | RuntimeException ex) {
            // A broken script must not take the cast down with it. One WARN and the cast proceeds.
            MagicNpcs.LOGGER.warn("[magicnpcs] a CustomNPCs script trigger for signal {} failed: {}",
                    signal.name(), ex.toString(), ex);
            return false;
        } finally {
            IN_FLIGHT.remove(id);
        }
    }

    /**
     * Read and consume the script's answer to a {@code cast_pre} trigger.
     *
     * <p>Removed whether or not it says yes: a leftover veto would silently cancel the NPC's next cast
     * as well, which is indistinguishable from the mod being broken.
     */
    private static boolean readVeto(ICustomNpc<?> npc) {
        if (!MagicNpcsConfig.customNpcsScriptCancelHandshakeEnabled()) {
            return false;
        }
        IData temp = npc.getTempdata();
        if (temp == null || !temp.has(KEY_CANCEL)) {
            return false;
        }
        Object answer = temp.get(KEY_CANCEL);
        temp.remove(KEY_CANCEL);
        if (answer instanceof Number number) {
            return number.intValue() == 1;
        }
        return answer instanceof String text && "1".equals(text.trim());
    }

    /** @return the payload value as a string, or {@code ""} — a script trigger has no notion of absent. */
    private static String text(MagicNpcSignal signal, String key) {
        Object value = signal.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    // --- in: mailbox ---------------------------------------------------------------------------

    /**
     * Execute one queued script request, if there is one.
     *
     * <p>Called from the NPC update event, so the cost on an NPC with no request must be one map lookup:
     * hence the single {@link IData#has} check on the op key before anything else is touched.
     *
     * <p>The request keys are removed <em>before</em> the operation runs. If the operation throws, or
     * the NPC unloads inside it, the request is already gone — otherwise the same failing request would
     * be retried twenty times a second for as long as the NPC is loaded.
     */
    public static void processMailbox(ICustomNpc<?> npc) {
        if (npc == null || !MagicNpcsConfig.customNpcsScriptMailboxEnabled()) {
            return;
        }
        Mob mob = npc.getMCEntity();
        if (mob == null || mob.level().isClientSide() || IN_FLIGHT.contains(mob.getUUID())) {
            return;
        }
        IData stored = npc.getStoreddata();
        if (stored == null || !stored.has(CustomNpcsMailboxCodec.KEY_OP)) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>(4);
        String[] keys = stored.getKeys();
        if (keys != null) {
            for (String key : keys) {
                if (key != null && key.startsWith(CustomNpcsMailboxCodec.REQUEST_PREFIX)) {
                    data.put(key, stored.get(key));
                }
            }
        }
        for (String key : CustomNpcsMailboxCodec.requestKeys(data)) {
            stored.remove(key);
        }
        CustomNpcsMailboxCodec.Request request = CustomNpcsMailboxCodec.decodeRequest(data);
        if (request.isEmpty()) {
            return; // the op key was there but was not a string; nothing to answer and nothing to say
        }
        MAILBOX_REQUESTS.incrementAndGet();
        CustomNpcsScriptApi.Result result = execute(mob, request);
        lastResultCode = result.codeAsEnum().name();
        for (Map.Entry<String, Object> entry
                : CustomNpcsMailboxCodec.encodeResult(request.seq(), result).entrySet()) {
            stored.put(entry.getKey(), entry.getValue());
        }
    }

    /** Dispatch one decoded request. An op this build does not implement is an argument error. */
    private static CustomNpcsScriptApi.Result execute(Mob mob, CustomNpcsMailboxCodec.Request request) {
        CustomNpcsScriptApi target = api;
        if (!request.isKnownOp()) {
            return CustomNpcsScriptApi.Result.no(CustomNpcsScriptApi.ResultCode.INVALID_ARGUMENT,
                    "'" + request.op() + "' is not a Magic NPCs script operation");
        }
        return switch (request.op()) {
            case "isCaster" -> target.isCaster(mob);
            case "getSchool" -> target.getSchool(mob);
            case "getLoadout" -> target.getLoadout(mob);
            case "getMana" -> target.getMana(mob);
            case "getMaxMana" -> target.getMaxMana(mob);
            case "canCast" -> target.canCast(mob, request.string(CustomNpcsMailboxCodec.ARG_SPELL),
                    request.integer(CustomNpcsMailboxCodec.ARG_LEVEL, 1));
            case "why" -> target.why(mob);
            case "setSchool" -> target.setSchool(mob, request.string(CustomNpcsMailboxCodec.ARG_SCHOOL));
            case "clearSchool" -> target.clearSchool(mob);
            case "returnToAuto" -> target.returnToAuto(mob);
            case "setCastingSuspended" ->
                    target.setCastingSuspended(mob, request.flag(CustomNpcsMailboxCodec.ARG_SUSPENDED));
            case "cast" -> target.cast(mob, request.string(CustomNpcsMailboxCodec.ARG_SPELL),
                    request.integer(CustomNpcsMailboxCodec.ARG_LEVEL, 1),
                    request.uuid(CustomNpcsMailboxCodec.ARG_TARGET));
            default -> CustomNpcsScriptApi.Result.no(
                    CustomNpcsScriptApi.ResultCode.INVALID_ARGUMENT,
                    "'" + request.op() + "' is listed as an operation but has no implementation");
        };
    }

    // --- counters ------------------------------------------------------------------------------

    /** @return how many signals have reached a script this session. */
    public static int signalsEmitted() {
        return SIGNALS_EMITTED.get();
    }

    /** @return how many mailbox requests have been executed this session. */
    public static int mailboxRequests() {
        return MAILBOX_REQUESTS.get();
    }

    /** @return the code of the last mailbox answer, or {@code "none"}. */
    public static String lastResultCode() {
        return lastResultCode;
    }

    /** @return the last signal this NPC's script was told about, or {@code "none"}. */
    public static String lastSignal(Mob mob) {
        String last = mob == null ? null : LAST_SIGNAL.get(mob.getUUID());
        return last == null ? "none" : last;
    }

    /** @return how many NPC scripts are on the stack right now — non-zero only mid-trigger. */
    public static int inFlight() {
        return IN_FLIGHT.size();
    }

    /** Drop the in-flight guard, the counters and the per-NPC history. Bridge teardown. */
    static void reset() {
        IN_FLIGHT.clear();
        LAST_SIGNAL.clear();
        SIGNALS_EMITTED.set(0);
        MAILBOX_REQUESTS.set(0);
        lastResultCode = "none";
        api = CustomNpcsScriptApi.inactive();
    }
}
