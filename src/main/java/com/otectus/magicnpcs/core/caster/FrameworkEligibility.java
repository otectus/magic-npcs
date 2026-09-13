package com.otectus.magicnpcs.core.caster;

import com.otectus.magicnpcs.compat.CustomNpcsCompat;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

/**
 * May Magic NPCs manage this mob at all, given the NPC framework it belongs to?
 *
 * <p>This is a different question from {@link SpellEligibility}, which asks whether a particular spell
 * suits a particular moment. This one is asked first and answered once per request, before anything is
 * written: before a school is assigned, before attributes or equipment are touched, before RNG is
 * consumed and before a cast is prepared.
 *
 * <p><b>Why it exists.</b> The CustomNPCs bridge refuses to activate against an unrecognised build —
 * {@link CustomNpcsCompat#SUPPORTED_VERSIONS} is a deliberate pin, because this is a community port
 * with no API stability promise. Through 0.9.0 that pin only gated the <em>adapter</em>. Adapter
 * resolution falls back to a generic default when no adapter applies, so a CustomNPC on an unsupported
 * build still reached the reconciler's assignment path and the manual school writer: the gate declined
 * to speak for the NPC and the mod managed it anyway (roadmap MN-002).
 *
 * <p><b>Neutral by construction.</b> The family is recognised from the entity type's namespace, which
 * is vanilla data. Nothing here imports, loads or names a {@code noppes} type, so this class is usable
 * from {@code core/} and readable with CustomNPCs absent — which is the point, since "absent" is one
 * of the answers it has to be able to give.
 */
public final class FrameworkEligibility {

    /** The namespace every CustomNPCs entity type is registered under. */
    private static final String CUSTOMNPCS_NAMESPACE = CustomNpcsCompat.MODID;

    private FrameworkEligibility() {}

    /** Why a mob's own NPC framework does or does not permit Magic NPCs to manage it. */
    public enum Decision {
        /** Either no framework claims this mob, or the one that does is supported and running. */
        ALLOWED,
        /** The framework is installed at a build this bridge is not pinned to. */
        UNSUPPORTED_FRAMEWORK,
        /** The build is supported but its bridge is not running — probe failure, or shut down. */
        BRIDGE_UNAVAILABLE,
        /** The operator switched this framework's integration off. */
        FRAMEWORK_DISABLED
    }

    /**
     * @param detail a human-readable reason, or {@code null} when allowed
     */
    public record Verdict(Decision decision, String detail) {

        private static final Verdict ALLOWED = new Verdict(Decision.ALLOWED, null);

        public boolean allowed() {
            return decision == Decision.ALLOWED;
        }
    }

    /** @return whether {@code mob}'s framework permits Magic NPCs to manage it right now. */
    public static Verdict check(Mob mob) {
        if (mob == null) {
            return new Verdict(Decision.BRIDGE_UNAVAILABLE, "no entity");
        }
        ResourceLocation type = EntityType.getKey(mob.getType());
        return decide(type == null ? null : type.getNamespace(), CustomNpcsCompat.status(),
                MagicNpcsConfig.customNpcsBridgeEnabled(), CustomNpcsCompat.detectedVersion());
    }

    /**
     * The decision itself, with no world and no mob: given the entity type's namespace, the bridge's
     * status and its config switch, may the mob be managed?
     *
     * <p>Split out so every branch can be asserted in a plain unit test. A gate whose refusals are only
     * reachable with three mods staged is a gate nobody checks.
     */
    public static Verdict decide(String entityNamespace, CustomNpcsCompat.Status status,
                                 boolean bridgeEnabled) {
        return decide(entityNamespace, status, bridgeEnabled, null);
    }

    /**
     * As {@link #decide(String, CustomNpcsCompat.Status, boolean)}, naming the build that was found.
     *
     * <p>The version is passed in rather than read here so this method touches nothing global: the
     * mod list does not exist in a unit test, and a decision that cannot be asserted without a running
     * game is a decision nobody asserts.
     *
     * @param detectedVersion the framework build actually installed, or {@code null} if unknown
     */
    public static Verdict decide(String entityNamespace, CustomNpcsCompat.Status status,
                                 boolean bridgeEnabled, String detectedVersion) {
        if (!CUSTOMNPCS_NAMESPACE.equals(entityNamespace)) {
            // Not a framework this mod gates on. Vanilla mobs, Recruits and Easy NPC are managed
            // through their adapters and have no exact-build pin to enforce.
            return Verdict.ALLOWED;
        }
        if (!bridgeEnabled) {
            return new Verdict(Decision.FRAMEWORK_DISABLED,
                    "the CustomNPCs integration is switched off (customnpcs.bridgeEnabled = false)");
        }
        return switch (status) {
            case ACTIVE_PUBLIC_API, ACTIVE_FULL, DEGRADED_AI_REPAIR -> Verdict.ALLOWED;
            case PRESENT_UNSUPPORTED -> new Verdict(Decision.UNSUPPORTED_FRAMEWORK,
                    "CustomNPCs " + (detectedVersion == null ? "(build unknown)" : detectedVersion)
                            + " is not one of the builds this bridge is pinned to ("
                            + String.join(", ", CustomNpcsCompat.SUPPORTED_VERSIONS) + ")");
            case PROBE_FAILED -> new Verdict(Decision.BRIDGE_UNAVAILABLE,
                    "the CustomNPCs API probe failed, so this NPC cannot be managed safely");
            case DISABLED_ERROR -> new Verdict(Decision.BRIDGE_UNAVAILABLE,
                    "the CustomNPCs bridge shut down after an error");
            case ABSENT -> new Verdict(Decision.BRIDGE_UNAVAILABLE,
                    "a CustomNPCs entity exists but the bridge reports the mod as absent");
        };
    }

    /**
     * {@code DEGRADED_AI_REPAIR} is deliberately allowed above: the NPC is managed, its goals are
     * simply at risk of being stripped, and refusing every request would remove casting from NPCs that
     * are working. It is called out here so the choice is a decision rather than an oversight.
     */
    public static boolean isDegradedButUsable(CustomNpcsCompat.Status status) {
        return status == CustomNpcsCompat.Status.DEGRADED_AI_REPAIR;
    }
}
