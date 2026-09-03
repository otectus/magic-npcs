package com.otectus.magicnpcs.compat.customnpcs;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.compat.CustomNpcsCompat;
import com.otectus.magicnpcs.config.MagicNpcsConfig;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Puts a {@code MagicNPCs} global into every CustomNPCs script, so a script can call the bridge
 * directly instead of going through the mailbox.
 *
 * <p><b>Reflective on purpose, and it must stay that way.</b> The globals map lives on
 * {@code ScriptContainer}, which is CustomNPCs <em>internals</em> — not the {@code noppes.npcs.api}
 * surface everything else in this package is compiled against. Naming that class in code would put a
 * constant-pool reference to it in this class file, and a CustomNPCs build that moved or renamed it
 * would then fail to link the bridge rather than fail to install one optional convenience. So the name
 * is assembled from parts at runtime and there is no {@code Lnoppes/} token in this class file at all —
 * an invariant the isolation test checks, because it is the sort of thing a later "tidy-up" would undo.
 *
 * <p>Gated twice: on {@code customnpcs.scriptGlobalEnabled}, and on the detected CustomNPCs version
 * being one of the pinned ones. Reaching into another mod's internals is only defensible when the
 * build is the exact one it was checked against.
 *
 * <p>Failure is not an error. The mailbox and the triggers work without this, so a
 * {@link ReflectiveOperationException}, a {@link LinkageError} or a {@link ClassCastException} produces
 * one WARN and leaves the bridge at {@code ACTIVE_PUBLIC_API}. Success is what promotes it to
 * {@code ACTIVE_FULL} — which is exactly what that status is for.
 */
public final class CustomNpcsScriptGlobal {

    /** The name scripts see: {@code MagicNPCs.getSchool(npc)}. */
    public static final String GLOBAL_NAME = "MagicNPCs";

    /**
     * {@code noppes.npcs.controllers.ScriptContainer}, in pieces. Joined at runtime so the class file
     * carries no reference to it — see the class comment.
     */
    private static final String[] CLASS_NAME_PARTS = {"noppes", "npcs", "controllers", "ScriptContainer"};

    /** The {@code public static final HashMap<String, Object>} of script globals. */
    private static final String FIELD_NAME = "Data";

    private static volatile boolean installed;
    private static volatile String failure = "";

    private CustomNpcsScriptGlobal() {}

    /**
     * Install the global, if this build allows it.
     *
     * <p>Called last in {@link CustomNpcsIntegration#init}: everything a script could reach through the
     * global has to already be wired, or the first script to run in the world join tick would call into
     * a half-built bridge.
     */
    public static void install() {
        if (!MagicNpcsConfig.customNpcsScriptGlobalEnabled()) {
            failure = "disabled by customnpcs.scriptGlobalEnabled";
            return;
        }
        String version = CustomNpcsCompat.detectedVersion();
        if (version == null || !CustomNpcsCompat.SUPPORTED_VERSIONS.contains(version)) {
            failure = "CustomNPCs " + version + " is not a pinned build; the script global reaches "
                    + "into internals and is only installed on a build it was verified against";
            return;
        }
        try {
            globals().put(GLOBAL_NAME, new CustomNpcsScriptFacade());
            installed = true;
            failure = "";
            // Only now is the whole feature set live, which is what ACTIVE_FULL means.
            CustomNpcsCompat.markActiveFull();
            MagicNpcs.LOGGER.info("[magicnpcs] The '{}' script global is available to CustomNPCs scripts.",
                    GLOBAL_NAME);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException ex) {
            failure = ex.toString();
            MagicNpcs.LOGGER.warn("[magicnpcs] The CustomNPCs script global could not be installed ({}). "
                    + "Script triggers and the mailbox are unaffected; only the direct '{}' object is "
                    + "missing.", ex, GLOBAL_NAME);
        }
    }

    /** Take the global back out. Leaving a facade behind after shutdown would outlive its own bridge. */
    public static void uninstall() {
        if (!installed) {
            return;
        }
        installed = false;
        try {
            globals().remove(GLOBAL_NAME);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException ex) {
            MagicNpcs.LOGGER.warn("[magicnpcs] The CustomNPCs script global could not be removed: {}", ex);
        }
    }

    /** @return whether scripts currently have the global. */
    public static boolean installed() {
        return installed;
    }

    /** @return why the global is not installed, or {@code ""} when it is. */
    public static String failure() {
        return failure;
    }

    /** @return CustomNPCs' script globals map, reached by name only. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> globals() throws ReflectiveOperationException {
        Class<?> container = Class.forName(String.join(".", CLASS_NAME_PARTS));
        Field field = container.getField(FIELD_NAME);
        return (Map<String, Object>) field.get(null);
    }
}
