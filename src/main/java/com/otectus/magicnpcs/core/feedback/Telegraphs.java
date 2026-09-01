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
     * @return true if a glow was applied and the caller therefore owns clearing it
     */
    /** Saved scoreboard tag marking a glow this mod applied, so it can be recognised after a reload. */
    public static final String GLOW_TAG = "magicnpcs.telegraph";

    public static boolean play(Mob caster, TelegraphInfo info, double safetyRadius) {
        if (info == null
                || !MagicNpcsConfig.FEEDBACK_TELEGRAPHS.get()
                || info.dangerTier() < MagicNpcsConfig.FEEDBACK_MIN_DANGER_TIER.get()
                || !(caster.level() instanceof ServerLevel level)) {
            return false;
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

        // Only glow a mob that wasn't already glowing, and report back that we did: the caller tracks
        // ownership so toggling telegraphGlow off mid-wind-up can't strand a permanently glowing mob,
        // and so we never clear a glow another mod set (backlog B15).
        boolean glowed = false;
        if (MagicNpcsConfig.FEEDBACK_TELEGRAPH_GLOW.get() && !caster.hasGlowingTag()) {
            caster.setGlowingTag(true);
            // Mark ownership in saved NBT, not just in the goal's transient field. setGlowingTag writes
            // the persisted "Glowing" key, but the only thing that cleared it was the goal's stop() —
            // so a mob that unloaded, died, or was on a server shut down mid-wind-up came back
            // permanently outlined, with a fresh goal that no longer believed it owned the glow.
            // GLOW_TAG lets the join handler recognise and clear a stranded one.
            caster.addTag(GLOW_TAG);
            glowed = true;
        }

        float volume = (float) (double) MagicNpcsConfig.FEEDBACK_TELEGRAPH_VOLUME.get();
        if (volume > 0.0f && info.castSound() != null) {
            SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(info.castSound());
            if (sound != null) {
                float pitch = 1.0f - Math.min(tier, 4) * 0.08f; // deeper tell for scarier spells
                // Category by what the caster actually is: filing a player-owned recruit's or a village
                // cleric's tell under "Hostile Creatures" meant turning that slider down silenced your
                // own allies, and turning it up to hear enemies made your own side louder too.
                SoundSource category = caster instanceof net.minecraft.world.entity.monster.Enemy
                        ? SoundSource.HOSTILE
                        : SoundSource.NEUTRAL;
                level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                        sound, category, volume * (0.6f + tier * 0.1f), pitch);
            }
        }
        return glowed;
    }

    /**
     * Clear a telegraph glow <em>we</em> applied. Unconditional on the config value: {@code play}
     * returns whether it set the tag, so the caller only calls this when it owns the glow, and a
     * mid-wind-up config change can no longer leave the mob lit up forever.
     *
     * <p>Ownership is now actually enforced rather than merely documented: a glow set by another mod
     * or by {@code /data} during our wind-up carries no {@link #GLOW_TAG} and is left alone.
     */
    public static void clearGlow(Mob caster) {
        if (!caster.getTags().contains(GLOW_TAG)) {
            return;
        }
        caster.removeTag(GLOW_TAG);
        if (caster.hasGlowingTag()) {
            caster.setGlowingTag(false);
        }
    }

    /**
     * Clear a glow left behind by a wind-up that never finished — the mob unloaded, died, or the
     * server stopped mid-cast. Called when a mob joins a level, which is the first moment we can
     * observe the stranded state.
     */
    public static void clearStrandedGlow(Mob caster) {
        if (caster.getTags().contains(GLOW_TAG)) {
            clearGlow(caster);
        }
    }
}
