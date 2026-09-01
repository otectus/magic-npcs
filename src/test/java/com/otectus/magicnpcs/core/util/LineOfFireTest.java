package com.otectus.magicnpcs.core.util;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
