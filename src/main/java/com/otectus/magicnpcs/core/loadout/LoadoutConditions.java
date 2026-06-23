package com.otectus.magicnpcs.core.loadout;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Set;

/**
 * Optional context gate on a {@link SpellcasterLoadout}: world conditions that must hold
 * for the loadout to apply to a mob. Evaluated when the mob spawns / loads, so a pack can
 * make e.g. nether mobs cast fire, surface mobs cast only at night, or a variant appear
 * only during a raid. All fields are optional; an unset field is not checked, so an empty
 * block always passes. Vanilla-only (no Iron's).
 *
 * @param dimensions  allowed dimension ids (e.g. {@code minecraft:the_nether}); any if empty
 * @param biomes      allowed biome ids or {@code #biome-tags} (e.g. {@code #minecraft:is_forest}); any if empty
 * @param difficulties allowed world difficulties; any if empty
 * @param time        DAY / NIGHT / ANY
 * @param minY        inclusive minimum block-Y (null = no floor)
 * @param maxY        inclusive maximum block-Y (null = no ceiling)
 * @param requireRaid require an active raid at the mob's position
 * @param requireStorm require the world to be thundering
 * @param moonPhases  allowed moon phases 0..7; any if empty
 */
public record LoadoutConditions(
        List<ResourceLocation> dimensions,
        List<String> biomes,
        Set<Difficulty> difficulties,
        TimeOfDay time,
        Integer minY,
        Integer maxY,
        Boolean requireRaid,
        Boolean requireStorm,
        List<Integer> moonPhases
) {
    public enum TimeOfDay { ANY, DAY, NIGHT }

    public boolean test(Mob mob) {
        Level level = mob.level();
        if (notEmpty(dimensions) && !dimensions.contains(level.dimension().location())) {
            return false;
        }
        if (difficulties != null && !difficulties.isEmpty()
                && !difficulties.contains(level.getDifficulty())) {
            return false;
        }
        if (time != null && time != TimeOfDay.ANY) {
            boolean day = level.isDay();
            if ((time == TimeOfDay.DAY) != day) {
                return false;
            }
        }
        if (minY != null && mob.getBlockY() < minY) {
            return false;
        }
        if (maxY != null && mob.getBlockY() > maxY) {
            return false;
        }
        if (requireStorm != null && requireStorm && !level.isThundering()) {
            return false;
        }
        if (notEmpty(moonPhases)
                && !moonPhases.contains(level.dimensionType().moonPhase(level.getDayTime()))) {
            return false;
        }
        if (requireRaid != null && requireRaid
                && (!(level instanceof ServerLevel sl) || sl.getRaidAt(mob.blockPosition()) == null)) {
            return false;
        }
        if (notEmpty(biomes) && !matchesBiome(level, mob)) {
            return false;
        }
        return true;
    }

    private boolean matchesBiome(Level level, Mob mob) {
        Holder<Biome> holder = level.getBiome(mob.blockPosition());
        for (String b : biomes) {
            if (b.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(b.substring(1));
                if (tagId != null && holder.is(TagKey.create(Registries.BIOME, tagId))) {
                    return true;
                }
            } else {
                ResourceLocation id = ResourceLocation.tryParse(b);
                if (id != null && holder.is(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }
}
