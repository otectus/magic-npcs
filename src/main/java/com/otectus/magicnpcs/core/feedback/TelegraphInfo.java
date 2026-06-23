package com.otectus.magicnpcs.core.feedback;

import net.minecraft.resources.ResourceLocation;

/**
 * Iron's-free description of a cast "tell", produced on the Iron's side
 * ({@code IronsBridge.telegraphFor}) and consumed by the vanilla-only {@link Telegraphs}
 * spawner. Keeping this a plain data carrier (RGB + a sound id + a danger tier) lets the
 * particle/sound code live in {@code core} with no Iron's import.
 *
 * @param r          school colour red component (0..1)
 * @param g          school colour green component (0..1)
 * @param b          school colour blue component (0..1)
 * @param castSound  id of the telegraph/charge sound, or {@code null} for none
 * @param dangerTier 0..4, derived from spell rarity + AoE size; scales the effect intensity
 */
public record TelegraphInfo(float r, float g, float b, ResourceLocation castSound, int dangerTier) {}
