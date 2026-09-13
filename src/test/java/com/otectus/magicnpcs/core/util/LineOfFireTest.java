package com.otectus.magicnpcs.core.util;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometry tests for {@link LineOfFire.Scan}, which decides whether an ally or bystander stands close
 * enough to the caster→target line to withhold an attack spell.
 *
 * <p>These pin down the bug that made friendly fire protection inert at its shipped settings: blockers
 * used to be recorded at {@code Entity#position()} — the entity's <em>feet</em> — and measured against
 * a segment drawn between two <em>eye</em> positions. A villager standing squarely in the line of fire
 * measured its own eye height (~1.62 blocks) away from that segment, comfortably outside the default
 * {@code safety_radius} of 1.5, so nothing was ever blocked.
 */
class LineOfFireTest {

    /** Eye-height firing line, as {@code LineOfFire.scan} builds it: caster eye → target eye. */
    private static final Vec3 FROM = new Vec3(0.0, 1.62, 0.0);
    private static final Vec3 TO = new Vec3(10.0, 1.62, 0.0);

    /** A villager-sized body (0.6 wide, 1.95 tall) standing with its feet on y=0 at (x, z). */
    private static AABB standingAt(double x, double z) {
        return new AABB(x - 0.3, 0.0, z - 0.3, x + 0.3, 1.95, z + 0.3);
    }

    private static LineOfFire.Scan scanOf(AABB... blockers) {
        return new LineOfFire.Scan(List.of(blockers), FROM, TO);
    }

    @Test
    void anAllyStandingInTheLineOfFireBlocksTheShot() {
        // Directly on the line, halfway to the target. This is the case that silently passed before:
        // its feet are 1.62 below the segment, but its body sits right on it.
        assertFalse(scanOf(standingAt(5.0, 0.0)).clearAt(1.5));
    }

    @Test
    void theDefaultSafetyRadiusIsEnoughToNoticeANeighbour() {
        // Just over half a block to the side — still overlapping the corridor at radius 1.5.
        assertFalse(scanOf(standingAt(5.0, 0.6)).clearAt(1.5));
    }

    @Test
    void anAllyWellClearOfTheLineDoesNotBlock() {
        assertTrue(scanOf(standingAt(5.0, 4.0)).clearAt(1.5));
    }

    @Test
    void anAllyBesideTheCasterButBehindTheShotDoesNotBlock() {
        // Behind the caster, away from the segment entirely.
        assertTrue(scanOf(standingAt(-5.0, 0.0)).clearAt(1.5));
    }

    @Test
    void anAllyAtTheImpactPointBlocksAnAoe() {
        // The impact point is an endpoint of the segment, so blast-radius clearance is the same test.
        assertFalse(scanOf(standingAt(10.0, 1.0)).clearAt(2.5));
    }

    @Test
    void aLargerSafetyRadiusBlocksFromFurtherAway() {
        AABB neighbour = standingAt(5.0, 2.5);
        assertTrue(scanOf(neighbour).clearAt(1.5));
        assertFalse(scanOf(neighbour).clearAt(3.0));
    }

    @Test
    void heightIsMeasuredAgainstTheWholeBody() {
        // A short mob (a 0.7-tall pig) directly under the line: its body stops well below eye height,
        // so at a tight radius it should not block, but a generous AoE radius should still catch it.
        AABB shortMob = new AABB(4.7, 0.0, -0.3, 5.3, 0.7, 0.3);
        assertTrue(scanOf(shortMob).clearAt(0.5));
        assertFalse(scanOf(shortMob).clearAt(1.5));
    }

    @Test
    void anEmptyScanNeverBlocks() {
        assertTrue(LineOfFire.CLEAR.clearAt(4.0));
        assertTrue(LineOfFire.CLEAR.isEmpty());
    }

    // --- REG-20: exact segment↔box distance (MN-011) ------------------------------------------

    /**
     * Independent oracle: sample the segment finely and clamp each sample into the box. This is a
     * different algorithm from the production piecewise-quadratic one (brute force, not closed form),
     * so agreement between them is evidence rather than a restatement. A sampled minimum is always an
     * upper bound on the true minimum.
     */
    private static double sampledMinDistanceSqr(AABB box, Vec3 a, Vec3 b) {
        int steps = 200_000;
        double best = Double.MAX_VALUE;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = a.x + t * (b.x - a.x);
            double py = a.y + t * (b.y - a.y);
            double pz = a.z + t * (b.z - a.z);
            double dx = Math.max(box.minX - px, Math.max(0.0, px - box.maxX));
            double dy = Math.max(box.minY - py, Math.max(0.0, py - box.maxY));
            double dz = Math.max(box.minZ - pz, Math.max(0.0, pz - box.maxZ));
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return best;
    }

    /** The exact result must never exceed the sampled bound, and must not sit far below it either. */
    private static void assertMatchesOracle(AABB box, Vec3 a, Vec3 b) {
        double exact = LineOfFire.distanceToSegmentSqr(box, a, b);
        double sampled = sampledMinDistanceSqr(box, a, b);
        assertTrue(exact <= sampled + 1.0E-9,
                () -> "exact " + exact + " exceeded sampled minimum " + sampled);
        assertTrue(sampled - exact < 1.0E-3,
                () -> "exact " + exact + " is implausibly below sampled minimum " + sampled);
    }

    @Test
    void theAuditCertificateSegmentIsInsideTheBox() {
        // REG-20 certificate from the audit (MN-011): the segment is strictly inside the box at
        // t = 13/50, where it passes through (1/5, 17/25, 1/2). The old centre-projection formula
        // reported a squared gap of 3.94 and let the shot through.
        Vec3 a = new Vec3(-5.0, -4.0, 0.5);
        Vec3 b = new Vec3(15.0, 14.0, 0.5);
        AABB box = new AABB(0.0, 0.0, 0.0, 10.0, 1.0, 1.0);
        assertEquals(0.0, LineOfFire.distanceToSegmentSqr(box, a, b), 0.0);
        assertFalse(new LineOfFire.Scan(List.of(box), a, b).clearAt(1.5));
        // The witness point itself, checked independently of the algorithm under test.
        double t = 13.0 / 50.0;
        Vec3 witness = a.add(b.subtract(a).scale(t));
        assertTrue(witness.x > box.minX && witness.x < box.maxX
                && witness.y > box.minY && witness.y < box.maxY
                && witness.z > box.minZ && witness.z < box.maxZ,
                "witness " + witness + " should be inside the box");
    }

    @Test
    void aSegmentBesideTheBoxMeasuresTheSidewaysGap() {
        // Parallel to the box on the z axis, 2 blocks clear of its near face: gap² = 4.
        AABB box = new AABB(0.0, 0.0, 0.0, 10.0, 1.0, 1.0);
        assertEquals(4.0, LineOfFire.distanceToSegmentSqr(box, new Vec3(-5.0, 0.5, 3.0), new Vec3(15.0, 0.5, 3.0)), 1.0E-12);
    }

    @Test
    void aSegmentPastTheEndOfTheBoxMeasuresFromItsNearestEnd() {
        // Segment lives entirely beyond maxX: the closest pair is the segment's start and the box corner.
        AABB box = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        double d = LineOfFire.distanceToSegmentSqr(box, new Vec3(4.0, 0.5, 0.5), new Vec3(9.0, 0.5, 0.5));
        assertEquals(9.0, d, 1.0E-12);
    }

    @Test
    void aZeroLengthSegmentIsJustAPoint() {
        AABB box = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        assertEquals(0.0, LineOfFire.distanceToSegmentSqr(box, new Vec3(0.5, 0.5, 0.5), new Vec3(0.5, 0.5, 0.5)), 0.0);
        assertEquals(4.0, LineOfFire.distanceToSegmentSqr(box, new Vec3(3.0, 0.5, 0.5), new Vec3(3.0, 0.5, 0.5)), 1.0E-12);
    }

    @Test
    void anEndpointInsideTheBoxTouches() {
        AABB box = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        assertEquals(0.0, LineOfFire.distanceToSegmentSqr(box, new Vec3(0.5, 0.5, 0.5), new Vec3(8.0, 6.0, 4.0)), 0.0);
        assertEquals(0.0, LineOfFire.distanceToSegmentSqr(box, new Vec3(8.0, 6.0, 4.0), new Vec3(0.5, 0.5, 0.5)), 0.0);
    }

    @Test
    void aGrazingSegmentOnTheFaceIsZero() {
        // Runs exactly along the maxY face: touching counts as a hit, not as clearance.
        AABB box = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
        assertEquals(0.0, LineOfFire.distanceToSegmentSqr(box, new Vec3(-2.0, 1.0, 0.5), new Vec3(3.0, 1.0, 0.5)), 1.0E-12);
    }

    @Test
    void flatAndTallAndLargeBoxesAllMeasureCorrectly() {
        Vec3 a = new Vec3(-5.0, -4.0, 0.5);
        Vec3 b = new Vec3(15.0, 14.0, 0.5);
        assertMatchesOracle(new AABB(0.0, 0.0, 0.0, 10.0, 1.0, 1.0), a, b);            // flat slab
        assertMatchesOracle(new AABB(4.0, -20.0, -0.2, 4.4, 20.0, 0.2), a, b);         // tall pole
        assertMatchesOracle(new AABB(-30.0, -30.0, -30.0, 30.0, 30.0, 30.0), a, b);    // large body
        assertMatchesOracle(new AABB(20.0, 20.0, 20.0, 21.0, 21.0, 21.0), a, b);       // far away
    }

    @Test
    void theResultIsInvariantUnderTranslationAndEndpointOrder() {
        Vec3 a = new Vec3(-5.0, -4.0, 0.5);
        Vec3 b = new Vec3(15.0, 14.0, 0.5);
        AABB box = new AABB(0.0, 0.0, 0.0, 10.0, 1.0, 1.0);
        double base = LineOfFire.distanceToSegmentSqr(box, a, b);
        assertEquals(base, LineOfFire.distanceToSegmentSqr(box, b, a), 1.0E-12);

        Vec3 offset = new Vec3(100.0, -50.0, 7.5);
        AABB moved = new AABB(box.minX + offset.x, box.minY + offset.y, box.minZ + offset.z,
                box.maxX + offset.x, box.maxY + offset.y, box.maxZ + offset.z);
        assertEquals(base, LineOfFire.distanceToSegmentSqr(moved, a.add(offset), b.add(offset)), 1.0E-6);
    }

    @Test
    void randomSegmentsAndBoxesAgreeWithTheOracle() {
        java.util.Random random = new java.util.Random(20260912L);
        for (int i = 0; i < 200; i++) {
            double cx = random.nextDouble() * 20.0 - 10.0;
            double cy = random.nextDouble() * 20.0 - 10.0;
            double cz = random.nextDouble() * 20.0 - 10.0;
            AABB box = new AABB(cx, cy, cz,
                    cx + random.nextDouble() * 6.0 + 0.05,
                    cy + random.nextDouble() * 6.0 + 0.05,
                    cz + random.nextDouble() * 6.0 + 0.05);
            Vec3 a = new Vec3(random.nextDouble() * 30.0 - 15.0,
                    random.nextDouble() * 30.0 - 15.0,
                    random.nextDouble() * 30.0 - 15.0);
            Vec3 b = new Vec3(random.nextDouble() * 30.0 - 15.0,
                    random.nextDouble() * 30.0 - 15.0,
                    random.nextDouble() * 30.0 - 15.0);
            assertMatchesOracle(box, a, b);
            assertEquals(LineOfFire.distanceToSegmentSqr(box, a, b),
                    LineOfFire.distanceToSegmentSqr(box, b, a), 1.0E-9);
        }
    }

    @Test
    void aScanBlocksExactlyWhenTheExactDistanceIsWithinTheRadius() {
        // Scan.clearAt must agree with the geometry it is built on, at both sides of the boundary.
        AABB box = standingAt(5.0, 2.0);
        double d2 = LineOfFire.distanceToSegmentSqr(box, FROM, TO);
        double d = Math.sqrt(d2);
        assertFalse(scanOf(box).clearAt(d + 0.1));
        assertTrue(scanOf(box).clearAt(Math.max(0.0, d - 0.1)));
    }
}
