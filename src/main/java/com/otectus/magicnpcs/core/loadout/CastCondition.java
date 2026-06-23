package com.otectus.magicnpcs.core.loadout;

import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Optional reactive trigger on a {@link LoadoutEntry}: extra conditions a spell must
 * meet (beyond role/range/line-of-sight) before it is eligible. Lets a pack author make
 * a spell "smart" — a shield cast only when the caster is hurt, an execute only when the
 * target is low, an AoE only when several enemies are clustered, a blink only when
 * recently struck.
 *
 * <p>All fields are optional; an unset (null) field is simply not checked, so an empty
 * condition always passes. Vanilla-only (no Iron's), so it evaluates fine without Iron's
 * installed.
 *
 * @param selfHpBelow        eligible when the caster's health fraction is below this (0..1)
 * @param targetHpBelow      eligible when the target's health fraction is below this (0..1);
 *                           an ATTACK "execute" — ignored for self-cast SUPPORT spells with no target
 * @param enemiesWithin      eligible when at least this many hostiles are within {@code enemiesRadius}
 * @param enemiesRadius      radius (blocks) for the {@code enemiesWithin} count (default 8.0)
 * @param whenRecentlyHurt   eligible only if the caster took mob damage within {@code recentDamageWindow}
 * @param recentDamageWindow ticks for {@code whenRecentlyHurt} (default 60)
 */
public record CastCondition(
        Double selfHpBelow,
        Double targetHpBelow,
        Integer enemiesWithin,
        Double enemiesRadius,
        Boolean whenRecentlyHurt,
        Integer recentDamageWindow
) {
    private static final double DEFAULT_ENEMY_RADIUS = 8.0;
    private static final int DEFAULT_DAMAGE_WINDOW = 60;

    /** @return true if no sub-condition is set (the condition imposes nothing). */
    public boolean isEmpty() {
        return selfHpBelow == null && targetHpBelow == null
                && (enemiesWithin == null || enemiesWithin <= 0)
                && (whenRecentlyHurt == null || !whenRecentlyHurt);
    }

    /**
     * @param caster  the casting mob
     * @param target  the current hostile target (may be null for SUPPORT self-casts)
     * @param adapter the mob's adapter — used so the enemy count includes only entities the
     *                caster may target (never its allies)
     * @return true if every set sub-condition currently holds
     */
    public boolean evaluate(Mob caster, LivingEntity target, NpcAdapter adapter) {
        if (selfHpBelow != null
                && caster.getHealth() >= caster.getMaxHealth() * selfHpBelow) {
            return false;
        }
        if (targetHpBelow != null
                && (target == null || target.getHealth() >= target.getMaxHealth() * targetHpBelow)) {
            return false;
        }
        if (whenRecentlyHurt != null && whenRecentlyHurt) {
            int window = recentDamageWindow != null ? recentDamageWindow : DEFAULT_DAMAGE_WINDOW;
            if (caster.getLastHurtByMob() == null
                    || caster.tickCount - caster.getLastHurtByMobTimestamp() > window) {
                return false;
            }
        }
        if (enemiesWithin != null && enemiesWithin > 0) {
            double radius = enemiesRadius != null ? enemiesRadius : DEFAULT_ENEMY_RADIUS;
            if (countHostiles(caster, adapter, radius) < enemiesWithin) {
                return false;
            }
        }
        return true;
    }

    private static int countHostiles(Mob caster, NpcAdapter adapter, double radius) {
        AABB box = caster.getBoundingBox().inflate(radius);
        List<LivingEntity> near = caster.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != caster && e.isAlive() && adapter.canCastAt(caster, e));
        return near.size();
    }
}
