package com.otectus.magicnpcs.compat.customnpcs;

import net.minecraft.world.entity.Mob;

import java.util.UUID;

/**
 * Everything a CustomNPCs script may ask of Magic NPCs, in one server-only surface.
 *
 * <p>Two implementations. {@link CustomNpcsScriptApiIrons} does the work and is constructed only
 * inside the Iron's Spellbooks guard, because every question here ultimately needs Iron's to answer.
 * {@link #inactive()} is the null object: it answers {@link ResultCode#BRIDGE_INACTIVE} to everything,
 * so a script written against this API on a server without Iron's gets a stated reason rather than a
 * missing method or a stack trace.
 *
 * <p><b>Nothing here throws.</b> A script engine is a hostile caller: it passes strings where numbers
 * belong, holds entities that have despawned, and turns any exception into a script error the pack
 * author cannot act on. So every operation returns a {@link Result} with a code, and implementations
 * catch {@link RuntimeException} and answer {@link ResultCode#INTERNAL_ERROR}.
 *
 * <p>Takes vanilla {@link Mob}s, not CustomNPCs wrappers: unwrapping is the bridge's job, and keeping
 * the wrapper out of here is what lets the Iron's-backed implementation avoid importing CustomNPCs.
 */
public interface CustomNpcsScriptApi {

    /** Why an operation answered the way it did. {@link #OK} is the only success. */
    enum ResultCode {
        OK,
        /** The bridge is not running, or Iron's Spellbooks is absent. */
        BRIDGE_INACTIVE,
        /** Asked off the server thread or on a client level. */
        NOT_SERVER,
        /** The entity is dead, removed, or was never there. */
        ENTITY_GONE,
        /** The entity is not a CustomNPC. */
        NOT_CUSTOMNPC,
        /** The NPC has no casting setup, so there is nothing to report or change. */
        NOT_CASTER,
        /** {@code scriptMutationsEnabled} is off: this server allows reads only. */
        MUTATIONS_DISABLED,
        /** An argument was missing, malformed, or of the wrong type. */
        INVALID_ARGUMENT,
        /** The school exists but the config does not allow it. */
        SCHOOL_NOT_ALLOWED,
        /** The spell is unknown, blacklisted, or has no verified mob-cast strategy. */
        SPELL_NOT_ALLOWED,
        /** The spell is still on cooldown for this NPC. */
        ON_COOLDOWN,
        /** The NPC cannot afford the spell. */
        NO_MANA,
        /** The spell needs a target entity and none was given, or the one given is gone. */
        NO_TARGET,
        /** The target is this NPC's owner, faction friend, or otherwise an ally. */
        FRIENDLY_TARGET,
        /** Something threw. The message says what; the script gets a code, not a stack trace. */
        INTERNAL_ERROR
    }

    /**
     * One answer.
     *
     * @param code    a {@link ResultCode} ordinal — an int rather than the enum because a script
     *                engine compares numbers reliably and host enums unreliably
     * @param message a human-readable explanation, always present
     * @param value   the answer for a read operation ({@code String}, {@link Number} or
     *                {@code Boolean}), or {@code null}
     */
    record Result(int code, String message, Object value) {

        public boolean isOk() {
            return code == ResultCode.OK.ordinal();
        }

        /** @return the code as an enum, for callers on this side of the bridge. */
        public ResultCode codeAsEnum() {
            ResultCode[] all = ResultCode.values();
            return code >= 0 && code < all.length ? all[code] : ResultCode.INTERNAL_ERROR;
        }

        // Bean-shaped aliases for the record accessors. Not redundant: the script engines CustomNPCs
        // ships with expose a host object's properties by getter name, so a script reading r.code on a
        // record sees nothing while r.getCode() works.

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public Object getValue() {
            return value;
        }

        public static Result ok(Object value) {
            return new Result(ResultCode.OK.ordinal(), "ok", value);
        }

        public static Result no(ResultCode code, String message) {
            return new Result(code.ordinal(), message, null);
        }
    }

    // --- reads ---------------------------------------------------------------------------------

    Result isCaster(Mob mob);

    Result getSchool(Mob mob);

    Result getLoadout(Mob mob);

    Result getMana(Mob mob);

    Result getMaxMana(Mob mob);

    /** @return OK if this NPC could cast {@code spell} at {@code level} right now, or why it could not. */
    Result canCast(Mob mob, String spell, int level);

    /** The {@code /magicnpcs why} report for this NPC, as one newline-separated string. */
    Result why(Mob mob);

    // --- mutations (gated on scriptMutationsEnabled) ---------------------------------------------

    Result setSchool(Mob mob, String school);

    Result clearSchool(Mob mob);

    Result returnToAuto(Mob mob);

    /** Suspend or resume this NPC's casting for as long as the NPC is loaded. */
    Result setCastingSuspended(Mob mob, boolean suspended);

    /** @param target the entity to cast at, or {@code null} for a self-cast */
    Result cast(Mob mob, String spell, int level, UUID target);

    /** @return the null object: every operation answers {@link ResultCode#BRIDGE_INACTIVE}. */
    static CustomNpcsScriptApi inactive() {
        return new Inactive();
    }

    /**
     * The null object. Deliberately not a lambda or a set of default methods: the bridge holds one of
     * these unconditionally, and a script asking a question on a server without Iron's must get the
     * same shape of answer as one that has it.
     */
    final class Inactive implements CustomNpcsScriptApi {

        private static final String WHY =
                "the Magic NPCs script bridge is not active (Iron's Spellbooks absent, or the "
                        + "CustomNPCs bridge failed to start) — run /magicnpcs config";

        private static Result inactive() {
            return Result.no(ResultCode.BRIDGE_INACTIVE, WHY);
        }

        @Override public Result isCaster(Mob mob) { return inactive(); }
        @Override public Result getSchool(Mob mob) { return inactive(); }
        @Override public Result getLoadout(Mob mob) { return inactive(); }
        @Override public Result getMana(Mob mob) { return inactive(); }
        @Override public Result getMaxMana(Mob mob) { return inactive(); }
        @Override public Result canCast(Mob mob, String spell, int level) { return inactive(); }
        @Override public Result why(Mob mob) { return inactive(); }
        @Override public Result setSchool(Mob mob, String school) { return inactive(); }
        @Override public Result clearSchool(Mob mob) { return inactive(); }
        @Override public Result returnToAuto(Mob mob) { return inactive(); }
        @Override public Result setCastingSuspended(Mob mob, boolean suspended) { return inactive(); }
        @Override public Result cast(Mob mob, String spell, int level, UUID target) { return inactive(); }
    }
}
