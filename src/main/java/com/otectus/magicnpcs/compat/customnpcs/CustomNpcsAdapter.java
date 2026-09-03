package com.otectus.magicnpcs.compat.customnpcs;

import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.compat.CustomNpcsCompat;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import noppes.npcs.api.NpcAPI;
import noppes.npcs.api.entity.ICustomNpc;
import noppes.npcs.api.entity.IEntityLiving;
import noppes.npcs.api.entity.IPlayer;
import noppes.npcs.api.entity.data.INPCAi;
import noppes.npcs.api.handler.data.IFaction;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CustomNPCs adapter: identifies a CustomNPC, states where it may stand, and translates its authored
 * configuration — faction, owner, role, job, AI modes — into the neutral vocabulary the casting path
 * speaks.
 *
 * <p>Imports CustomNPCs; classloaded only behind the reflective hop in {@link CustomNpcsCompat}, so
 * neither the class nor its imports are touched when CustomNPCs is absent or unsupported.
 *
 * <p>The rule throughout is that an authored NPC's own settings win. An NPC told to stand still is not
 * walked to a better firing angle, one told not to retaliate does not open fire, one talking to a
 * player does not cast mid-sentence, and one whose faction calls a player friendly does not throw a
 * fireball at them.
 */
public final class CustomNpcsAdapter implements NpcAdapter {

    /** This adapter's framework id, and the namespace its traits are published under. */
    private static final ResourceLocation FRAMEWORK_ID = new ResourceLocation("customnpcs", "npc");

    /** {@code IFaction.playerStatus}: 1 friendly, 0 neutral, -1 hostile. Only friendly is protective. */
    private static final int FRIENDLY = 1;

    /** CustomNPCs retaliate type 0. Any other value means panic, avoid, or never fight back. */
    private static final int RETALIATE_FIGHT = 0;

    /** CustomNPCs moving type 1. Type 0 stands still and type 2 walks an authored path. */
    private static final int MOVING_WANDER = 1;

    /** Roles whose whole point is to stay with a player; repositioning them fights their own AI. */
    private static final Set<String> ESCORT_ROLES = Set.of("follower", "companion");

    /** Config entries already complained about, so a typo is reported once and not once per decision. */
    private static final Set<String> WARNED_CONFIG_NAMES = ConcurrentHashMap.newKeySet();

    @Override
    public int priority() {
        return 100; // beat the generic owner/team adapter, as the other mod-specific adapters do
    }

    /**
     * Applies to a live server-side CustomNPC, and only while the bridge is actually running.
     */
    @Override
    public boolean appliesTo(Mob mob) {
        if (!isActive() || !MagicNpcsConfig.customNpcsBridgeEnabled()) {
            return false;
        }
        if (!(mob.level() instanceof ServerLevel) || !mob.isAddedToWorld()) {
            return false;
        }
        // getIEntity returns the NPC's own long-lived wrapper (a field on the entity), not a fresh
        // object, so this is a cheap identity check rather than an allocation per call.
        return NpcAPI.IsAvailable() && NpcAPI.Instance().getIEntity(mob) instanceof ICustomNpc<?>;
    }

    /** No mana scaling: CustomNPCs has no progression to scale by. */
    @Override
    public double manaScale(Mob mob) {
        return 1.0;
    }

    /** Always 0 — CustomNPCs NPCs have stats an author sets, not a level they earn. */
    @Override
    public int level(Mob mob) {
        return 0;
    }

    /**
     * Eligible for automatic school assignment only when {@code [schools.customnpcs]} says so, which
     * is off by default: an authored NPC should not silently acquire a random school.
     */
    @Override
    public boolean schoolAssignable(Mob mob) {
        return MagicNpcsConfig.SCHOOLS_CUSTOMNPCS_ENABLED.get();
    }

    /** The {@code [schools.customnpcs]} settings, so CustomNPCs is not rolled under another mod's rules. */
    @Override
    public SchoolRollPolicy schoolRollPolicy(Mob mob) {
        return new SchoolRollPolicy(
                MagicNpcsConfig.SCHOOLS_CUSTOMNPCS_ENABLED.get(),
                MagicNpcsConfig.SCHOOLS_CUSTOMNPCS_CASTER_CHANCE.get(),
                MagicNpcsConfig.SCHOOLS_CUSTOMNPCS_MIN_LEVEL.get(),
                MagicNpcsConfig.SCHOOLS_CUSTOMNPCS_MODE.get(),
                MagicNpcsConfig.SCHOOLS_CUSTOMNPCS_TYPE_SCHOOLS.get());
    }

    @Override
    public Optional<ResourceLocation> frameworkId() {
        return Optional.of(FRAMEWORK_ID);
    }

    // --- identity -----------------------------------------------------------------------------------

    /**
     * The NPC's live owner, read from CustomNPCs every call and never cached: clearing an owner in the
     * CustomNPCs GUI has to stop protecting that player immediately, and a persisted copy would not.
     */
    @Override
    public Optional<UUID> ownerId(Mob mob) {
        ICustomNpc<?> npc = npc(mob);
        if (npc == null) {
            return Optional.empty();
        }
        IEntityLiving<?> owner = npc.getOwner();
        LivingEntity entity = owner == null ? null : owner.getMCEntity();
        return entity == null ? Optional.empty() : Optional.of(entity.getUUID());
    }

    /** The NPC's authored configuration as ids: what it is, what it does, and how it behaves. */
    @Override
    public Set<ResourceLocation> traits(Mob mob) {
        ICustomNpc<?> npc = npc(mob);
        if (npc == null) {
            return Set.of();
        }
        Set<ResourceLocation> out = new LinkedHashSet<>(6);
        out.add(CustomNpcsIds.role(roleType(npc)));
        out.add(CustomNpcsIds.job(jobType(npc)));
        INPCAi ai = npc.getAi();
        if (ai != null) {
            out.add(CustomNpcsIds.retaliate(ai.getRetaliateType()));
            out.add(CustomNpcsIds.moving(ai.getMovingType()));
            out.add(CustomNpcsIds.navigation(ai.getNavigationType()));
        }
        IFaction faction = npc.getFaction();
        if (faction != null) {
            out.add(new ResourceLocation("customnpcs", "faction/" + faction.getId()));
        }
        return out;
    }

    // --- relationships ------------------------------------------------------------------------------

    /** Allies exist only while {@code respectFactions} is on; with it off there is nothing to consult. */
    @Override
    public boolean tracksAllies() {
        return MagicNpcsConfig.customNpcsRespectFactions();
    }

    /**
     * An ally is the NPC's owner, another NPC in the same faction, or a player this NPC's faction
     * considers friendly. Ownership is not a faction concept, so it holds even with
     * {@code respectFactions} off — turning faction rules off must not make an NPC shoot its owner.
     */
    @Override
    public boolean isAlly(Mob caster, LivingEntity other) {
        ICustomNpc<?> npc = npc(caster);
        if (npc == null || other == null || other == caster) {
            return false;
        }
        if (other instanceof Player && ownerId(caster).filter(other.getUUID()::equals).isPresent()) {
            return true;
        }
        if (!MagicNpcsConfig.customNpcsRespectFactions()) {
            return false;
        }
        IFaction faction = npc.getFaction();
        if (faction == null) {
            return false;
        }
        if (other instanceof Mob otherMob) {
            ICustomNpc<?> otherNpc = npc(otherMob);
            IFaction otherFaction = otherNpc == null ? null : otherNpc.getFaction();
            return otherFaction != null && otherFaction.getId() == faction.getId();
        }
        return other instanceof Player player && playerStatus(faction, player) == FRIENDLY;
    }

    /**
     * Refuse an offensive cast at anything this NPC would not have attacked anyway.
     *
     * <p>Two gates. An NPC whose retaliate type is not "fight" panics, flees, or ignores attacks — it
     * has no offensive AI at all, and giving it spells must not invent one. And an ally is never a
     * target. A player the faction is merely neutral about is neither: this returns true and leaves
     * the decision to the other adapters and the bystander rules.
     */
    @Override
    public boolean canCastAt(Mob caster, LivingEntity target) {
        ICustomNpc<?> npc = npc(caster);
        if (npc == null || target == null) {
            return true;
        }
        INPCAi ai = npc.getAi();
        if (ai != null && ai.getRetaliateType() != RETALIATE_FIGHT) {
            return false;
        }
        return !isAlly(caster, target);
    }

    // --- state --------------------------------------------------------------------------------------

    /**
     * Block casting for an NPC that is mid-conversation, has a role or job the pack has barred, or
     * belongs to a bridge whose goal repair is failing — in the last case its goals are being lost
     * anyway, and casting from a half-repaired caster is worse than not casting.
     */
    @Override
    public boolean canCastNow(Mob mob) {
        if (CustomNpcsCompat.status() == CustomNpcsCompat.Status.DEGRADED_AI_REPAIR) {
            return false;
        }
        if (MagicNpcsConfig.customNpcsPauseDuringDialog()
                && CustomNpcsActivityState.isDialogOpen(mob.getUUID())) {
            return false;
        }
        // A script suspension covers self-cast support too, because canSupportCastNow follows this
        // method by default: "stop casting during this scene" that still let an NPC heal itself would
        // not be the instruction the pack author gave.
        if (CustomNpcsActivityState.isScriptSuspended(mob.getUUID())) {
            return false;
        }
        ICustomNpc<?> npc = npc(mob);
        if (npc == null) {
            return true;
        }
        return !isBlocked(CustomNpcsIds.roleName(roleType(npc)),
                        MagicNpcsConfig.customNpcsBlockedRoles(), "role")
                && !isBlocked(CustomNpcsIds.jobName(jobType(npc)),
                        MagicNpcsConfig.customNpcsBlockedJobs(), "job");
    }

    /**
     * Hand the signal to this NPC's script. The one inbound path from
     * {@code core.caster.MagicNpcEvents}; nothing in this package listens for cast events on the Forge
     * bus, so a script hears about a cast exactly once.
     */
    @Override
    public boolean publish(Mob mob, com.otectus.magicnpcs.core.adapter.MagicNpcSignal signal) {
        ICustomNpc<?> npc = npc(mob);
        return npc != null && CustomNpcsScriptBridge.emit(npc, signal);
    }

    // --- movement -----------------------------------------------------------------------------------

    /**
     * A CustomNPC's position is authored, so the default is {@link MovementPolicy#PINNED}: it was put
     * somewhere, usually given a home and a standing type, and frequently exists to be talked to there.
     * The one exception is an NPC explicitly set to wander, which may be repositioned inside the
     * wandering range it was given, around its own home block.
     */
    @Override
    public MovementPolicy movementPolicy(Mob mob) {
        ICustomNpc<?> npc = npc(mob);
        if (npc == null) {
            return MovementPolicy.PINNED;
        }
        if (ESCORT_ROLES.contains(CustomNpcsIds.roleName(roleType(npc)))) {
            return MovementPolicy.PINNED; // its whole job is to be where its player is
        }
        INPCAi ai = npc.getAi();
        if (ai == null || ai.getMovingType() != MOVING_WANDER) {
            // A stationary NPC stays put; a path-following one is walking an authored route and must
            // not be pulled off it. Either way, no repositioning.
            return MovementPolicy.PINNED;
        }
        Vec3 home = new Vec3(npc.getHomeX() + 0.5, npc.getHomeY(), npc.getHomeZ() + 0.5);
        return MovementPolicy.anchored(home, ai.getWanderingRange());
    }

    // --- equipment ----------------------------------------------------------------------------------

    /** Equip through CustomNPCs' own inventory, or report failure so the caller uses vanilla instead. */
    @Override
    public boolean setHeldItem(Mob mob, InteractionHand hand, ItemStack stack) {
        ICustomNpc<?> npc = npc(mob);
        return npc != null && CustomNpcsInventoryBridge.setHeldItem(npc, hand, stack);
    }

    // --- helpers ------------------------------------------------------------------------------------

    /** @return the CustomNPC behind {@code mob}, or {@code null} when it is not one. */
    private static ICustomNpc<?> npc(Mob mob) {
        if (mob == null || !NpcAPI.IsAvailable()) {
            return null;
        }
        return NpcAPI.Instance().getIEntity(mob) instanceof ICustomNpc<?> npc ? npc : null;
    }

    private static int roleType(ICustomNpc<?> npc) {
        return npc.getRole() == null ? 0 : npc.getRole().getType();
    }

    private static int jobType(ICustomNpc<?> npc) {
        return npc.getJob() == null ? 0 : npc.getJob().getType();
    }

    /** @return the faction's verdict on {@code player}, or neutral (0) when it has no view of them. */
    private static int playerStatus(IFaction faction, Player player) {
        return NpcAPI.Instance().getIEntity(player) instanceof IPlayer<?> iPlayer
                ? faction.playerStatus(iPlayer)
                : 0;
    }

    /**
     * @param name    the NPC's own role or job name
     * @param blocked the configured block list
     * @param kind    {@code "role"} or {@code "job"}, for the warning
     * @return true if the pack has barred this name. A configured name this build does not know is a
     *         typo or a newer CustomNPCs build: it is reported once and then ignored, rather than
     *         silently blocking nothing or silently blocking everything.
     */
    private static boolean isBlocked(String name, java.util.List<? extends String> blocked, String kind) {
        boolean hit = false;
        for (String entry : blocked) {
            if (entry == null) {
                continue;
            }
            String trimmed = entry.trim().toLowerCase(java.util.Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean known = "role".equals(kind)
                    ? CustomNpcsIds.isKnownRoleName(trimmed)
                    : CustomNpcsIds.isKnownJobName(trimmed);
            if (!known) {
                if (WARNED_CONFIG_NAMES.add(kind + ':' + trimmed)) {
                    MagicNpcs.LOGGER.warn("[magicnpcs] customnpcs.blocked{}s lists '{}', which is not a "
                                    + "CustomNPCs {} this build knows. The entry does nothing.",
                            kind.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + kind.substring(1),
                            trimmed, kind);
                }
                continue;
            }
            if (trimmed.equals(name)) {
                hit = true;
            }
        }
        return hit;
    }

    private static boolean isActive() {
        return switch (CustomNpcsCompat.status()) {
            case ACTIVE_PUBLIC_API, ACTIVE_FULL, DEGRADED_AI_REPAIR -> true;
            default -> false;
        };
    }
}
