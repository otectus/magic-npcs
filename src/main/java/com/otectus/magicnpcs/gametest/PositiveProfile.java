package com.otectus.magicnpcs.gametest;

import com.otectus.magicnpcs.MagicNpcs;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decides whether an integration GameTest may skip, and records what actually ran.
 *
 * <p>Through 0.9.0 every integration test opened with "if the host mod is absent, succeed". Offline
 * that is exactly right — the point of the base run is that the mod boots with its optional
 * dependencies missing. For a run whose whole purpose is to prove an integration works it is a lie by
 * omission: a green report in which every positive test skipped looked identical to one in which they
 * all passed, which is the hole the roadmap records as MN-015.
 *
 * <p>So the two runs are now different runs. With no profile selected nothing changes: an absent host
 * still skips. When {@code -PtestRuntimeProfile=<name>} selects a profile, {@code build.gradle} passes
 * the mod ids that profile requires as {@code magicnpcs.requiredMods}, and a test needing one of them
 * <em>fails</em> — naming the mod — rather than skipping. Executed, skipped and failed counts are kept
 * apart and written to {@code magicnpcs.gametestReport} at shutdown, where {@code
 * verifyPositiveGameTests} reads them back and rejects a run in which no positive path executed.
 *
 * <p>Vanilla + Forge only, and it names no optional mod: the mod ids come from the profile.
 */
public final class PositiveProfile {

    /** System property naming the selected profile, or absent for the offline smoke run. */
    public static final String PROFILE_PROPERTY = "magicnpcs.positiveProfile";

    /** System property carrying the comma-separated mod ids the selected profile requires. */
    public static final String REQUIRED_MODS_PROPERTY = "magicnpcs.requiredMods";

    /** System property naming the file the executed/skipped/failed counts are written to. */
    public static final String REPORT_PROPERTY = "magicnpcs.gametestReport";

    /** What a test should do about a host mod it needs. */
    public enum Verdict {
        /** The host is present: run the positive path. */
        RUN,
        /** The host is absent and no profile requires it: skip, and say so in the counts. */
        SKIP,
        /** The host is absent and the selected profile requires it: fail, naming the mod. */
        MISSING_REQUIRED
    }

    private static final AtomicInteger EXECUTED = new AtomicInteger();
    private static final AtomicInteger SKIPPED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();

    /** Positive tests that skipped although a profile was selected — the MN-015 failure mode. */
    private static final Set<String> SKIPPED_POSITIVE = Collections.synchronizedSet(new LinkedHashSet<>());

    /** Tests that failed their preflight, with the mod id they were waiting for. */
    private static final Set<String> FAILURES = Collections.synchronizedSet(new LinkedHashSet<>());

    private static volatile boolean reportHookInstalled;

    private PositiveProfile() {
    }

    /**
     * The whole decision, with no static state and no Minecraft: given a host mod, whether it is
     * present, and the mods the selected profile requires, what should the test do?
     *
     * <p>Separated out so it can be asserted in a plain unit test. The three cases are the REG-35
     * negatives: a required host that is missing must fail, an unrequired host that is missing must
     * skip, and a present host must always run.
     */
    public static Verdict verdictFor(String modId, boolean present, Set<String> requiredMods) {
        if (present) {
            return Verdict.RUN;
        }
        return requiredMods != null && requiredMods.contains(modId)
                ? Verdict.MISSING_REQUIRED
                : Verdict.SKIP;
    }

    /** @return the profile selected for this run, or {@code null} for the offline smoke run. */
    public static String selected() {
        String profile = System.getProperty(PROFILE_PROPERTY);
        return profile == null || profile.isBlank() ? null : profile.trim();
    }

    /** @return the mod ids the selected profile requires; empty when no profile is selected. */
    public static Set<String> requiredMods() {
        String raw = System.getProperty(REQUIRED_MODS_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> mods = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String id = part.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                mods.add(id);
            }
        }
        return Set.copyOf(mods);
    }

    /**
     * Gate one integration test on the host mod it needs.
     *
     * <p>The test name is passed in rather than read off the helper so the report names the wrapper
     * the operator selected, which is what a re-run is filtered by.
     *
     * @return true when the caller should run its positive path. When false, the helper has already
     *         been told to succeed (a legitimate skip) or to fail (a required host that is absent),
     *         and the caller must simply return.
     */
    public static boolean require(GameTestHelper helper, String modId, String testName) {
        installReportHook();
        Verdict verdict = verdictFor(modId, isLoaded(modId), requiredMods());
        switch (verdict) {
            case RUN -> {
                EXECUTED.incrementAndGet();
                return true;
            }
            case SKIP -> {
                SKIPPED.incrementAndGet();
                if (selected() != null) {
                    // A profile is selected but does not require this mod: still a skip, and still
                    // worth naming, so nobody reads "0 failures" as "everything was exercised".
                    SKIPPED_POSITIVE.add(testName + " (" + modId + " absent)");
                }
                helper.succeed();
                return false;
            }
            default -> {
                FAILED.incrementAndGet();
                FAILURES.add(testName + " (" + modId + " not loaded)");
                SKIPPED_POSITIVE.add(testName + " (" + modId + " required but absent)");
                helper.fail("profile '" + selected() + "' requires " + modId
                        + ", which is not loaded — this is a preflight failure, not a test result");
                return false;
            }
        }
    }

    /**
     * Record that a positive test reached its assertions but could not observe the entity or API it
     * needed. Distinct from a skip: the host was there and something inside it was not, which is a
     * real finding rather than an absent-dependency run.
     */
    public static void missingHostCapability(GameTestHelper helper, String testName, String what) {
        FAILED.incrementAndGet();
        FAILURES.add(testName + " (" + what + ")");
        helper.fail(testName + ": " + what);
    }

    private static boolean isLoaded(String modId) {
        try {
            return ModList.get() != null && ModList.get().isLoaded(modId);
        } catch (RuntimeException | LinkageError ignored) {
            // Asked before the mod list exists: treat as absent rather than taking the server down.
            return false;
        }
    }

    /**
     * Write the counts once, at JVM shutdown. The GameTest framework has no "all tests finished" hook
     * a mod can subscribe to, and a report written per test would be overwritten by the last one.
     */
    private static synchronized void installReportHook() {
        if (reportHookInstalled) {
            return;
        }
        String path = System.getProperty(REPORT_PROPERTY);
        if (path == null || path.isBlank()) {
            reportHookInstalled = true; // no profile selected: nothing to report
            return;
        }
        reportHookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> writeReport(Path.of(path)), "magicnpcs-gametest-report"));
    }

    private static void writeReport(Path path) {
        Properties report = new Properties();
        report.setProperty("profile", selected() == null ? "" : selected());
        report.setProperty("requiredMods", String.join(",", requiredMods()));
        report.setProperty("executed", Integer.toString(EXECUTED.get()));
        report.setProperty("skipped", Integer.toString(SKIPPED.get()));
        report.setProperty("failed", Integer.toString(FAILED.get()));
        report.setProperty("skippedPositive", String.join("; ", SKIPPED_POSITIVE));
        report.setProperty("failures", String.join("; ", FAILURES));
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                report.store(out, "magicnpcs positive integration profile result");
            }
        } catch (IOException e) {
            MagicNpcs.LOGGER.error("Could not write the positive-profile GameTest report to {}", path, e);
        }
    }
}
