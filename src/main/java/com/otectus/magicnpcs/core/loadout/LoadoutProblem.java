package com.otectus.magicnpcs.core.loadout;

/**
 * One structured complaint about a discovered spellcaster resource: a machine-stable {@link #code()},
 * the JSON location it applies to, a human message, and — where one exists — a concrete suggestion.
 *
 * <p>Before 0.6.2 a bad loadout produced a single logger line and was dropped, so {@code /validate}
 * could report "no issues" while the user's file had been rejected outright (audit VAL-001). Problems
 * are now retained on the {@link LoadoutRecord} and printed by the command, which means they need a
 * stable identity: support and tests refer to {@link #code()}, never to the English text.
 *
 * @param severity how bad it is — an {@link Severity#ERROR} keeps the resource out of the runtime map
 * @param code     stable machine-readable code, {@code SCREAMING_SNAKE_CASE} (e.g. {@code UNKNOWN_SPELL})
 * @param pointer  JSON pointer into the offending file (e.g. {@code /spells/0/spell}), or {@code ""}
 *                 for a whole-file problem
 * @param message  one-sentence description of what is wrong
 * @param suggestion an actionable fix, or {@code null} when none can be inferred
 */
public record LoadoutProblem(Severity severity, String code, String pointer, String message, String suggestion) {

    public enum Severity {
        /** Worth knowing, changes nothing. */
        INFO,
        /** The resource still loads, but it probably does not do what the author meant. */
        WARNING,
        /** The resource cannot be used. It is retained for diagnostics but never becomes active. */
        ERROR;

        public boolean atLeast(Severity other) {
            return ordinal() >= other.ordinal();
        }
    }

    public static LoadoutProblem error(String code, String pointer, String message) {
        return new LoadoutProblem(Severity.ERROR, code, pointer, message, null);
    }

    public static LoadoutProblem error(String code, String pointer, String message, String suggestion) {
        return new LoadoutProblem(Severity.ERROR, code, pointer, message, suggestion);
    }

    public static LoadoutProblem warning(String code, String pointer, String message) {
        return new LoadoutProblem(Severity.WARNING, code, pointer, message, null);
    }

    public static LoadoutProblem warning(String code, String pointer, String message, String suggestion) {
        return new LoadoutProblem(Severity.WARNING, code, pointer, message, suggestion);
    }

    public static LoadoutProblem info(String code, String pointer, String message) {
        return new LoadoutProblem(Severity.INFO, code, pointer, message, null);
    }

    public static LoadoutProblem info(String code, String pointer, String message, String suggestion) {
        return new LoadoutProblem(Severity.INFO, code, pointer, message, suggestion);
    }

    /** {@code ERROR my_pack:skeleton /spells/0/spell: unknown spell … (did you mean …?)} */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(severity.name());
        if (!pointer.isEmpty()) {
            sb.append(' ').append(pointer);
        }
        sb.append(": ").append(message);
        if (suggestion != null && !suggestion.isEmpty()) {
            sb.append(" — ").append(suggestion);
        }
        return sb.toString();
    }
}
