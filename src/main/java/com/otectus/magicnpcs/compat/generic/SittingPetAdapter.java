package com.otectus.magicnpcs.compat.generic;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;

/**
 * A tamed companion that has been ordered to sit does not cast.
 *
 * <p>Present in the 0.6.0 binary and absent from 0.6.1 (audit TGT-003). "Sit" is the one instruction a
 * player has for switching a companion off, so a sitting pet that keeps flinging spells is a pet the
 * player cannot stop — and unlike a missed damage tick, it is immediately visible and impossible to
 * work around.
 *
 * <p>Deliberately independent of {@code targeting.protectOwners}. In 0.6.0 the sitting check lived
 * inside the owner/team adapter, so switching owner protection off also switched this off; those are
 * different questions ("who may I hit?" versus "may I act at all?"). It is its own adapter now, and
 * since 0.6.2 every applicable adapter's state blockers are combined, so a companion mod's own adapter
 * applying to the same mob no longer displaces it.
 *
 * <p>Blocks support casting too: a sitting pet healing itself is still acting. The
 * {@code targeting.sittingPetsMayCast} option opts back in for packs that want turret pets.
 *
 * <p>Vanilla-only ({@link TamableAnimal}), so it covers companion mods built on the vanilla tameable
 * without importing them.
 */
public final class SittingPetAdapter implements NpcAdapter {

    @Override
    public int priority() {
        return -150; // below owner/team; priority only picks the mana-scaling provider now
    }

    @Override
    public boolean appliesTo(Mob mob) {
        return !MagicNpcsConfig.sittingPetsMayCast() && mob instanceof TamableAnimal;
    }

    @Override
    public boolean canCastNow(Mob mob) {
        return !((TamableAnimal) mob).isOrderedToSit();
    }

    @Override
    public boolean canSupportCastNow(Mob mob) {
        return canCastNow(mob);
    }
}
