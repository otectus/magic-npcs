package com.otectus.magicnpcs.core.feedback;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Spawns a cast "tell" during a caster's wind-up so its attacks can be seen coming. Pure
 * vanilla — server-spawned particles + a sound — so it works with no extra dependency and
 * is safe on dedicated servers (the server broadcasts to tracking clients). All Iron's
 * data arrives pre-resolved as a {@link TelegraphInfo}, so this class has no Iron's import.
 */
public final class Telegraphs {
    private static final double MAX_SPREAD = 3.0;

    private Telegraphs() {}

    /**
     * Play the telegraph for {@code caster}. No-op when telegraphs are disabled, the spell is
     * below the configured danger tier, or this is not a server level.
     *
     * @param safetyRadius the spell's friendly-fire radius — bigger AoE casts get a bigger tell
     */
    public static void play(Mob caster, TelegraphInfo info, double safetyRadius) {
        if (info == null
                || !MagicNpcsConfig.FEEDBACK_TELEGRAPHS.get()
                || info.dangerTier() < MagicNpcsConfig.FEEDBACK_MIN_DANGER_TIER.get()
                || !(caster.level() instanceof ServerLevel level)) {
            return;
        }

        int tier = info.dangerTier();
        Vec3 at = caster.getEyePosition();
        int count = 6 + tier * 6;
        double spread = Math.min(1.0 + safetyRadius * 0.25, MAX_SPREAD);

        if (MagicNpcsConfig.FEEDBACK_SCHOOL_PARTICLES.get()) {
            DustParticleOptions dust = new DustParticleOptions(
                    new Vector3f(info.r(), info.g(), info.b()), 1.0f + tier * 0.25f);
            level.sendParticles(dust, at.x, at.y, at.z, count, spread, spread * 0.5, spread, 0.0);
        } else {
            level.sendParticles(ParticleTypes.WITCH, at.x, at.y, at.z, count, spread, spread * 0.5, spread, 0.0);
        }

        if (MagicNpcsConfig.FEEDBACK_TELEGRAPH_GLOW.get()) {
            caster.setGlowingTag(true);
        }

        float volume = (float) (double) MagicNpcsConfig.FEEDBACK_TELEGRAPH_VOLUME.get();
        if (volume > 0.0f && info.castSound() != null) {
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(info.castSound());
            if (sound != null) {
                float pitch = 1.0f - Math.min(tier, 4) * 0.08f; // deeper tell for scarier spells
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                        sound, SoundSource.HOSTILE, volume * (0.6f + tier * 0.1f), pitch);
            }
        }
    }

    /** Clear any telegraph glow applied during the wind-up (called when the cast attempt ends). */
    public static void clearGlow(Mob caster) {
        if (MagicNpcsConfig.FEEDBACK_TELEGRAPH_GLOW.get() && caster.hasGlowingTag()) {
            caster.setGlowingTag(false);
        }
    }
}
