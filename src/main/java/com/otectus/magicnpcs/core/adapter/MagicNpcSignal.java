package com.otectus.magicnpcs.core.adapter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One cast- or school-lifecycle fact, in a form an NPC framework can hand to a script.
 *
 * <p>The Forge events carry live entities, which is right for a mod listener and wrong for a script
 * engine: a script that keeps a reference to a {@code LivingEntity} keeps a dead entity alive in a
 * closure, and a script engine that is handed one has to be taught what it is. So the signal carries
 * only strings, numbers and booleans — a target is its UUID string, a spell is its id — and the
 * payload is validated on construction rather than at the far end of the bridge.
 *
 * @param name    one of the constants below
 * @param payload immutable, and guaranteed to hold only {@code String}, {@link Number} and
 *                {@code Boolean} values
 */
public record MagicNpcSignal(String name, Map<String, Object> payload) {

    /** A cast is about to start and may still be vetoed. */
    public static final String CAST_PRE = "cast_pre";
    /** The cast was accepted: mana is spent, the cooldown is running. */
    public static final String CAST_STARTED = "cast_started";
    /** The cast ran to the end of its duration. */
    public static final String CAST_COMPLETED = "cast_completed";
    /** The cast ended early, or was vetoed before it started. */
    public static final String CAST_CANCELLED = "cast_cancelled";
    /** The cast could not be started at all. Carries a reason; no Forge event accompanies it. */
    public static final String CAST_FAILED = "cast_failed";
    /** An entity's school assignment changed. */
    public static final String SCHOOL_CHANGED = "school_changed";

    /**
     * @throws IllegalArgumentException if any value is not a {@code String}, {@link Number} or
     *         {@code Boolean} — better a loud failure at the emission point than a script engine
     *         being handed something it turns into an opaque host object.
     */
    public static MagicNpcSignal of(String name, Map<String, Object> payload) {
        Map<String, Object> copy = new LinkedHashMap<>(payload.size());
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
                throw new IllegalArgumentException("signal " + name + " key '" + entry.getKey()
                        + "' holds " + (value == null ? "null" : value.getClass().getName())
                        + "; only String, Number and Boolean may cross into a script");
            }
            copy.put(entry.getKey(), value);
        }
        return new MagicNpcSignal(name, Map.copyOf(copy));
    }

    /** @return the value under {@code key}, or {@code null} when the signal does not carry it. */
    public Object get(String key) {
        return payload.get(key);
    }
}
