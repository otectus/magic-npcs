package com.otectus.magicnpcs.compat;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.compat.customnpcs.CustomNpcsIds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The load-bearing invariant of the CustomNPCs bridge: <b>nothing outside
 * {@code compat/customnpcs/} may name a CustomNPCs type</b>. That is what lets the mod run with
 * CustomNPCs absent, and what lets {@link CustomNpcsCompat} report an unsupported build instead of
 * dying on a {@code NoClassDefFoundError} the moment a status is asked for.
 *
 * <p>A review rule cannot enforce this — one careless import in a diagnostics class is enough, and it
 * only fails on someone else's machine. So it is checked against the compiled bytes: a class file's
 * constant pool holds every type it references by name, so a plain byte scan for {@code noppes/npcs}
 * finds an import, a field type, a cast and a {@code Class.forName} literal alike, without ASM.
 */
class CustomNpcsCompatIsolationTest {

    /** The one package allowed to reference CustomNPCs, in class-file (slash) form. */
    private static final String ALLOWED_PREFIX = "com/otectus/magicnpcs/compat/customnpcs/";

    private static final String[] FORBIDDEN = {"noppes/npcs", "noppes.npcs"};

    // --- (a) package isolation ------------------------------------------------------------------

    @Test
    void noClassOutsideTheCustomNpcsPackageNamesACustomNpcsType() throws IOException {
        Path root = mainClassesDir();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith(ALLOWED_PREFIX)) {
                    continue;
                }
                if (referencesCustomNpcs(file)) {
                    offenders.add(relative);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these classes live outside " + ALLOWED_PREFIX + " but reference CustomNPCs, so they "
                        + "will fail to load when it is absent: " + offenders);
    }

    @Test
    void theNeutralFacadeItselfNamesNothingFromCustomNpcs() throws IOException {
        // Called on every status line and every init path, including the one that reports "installed
        // but unsupported" — the exact case where linking against CustomNPCs would be fatal.
        Path facade = mainClassesDir().resolve("com/otectus/magicnpcs/compat/CustomNpcsCompat.class");
        assumeTrue(Files.isRegularFile(facade), "CustomNpcsCompat.class was not compiled");
        assertFalse(referencesCustomNpcs(facade),
                "CustomNpcsCompat must reach the typed integration by name only");
    }

    private static boolean referencesCustomNpcs(Path classFile) throws IOException {
        // ISO-8859-1 maps every byte to one char, so this is a byte scan spelled as a string search.
        String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        for (String forbidden : FORBIDDEN) {
            if (bytes.contains(forbidden)) {
                return true;
            }
        }
        return false;
    }

    /** @return the compiled main output directory; skips the test when running from a jar. */
    private static Path mainClassesDir() {
        CodeSource source = MagicNpcs.class.getProtectionDomain().getCodeSource();
        assumeTrue(source != null, "no code source: nothing to scan");
        Path path;
        try {
            path = Path.of(source.getLocation().toURI());
        } catch (URISyntaxException ex) {
            throw new AssertionError("unreadable code source location", ex);
        }
        assumeTrue(Files.isDirectory(path), "main classes are packaged, not a directory: " + path);
        return path;
    }

    // --- (b) the id table -----------------------------------------------------------------------

    private static final String[] ROLES = {
            "none", "trader", "follower", "bank", "transporter", "postman", "companion", "dialog"
    };

    private static final String[] JOBS = {
            "none", "bard", "healer", "guard", "item_giver", "follower", "spawner", "conversation",
            "chunk_loader", "puppet", "builder", "farmer"
    };

    private static final String[] RETALIATE = {"fight", "panic", "avoid", "none"};

    private static final String[] MOVING = {"stationary", "wander", "path"};

    private static final String[] NAVIGATION = {"ground", "flying", "swimming"};

    @Test
    void everyKnownIdRoundTripsToItsNamespacedTrait() {
        assertKind("role", ROLES, CustomNpcsIds::role);
        assertKind("job", JOBS, CustomNpcsIds::job);
        assertKind("retaliate", RETALIATE, CustomNpcsIds::retaliate);
        assertKind("moving", MOVING, CustomNpcsIds::moving);
        assertKind("navigation", NAVIGATION, CustomNpcsIds::navigation);
    }

    @Test
    void anIdOutsideTheTableIsNamedUnknownRatherThanGuessedAt() {
        assertEquals(new ResourceLocation("customnpcs", "job/unknown_99"), CustomNpcsIds.job(99));
        assertEquals(new ResourceLocation("customnpcs", "role/unknown_-1"), CustomNpcsIds.role(-1));
        assertEquals("unknown_99", CustomNpcsIds.jobName(99));
        assertEquals("unknown_99", CustomNpcsIds.roleName(99));
    }

    @Test
    void theKnownNameChecksAgreeWithTheTables() {
        for (String role : ROLES) {
            assertTrue(CustomNpcsIds.isKnownRoleName(role), role + " should be a known role");
        }
        for (String job : JOBS) {
            assertTrue(CustomNpcsIds.isKnownJobName(job), job + " should be a known job");
        }
        // "guard" is a job, not a role: a config list that mixes the two must be flagged, not accepted.
        assertFalse(CustomNpcsIds.isKnownRoleName("guard"));
        assertFalse(CustomNpcsIds.isKnownJobName("trader"));
        assertFalse(CustomNpcsIds.isKnownJobName("unknown_99"));
    }

    private static void assertKind(String kind, String[] names,
                                   java.util.function.IntFunction<ResourceLocation> lookup) {
        for (int i = 0; i < names.length; i++) {
            assertEquals(new ResourceLocation("customnpcs", kind + "/" + names[i]), lookup.apply(i),
                    kind + " id " + i);
        }
    }

    // --- (c) status transitions -----------------------------------------------------------------

    /**
     * One method, not five: {@link CustomNpcsCompat}'s status is process-global with no reset, so the
     * transitions are only meaningful as an ordered sequence. Splitting them would make the result
     * depend on JUnit's method order.
     *
     * <p>{@code init()} itself is not exercised — it calls {@code ModList.get()}, which does not exist
     * outside a Forge runtime — so the version gate is checked as the predicate {@code init} applies
     * rather than through {@code init}. The gate end-to-end is covered by the game tests.
     */
    @Test
    void theStatusMachineOnlyEverMovesTheWayItIsMeantTo() {
        assertSame(CustomNpcsCompat.Status.ABSENT, CustomNpcsCompat.status(),
                "nothing has been attempted yet, so the bridge must read as absent");

        CustomNpcsCompat.setDetectedVersionForTest("1.20.1.19990101");
        assertFalse(CustomNpcsCompat.SUPPORTED_VERSIONS.contains(CustomNpcsCompat.detectedVersion()),
                "an unrecognised build must not pass the version gate — that is what makes it "
                        + CustomNpcsCompat.Status.PRESENT_UNSUPPORTED);
        for (String supported : CustomNpcsCompat.SUPPORTED_VERSIONS) {
            CustomNpcsCompat.setDetectedVersionForTest(supported);
            assertTrue(CustomNpcsCompat.SUPPORTED_VERSIONS.contains(CustomNpcsCompat.detectedVersion()));
        }
        CustomNpcsCompat.setDetectedVersionForTest(null);

        CustomNpcsCompat.markProbeFailed("no usable API");
        assertSame(CustomNpcsCompat.Status.PROBE_FAILED, CustomNpcsCompat.status());

        CustomNpcsCompat.markActivePublicApi();
        assertSame(CustomNpcsCompat.Status.ACTIVE_PUBLIC_API, CustomNpcsCompat.status());
        CustomNpcsCompat.markActiveFull();
        assertSame(CustomNpcsCompat.Status.ACTIVE_FULL, CustomNpcsCompat.status());

        CustomNpcsCompat.markDegraded("AI repair is being skipped");
        assertSame(CustomNpcsCompat.Status.DEGRADED_AI_REPAIR, CustomNpcsCompat.status(),
                "a degraded bridge is losing goals silently; an active status must not mask it");
        CustomNpcsCompat.markActiveFull();
        assertSame(CustomNpcsCompat.Status.DEGRADED_AI_REPAIR, CustomNpcsCompat.status(),
                "markActiveFull only ever upgrades from ACTIVE_PUBLIC_API");
        CustomNpcsCompat.markActivePublicApi();
        assertSame(CustomNpcsCompat.Status.DEGRADED_AI_REPAIR, CustomNpcsCompat.status(),
                "a fault already decided the outcome; it must not be walked back");

        Throwable first = new IllegalStateException("the first failure");
        CustomNpcsCompat.markError("bridge stopped", first);
        assertSame(CustomNpcsCompat.Status.DISABLED_ERROR, CustomNpcsCompat.status());
        CustomNpcsCompat.markError("a later, less useful failure", new IllegalStateException("second"));
        assertSame(first, CustomNpcsCompat.firstFailure(), "the first throwable is the useful one");
        assertNotNull(CustomNpcsCompat.firstFailure());

        CustomNpcsCompat.markDegraded("too late");
        assertSame(CustomNpcsCompat.Status.DISABLED_ERROR, CustomNpcsCompat.status());
    }

    // --- (d) the neutral announcement layer ------------------------------------------------------

    /**
     * The event and signal layer must be usable, and loadable, with neither CustomNPCs nor the bridge
     * present. It is the surface another mod subscribes to, and the reason the bridge can be a leaf at
     * all: a listener that had to load {@code compat/customnpcs} to hear about a cast would defeat the
     * whole arrangement.
     */
    @Test
    void theCastAndSchoolEventsNameNeitherCustomNpcsNorTheBridge() throws IOException {
        Path root = mainClassesDir();
        List<String> offenders = new ArrayList<>();
        for (Path file : neutralAnnouncementClasses(root)) {
            String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            if (referencesCustomNpcs(file) || bytes.contains("compat/customnpcs")) {
                offenders.add(root.relativize(file).toString().replace('\\', '/'));
            }
        }
        assertFalse(neutralAnnouncementClasses(root).isEmpty(),
                "found no api/event or MagicNpcEvents classes to check — the scan is looking in the "
                        + "wrong place, which would make this test pass for the wrong reason");
        assertTrue(offenders.isEmpty(),
                "the neutral announcement layer must not name CustomNPCs or its bridge: " + offenders);
    }

    /** @return every {@code api/event/} class plus {@code core/caster/MagicNpcEvents*}. */
    private static List<Path> neutralAnnouncementClasses(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith("com/otectus/magicnpcs/api/event/")
                        || relative.startsWith("com/otectus/magicnpcs/core/caster/MagicNpcEvents")) {
                    out.add(file);
                }
            }
        }
        return out;
    }

    // --- (e) the leaf package touches only the public API ----------------------------------------

    /**
     * Inside the leaf package, only {@code noppes.npcs.api.*} may be named. Everything below that is
     * CustomNPCs internals: a community port with no stability promise, where a class that moved
     * between two builds with the same version string is a link error in someone else's event dispatch.
     *
     * <p>{@code CustomNpcsScriptGlobal} is the single exception, and it earns it by naming
     * {@code ScriptContainer} only as runtime-assembled string parts — so it must contain no
     * {@code Lnoppes/} descriptor at all, which is the stricter claim and the one checked here.
     */
    @Test
    void theLeafPackageNamesOnlyTheCustomNpcsPublicApi() throws IOException {
        Path root = mainClassesDir();
        Path leaf = root.resolve(ALLOWED_PREFIX);
        assumeTrue(Files.isDirectory(leaf), "the CustomNPCs leaf package was not compiled");
        List<String> offenders = new ArrayList<>();
        List<String> globalOffence = new ArrayList<>();
        try (Stream<Path> files = Files.walk(leaf)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                if (relative.contains("CustomNpcsScriptGlobal")) {
                    if (bytes.contains("Lnoppes/")) {
                        globalOffence.add(relative);
                    }
                    continue;
                }
                if (bytes.contains("noppes/npcs/controllers/") || namesSomethingOutsideTheApi(bytes)) {
                    offenders.add(relative);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "these leaf classes reach outside noppes.npcs.api, which no CustomNPCs build promises "
                        + "to keep stable: " + offenders);
        assertTrue(globalOffence.isEmpty(),
                "CustomNpcsScriptGlobal must reach ScriptContainer by assembled name only, so its class "
                        + "file must carry no CustomNPCs type descriptor: " + globalOffence);
    }

    /** @return true if any {@code noppes/npcs/} reference in these bytes is not under {@code api/}. */
    private static boolean namesSomethingOutsideTheApi(String bytes) {
        String needle = "noppes/npcs/";
        for (int at = bytes.indexOf(needle); at >= 0; at = bytes.indexOf(needle, at + 1)) {
            if (!bytes.startsWith("noppes/npcs/api/", at)) {
                return true;
            }
        }
        return false;
    }
}
