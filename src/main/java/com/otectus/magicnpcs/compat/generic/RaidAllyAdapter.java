package com.otectus.magicnpcs.compat.generic;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapter;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;

/**
 * Vanilla raid friendly-fire protection: a raider never catches another raider from the same raid in
 * its line of fire.
 *
 * <p>Present in the 0.6.0 binary, absent from 0.6.1 while the project page still promised that casters
 * "never own" their raid — so a pillager captain given an AoE loadout would happily delete its own wave
 * (audit TGT-002). Restored here as a <em>composable</em> policy: since 0.6.2 every applicable adapter
 * contributes, so this protection survives a mod-specific adapter also applying to the same mob, which
 * is what made losing it so easy in the first place.
 *
 * <p>Two raiders count as allies when they share a {@link Raid}. Raiders in <em>different</em> raids are
 * not allies — two separate waves fighting each other is legitimate — and an unaffiliated raider (one
 * that spawned outside a raid) is treated the same way vanilla does: not a raid member, so not covered.
 *
 * <p>Vanilla-only (no mod imports), so it is safe to register unconditionally; gated at runtime by
 * {@code targeting.protectRaidAllies}.
 */
public final class RaidAllyAdapter implements NpcAdapter {

    @Override
    public int priority() {
        // Below the generic owner/team adapter, and far below any mod adapter. Priority now only
        // decides who provides mana scaling; every applicable adapter's safety rules are combined.
        return -200;
    }

    @Override
    public boolean appliesTo(Mob mob) {
        return MagicNpcsConfig.protectRaidAllies() && mob instanceof Raider raider && raider.getCurrentRaid() != null;
    }

    @Override
    public boolean canCastAt(Mob caster, LivingEntity target) {
        return !isAlly(caster, target);
    }

    @Override
    public boolean tracksAllies() {
        return true;
    }

    @Override
    public boolean isAlly(Mob caster, LivingEntity other) {
        if (other == caster) {
            return true;
        }
        if (!(caster instanceof Raider raider) || !(other instanceof Raider otherRaider)) {
            return false;
        }
        Raid raid = raider.getCurrentRaid();
        // Same raid object, not merely "both are raiders": two waves from different raids fighting is
        // a real situation and blocking it would be a behaviour change, not a safety fix.
        return raid != null && raid == otherRaider.getCurrentRaid();
    }
}
