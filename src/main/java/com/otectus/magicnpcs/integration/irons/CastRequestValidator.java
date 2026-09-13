package com.otectus.magicnpcs.integration.irons;

import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.adapter.NpcAdapters;
import com.otectus.magicnpcs.core.caster.FrameworkEligibility;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * The mandatory part of "may this cast happen", asked the same way whoever is asking.
 *
 * <p>Through 0.9.0 each entry point applied its own subset. The AI goal checked relationship, range
 * and line of sight; the CustomNPCs script API checked that the target UUID named a living entity in
 * the same level and then delegated; Easy NPC filtered candidates but could still pass {@code null};
 * the detached driver re-checked only that its caster was still there. So a scripted cast could do
 * things the same NPC's own AI would have refused (roadmap MN-003).
 *
 * <p><b>Side-effect free, deliberately.</b> Readiness is asked in places that must not commit to
 * anything — a {@code canCast} query, a diagnostic table, a decision that is then vetoed. So this
 * prepares no spell, creates no managed state ({@link ManagedCasterState#peek} only), spends nothing,
 * publishes no event and consumes no RNG. Anything that must happen exactly once at acceptance stays
 * in {@link MobCastSession}.
 *
 * <p>What it does <em>not</em> do is police the discretionary half: weighted selection, cast chance,
 * wind-up and per-entry conditions stay in {@link NpcSpellAttackGoal}, because an explicit scripted
 * cast is meant to bypass them. This is the floor every caller shares, not the AI's policy.
 */
public final class CastRequestValidator {

    private CastRequestValidator() {}

    /** Why a request is refused. Stable names: commands, scripts and tests key off these. */
    public enum Problem {
        /** No problem found. */
        NONE("the request is valid"),
        SPELL_UNKNOWN("no spell is registered under that id"),
        NOT_SERVER_SIDE("casting was requested on the client"),
        CASTER_UNAVAILABLE("the caster is dead, removed, or has its AI disabled"),
        FRAMEWORK_UNSUPPORTED("the NPC's own mod is at a build this bridge does not support"),
        FRAMEWORK_UNAVAILABLE("the NPC's own mod is installed but its bridge is not running"),
        FRAMEWORK_DISABLED("the integration for the NPC's own mod is switched off"),
        SPELL_BLACKLISTED("the spell is excluded by spells.spellBlacklist / spellWhitelist"),
        NOT_CASTABLE_BY_MOB("no verified mob-cast strategy for this spell"),
        CASTER_BUSY("the caster is already channelling a spell"),
        ACTIVITY_REFUSED("the NPC's own mod says it may not act right now"),
        NEEDS_TARGET("the spell needs a target entity and none was supplied"),
        TARGET_INVALID("the target is dead, removed, or not in the caster's level"),
        TARGET_IS_CASTER("the caster was given as its own offensive target"),
        TARGET_PROTECTED("the target is an ally the caster must not cast at"),
        TARGET_NOT_VISIBLE("the target is not in line of sight"),
        ON_COOLDOWN("the spell is still resting"),
        INSUFFICIENT_MANA("the caster cannot afford the spell");

        private final String description;

        Problem(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /**
     * @param detail extra context — the remaining cooldown, the unsupported build — or {@code null}
     */
    public record Verdict(Problem problem, String detail) {

        private static final Verdict OK = new Verdict(Problem.NONE, null);

        public boolean ok() {
            return problem == Problem.NONE;
        }

        /** A one-line explanation for a command, a script result or a debug log. */
        public String describe() {
            return problem.description() + (detail == null ? "" : " — " + detail);
        }
    }

    public static Verdict ok() {
        return Verdict.OK;
    }

    private static Verdict no(Problem problem, String detail) {
        return new Verdict(problem, detail);
    }

    /**
     * Run every mandatory check, in the cheapest-first order that also makes the first refusal the
     * most informative one.
     *
     * @param request the normalised request; {@code null} means the spell id did not resolve
     */
    public static Verdict validate(CastRequest request) {
        if (request == null) {
            return no(Problem.SPELL_UNKNOWN,
                    "run /magicnpcs spells to list the ids this build accepts");
        }
        Mob caster = request.caster();
        if (caster.level().isClientSide()) {
            return no(Problem.NOT_SERVER_SIDE, null);
        }
        if (!caster.isAlive() || caster.isRemoved() || caster.isNoAi()) {
            return no(Problem.CASTER_UNAVAILABLE, null);
        }

        // The framework gate first, and before anything is read off the spell: an NPC whose own mod is
        // at an unsupported build must not be managed at all, whatever it was asked to cast (MN-002).
        FrameworkEligibility.Verdict framework = FrameworkEligibility.check(caster);
        if (!framework.allowed()) {
            return switch (framework.decision()) {
                case UNSUPPORTED_FRAMEWORK -> no(Problem.FRAMEWORK_UNSUPPORTED, framework.detail());
                case FRAMEWORK_DISABLED -> no(Problem.FRAMEWORK_DISABLED, framework.detail());
                default -> no(Problem.FRAMEWORK_UNAVAILABLE, framework.detail());
            };
        }

        if (!IronsBridge.isAllowedSpell(request.spell())) {
            return no(Problem.SPELL_BLACKLISTED, request.describe());
        }
        if (!SpellCompat.castableByMob(request.spell())) {
            return no(Problem.NOT_CASTABLE_BY_MOB, SpellCompat.unsupportedReason(request.spell()));
        }
        if (!NpcAdapters.resolve(caster).canCastNow(caster)) {
            return no(Problem.ACTIVITY_REFUSED, null);
        }

        Verdict recipient = validateRecipient(request);
        if (!recipient.ok()) {
            return recipient;
        }

        ManagedCasterState state = ManagedCasterState.peek(caster);
        if (state != null) {
            long remaining = state.cooldownRemaining(request.canonicalId(), caster);
            if (remaining > 0) {
                return no(Problem.ON_COOLDOWN, remaining + " ticks left");
            }
        }
        if (!IronsBridge.canAfford(caster, request.spell(), request.level())) {
            return no(Problem.INSUFFICIENT_MANA, null);
        }
        return ok();
    }

    /**
     * The target policy every caller shares: identity, world, liveness and relationship.
     *
     * <p>A genuine self-cast is not made to invent a recipient — a support spell with no target is
     * the normal out-of-combat case. What is refused is an <em>offensive</em> request with no
     * recipient, and a recipient the caster is not allowed to attack.
     */
    private static Verdict validateRecipient(CastRequest request) {
        LivingEntity target = request.target();
        Mob caster = request.caster();
        boolean needsTarget = SpellCompat.requiresTargetEntity(request.spell());
        if (target == null) {
            return needsTarget ? no(Problem.NEEDS_TARGET, request.describe()) : ok();
        }
        if (target == caster) {
            // A self-cast is expressed by passing no target at all. Passing the caster as its own
            // offensive recipient is a malformed request, not a support cast.
            return no(Problem.TARGET_IS_CASTER, null);
        }
        if (!target.isAlive() || target.isRemoved() || target.level() != caster.level()) {
            return no(Problem.TARGET_INVALID, null);
        }
        if (!NpcAdapters.resolve(caster).canCastAt(caster, target)) {
            return no(Problem.TARGET_PROTECTED, null);
        }
        if (MagicNpcsConfig.REQUIRE_LINE_OF_SIGHT.get() && !caster.getSensing().hasLineOfSight(target)) {
            return no(Problem.TARGET_NOT_VISIBLE, null);
        }
        return ok();
    }
}
