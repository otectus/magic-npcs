package com.otectus.magicnpcs.compat.easynpc;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import de.markusbordihn.easynpc.api.handler.EasyNPCPauseHandler;
import de.markusbordihn.easynpc.entity.easynpc.EasyNPC;
import de.markusbordihn.easynpc.entity.easynpc.data.FactionDataCapable;
import de.markusbordihn.easynpc.entity.easynpc.data.NavigationDataCapable;
import de.markusbordihn.easynpc.entity.easynpc.data.OwnerDataCapable;
import de.markusbordihn.easynpc.entity.easynpc.data.ProgressionDataCapable;
import de.markusbordihn.easynpc.handler.FactionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Easy NPC adapter. Scales mana by the NPC's Easy NPC experience level, routes ally/target decisions
 * through Easy NPC's own owner and faction data, and translates its navigation settings into the
 * movement latitude the caster-movement goal may use.
 *
 * <p>Imports Easy NPC; classloaded only behind {@code EasyNpcCompat.isLoaded()} via
 * {@link EasyNpcIntegration}.
 *
 * <p>Every {@code getEasyNPC*Data()} accessor is a {@code default} method that instanceof-checks and
 * returns {@code null} when the NPC does not carry that capability, so every call here is
 * null-guarded. An Easy NPC built from a raw entity type genuinely may have no faction or progression
 * data, and treating that as an error would make the adapter refuse to work on half the NPC types.
 */
public final class EasyNpcAdapter implements NpcAdapter {

    /**
     * Slack around an Easy NPC's home position. Enough to sidestep for a firing angle, not enough to
     * abandon the post — the same reasoning as the Recruits adapter's hold leash, because it is the
     * same situation: a player put this NPC somewhere on purpose.
     */
    private static final double HOME_LEASH = 6.0;

    @Override
    public int priority() {
        return 100; // beat the generic owner/team adapter when an Easy NPC is also Ownable
    }

    /**
     * Applies to every Easy NPC, <b>regardless of the {@code easynpc.enabled} toggle</b>.
     *
     * <p>Same rule as {@code RecruitsAdapter}: checking the toggle here would make turning the
     * integration off actively dangerous. A datapack loadout naming an {@code easy_npc:} type would
     * still apply, so the NPC would keep casting — but this adapter would no longer resolve, and the
     * fallback default answers {@code canCastAt = true} / {@code isAlly = false}. "Disable the Easy
     * NPC integration" would therefore strip owner and faction protection while leaving the spells
     * switched on. The toggle suppresses <em>casting</em> ({@link #canCastNow}) and level scaling
     * instead; the safety logic is never removed.
     */
    @Override
    public boolean appliesTo(Mob mob) {
        return mob instanceof EasyNPC<?>;
    }

    @Override
    public boolean canCastNow(Mob mob) {
        if (!MagicNpcsConfig.EASYNPC_INTEGRATION_ENABLED.get()) {
            return false; // integration off: Easy NPCs do not cast at all (but stay protected)
        }
        // Easy NPC's pause is an explicit "this NPC is doing nothing right now" from a player or an
        // operator — freezing the model but leaving it slinging fireballs would be absurd.
        return !EasyNPCPauseHandler.isPaused((EasyNPC<?>) mob);
    }

    /**
     * A paused NPC may not self-heal either — unlike a Recruits "passive" order, which means <em>do
     * not fight</em> and is precisely when a recruit needs to recover, an Easy NPC pause means
     * <em>do nothing</em>.
     */
    @Override
    public boolean canSupportCastNow(Mob mob) {
        return MagicNpcsConfig.EASYNPC_INTEGRATION_ENABLED.get()
                && !EasyNPCPauseHandler.isPaused((EasyNPC<?>) mob);
    }

    @Override
    public double manaScale(Mob mob) {
        if (!MagicNpcsConfig.EASYNPC_INTEGRATION_ENABLED.get()) {
            return 1.0; // no level scaling when the integration is off
        }
        return 1.0 + level(mob) * MagicNpcsConfig.EASYNPC_MANA_PER_LEVEL.get();
    }

    @Override
    public int level(Mob mob) {
        ProgressionDataCapable<?> progression = ((EasyNPC<?>) mob).getEasyNPCProgressionData();
        return progression == null ? 0 : Math.max(0, progression.getExperienceLevel());
    }

    /**
     * Eligible for the progression branch of school assignment. Whether an Easy NPC actually rolls a
     * school is decided by {@code [schools.easynpc]}, which is off by default.
     */
    @Override
    public boolean schoolAssignable(Mob mob) {
        return true;
    }

    /**
     * The {@code [schools.easynpc]} settings. Without this an Easy NPC would be rolled under Villager
     * Recruits' caster chance and rank threshold purely because both adapters answer
     * {@link #schoolAssignable} true.
     */
    @Override
    public SchoolRollPolicy schoolRollPolicy(Mob mob) {
        return new SchoolRollPolicy(
                MagicNpcsConfig.SCHOOLS_EASYNPC_ENABLED.get(),
                MagicNpcsConfig.SCHOOLS_EASYNPC_CASTER_CHANCE.get(),
                MagicNpcsConfig.SCHOOLS_EASYNPC_MIN_LEVEL.get(),
                MagicNpcsConfig.SCHOOLS_EASYNPC_MODE.get(),
                MagicNpcsConfig.SCHOOLS_EASYNPC_TYPE_SCHOOLS.get());
    }

    @Override
    public boolean canCastAt(Mob caster, LivingEntity target) {
        return !isAlly(caster, target);
    }

    @Override
    public boolean tracksAllies() {
        return true;
    }

    /**
     * An Easy NPC's owner, a fellow NPC of the same owner, and a fellow NPC of the same faction.
     *
     * <p><b>Deliberately not {@code FactionHandler.isHostile(...)} as the permission to cast.</b> That
     * predicate returns {@code false} for a faction-less NPC, for a faction-less target, and whenever
     * no hostile relation has been configured — so using it as the gate would mean a freshly created
     * Easy NPC could never cast at anything, which is the "silently never casts" defect class this
     * codebase already has a backlog for (B7). Faction data is used here only to <em>protect</em>.
     * Explicit hostility still gets the last word: an entity the faction system calls hostile is not
     * an ally, whatever group name it happens to share.
     */
    @Override
    public boolean isAlly(Mob caster, LivingEntity other) {
        if (other == null || other == caster) {
            return false;
        }
        EasyNPC<?> npc = (EasyNPC<?>) caster;
        OwnerDataCapable<?> ownerData = npc.getEasyNPCOwnerData();
        if (ownerData != null) {
            if (ownerData.isNPCOwnedBy(other)) {
                return true; // the owning player
            }
            // Compare owner UUIDs rather than resolved owners: getOwner() is null while the owning
            // player is offline, which is exactly when a pack of NPCs would otherwise blast each
            // other (the same defect the generic owner/team adapter was fixed for in B14).
            UUID ownerId = ownerData.getOwnerUUID();
            if (ownerId != null && other instanceof OwnableEntity ownable
                    && ownerId.equals(ownable.getOwnerUUID())) {
                return true; // a sibling NPC belonging to the same owner
            }
        }
        return sameFaction(npc, other);
    }

    /** @return true if {@code other} shares this NPC's faction and is not explicitly hostile to it. */
    private static boolean sameFaction(EasyNPC<?> npc, LivingEntity other) {
        if (!MagicNpcsConfig.EASYNPC_RESPECT_FACTIONS.get()) {
            return false;
        }
        FactionDataCapable<?> factionData = npc.getEasyNPCFactionData();
        if (factionData == null || !factionData.hasFactionName()) {
            return false;
        }
        String faction = factionData.getFactionName();
        if (FactionHandler.isHostile(faction, other)) {
            return false; // a configured hostile relation beats a shared group name
        }
        return faction.equals(FactionHandler.getTargetGroupName(other));
    }

    /**
     * Translate Easy NPC's navigation settings into movement latitude for the caster-movement goal, so
     * a casting NPC repositions like a ranged unit without wandering off the spot it was placed on.
     *
     * <p>A home position is an anchor rather than a pin: Easy NPC lets such an NPC roam and return, so
     * a few blocks of slack to find a firing angle is within what its own AI would already do. An
     * immovable NPC is {@link MovementPolicy#PINNED} — that flag exists precisely to say "this one
     * does not walk", and a decorative shopkeeper sliding across its shop to line up a fireball is the
     * exact failure the policy was added to prevent.
     */
    @Override
    public MovementPolicy movementPolicy(Mob mob) {
        NavigationDataCapable<?> navigation = ((EasyNPC<?>) mob).getEasyNPCNavigationData();
        if (navigation == null) {
            return MovementPolicy.FREE;
        }
        if (navigation.isImmovable()) {
            return MovementPolicy.PINNED;
        }
        if (navigation.hasHomePosition()) {
            BlockPos home = navigation.getHomePosition();
            if (home != null) {
                return MovementPolicy.anchored(Vec3.atCenterOf(home), HOME_LEASH);
            }
        }
        return MovementPolicy.FREE;
    }
}
