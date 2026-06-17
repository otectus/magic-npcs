package com.otectus.magicnpcs.compat.generic;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.scores.Team;

/**
 * Generic, jar-free friendly-fire protection for owned/team NPCs. Uses only
 * vanilla concepts — {@link OwnableEntity} (which {@code TamableAnimal} and many
 * companion/pet/follower NPCs implement) and scoreboard {@link Team}s — so it
 * extends safety to NPC mods we cannot compile against (Human Companions and other
 * tamable-based followers, plus any mod that puts its NPCs on a team) without
 * importing them.
 *
 * <p>Lowest priority ({@code -100}): a mod-specific adapter (e.g. Recruits) always
 * wins when both apply. Registered unconditionally from the mod entrypoint; gated
 * at runtime by {@code targeting.protectOwners}.
 */
public final class OwnableTeamAdapter implements NpcAdapter {

    @Override
    public int priority() {
        return -100;
    }

    @Override
    public boolean appliesTo(Mob mob) {
        return MagicNpcsConfig.PROTECT_OWNERS.get()
                && (mob instanceof OwnableEntity || mob.getTeam() != null);
    }

    @Override
    public boolean canCastAt(Mob caster, LivingEntity target) {
        return !isAlly(caster, target);
    }

    @Override
    public boolean tracksAllies() {
        return true;
    }

    @Override
    public boolean isAlly(Mob caster, LivingEntity other) {
        if (other == caster) {
            return true;
        }
        // Shared scoreboard team → allies (covers team-based NPC mods and the owner if teamed).
        Team team = caster.getTeam();
        if (team != null && other.getTeam() == team) {
            return true;
        }
        if (caster instanceof OwnableEntity owned) {
            LivingEntity owner = owned.getOwner();
            if (owner != null) {
                if (other == owner) {
                    return true; // never harm our owner
                }
                // A sibling NPC owned by the same player.
                if (other instanceof OwnableEntity otherOwned && otherOwned.getOwnerUUID() != null
                        && otherOwned.getOwnerUUID().equals(owned.getOwnerUUID())) {
                    return true;
                }
            }
        }
        return false;
    }
}
