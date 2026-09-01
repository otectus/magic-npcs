package com.otectus.magicnpcs.core.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A rendered-but-unstyled diagnostic answer: an ordered list of lines, each tagged with a severity the
 * command layer turns into a chat colour. This is the seam that keeps {@code command/} Iron's-free —
 * the Iron's-side producers ({@code CasterDiagnostics}, {@code SchoolSpellPool}) know the facts, the
 * command only knows how to print them.
 *
 * <p>Deliberately text-shaped rather than a deep record tree: the audience is a pack author reading
 * chat, the content differs per question ("why isn't this mob casting", "what's in this school's
 * pool"), and a structured model would have to be widened for every new check.
 */
public record DiagnosticReport(List<Line> lines) {

    public enum Level {
        /** Section heading. */
        HEADER,
        /** Neutral fact. */
        INFO,
        /** Secondary detail, indented under the previous line. */
        DETAIL,
        /** A check that passed. */
        GOOD,
        /** Works, but is probably not what the author intended. */
        WARN,
        /** The blocker — the reason the thing being diagnosed does not happen. */
        BAD
    }

    /**
     * @param level how to present the line
     * @param text  the line, already formatted (no trailing newline)
     */
    public record Line(Level level, String text) {}

    public static Builder builder() {
        return new Builder();
    }

    /** Small append-only builder; every {@code add*} returns {@code this} so producers read as a script. */
    public static final class Builder {
        private final List<Line> lines = new ArrayList<>();

        public Builder header(String text) {
            return add(Level.HEADER, text);
        }

        public Builder info(String text) {
            return add(Level.INFO, text);
        }

        public Builder detail(String text) {
            return add(Level.DETAIL, "    " + text);
        }

        public Builder good(String text) {
            return add(Level.GOOD, text);
        }

        public Builder warn(String text) {
            return add(Level.WARN, text);
        }

        public Builder bad(String text) {
            return add(Level.BAD, text);
        }

        /** Add a line whose level depends on a check outcome — the common "pass/fail" shape. */
        public Builder check(boolean ok, String text) {
            return add(ok ? Level.GOOD : Level.BAD, text);
        }

        public Builder format(Level level, String format, Object... args) {
            return add(level, String.format(Locale.ROOT, format, args));
        }

        public Builder add(Level level, String text) {
            lines.add(new Line(level, text));
            return this;
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }

        public DiagnosticReport build() {
            return new DiagnosticReport(List.copyOf(lines));
        }
    }
}
