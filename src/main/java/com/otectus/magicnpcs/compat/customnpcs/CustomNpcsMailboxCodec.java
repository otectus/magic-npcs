package com.otectus.magicnpcs.compat.customnpcs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The wire format of the script mailbox: how a request written into a CustomNPCs NPC's stored data is
 * read, and how the answer is written back.
 *
 * <p>A mailbox rather than a function call because CustomNPCs scripts cannot call into another mod
 * directly on every build — but every build lets a script put a value on an NPC and read one back, and
 * the bridge is already ticking that NPC. So a script writes {@code magicnpcs.request.v1.op} plus its
 * arguments, and finds {@code magicnpcs.result.v1.*} there on the next update.
 *
 * <p>Pure and typed only in strings and numbers, with no CustomNPCs types anywhere: the reason this is
 * its own class is that the encoding is the part most likely to be wrong, and the only part that can be
 * tested without staging two other mods.
 */
public final class CustomNpcsMailboxCodec {

    /** Version in the key, not in a field: a v2 request must be ignorable by a v1 reader and vice versa. */
    public static final String REQUEST_PREFIX = "magicnpcs.request.v1.";

    /** The one key whose presence means "there is a request" — checked first, and on its own. */
    public static final String KEY_OP = REQUEST_PREFIX + "op";
    public static final String KEY_SEQ = REQUEST_PREFIX + "seq";
    public static final String ARG_PREFIX = REQUEST_PREFIX + "arg.";

    public static final String RESULT_PREFIX = "magicnpcs.result.v1.";
    public static final String RESULT_SEQ = RESULT_PREFIX + "seq";
    public static final String RESULT_CODE = RESULT_PREFIX + "code";
    public static final String RESULT_MESSAGE = RESULT_PREFIX + "message";
    public static final String RESULT_VALUE = RESULT_PREFIX + "value";

    /** Argument names, matching the {@link CustomNpcsScriptApi} parameters they feed. */
    public static final String ARG_SCHOOL = "school";
    public static final String ARG_SPELL = "spell";
    public static final String ARG_LEVEL = "level";
    public static final String ARG_TARGET = "target";
    public static final String ARG_SUSPENDED = "suspended";

    /**
     * The operation names a script may ask for, spelled exactly as the {@link CustomNpcsScriptApi}
     * methods. An op outside this set is answered {@code INVALID_ARGUMENT} rather than ignored: a
     * script author who has mistyped {@code getSchool} needs to be told, not left waiting.
     */
    public static final Set<String> OPS = Set.of(
            "isCaster", "getSchool", "getLoadout", "getMana", "getMaxMana", "canCast", "why",
            "setSchool", "clearSchool", "returnToAuto", "setCastingSuspended", "cast");

    private CustomNpcsMailboxCodec() {}

    /**
     * One decoded request.
     *
     * @param op   the requested operation, or {@code null} when there was no request at all
     * @param seq  the sequence number the script gave, echoed back so it can tell its own answer from
     *             a stale one; zero when it gave none
     * @param args the arguments, already filtered to strings and numbers — a value of any other type is
     *             dropped here, which turns into an {@code INVALID_ARGUMENT} for the op that needed it
     */
    public record Request(String op, Number seq, Map<String, Object> args) {

        /** @return true when the NPC's data held no request. */
        public boolean isEmpty() {
            return op == null || op.isEmpty();
        }

        /** @return true when this is an op the bridge actually implements. */
        public boolean isKnownOp() {
            return op != null && OPS.contains(op);
        }

        /** @return the argument as a string, or {@code null} when absent. A number is rendered plainly. */
        public String string(String name) {
            Object value = args.get(name);
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return trimNumber(number);
            }
            return value.toString();
        }

        /** @return the argument as an int, or {@code fallback} when absent or unparseable. */
        public int integer(String name, int fallback) {
            Object value = args.get(name);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String text) {
                try {
                    return (int) Double.parseDouble(text.trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            return fallback;
        }

        /** @return the argument as a UUID, or {@code null} when absent or malformed. */
        public UUID uuid(String name) {
            String text = string(name);
            if (text == null || text.isEmpty()) {
                return null;
            }
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        /** @return the argument as a flag. {@code 1}, {@code "1"} and {@code "true"} are true. */
        public boolean flag(String name) {
            Object value = args.get(name);
            if (value instanceof Number number) {
                return number.intValue() != 0;
            }
            if (value instanceof String text) {
                String trimmed = text.trim().toLowerCase(Locale.ROOT);
                return "1".equals(trimmed) || "true".equals(trimmed);
            }
            return false;
        }
    }

    /**
     * Read a request out of a copy of an NPC's stored data.
     *
     * @param data every key the NPC holds, or just the Magic NPCs ones — anything not under
     *             {@link #REQUEST_PREFIX} is ignored
     * @return the request, or an empty one when {@link #KEY_OP} is absent or is not a string
     */
    public static Request decodeRequest(Map<String, Object> data) {
        Object rawOp = data.get(KEY_OP);
        if (!(rawOp instanceof String op) || op.isBlank()) {
            return new Request(null, 0, Map.of());
        }
        Object rawSeq = data.get(KEY_SEQ);
        Number seq = rawSeq instanceof Number number ? number : 0;
        Map<String, Object> args = new LinkedHashMap<>(4);
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(ARG_PREFIX)) {
                continue;
            }
            Object value = entry.getValue();
            // Only strings and numbers cross. A script that stored a boolean, a list or a host object
            // has stored something the codec cannot state a meaning for, so the argument is treated as
            // missing and the op refuses with INVALID_ARGUMENT rather than guessing.
            if (value instanceof String || value instanceof Number) {
                args.put(key.substring(ARG_PREFIX.length()), value);
            }
        }
        return new Request(op.trim(), seq, Map.copyOf(args));
    }

    /**
     * Render an answer as data keys.
     *
     * <p>A boolean answer becomes {@code 1} or {@code 0}: the CustomNPCs data API stores arbitrary
     * objects, but a script engine reading one back compares numbers far more reliably than it compares
     * a boxed {@code Boolean} from another classloader.
     *
     * @return the keys to write, in write order
     */
    public static Map<String, Object> encodeResult(Number seq, CustomNpcsScriptApi.Result result) {
        Map<String, Object> out = new LinkedHashMap<>(4);
        out.put(RESULT_SEQ, seq == null ? 0 : seq);
        out.put(RESULT_CODE, result.code());
        out.put(RESULT_MESSAGE, result.message() == null ? "" : result.message());
        Object value = result.value();
        if (value instanceof Boolean flag) {
            out.put(RESULT_VALUE, flag ? 1 : 0);
        } else if (value != null) {
            out.put(RESULT_VALUE, value);
        }
        return out;
    }

    /**
     * @return every request key present in {@code data}, so the caller can remove them <em>before</em>
     *         executing. Removing first is what stops an op that throws, or one whose NPC unloads
     *         mid-call, from being re-executed on every subsequent update tick forever.
     */
    public static List<String> requestKeys(Map<String, Object> data) {
        List<String> keys = new ArrayList<>(4);
        for (String key : data.keySet()) {
            if (key != null && key.startsWith(REQUEST_PREFIX)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** {@code 3.0} reads badly as a spell level; render whole numbers without the decimal point. */
    private static String trimNumber(Number number) {
        double value = number.doubleValue();
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }
}
