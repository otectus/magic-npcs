package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.api.event.MagicNpcCastEvent;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * One request to cast one spell, normalised once and then immutable.
 *
 * <p>Through 0.9.0 the four ways into casting — the AI goal, the detached driver, a CustomNPCs script
 * and an Easy NPC action — each carried the author's or caller's own spelling of the spell id, their
 * own idea of what "level" meant, and their own subset of the checks. So the same spell could be two
 * cooldowns (roadmap MN-005), and a level nobody had bounded reached Iron's timing and mana
 * arithmetic (MN-003). Every path now builds one of these first, and everything downstream reads the
 * request rather than re-deriving the facts.
 *
 * @param caster      the mob that will cast
 * @param target      the recipient for a targeted cast, or {@code null} for a self-cast
 * @param spell       the resolved Iron's spell
 * @param canonicalId {@code spell.getSpellResource()} — the single identity used for cooldowns,
 *                    retained keys and cast events
 * @param requestedId exactly what the author or caller wrote, kept for diagnostics only
 * @param level       the spell level, already bounded to the spell's own min/max
 * @param source      whose decision this was, so the terminal announcements match the start
 * @param selfCast    true when the request deliberately has no offensive recipient
 */
public record CastRequest(Mob caster, LivingEntity target, AbstractSpell spell,
                          ResourceLocation canonicalId, ResourceLocation requestedId,
                          int level, MagicNpcCastEvent.CastSource source, boolean selfCast) {

    /**
     * Build a request, resolving the spell id to its one canonical identity and bounding the level.
     *
     * <p>Bounding happens here rather than at each call site because it has to happen before anything
     * reads the level: Iron's derives mana cost, cast time and effect magnitude from it, and a
     * negative or absurd level reaching those is exactly the malformed input MN-003 describes. The
     * authored value is not "corrected" silently in the diagnostic sense — {@link #levelWasBounded}
     * reports the difference so a caller can say so.
     *
     * @return the request, or {@code null} when {@code spellId} resolves to no registered spell
     */
    public static CastRequest of(Mob caster, LivingEntity target, ResourceLocation spellId, int level,
                                 MagicNpcCastEvent.CastSource source) {
        if (caster == null || spellId == null) {
            return null;
        }
        AbstractSpell spell = IronsBridge.getSpell(spellId);
        if (spell == null) {
            return null;
        }
        return of(caster, target, spell, spellId, level, source);
    }

    /** As {@link #of(Mob, LivingEntity, ResourceLocation, int, MagicNpcCastEvent.CastSource)}, for an
     *  already resolved spell — the AI goal resolves its loadout once at construction. */
    public static CastRequest of(Mob caster, LivingEntity target, AbstractSpell spell,
                                 ResourceLocation requestedId, int level,
                                 MagicNpcCastEvent.CastSource source) {
        ResourceLocation canonical = spell.getSpellResource();
        return new CastRequest(caster, target,
                spell,
                canonical == null ? requestedId : canonical,
                requestedId == null ? canonical : requestedId,
                boundLevel(spell, level),
                source,
                target == null);
    }

    /**
     * Clamp to the spell's own declared range. A spell that declares levels 1..10 has no behaviour
     * defined outside them, and Iron's arithmetic does not reject the request — it simply computes
     * with whatever arrives.
     */
    public static int boundLevel(AbstractSpell spell, int level) {
        int min = Math.max(1, spell.getMinLevel());
        int max = Math.max(min, spell.getMaxLevel());
        return Math.max(min, Math.min(level, max));
    }

    /** @return true when the caller's level was outside the spell's range and had to be brought in. */
    public boolean levelWasBounded(int requestedLevel) {
        return requestedLevel != level;
    }

    /** @return the same request aimed at a different recipient, e.g. a goal re-validating its target. */
    public CastRequest withTarget(LivingEntity newTarget) {
        return new CastRequest(caster, newTarget, spell, canonicalId, requestedId, level, source,
                newTarget == null);
    }

    /** @return true when the author's spelling and the canonical id differ, for a diagnostic line. */
    public boolean isAlias() {
        return requestedId != null && !requestedId.equals(canonicalId);
    }

    /** A short "what was asked for" string for a log line or a refusal message. */
    public String describe() {
        return isAlias() ? requestedId + " (" + canonicalId + ")" : String.valueOf(canonicalId);
    }
}
