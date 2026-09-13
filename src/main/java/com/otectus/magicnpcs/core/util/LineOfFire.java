package com.otectus.magicnpcs.core.util;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Friendly-fire line-of-fire check: is the corridor from {@code caster} to its target clear of
 * adapter-defined allies and protected bystanders? Used by the universal goal before an ATTACK cast.
 * Pure geometry — no Iron's, no mod imports.
 *
 * <p><b>Scan once per decision, test per spell.</b> Before 0.6.0 the whole check — corridor AABB plus an
 * unfiltered {@code getEntitiesOfClass} — ran inside the per-spell loop, so a six-spell loadout did six
 * full entity scans per decision (backlog B12). {@link #scan} now collects the protected entities once,
 * at the largest {@code safety_radius} in the loadout, and {@link Scan#clearAt(double)} answers each
 * spell from that snapshot.
 *
 * <p><b>Bystanders exclude the fight.</b> The caster's own target is never protected, and players are
 * only protected when {@code targeting.protectBystanderPlayers} is on (off by default since 0.6.0):
 * treating every player as a bystander meant a hostile caster fighting player A silently never cast
 * while player B stood anywhere near the firing line (backlog B7).
 */
public final class LineOfFire {
    private LineOfFire() {}

    /**
     * The shape a spell's danger actually takes, so {@code safety_radius} means the same thing for a
     * projectile and for a stomp.
     *
     * <p>0.6.1 treated every spell as a straight caster→target corridor. That is right for a magic
     * missile and wrong for the two other shapes Magic NPCs actually ships support for: a forward
     * ground AoE lands in front of the <em>caster</em>, not along the line to the target, and a
     * target-area spell detonates around the <em>target</em>. Measuring both against a corridor
     * over-blocks the first (an ally behind the target vetoes a cast that could never reach it) and
     * under-blocks the second (an ally beside the target is nowhere near the line but squarely inside
     * the blast). The audit calls for the geometry to come from the spell's capability rather than from
     * one scalar; this is that split, kept to the shapes the manifest can actually distinguish.
     */
    public enum Geometry {
        /** Straight line from the caster's eyes to the target's: projectiles, rays, bolts. */
        CORRIDOR,
        /** A sphere around the impact point: target-area and blast spells. */
        TARGET_BLAST,
        /** A sphere around the caster: forward ground AoE, self-centred bursts. */
        CASTER_AOE
    }

    /** An empty scan: nothing is ever blocked. Shared, so the "no protection configured" path allocates nothing. */
    public static final Scan CLEAR = new Scan(List.of(), Vec3.ZERO, Vec3.ZERO);

    /**
     * The protected entities near the caster→target corridor, captured once per decision.
     *
     * <p>Blockers are stored as their <b>bounding boxes</b>, not their positions. Before 0.6.1 they
     * were stored as {@code Entity#position()} — the entity's <em>feet</em> — and compared against a
     * segment drawn between two <em>eye</em> positions. A villager standing squarely in the line of
     * fire measured its own eye height (~1.62) away from that segment, so the default
     * {@code safety_radius} of 1.5 never tripped: {@code friendlyFireCheck}, {@code protectBystanders},
     * {@code protectOwners} and the Recruits ally logic all silently passed at their shipped settings,
     * and {@code /magicnpcs why} agreed with the wrong answer because it repeated the same arithmetic.
     *
     * @param blockers bounding boxes of protected entities inside the widened corridor box
     * @param from     the caster's eye position
     * @param to       the target's eye position (the impact point)
     */
    public record Scan(List<AABB> blockers, Vec3 from, Vec3 to) {

        /**
         * @return true if no protected entity's body comes within {@code radius} of the firing line.
         *         Equivalent to {@code clearAt(radius, Geometry.CORRIDOR)}; kept for callers and tests
         *         written before geometry was a parameter.
         */
        public boolean clearAt(double radius) {
            return clearAt(radius, Geometry.CORRIDOR);
        }

        /** @return true if no protected entity's body lies inside {@code geometry} at {@code radius}. */
        public boolean clearAt(double radius, Geometry geometry) {
            if (blockers.isEmpty()) {
                return true;
            }
            double r2 = radius * radius;
            for (AABB box : blockers) {
                if (distanceSqr(box, from, to, geometry) <= r2) {
                    return false;
                }
            }
            return true;
        }

        /** @return true if this scan found nothing to protect (so no spell can be blocked by it). */
        public boolean isEmpty() {
            return blockers.isEmpty();
        }
    }

    /**
     * Collect the protected entities around the caster→target corridor once.
     *
     * @param maxRadius the largest {@code safety_radius} any candidate spell uses this decision
     */
    public static Scan scan(Mob caster, LivingEntity target, double maxRadius, NpcAdapter adapter) {
        Vec3 from = caster.getEyePosition();
        Vec3 to = target.getEyePosition();
        List<LivingEntity> nearby = protectedNear(caster, target, maxRadius, adapter);
        if (nearby.isEmpty()) {
            return CLEAR;
        }
        List<AABB> blockers = new ArrayList<>(nearby.size());
        for (LivingEntity e : nearby) {
            blockers.add(e.getBoundingBox());
        }
        return new Scan(blockers, from, to);
    }

    /**
     * The protected entities in the widened caster→target corridor. Single source of truth for
     * "who counts as protected", shared by {@link #scan} and {@link #firstBlocker} so the
     * {@code /magicnpcs why} diagnostic can never disagree with the check it is explaining.
     */
    private static List<LivingEntity> protectedNear(Mob caster, LivingEntity target,
                                                    double radius, NpcAdapter adapter) {
        AABB corridor = new AABB(caster.getEyePosition(), target.getEyePosition()).inflate(radius + 1.0);
        boolean protectBystanders = MagicNpcsConfig.PROTECT_BYSTANDERS.get();
        boolean protectPlayers = MagicNpcsConfig.PROTECT_TARGETED_PLAYERS.get();
        // Filter inside getEntitiesOfClass rather than materialising every LivingEntity first.
        return caster.level().getEntitiesOfClass(LivingEntity.class, corridor,
                e -> e != caster && e != target && e.isAlive()
                        && (adapter.isAlly(caster, e)
                            || (protectBystanders && isProtectedBystander(e, protectPlayers))));
    }

    /**
     * Generic, mod-agnostic "do not blast the townsfolk" check. Covers vanilla and most NPC mods'
     * civilians without importing them: any villager (vanilla, MCA, More Villagers and VillagersPlus all
     * extend {@link AbstractVillager}), iron golems, tamed pets, and — only when
     * {@code protectBystanderPlayers} is on — players.
     */
    private static boolean isProtectedBystander(LivingEntity e, boolean protectPlayers) {
        if (e instanceof Player player) {
            // A spectator cannot be hit and a creative player cannot be hurt, so neither is a reason
            // to withhold a cast — an admin flying through a fight would otherwise mute every caster.
            return protectPlayers && !player.isSpectator() && !player.isCreative();
        }
        return e instanceof AbstractVillager
                || e instanceof IronGolem
                || e instanceof TamableAnimal;
    }

    /**
     * Single-spell convenience, kept for callers (and tests) that only ever check one radius.
     *
     * @return true if no protected entity sits within {@code radius} of the caster→target segment
     */
    public static boolean clear(Mob caster, LivingEntity target, double radius, NpcAdapter adapter) {
        return scan(caster, target, radius, adapter).clearAt(radius);
    }

    /**
     * Which protected entity blocks a shot, for the {@code /magicnpcs why} diagnostic. Returns the first
     * blocker's name rather than a boolean so the report can say <em>who</em> is in the way.
     */
    public static LivingEntity firstBlocker(Mob caster, LivingEntity target, double radius, NpcAdapter adapter) {
        return firstBlocker(caster, target, radius, adapter, Geometry.CORRIDOR);
    }

    /** As above, for a specific spell {@link Geometry}. */
    public static LivingEntity firstBlocker(Mob caster, LivingEntity target, double radius,
                                            NpcAdapter adapter, Geometry geometry) {
        Vec3 from = caster.getEyePosition();
        Vec3 to = target.getEyePosition();
        double r2 = radius * radius;
        for (LivingEntity e : protectedNear(caster, target, radius, adapter)) {
            if (distanceSqr(e.getBoundingBox(), from, to, geometry) <= r2) {
                return e;
            }
        }
        return null;
    }

    /** Squared gap between {@code box} and the region {@code geometry} describes between a and b. */
    private static double distanceSqr(AABB box, Vec3 a, Vec3 b, Geometry geometry) {
        return switch (geometry) {
            case CORRIDOR -> distanceToSegmentSqr(box, a, b);
            case TARGET_BLAST -> distanceToPointSqr(box, b);
            case CASTER_AOE -> distanceToPointSqr(box, a);
        };
    }

    /** Squared gap between {@code box} and a single point, zero when the point is inside the box. */
    private static double distanceToPointSqr(AABB box, Vec3 p) {
        double dx = Math.max(box.minX - p.x, Math.max(0.0, p.x - box.maxX));
        double dy = Math.max(box.minY - p.y, Math.max(0.0, p.y - box.maxY));
        double dz = Math.max(box.minZ - p.z, Math.max(0.0, p.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Squared gap between {@code box} and the segment a→b, zero when they touch or cross.
     *
     * <p>Until 0.9.1 this projected the box's <em>centre</em> onto the segment and measured that one
     * point. That is not the minimum distance between a segment and a box: a segment can run clean
     * through a body while the centre-projected point sits outside it. The audit's witness is a
     * villager-flat box [(0,0,0),(10,1,1)] and the segment (-5,-4,0.5)→(15,14,0.5), which is strictly
     * inside the box at t = 13/50 yet measured a squared gap of 3.94 — a silent "clear" through an ally
     * (MN-011).
     *
     * <p>Exact replacement. Per axis the offset out of the slab,
     * {@code f(t) = max(min - p(t), 0, p(t) - max)}, is piecewise linear in t with corners only where
     * the segment crosses that axis' two slab planes. Cut [0,1] at those (at most six) crossings and on
     * each piece every axis is a single linear term, so the squared distance is one convex quadratic
     * {@code A t² + B t + C} whose minimum is closed-form. The smallest piece minimum is the answer,
     * exactly zero whenever the segment enters the box.
     */
    static double distanceToSegmentSqr(AABB box, Vec3 a, Vec3 b) {
        double[] origin = {a.x, a.y, a.z};
        double[] dir = {b.x - a.x, b.y - a.y, b.z - a.z};
        double[] min = {box.minX, box.minY, box.minZ};
        double[] max = {box.maxX, box.maxY, box.maxZ};

        // The piece boundaries: the ends, plus every slab-plane crossing strictly inside the segment.
        double[] cuts = new double[8];
        int cutCount = 0;
        cuts[cutCount++] = 0.0;
        cuts[cutCount++] = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            if (dir[axis] == 0.0) {
                continue;
            }
            double lo = (min[axis] - origin[axis]) / dir[axis];
            double hi = (max[axis] - origin[axis]) / dir[axis];
            if (lo > 0.0 && lo < 1.0) {
                cuts[cutCount++] = lo;
            }
            if (hi > 0.0 && hi < 1.0) {
                cuts[cutCount++] = hi;
            }
        }
        Arrays.sort(cuts, 0, cutCount);

        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < cutCount; i++) {
            double lo = cuts[i];
            double hi = cuts[i + 1];
            if (hi <= lo) {
                continue;
            }
            // Inside a piece the regime of each axis (below the slab, inside it, above it) is fixed,
            // so read it off the midpoint and collect f(t) = u + v t for each axis.
            double mid = 0.5 * (lo + hi);
            double quadA = 0.0;
            double quadB = 0.0;
            double quadC = 0.0;
            for (int axis = 0; axis < 3; axis++) {
                double u;
                double v;
                double at = origin[axis] + mid * dir[axis];
                if (at < min[axis]) {
                    u = min[axis] - origin[axis];
                    v = -dir[axis];
                } else if (at > max[axis]) {
                    u = origin[axis] - max[axis];
                    v = dir[axis];
                } else {
                    continue; // inside the slab: contributes nothing on this piece
                }
                quadA += v * v;
                quadB += 2.0 * u * v;
                quadC += u * u;
            }
            double t = lo;
            if (quadA > 0.0) {
                t = Math.max(lo, Math.min(hi, -quadB / (2.0 * quadA)));
            }
            double value = (quadA * t + quadB) * t + quadC;
            if (value < best) {
                best = value;
            }
        }
        return Math.max(0.0, best);
    }
}
