package com.otectus.magicnpcs.core.caster;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Point a caster straight at what it is about to cast on, this tick.
 *
 * <p>{@code LookControl} defers its rotation until after the goal tick, so it cannot be relied on for
 * the cast frame: Iron's reads {@code getLookAngle()} inside {@code onCast} for projectile spells, and
 * its target-seeking pre-cast helpers ({@code Utils.preCastTargetHelper}) raycast along the caster's
 * <em>current</em> facing rather than reading any pre-installed target cast data. A caster that has not
 * turned yet therefore fires off-aim, or is refused outright.
 *
 * <p>Vanilla-only by design: this is geometry, and both the casting goal and the Iron's cast session
 * need it without either importing the other.
 */
public final class CasterFacing {

    private CasterFacing() {}

    /**
     * Force {@code mob}'s yaw and pitch (head and body) at {@code target}'s eyes immediately. The
     * {@code *O} previous-frame values are written too, so the snap does not show up as an
     * interpolation artifact on the client.
     */
    public static void snap(Mob mob, LivingEntity target) {
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        double dy = target.getEyeY() - mob.getEyeY();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        mob.setYRot(yaw);
        mob.yRotO = yaw;
        mob.yBodyRot = yaw;
        mob.yBodyRotO = yaw;
        mob.setYHeadRot(yaw);
        mob.yHeadRotO = yaw;
        mob.setXRot(pitch);
        mob.xRotO = pitch;
    }
}
