package com.otectus.magicnpcs.compat;

import com.otectus.magicnpcs.MagicNpcs;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;

import java.util.Set;

/**
 * Single source of truth for whether the CustomNPCs bridge is available, and for what state it is in.
 * Mirrors {@link EasyNpcCompat}, with two differences that CustomNPCs forces.
 *
 * <p>First, this class is <b>neutral</b>: it names no CustomNPCs type at all, not even as a class
 * literal, and reaches the typed integration only by name through {@link Class#forName}. Every other
 * compat guard here can afford a direct call because its leaf package is only touched behind the
 * guard; this one additionally has to survive a CustomNPCs build whose API shape is not the one this
 * was compiled against, which is a link error rather than an absent class.
 *
 * <p>Second, presence is not enough. CustomNPCs for 1.20.1 is a community port with no API stability
 * promise, so activation is gated on {@link #SUPPORTED_VERSIONS} as well: an unrecognised build is
 * reported once, loudly, and then left alone.
 */
public final class CustomNpcsCompat {

    public static final String MODID = "customnpcs";

    /**
     * The CustomNPCs builds this integration has actually been compiled and tested against. A Java
     * constant, not a config key: allowing an operator to declare an untested build supported would
     * turn a clear "unsupported" message into an unexplained crash.
     */
    public static final Set<String> SUPPORTED_VERSIONS = Set.of("1.20.1.20260711");

    /** What the bridge is doing, in the order it can progress through. */
    public enum Status {
        /** CustomNPCs is not installed. Nothing was attempted. */
        ABSENT,
        /** CustomNPCs is installed, but its version is not on {@link #SUPPORTED_VERSIONS}. */
        PRESENT_UNSUPPORTED,
        /** The API probe failed: the classes or entry points this bridge needs are not there. */
        PROBE_FAILED,
        /** The public-API surface is live: adapter, event bridge and diagnostics are registered. */
        ACTIVE_PUBLIC_API,
        /** Everything above, plus the optional deeper features, are live. */
        ACTIVE_FULL,
        /** Running, but AI repair is failing or being skipped, so casters may lose their goals. */
        DEGRADED_AI_REPAIR,
        /** Shut down after an error. Nothing of the bridge is running. */
        DISABLED_ERROR
    }

    private static final String INTEGRATION_CLASS =
            "com.otectus.magicnpcs.compat.customnpcs.CustomNpcsIntegration";

    private static volatile Boolean cached;
    private static volatile String versionOverride;
    private static volatile Status status = Status.ABSENT;
    private static volatile String statusDetail = "";
    private static volatile Throwable firstFailure;
    private static volatile boolean initialised;

    private CustomNpcsCompat() {}

    public static boolean isLoaded() {
        Boolean c = cached;
        if (c != null) {
            return c;
        }
        synchronized (CustomNpcsCompat.class) {
            if (cached == null) {
                cached = ModList.get().isLoaded(MODID);
            }
            return cached;
        }
    }

    /** @return the CustomNPCs version Forge reports, or {@code null} when it is not installed. */
    public static String detectedVersion() {
        String override = versionOverride;
        if (override != null) {
            return override;
        }
        return ModList.get().getModContainerById(MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse(null);
    }

    /**
     * Force the version {@link #detectedVersion()} reports. For tests only: the version gate is the
     * whole reason this class exists, and it cannot be exercised without being able to state a
     * version that is not the one in the dev environment.
     */
    static void setDetectedVersionForTest(String version) {
        versionOverride = version;
    }

    /**
     * Detect, version-check, and — only then — reflectively start the typed integration.
     *
     * @param modBus the mod event bus, passed through to the integration for its own registrations
     */
    public static void init(IEventBus modBus) {
        if (!isLoaded()) {
            status = Status.ABSENT;
            MagicNpcs.LOGGER.debug("[magicnpcs] CustomNPCs is not installed; bridge not started.");
            return;
        }
        String version = detectedVersion();
        if (version == null || !SUPPORTED_VERSIONS.contains(version)) {
            status = Status.PRESENT_UNSUPPORTED;
            statusDetail = "detected " + version;
            // Exactly one ERROR line, naming everything needed to act on it. A per-tick or per-NPC
            // complaint about an unsupported build would bury the one message that matters.
            MagicNpcs.LOGGER.error("[magicnpcs] CustomNPCs {} is installed, but this build of Magic NPCs "
                            + "supports only {}. The CustomNPCs bridge is off: NPCs will not cast and their "
                            + "AI will not be repaired. Run /magicnpcs config for the current status.",
                    version, String.join(", ", SUPPORTED_VERSIONS));
            return;
        }
        try {
            Class.forName(INTEGRATION_CLASS).getMethod("init", IEventBus.class).invoke(null, modBus);
            initialised = true;
        } catch (ReflectiveOperationException | LinkageError ex) {
            markError("the CustomNPCs integration could not be started", ex);
        }
    }

    /** Tear the bridge down when the server stops. No-op unless {@link #init} actually started it. */
    public static void shutdown() {
        if (!initialised) {
            return;
        }
        try {
            Class.forName(INTEGRATION_CLASS).getMethod("shutdown").invoke(null);
        } catch (ReflectiveOperationException | LinkageError ex) {
            markError("the CustomNPCs integration could not be shut down cleanly", ex);
        } finally {
            initialised = false;
        }
    }

    public static Status status() {
        return status;
    }

    /** @return the first throwable that took the bridge down, or {@code null}. */
    public static Throwable firstFailure() {
        return firstFailure;
    }

    /** The probe found the API shape unusable. Neutral setter, so the leaf never assigns status itself. */
    public static void markProbeFailed(String detail) {
        status = Status.PROBE_FAILED;
        statusDetail = detail;
    }

    /** The public-API surface is registered and running. */
    public static void markActivePublicApi() {
        if (status == Status.DEGRADED_AI_REPAIR || status == Status.DISABLED_ERROR) {
            return; // a fault already decided the outcome; do not walk it back
        }
        status = Status.ACTIVE_PUBLIC_API;
        statusDetail = "";
    }

    /** Promote to the full feature set. Only ever from {@link Status#ACTIVE_PUBLIC_API}. */
    public static void markActiveFull() {
        if (status == Status.ACTIVE_PUBLIC_API) {
            status = Status.ACTIVE_FULL;
        }
    }

    /** AI repair is not working. Overrides either active status, because casters are silently losing goals. */
    public static void markDegraded(String detail) {
        if (status == Status.DISABLED_ERROR) {
            return;
        }
        status = Status.DEGRADED_AI_REPAIR;
        statusDetail = detail;
    }

    /** The bridge has stopped after a fault. Retains the <em>first</em> throwable, which is the useful one. */
    public static void markError(String detail, Throwable cause) {
        status = Status.DISABLED_ERROR;
        statusDetail = detail;
        if (firstFailure == null) {
            firstFailure = cause;
        }
        MagicNpcs.LOGGER.error("[magicnpcs] CustomNPCs bridge disabled: {}", detail, cause);
    }

    /** One line for {@code /magicnpcs config}: the status, and whatever explains it. */
    public static String statusLine() {
        if (!isLoaded()) {
            return "absent";
        }
        StringBuilder out = new StringBuilder(status.name().toLowerCase(java.util.Locale.ROOT));
        String version = detectedVersion();
        out.append(" (").append(version == null ? "unknown version" : version).append(')');
        if (!statusDetail.isEmpty()) {
            out.append(" — ").append(statusDetail);
        }
        return out.toString();
    }
}
