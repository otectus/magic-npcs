package com.otectus.magicnpcs.core.caster;

import com.otectus.magicnpcs.core.caster.SpellEligibility.Verdict;
import com.otectus.magicnpcs.core.loadout.CastCondition;
import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link SpellEligibility}: the out-of-combat SUPPORT rules of ADR 0005, which
 * shipped in 0.6.0 with no regression coverage. No Minecraft/Iron's classes touched.
 */
class SpellEligibilityTest {

    /** A condition with a self-health term, so it may carry an idle caster on its own. */
    private static final CastCondition SELF_HEALTH_GATED =
            new CastCondition(0.6, null, null, null, null, null);

    /** A condition with no self-health term — the anti-loop floor applies to it out of combat. */
    private static final CastCondition NO_HEALTH_TERM =
            new CastCondition(null, null, 3, 8.0, null, null);

    private static final BooleanSupplier MATCHES = () -> true;
    private static final BooleanSupplier NEVER_CALLED = () -> {
        throw new AssertionError("the reactive condition must not be evaluated once an earlier "
                + "check has already skipped the entry");
    };

    @Test
    void woundedSupportOutOfCombatWithNoConditionIsEligible() {
        assertEquals(Verdict.ELIGIBLE, SpellEligibility.roleGate(
                        LoadoutEntry.Role.SUPPORT, true, true, null, NEVER_CALLED, false, false, false),
                "a wounded caster with no target must be able to heal itself");
    }

    @Test
    void fullHealthSupportOutOfCombatIsSkipped() {
        assertEquals(Verdict.SKIP, SpellEligibility.roleGate(
                        LoadoutEntry.Role.SUPPORT, true, false, null, NEVER_CALLED, false, false, false),
                "out-of-combat support must not become a mana-burning idle animation");
    }

    @Test
    void attackIsNeverEligibleOutOfCombat() {
        // Every combination of the remaining inputs: no input may rescue an ATTACK entry.
        for (int bits = 0; bits < 32; bits++) {
            boolean hurt = (bits & 1) != 0;
            boolean canAttackTarget = (bits & 2) != 0;
            boolean inRange = (bits & 4) != 0;
            boolean lineOfSightOk = (bits & 8) != 0;
            CastCondition cond = (bits & 16) != 0 ? SELF_HEALTH_GATED : null;
            assertEquals(Verdict.SKIP, SpellEligibility.roleGate(
                            LoadoutEntry.Role.ATTACK, true, hurt, cond, MATCHES,
                            canAttackTarget, inRange, lineOfSightOk),
                    "ATTACK must never be selected without a target (inputs " + bits + ")");
        }
    }

    @Test
    void mixedLoadoutOutOfCombatLeavesOnlySupport() {
        Verdict attack = SpellEligibility.roleGate(
                LoadoutEntry.Role.ATTACK, true, true, null, NEVER_CALLED, true, true, true);
        Verdict support = SpellEligibility.roleGate(
                LoadoutEntry.Role.SUPPORT, true, true, null, NEVER_CALLED, true, true, true);
        assertEquals(Verdict.SKIP, attack, "the ATTACK half of a mixed loadout must not survive");
        assertNotEquals(Verdict.SKIP, support, "the SUPPORT half must survive");
    }

    @Test
    void idleConditionWithoutHealthTermStillNeedsTheHurtGate() {
        assertEquals(Verdict.SKIP, SpellEligibility.roleGate(
                        LoadoutEntry.Role.SUPPORT, true, false, NO_HEALTH_TERM, MATCHES, false, false, false),
                "a matched condition with no self-health term must not carry a full-health idle caster");
    }

    @Test
    void idleConditionWithHealthTermIsEligibleAtFullHealth() {
        assertEquals(Verdict.ELIGIBLE_CONDITION_MATCHED, SpellEligibility.roleGate(
                        LoadoutEntry.Role.SUPPORT, true, false, SELF_HEALTH_GATED, MATCHES,
                        false, false, false),
                "a condition that gates on the caster's own health replaces the hurt gate");
    }

    @Test
    void inCombatSupportWithAMatchedConditionReportsTheMatch() {
        assertEquals(Verdict.ELIGIBLE_CONDITION_MATCHED, SpellEligibility.roleGate(
                        LoadoutEntry.Role.SUPPORT, false, false, NO_HEALTH_TERM, MATCHES, true, true, true),
                "in combat the anti-loop floor does not apply");
    }

    @Test
    void anEmptyConditionImposesNothing() {
        CastCondition empty = new CastCondition(null, null, null, null, null, null);
        assertTrue(empty.isEmpty());
        assertEquals(Verdict.ELIGIBLE, SpellEligibility.roleGate(
                        LoadoutEntry.Role.SUPPORT, true, true, empty, NEVER_CALLED, false, false, false),
                "an empty condition must be treated as absent, not evaluated");
    }

    @Test
    void attackConditionIsNotEvaluatedWhenRangeOrSightAlreadySkip() {
        // Out of range: the range check comes first, so the condition supplier is never touched.
        assertEquals(Verdict.SKIP, SpellEligibility.roleGate(
                LoadoutEntry.Role.ATTACK, false, false, NO_HEALTH_TERM, NEVER_CALLED,
                true, false, true));
        // No line of sight: likewise.
        assertEquals(Verdict.SKIP, SpellEligibility.roleGate(
                LoadoutEntry.Role.ATTACK, false, false, NO_HEALTH_TERM, NEVER_CALLED,
                true, true, false));
        // Not attackable: likewise.
        assertEquals(Verdict.SKIP, SpellEligibility.roleGate(
                LoadoutEntry.Role.ATTACK, false, false, NO_HEALTH_TERM, NEVER_CALLED,
                false, true, true));
    }

    @Test
    void aMatchedAttackConditionIsEvaluatedExactlyOnce() {
        int[] calls = {0};
        BooleanSupplier counting = () -> {
            calls[0]++;
            return true;
        };
        assertEquals(Verdict.ELIGIBLE_CONDITION_MATCHED, SpellEligibility.roleGate(
                LoadoutEntry.Role.ATTACK, false, false, NO_HEALTH_TERM, counting, true, true, true));
        assertEquals(1, calls[0], "the condition must be evaluated exactly once per entry");
    }

    @Test
    void outOfCombatPathOpensOnlyWithSupportAndTheFeatureOn() {
        assertTrue(SpellEligibility.outOfCombatPathOpen(true, true));
        assertFalse(SpellEligibility.outOfCombatPathOpen(true, false),
                "supportOutOfCombat=false must close the idle path");
        assertFalse(SpellEligibility.outOfCombatPathOpen(false, true),
                "a loadout with no SUPPORT entry has nothing to decide out of combat");
    }
}
