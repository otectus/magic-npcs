package com.otectus.magicnpcs.core.util;

import net.minecraft.world.entity.ai.goal.Goal;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link AttackGoals}, which backs the {@code native_attack} loadout policy. Matching is on the
 * goal's <em>simple class name</em> so it recognises a mod's subclass (or reimplementation) of a
 * vanilla attack goal without importing that mod — these fixtures stand in for exactly that case.
 */
class AttackGoalsTest {

    /** Same simple name as vanilla's melee goal, different package — the "modded subclass" shape. */
    private static final class MeleeAttackGoal extends Goal {
        @Override
        public boolean canUse() {
            return false;
        }
    }

    private static final class SomeUnrelatedGoal extends Goal {
        @Override
        public boolean canUse() {
            return false;
        }
    }

    @Test
    void recognisesVanillaAttackGoalNames() {
        assertTrue(AttackGoals.isNativeAttackGoal(new MeleeAttackGoal()));
    }

    @Test
    void leavesUnrelatedGoalsAlone() {
        // suppress must not strip a mob's swimming, panic or look goals.
        assertFalse(AttackGoals.isNativeAttackGoal(new SomeUnrelatedGoal()));
    }

    @Test
    void nullIsNotAnAttackGoal() {
        assertFalse(AttackGoals.isNativeAttackGoal(null));
    }

    @Test
    void anonymousGoalClassesGetAReadableName() {
        // Vanilla uses anonymous goal classes freely (AbstractSkeleton$1); getSimpleName() is "" for
        // those, which would print a blank column in the /magicnpcs why goal dump.
        Goal anonymous = new Goal() {
            @Override
            public boolean canUse() {
                return false;
            }
        };
        String name = AttackGoals.simpleName(anonymous.getClass());
        assertFalse(name.isEmpty());
        assertTrue(name.contains("AttackGoalsTest"), "expected an enclosing-class-derived name, got " + name);
    }

    /** Stands in for vanilla's {@code SpellcasterIllager$SpellcasterCastingSpellGoal}: a *named* nested class. */
    private static final class SpellcasterIllager {
        static final class SpellcasterCastingSpellGoal extends Goal {
            @Override
            public boolean canUse() {
                return false;
            }
        }
    }

    @Test
    void namedNestedGoalsAreMatchableByTheirOuterQualifiedName() {
        // Class#getSimpleName returns only "SpellcasterCastingSpellGoal" for a named nested class, so
        // the shipped default entry "SpellcasterIllager$SpellcasterCastingSpellGoal" could never match
        // anything: native_attack "suppress" silently failed to remove an evoker's casting goal, and
        // "yield" never saw it running. (The sibling default "AbstractSkeleton$1" worked only because
        // anonymous classes fall through to the binary-name path.)
        assertEquals("SpellcasterIllager$SpellcasterCastingSpellGoal",
                AttackGoals.nestedName(SpellcasterIllager.SpellcasterCastingSpellGoal.class));
    }

    @Test
    void aTopLevelGoalsNestedNameIsJustItsSimpleName() {
        assertEquals("Goal", AttackGoals.nestedName(Goal.class));
    }

    @Test
    void aNestedGoalStillMatchesOnItsPlainSimpleName() {
        // Both spellings must work, so existing configs naming just "MeleeAttackGoal" keep matching.
        assertTrue(AttackGoals.isNativeAttackGoal(new MeleeAttackGoal()));
        assertEquals("AttackGoalsTest$MeleeAttackGoal", AttackGoals.nestedName(MeleeAttackGoal.class));
    }

    @Test
    void flagsRenderAsAReadableList() {
        Goal goal = new Goal() {
            @Override
            public boolean canUse() {
                return false;
            }
        };
        assertEquals("[]", AttackGoals.flags(goal));
        goal.setFlags(EnumSet.of(Goal.Flag.LOOK));
        assertEquals("[LOOK]", AttackGoals.flags(goal));
    }
}
