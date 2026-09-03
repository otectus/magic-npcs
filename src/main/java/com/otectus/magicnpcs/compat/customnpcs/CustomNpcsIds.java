package com.otectus.magicnpcs.compat.customnpcs;

import com.otectus.magicnpcs.MagicNpcs;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place CustomNPCs' integer enums are turned into names.
 *
 * <p>CustomNPCs models role, job, and every AI mode as a bare {@code int} on its public API — there is
 * no enum to read and no name to ask for. Left as numbers those values are unusable in a diagnostic
 * report or a config list, and spread across the bridge they would be five copies of the same magic
 * table drifting apart. They are written down once, here.
 *
 * <p>Deliberately vanilla-only: no {@code noppes} import, so the table can be read and tested without
 * CustomNPCs present. The cost is that it is a transcription rather than a derivation, which is why an
 * id outside the table produces a named {@code unknown_<id>} value and a warning rather than a
 * silently wrong name — a CustomNPCs build that adds a job should be visible, not guessed at.
 */
public final class CustomNpcsIds {

    private static final String NAMESPACE = "customnpcs";

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

    /** {@code kind:id} pairs already warned about, so an unknown id is reported once, not every tick. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private CustomNpcsIds() {}

    public static ResourceLocation role(int id) {
        return id(ROLES, "role", id);
    }

    public static ResourceLocation job(int id) {
        return id(JOBS, "job", id);
    }

    public static ResourceLocation retaliate(int id) {
        return id(RETALIATE, "retaliate", id);
    }

    public static ResourceLocation moving(int id) {
        return id(MOVING, "moving", id);
    }

    public static ResourceLocation navigation(int id) {
        return id(NAVIGATION, "navigation", id);
    }

    /** @return the bare role name, for matching against the {@code blockedRoles} config list. */
    public static String roleName(int id) {
        return name(ROLES, "role", id);
    }

    /** @return the bare job name, for matching against the {@code blockedJobs} config list. */
    public static String jobName(int id) {
        return name(JOBS, "job", id);
    }

    /** @return true if {@code name} is a role this build knows, so a typo'd config entry can be flagged. */
    public static boolean isKnownRoleName(String name) {
        return contains(ROLES, name);
    }

    /** @return true if {@code name} is a job this build knows, so a typo'd config entry can be flagged. */
    public static boolean isKnownJobName(String name) {
        return contains(JOBS, name);
    }

    private static boolean contains(String[] table, String name) {
        for (String candidate : table) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static ResourceLocation id(String[] table, String kind, int value) {
        return new ResourceLocation(NAMESPACE, kind + "/" + name(table, kind, value));
    }

    private static String name(String[] table, String kind, int value) {
        if (value >= 0 && value < table.length) {
            return table[value];
        }
        if (WARNED.add(kind + ':' + value)) {
            MagicNpcs.LOGGER.warn("[magicnpcs] CustomNPCs reported an unknown {} id {}. This build of "
                            + "Magic NPCs knows {} ({}); the value is being reported as unknown_{}.",
                    kind, value, table.length, String.join(", ", table), value);
        }
        return "unknown_" + value;
    }
}
