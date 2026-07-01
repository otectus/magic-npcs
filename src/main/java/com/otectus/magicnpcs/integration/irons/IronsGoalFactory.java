package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.core.loadout.LoadoutEntry;
import com.otectus.magicnpcs.core.loadout.SpellcasterLoadout;
import io.redspace.ironsspellbooks.api.entity.IMagicEntity;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.entity.mobs.goals.WizardAttackGoal;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an Iron's {@code WizardAttackGoal} from a datapack loadout, for entities
 * the Recruits mixin has made {@code IMagicEntity}. Iron's goal supplies the
 * selection/positioning AI; our mixin's {@code initiateCastSpell} routes the
 * actual cast through {@link IronsBridge}. Iron's-side only (imports Iron's).
 *
 * <p>Note: in Iron's-AI mode the loadout's per-spell {@code level} is superseded
 * by {@code setSpellQuality} (Iron's derives the level from quality).
 */
public final class IronsGoalFactory {
    private IronsGoalFactory() {}

    public static Goal wizardGoal(Mob mob, SpellcasterLoadout loadout, double speed, int interval, float quality) {
        List<AbstractSpell> attack = new ArrayList<>();
        List<AbstractSpell> support = new ArrayList<>();
        for (LoadoutEntry entry : loadout.spells()) {
            AbstractSpell spell = IronsBridge.getSpell(entry.spell());
            if (spell == null) {
                IronsBridge.warnUnknownSpell(loadout.source(), loadout.entityType(), entry.spell());
                continue;
            }
            (entry.role() == LoadoutEntry.Role.SUPPORT ? support : attack).add(spell);
        }
        List<AbstractSpell> none = List.of();
        return new WizardAttackGoal((IMagicEntity) mob, speed, interval)
                .setSpells(attack, none, none, support)
                .setSpellQuality(quality, quality)
                .setAllowFleeing(true);
    }
}
