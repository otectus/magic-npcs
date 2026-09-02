package com.otectus.magicnpcs.core.diag;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /magicnpcs why} is what a pack author runs when something is silently not happening. A
 * contributor that throws must therefore degrade the report, never replace it with a stack trace —
 * losing the sections that had already worked out the answer would be strictly worse than losing the
 * one section that failed.
 */
class DiagnosticContributorsTest {

    @BeforeEach
    @AfterEach
    void reset() {
        DiagnosticContributors.clearForTest();
    }

    @Test
    void contributorsAppendTheirOwnRows() {
        DiagnosticContributors.register((mob, out) -> out.detail("from the first"));
        DiagnosticContributors.register((mob, out) -> out.detail("from the second"));

        DiagnosticReport.Builder out = DiagnosticReport.builder();
        DiagnosticContributors.describeAll(null, out);

        // Compared on content, not presentation: DiagnosticReport.detail() indents its rows, and
        // pinning the exact indentation here would turn a formatting tweak into a test failure.
        List<String> text = out.build().lines().stream()
                .map(line -> line.text().trim()).toList();
        assertEquals(List.of("from the first", "from the second"), text);
    }

    @Test
    void aThrowingContributorIsReportedInLineAndTheRestStillRun() {
        DiagnosticContributors.register((mob, out) -> {
            throw new IllegalStateException("boom");
        });
        DiagnosticContributors.register((mob, out) -> out.detail("still reported"));

        DiagnosticReport.Builder out = DiagnosticReport.builder();
        assertDoesNotThrow(() -> DiagnosticContributors.describeAll(null, out));

        List<DiagnosticReport.Line> lines = out.build().lines();
        assertEquals(2, lines.size());
        assertEquals(DiagnosticReport.Level.WARN, lines.get(0).level(),
                "the failure itself belongs in the report, not in the log only");
        assertTrue(lines.get(0).text().contains("boom"));
        assertEquals("still reported", lines.get(1).text().trim());
    }

    @Test
    void withNoContributorsTheReportIsUntouched() {
        DiagnosticReport.Builder out = DiagnosticReport.builder();
        out.detail("only line");
        DiagnosticContributors.describeAll(null, out);

        assertEquals(1, out.build().lines().size());
    }
}
