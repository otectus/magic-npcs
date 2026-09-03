package com.otectus.magicnpcs.compat.customnpcs;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import noppes.npcs.api.entity.IEntity;

import java.util.UUID;

/**
 * The object CustomNPCs scripts see as {@code MagicNPCs}. Installed by
 * {@link CustomNpcsScriptGlobal}; every method is one {@link CustomNpcsScriptApi} call with the
 * arguments a script engine can actually supply.
 *
 * <p>Public class, public methods, no generics in the signatures and no overloads: a script engine
 * reflects over this, and anything cleverer than a plain public method is either invisible to it or
 * ambiguous. Levels arrive as {@code double} because that is the only number type a script has.
 *
 * <p>Takes {@link IEntity} — the wrapper a script already holds — and unwraps it here, which is why
 * {@link CustomNpcsScriptApiIrons} can take a plain {@link Mob} and name no CustomNPCs type. An
 * argument that is not an NPC gets {@link CustomNpcsScriptApi.ResultCode#NOT_CUSTOMNPC} rather than a
 * script error.
 */
public class CustomNpcsScriptFacade {

    // --- reads ---------------------------------------------------------------------------------

    public CustomNpcsScriptApi.Result isCaster(IEntity<?> npc) {
        Mob mob = unwrap(npc);
        return mob == null ? notAnNpc() : api().isCaster(mob);
    }

    public CustomNpcsScriptApi.Result getSchool(IEntity<?> npc) {
        Mob mob = unwrap(npc);
        return mob == null ? notAnNpc() : api().getSchool(mob);
    }

    public CustomNpcsScriptApi.Result getLoadout(IEntity<?> npc) {
        Mob mob = unwrap(npc);
        return mob == null ? notAnNpc() : api().getLoadout(mob);
    }

    public CustomNpcsScriptApi.Result getMana(IEntity<?> npc) {
        Mob mob = unwrap(npc);
        return mob == null ? notAnNpc() : api().getMana(mob);
    }

    public CustomNpcsScriptApi.Result getMaxMana(IEntity<?> npc) {
        Mob mob = unwrap(npc);
        return mob == null ? notAnNpc() : api().getMaxMana(mob);
    }

    public CustomNpcsScriptApi.Result canCast(IEntity<?> npc, String spell, double level) {
        Mob mob = unwrap(npc);
        return mob == null ? notAnNpc() : api().canCast(mob, spell, (int) level);
    }

    public CustomNpcsScriptApi.Result why(IEntity<?> npc) {
        Mob mob = unwrap(npc);
        return mob == null ? notAnNpc() : api().why(mob);
    }

    // --- mutations -----------------------------------------------------------------------------

    public CustomNpcsScriptApi.Result setSchool(IEntity<?> npc, String school) {
        Mob mob = unwrap(npc);
        return mob == null ? notAnNpc() : api().setSchool(mob, school);
    }

    public CustomNpcsScriptApi.Result clearSchool(IEntity<?> npc) {
        Mob mob = unwrap(npc);
        return mob == null ? notAnNpc() : api().clearSchool(mob);
    }

    public CustomNpcsScriptApi.Result returnToAuto(IEntity<?> npc) {
        Mob mob = unwrap(npc);
        return mob == null ? notAnNpc() : api().returnToAuto(mob);
    }

    public CustomNpcsScriptApi.Result setCastingSuspended(IEntity<?> npc, boolean suspended) {
        Mob mob = unwrap(npc);
        return mob == null ? notAnNpc() : api().setCastingSuspended(mob, suspended);
    }

    /** @param target the entity to cast at, or {@code null} for a self-cast. */
    public CustomNpcsScriptApi.Result cast(IEntity<?> npc, String spell, double level, IEntity<?> target) {
        Mob mob = unwrap(npc);
        if (mob == null) {
            return notAnNpc();
        }
        UUID targetId = null;
        if (target != null) {
            Entity entity = target.getMCEntity();
            if (entity == null) {
                return CustomNpcsScriptApi.Result.no(CustomNpcsScriptApi.ResultCode.NO_TARGET,
                        "the target wrapper has no entity behind it any more");
            }
            targetId = entity.getUUID();
        }
        return api().cast(mob, spell, (int) level, targetId);
    }

    /** @return the {@link Mob} behind a script's wrapper, or {@code null} when there is not one. */
    private static Mob unwrap(IEntity<?> npc) {
        if (npc == null) {
            return null;
        }
        Entity entity = npc.getMCEntity();
        return entity instanceof Mob mob ? mob : null;
    }

    private static CustomNpcsScriptApi.Result notAnNpc() {
        return CustomNpcsScriptApi.Result.no(CustomNpcsScriptApi.ResultCode.NOT_CUSTOMNPC,
                "that argument is not an NPC Magic NPCs can act on");
    }

    private static CustomNpcsScriptApi api() {
        return CustomNpcsScriptBridge.api();
    }
}
