package com.otectus.magicnpcs.core.util;

import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Friendly-fire line-of-fire check: is the corridor from {@code caster} to
 * {@code target} clear of adapter-defined allies, within {@code radius}? Used by
 * the universal goal before an ATTACK cast when the adapter
 * {@link NpcAdapter#tracksAllies() tracks allies}. Pure geometry — no Iron's, no
 * mod imports.
 */
public final class LineOfFire {
    private LineOfFire() {}

    /** @return true if no ally sits within {@code radius} of the caster→target segment or the impact point. */
    public static boolean clear(Mob caster, LivingEntity target, double radius, NpcAdapter adapter) {
        Vec3 from = caster.getEyePosition();
        Vec3 to = target.getEyePosition();
        AABB corridor = new AABB(from, to).inflate(radius + 1.0);
        double r2 = radius * radius;
        List<LivingEntity> nearby = caster.level().getEntitiesOfClass(LivingEntity.class, corridor);
        for (LivingEntity e : nearby) {
            if (e == caster || e == target || !e.isAlive()) {
                continue;
            }
            if (!adapter.isAlly(caster, e)) {
                continue;
            }
            if (distanceToSegmentSqr(e.position(), from, to) <= r2 || e.distanceToSqr(to) <= r2) {
                return false; // ally in the path or near the impact point
            }
        }
        return true;
    }

    private static double distanceToSegmentSqr(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double abLen2 = ab.lengthSqr();
        if (abLen2 < 1.0E-6) {
            return p.distanceToSqr(a);
        }
        double t = Math.max(0.0, Math.min(1.0, p.subtract(a).dot(ab) / abLen2));
        return p.distanceToSqr(a.add(ab.scale(t)));
    }
}
