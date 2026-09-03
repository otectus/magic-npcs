package com.otectus.magicnpcs.compat.customnpcs;

import com.otectus.magicnpcs.api.event.MagicNpcSchoolChangedEvent;
import com.otectus.magicnpcs.config.MagicNpcsConfig;
import com.otectus.magicnpcs.core.SchoolAssignResult;
import com.otectus.magicnpcs.core.SchoolData;
import com.otectus.magicnpcs.core.caster.ManagedCasterState;
import com.otectus.magicnpcs.core.diag.DiagnosticReport;
import com.otectus.magicnpcs.core.loadout.LoadoutManager;
import com.otectus.magicnpcs.core.loadout.LoadoutResolution;
import com.otectus.magicnpcs.integration.irons.CasterDiagnostics;
import com.otectus.magicnpcs.integration.irons.DetachedCastDriver;
import com.otectus.magicnpcs.integration.irons.IronsBridge;
import com.otectus.magicnpcs.integration.irons.IronsSpellcasterHandler;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;

import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The working {@link CustomNpcsScriptApi}: every question answered from the Iron's-side casting code.
 *
 * <p>Imports {@code integration.irons}, so it is constructed <em>only</em> inside the Iron's Spellbooks
 * guard in {@link CustomNpcsIntegration}. Without Iron's the bridge holds
 * {@link CustomNpcsScriptApi#inactive()} instead and a script gets {@code BRIDGE_INACTIVE}, which is a
 * statement rather than a {@code NoClassDefFoundError} out of a script engine.
 *
 * <p>Names no CustomNPCs type: the wrapper is unwrapped by the bridge before it gets here. That is what
 * lets the whole read/write surface be reasoned about without CustomNPCs in the picture at all.
 *
 * <p>Every public method funnels through {@link #read} or {@link #mutate}, which apply the checks a
 * script cannot be trusted to have applied — server side, entity still alive, mutations allowed — and
 * turn any {@link RuntimeException} into {@link ResultCode#INTERNAL_ERROR}. A script engine turns a
 * thrown exception into an error the pack author cannot act on, so nothing here throws.
 */
public final class CustomNpcsScriptApiIrons implements CustomNpcsScriptApi {

    /** Every mutation made through this API was asked for by a script, whatever the script's reason. */
    private static final MagicNpcSchoolChangedEvent.ChangeSource SOURCE =
            MagicNpcSchoolChangedEvent.ChangeSource.SCRIPT;

    // --- reads ---------------------------------------------------------------------------------

    @Override
    public Result isCaster(Mob mob) {
        return read(mob, () -> Result.ok(IronsSpellcasterHandler.findSpellGoal(mob) != null));
    }

    @Override
    public Result getSchool(Mob mob) {
        return read(mob, () -> {
            ResourceLocation school = SchoolData.getSchool(mob);
            // The three states are distinct and a script needs all three: a school, an explicit
            // non-caster mark, or never rolled. Reported as the raw stored value for exactly that
            // reason — collapsing "none" and "unrolled" to an empty string loses the difference.
            String raw = SchoolData.getRaw(mob);
            return Result.ok(school != null ? school.toString() : (raw == null ? "" : raw));
        });
    }

    @Override
    public Result getLoadout(Mob mob) {
        return read(mob, () -> {
            LoadoutResolution resolution = LoadoutManager.peek(mob);
            if (resolution.loadout() == null) {
                return Result.no(ResultCode.NOT_CASTER, "no loadout applies: "
                        + resolution.status().name().toLowerCase(java.util.Locale.ROOT)
                        + (resolution.detail() == null ? "" : " (" + resolution.detail() + ")"));
            }
            return Result.ok(String.valueOf(resolution.loadout().source()));
        });
    }

    @Override
    public Result getMana(Mob mob) {
        return read(mob, () -> Result.ok((double) IronsBridge.currentMana(mob)));
    }

    @Override
    public Result getMaxMana(Mob mob) {
        return read(mob, () -> {
            AttributeInstance max = mob.getAttribute(AttributeRegistry.MAX_MANA.get());
            return max == null
                    ? Result.no(ResultCode.NOT_CASTER, "this entity type has no Iron's MAX_MANA attribute")
                    : Result.ok(max.getValue());
        });
    }

    @Override
    public Result canCast(Mob mob, String spell, int level) {
        return read(mob, () -> {
            ResourceLocation spellId = parseId(spell);
            if (spellId == null) {
                return Result.no(ResultCode.INVALID_ARGUMENT, "'" + spell + "' is not a spell id");
            }
            AbstractSpell resolved = IronsBridge.getSpell(spellId);
            if (resolved == null || !IronsBridge.isAllowedSpell(resolved)) {
                return Result.no(ResultCode.SPELL_NOT_ALLOWED, spellId
                        + " is unknown, blacklisted, or not castable by a mob in this build");
            }
            ManagedCasterState state = ManagedCasterState.peek(mob);
            if (state != null && state.cooldownRemaining(spellId, mob.tickCount) > 0) {
                return Result.no(ResultCode.ON_COOLDOWN, spellId + " is on cooldown for another "
                        + state.cooldownRemaining(spellId, mob.tickCount) + " ticks");
            }
            if (!IronsBridge.canAfford(mob, resolved, level)) {
                return Result.no(ResultCode.NO_MANA, spellId + " at level " + level
                        + " costs more mana than this NPC has");
            }
            return Result.ok(true);
        });
    }

    @Override
    public Result why(Mob mob) {
        return read(mob, () -> {
            DiagnosticReport report = CasterDiagnostics.describe(mob);
            StringJoiner joined = new StringJoiner("\n");
            for (DiagnosticReport.Line line : report.lines()) {
                joined.add(line.text());
            }
            return Result.ok(joined.toString());
        });
    }

    // --- mutations -----------------------------------------------------------------------------

    @Override
    public Result setSchool(Mob mob, String school) {
        return mutate(mob, () -> {
            ResourceLocation schoolId = parseId(school);
            if (schoolId == null) {
                return Result.no(ResultCode.INVALID_ARGUMENT, "'" + school + "' is not a school id");
            }
            SchoolAssignResult outcome = IronsSpellcasterHandler.applySchool(mob, schoolId, SOURCE);
            if (outcome.ok()) {
                return Result.ok(schoolId.toString());
            }
            ResultCode code = switch (outcome) {
                case UNKNOWN_SCHOOL, SCHOOL_NOT_ALLOWED, SCHOOLS_DISABLED -> ResultCode.SCHOOL_NOT_ALLOWED;
                case NO_CASTABLE_SPELLS -> ResultCode.SPELL_NOT_ALLOWED;
                case OK -> ResultCode.OK;
            };
            return Result.no(code, outcome.describe(schoolId));
        });
    }

    @Override
    public Result clearSchool(Mob mob) {
        return mutate(mob, () -> {
            IronsSpellcasterHandler.clearSchool(mob, SOURCE);
            return Result.ok(true);
        });
    }

    @Override
    public Result returnToAuto(Mob mob) {
        return mutate(mob, () -> {
            IronsSpellcasterHandler.resetSchoolToAuto(mob, SOURCE);
            return Result.ok(true);
        });
    }

    @Override
    public Result setCastingSuspended(Mob mob, boolean suspended) {
        return mutate(mob, () -> {
            CustomNpcsActivityState.setScriptSuspended(mob, suspended);
            return Result.ok(suspended);
        });
    }

    @Override
    public Result cast(Mob mob, String spell, int level, UUID target) {
        return mutate(mob, () -> {
            ResourceLocation spellId = parseId(spell);
            if (spellId == null) {
                return Result.no(ResultCode.INVALID_ARGUMENT, "'" + spell + "' is not a spell id");
            }
            LivingEntity aimedAt = null;
            if (target != null) {
                Entity found = ((ServerLevel) mob.level()).getEntity(target);
                if (!(found instanceof LivingEntity living) || !living.isAlive()) {
                    return Result.no(ResultCode.NO_TARGET,
                            "no living entity with uuid " + target + " is loaded in this level");
                }
                aimedAt = living;
            }
            // Through the detached driver, not straight at MobCastSession: the driver is what ticks a
            // cast nobody's goal owns, and it applies the same allow-list, mana and cooldown gates the
            // AI path does. A scripted cast must not be able to do something a chosen cast could not.
            DetachedCastDriver.Result outcome = DetachedCastDriver.cast(mob, aimedAt, spellId, level);
            if (outcome.started()) {
                return Result.ok(true);
            }
            String detail = outcome.detail() == null ? "the cast was refused" : outcome.detail();
            ResultCode code;
            if (outcome.refusal() == DetachedCastDriver.Refusal.NOT_A_CASTER) {
                code = ResultCode.NOT_CASTER;
            } else if (outcome.refusal() == DetachedCastDriver.Refusal.ON_COOLDOWN) {
                code = ResultCode.ON_COOLDOWN;
            } else {
                code = ResultCode.SPELL_NOT_ALLOWED;
            }
            return Result.no(code, detail);
        });
    }

    // --- gates ---------------------------------------------------------------------------------

    /** Server side, entity present, nothing thrown. The floor every operation stands on. */
    private static Result read(Mob mob, Supplier<Result> body) {
        if (mob == null) {
            return Result.no(ResultCode.ENTITY_GONE, "there is no entity to ask about");
        }
        if (!(mob.level() instanceof ServerLevel)) {
            return Result.no(ResultCode.NOT_SERVER, "the Magic NPCs script surface is server-side only");
        }
        if (!mob.isAlive() || mob.isRemoved()) {
            return Result.no(ResultCode.ENTITY_GONE, "that NPC is dead or has been removed");
        }
        try {
            return body.get();
        } catch (RuntimeException ex) {
            // The script gets a code and a sentence. The log gets the stack trace, because that is
            // where someone can actually act on it.
            com.otectus.magicnpcs.MagicNpcs.LOGGER.warn(
                    "[magicnpcs] a CustomNPCs script operation failed: {}", ex.toString(), ex);
            return Result.no(ResultCode.INTERNAL_ERROR, ex.toString());
        }
    }

    /** As {@link #read}, plus the read-only-server check. */
    private static Result mutate(Mob mob, Supplier<Result> body) {
        if (!MagicNpcsConfig.customNpcsScriptMutationsEnabled()) {
            return Result.no(ResultCode.MUTATIONS_DISABLED,
                    "this server runs the Magic NPCs script bridge read-only "
                            + "(customnpcs.scriptMutationsEnabled = false)");
        }
        return read(mob, body);
    }

    /** @return the id, or {@code null} for anything a script could plausibly have typed by mistake. */
    private static ResourceLocation parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return ResourceLocation.tryParse(raw.trim());
    }
}
