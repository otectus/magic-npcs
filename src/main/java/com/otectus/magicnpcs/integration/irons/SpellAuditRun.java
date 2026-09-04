package com.otectus.magicnpcs.integration.irons;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.otectus.magicnpcs.MagicNpcs;
import com.otectus.magicnpcs.api.event.MagicNpcCastEvent;
import com.otectus.magicnpcs.core.audit.AuditCursor;
import com.otectus.magicnpcs.core.audit.RefusalClassifier;
import com.otectus.magicnpcs.core.spell.SpellCapability;
import com.otectus.magicnpcs.core.spell.SpellSupportResolver;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.CastType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The runner behind {@code /magicnpcs audit spells}: walk every registered spell, one per step, and
 * write down what actually happens to a mob that tries to cast it.
 *
 * <p>This exists because the layered support resolver can only ever say what someone <em>claimed</em>
 * about a spell. With seventeen Iron's add-ons installed, a namespace-trusted spell is a guess until
 * something has run it, and hand-classifying 378 spells is exactly the mistake the manifest replaced.
 * So the audit asks the two empirical questions instead: does the spell resolve and pass Iron's own
 * pre-cast check ({@link Mode#RESOLVE}), and does a real {@link MobCastSession} carry it through the
 * whole Iron's lifecycle without throwing ({@link Mode#CAST}).
 *
 * <p>Neither mode proves a spell does anything <em>useful</em>. The report says so, and gives the mana
 * and nearby-entity deltas as the only honest hints available: a spell that spent mana and changed
 * nothing observable is flagged {@code NO_OBSERVABLE_EFFECT} rather than reported as working.
 *
 * <p>One run per server, driven from the existing server-tick handler. Every Iron's call is wrapped
 * individually — the entire point is to survive a spell that throws and record which one it was, so a
 * single bad add-on spell cannot end the audit that would have named it.
 */
public final class SpellAuditRun {

    /** What the audit does to each spell. */
    public enum Mode {
        /** Resolve, inspect and pre-cast check only. No spell effect ever runs. */
        RESOLVE,
        /** Everything RESOLVE does, then actually cast the spell on a dummy. Has real side effects. */
        CAST
    }

    /** Ticks between two spells, so one spell's leftovers do not land in the next one's measurements. */
    private static final int SPACING_TICKS = 5;

    /** How long a single CAST-mode spell may channel before the audit gives up on it. */
    private static final int CAST_BUDGET_TICKS = 40;

    /** How many spells between chat progress lines: often enough to see life, rare enough to read. */
    private static final int PROGRESS_EVERY = 50;

    /** The box around the caster whose entity count is sampled before and after a cast. */
    private static final double OBSERVE_RADIUS = 16.0;

    /** Everything CAST mode gives a dummy caster; a real loadout's max mana is far below this. */
    private static final double DUMMY_MAX_MANA = 1000.0;

    private static final double DUMMY_MANA_REGEN = 20.0;

    /** The cast duration CAST mode forces, so a 100-tick channel does not cost the audit 100 ticks. */
    private static final int FORCED_CAST_TIME = 2;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The one run this server may have. A second {@code /magicnpcs audit spells} is refused, not queued. */
    private static SpellAuditRun active;

    /** One report row: the pure result plus the descriptive columns the table prints beside it. */
    private record Row(AuditCursor.Result result, String provenance, String castType, double manaCost) {}

    private final ServerLevel level;
    private final Mode mode;
    private final String namespace;
    private final CommandSourceStack source;
    private final AuditCursor cursor;
    private final List<Row> rows = new ArrayList<>();
    private final Mob caster;
    private final Mob target;
    private final String startedAt;

    /**
     * A caveat printed in the report header and in chat when the dummies cannot see each other, so a
     * page of target-spell refusals is not read as a compatibility finding.
     */
    private String headerWarning;

    /** The live CAST-mode session, or {@code null} between spells and in RESOLVE mode. */
    private MobCastSession session;
    private String castingProvenance;
    private String castingCastType;
    private double castingManaCost;
    private float castingManaBefore;
    private int castingEntitiesBefore;
    private long castingStartedMillis;

    private SpellAuditRun(ServerLevel level, Mode mode, String namespace, CommandSourceStack source,
                          List<String> ids, Mob caster, Mob target) {
        this.level = level;
        this.mode = mode;
        this.namespace = namespace;
        this.source = source;
        this.caster = caster;
        this.target = target;
        this.startedAt = LocalDateTime.now().format(STAMP);
        // The budget is measured from the end of the previous spell, so it carries the spacing gap
        // with it: a spell still channelling SPACING+CAST_BUDGET ticks after the last one finished has
        // had its full CAST_BUDGET_TICKS of channel.
        this.cursor = new AuditCursor(ids, SPACING_TICKS + CAST_BUDGET_TICKS, SPACING_TICKS,
                level.getServer().getTickCount());
    }

    /** @return the run in progress, if there is one. */
    public static Optional<SpellAuditRun> active() {
        return Optional.ofNullable(active);
    }

    /**
     * Spawn the dummies and start walking the spell list.
     *
     * @param namespace only audit spells in this namespace, or {@code null} for every registered spell
     * @param castMode  true to actually cast each spell; see {@link Mode#CAST}
     * @return the started run, or empty when a run is already active or nothing matched the filter
     */
    public static Optional<SpellAuditRun> start(ServerLevel level, BlockPos origin, String namespace,
                                                boolean castMode, CommandSourceStack source) {
        if (active != null) {
            return Optional.empty();
        }
        List<String> ids = collectSpellIds(namespace);
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        Mob caster = spawnDummy(level, origin, "magicnpcs audit caster");
        Mob target = spawnDummy(level, origin.offset(4, 0, 0), "magicnpcs audit target");
        if (caster == null || target == null) {
            if (caster != null) {
                caster.discard();
            }
            if (target != null) {
                target.discard();
            }
            return Optional.empty();
        }
        primeMana(caster);
        SpellAuditRun run = new SpellAuditRun(level, castMode ? Mode.CAST : Mode.RESOLVE, namespace,
                source, ids, caster, target);
        active = run;
        if (!caster.hasLineOfSight(target)) {
            // Every target spell raycasts from the caster to whatever it can see first; with the view
            // blocked they all refuse, and none of those refusals says anything about the spell.
            run.headerWarning = "dummies have no line of sight; target-spell refusals are not meaningful";
            run.tell(run.headerWarning);
        }
        MagicNpcs.LOGGER.info("[audit] started {} mode over {} spell(s){}", run.mode, ids.size(),
                namespace == null ? "" : " in namespace " + namespace);
        return Optional.of(run);
    }

    /** Every registered spell id except Iron's {@code none} placeholder, sorted, optionally filtered. */
    private static List<String> collectSpellIds(String namespace) {
        List<String> ids = new ArrayList<>();
        for (AbstractSpell spell : SpellRegistry.REGISTRY.get()) {
            if (spell == null || spell == SpellRegistry.none()) {
                continue;
            }
            ResourceLocation id = spell.getSpellResource();
            if (id == null || (namespace != null && !namespace.equals(id.getNamespace()))) {
                continue;
            }
            ids.add(id.toString());
        }
        ids.sort(String::compareTo);
        return ids;
    }

    private static Mob spawnDummy(ServerLevel level, BlockPos pos, String name) {
        Mob dummy = EntityType.ZOMBIE.create(level);
        if (dummy == null) {
            return null;
        }
        dummy.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0F, 0.0F);
        dummy.setNoAi(true);
        dummy.setInvulnerable(true);
        dummy.setSilent(true);
        dummy.setPersistenceRequired();
        dummy.setCustomName(Component.literal(name));
        level.addFreshEntity(dummy);
        return dummy;
    }

    /**
     * Give the dummy caster enough mana that no spell in the list can be refused for cost.
     *
     * <p>{@link CasterReconciler}'s own attribute helper is private and scaled by the loadout, so the
     * base values are set here directly: this mob is scenery for the audit, not a managed caster.
     */
    private static void primeMana(Mob caster) {
        AttributeInstance maxMana = caster.getAttribute(AttributeRegistry.MAX_MANA.get());
        AttributeInstance manaRegen = caster.getAttribute(AttributeRegistry.MANA_REGEN.get());
        if (maxMana != null) {
            maxMana.setBaseValue(DUMMY_MAX_MANA);
        }
        if (manaRegen != null) {
            manaRegen.setBaseValue(DUMMY_MANA_REGEN);
        }
        IronsBridge.initMana(caster);
    }

    // --- driving -------------------------------------------------------------------------------

    /** Advance the run by one server tick. Called from the server-tick handler; never re-entrant. */
    public void tick(MinecraftServer server) {
        int now = server.getTickCount();
        if (caster.isRemoved() || target.isRemoved() || level.getServer() == null) {
            // The level unloaded or something removed a dummy under us. There is nothing left to
            // measure with, so stop where we are rather than reporting rows nothing produced.
            abort("the audit dummies are gone");
            return;
        }
        if (session != null) {
            tickCast(now);
            return;
        }
        if (cursor.isDone()) {
            complete();
            return;
        }
        if (!cursor.shouldStep(now)) {
            return;
        }
        step(now);
    }

    /** Exercise the spell the cursor is on. In RESOLVE mode this is the whole spell's work. */
    private void step(int now) {
        String id = cursor.current();
        long started = System.currentTimeMillis();
        AbstractSpell spell;
        try {
            ResourceLocation parsed = ResourceLocation.tryParse(id);
            spell = parsed == null ? null : IronsBridge.getSpell(parsed);
        } catch (Throwable t) {
            record(now, id, "EXCEPTION", "getSpell: " + describe(t), 0, 0, started, "-", "-", 0.0);
            return;
        }
        if (spell == null) {
            record(now, id, "EXCEPTION", "getSpell returned null for a registered id", 0, 0, started,
                    "-", "-", 0.0);
            return;
        }
        String provenance;
        try {
            provenance = SpellCompat.provenanceOf(spell).name();
        } catch (Throwable t) {
            provenance = "?";
        }
        String castTypeName;
        int minLevel;
        double manaCost;
        try {
            CastType castType = spell.getCastType();
            castTypeName = castType == null ? "NONE" : castType.name();
            minLevel = spell.getMinLevel();
            manaCost = spell.getManaCost(minLevel);
        } catch (Throwable t) {
            record(now, id, "EXCEPTION", "inspect: " + describe(t), 0, 0, started, provenance, "-", 0.0);
            return;
        }
        if (!safeIsEnabled(spell)) {
            record(now, id, "DISABLED", "the spell is disabled in Iron's config", 0, 0, started,
                    provenance, castTypeName, manaCost);
            return;
        }
        try {
            SpellCompat.effectiveCastTime(spell, minLevel, caster);
        } catch (Throwable t) {
            record(now, id, "EXCEPTION", "getEffectiveCastTime: " + describe(t), 0, 0, started,
                    provenance, castTypeName, manaCost);
            return;
        }
        MobCastSession.Prepared prepared;
        try {
            clearCastState();
            // The probe must run the same preparation the real casting path does — target set, facing
            // snapped, cast data installed — or Iron's target helpers raycast into empty air and refuse
            // twelve verified spells that cast perfectly well in game (the 0.9.0 instance run).
            caster.setTarget(target);
            prepared = MobCastSession.prepare(caster, target, spell, minLevel);
        } catch (Throwable t) {
            record(now, id, "EXCEPTION", "checkPreCastConditions: " + describe(t), 0, 0, started,
                    provenance, castTypeName, manaCost);
            return;
        }
        clearCastState(); // the pre-cast probe may have installed cast data of its own
        if (!prepared.passed()) {
            RefusalClassifier.Classified classified = classifyRefusal(spell, prepared.refusal());
            record(now, id, outcomeName(classified.outcome()), classified.detail(), 0, 0,
                    started, provenance, castTypeName, manaCost);
            return;
        }
        if (mode == Mode.RESOLVE) {
            record(now, id, "OK", "", 0, 0, started, provenance, castTypeName, manaCost);
            return;
        }
        beginCast(now, id, spell, minLevel, provenance, castTypeName, manaCost, started);
    }

    /** Hand the spell to a real session, exactly as the casting goal would. */
    private void beginCast(int now, String id, AbstractSpell spell, int minLevel, String provenance,
                           String castTypeName, double manaCost, long started) {
        IronsBridge.initMana(caster); // every spell starts from a full bar, so cost never refuses one
        castingManaBefore = IronsBridge.currentMana(caster);
        castingEntitiesBefore = countNearbyEntities();
        caster.setTarget(target);
        MobCastSession.Start start;
        try {
            start = MobCastSession.begin(caster, target, spell, minLevel, FORCED_CAST_TIME,
                    MagicNpcCastEvent.CastSource.SCRIPT);
        } catch (Throwable t) {
            record(now, id, "EXCEPTION", "begin: " + describe(t), 0, 0, started, provenance,
                    castTypeName, manaCost);
            return;
        }
        if (!start.started()) {
            if (start.refusal() == MobCastSession.RefusalReason.PRE_CAST_REFUSED) {
                // The same refusal the RESOLVE probe reports, and it deserves the same reading.
                RefusalClassifier.Classified classified = classifyRefusal(spell, start.refusal());
                record(now, id, outcomeName(classified.outcome()), classified.detail(), 0, 0, started,
                        provenance, castTypeName, manaCost);
                return;
            }
            String detail = start.refusal() == null
                    ? String.valueOf(start.detail())
                    : start.refusal().name() + " — " + start.refusal().description();
            record(now, id, "REFUSED", detail, 0, 0, started, provenance, castTypeName, manaCost);
            return;
        }
        session = start.session();
        castingProvenance = provenance;
        castingCastType = castTypeName;
        castingManaCost = manaCost;
        castingStartedMillis = started;
    }

    /** Drive the live session one tick, and close the row out when it ends or runs out of budget. */
    private void tickCast(int now) {
        if (cursor.budgetExceeded(now)) {
            try {
                session.cancel(MobCastSession.CancelReason.GOAL_STOPPED);
            } catch (Throwable t) {
                MagicNpcs.LOGGER.debug("[audit] cancel threw for {}", cursor.current(), t);
            }
            finishCast(now, "TIMEOUT", "still channelling after " + CAST_BUDGET_TICKS + " ticks");
            return;
        }
        boolean running;
        try {
            running = session.tick();
        } catch (Throwable t) {
            finishCast(now, "EXCEPTION", "tick: " + describe(t));
            return;
        }
        if (running) {
            return;
        }
        boolean completed = session.state() == MobCastSession.State.COMPLETE;
        finishCast(now, completed ? "LIFECYCLE_COMPLETED" : "CANCELLED",
                completed ? "" : "the session ended before its duration elapsed");
    }

    private void finishCast(int now, String outcome, String detail) {
        int manaDelta = Math.round(IronsBridge.currentMana(caster) - castingManaBefore);
        int entityDelta = countNearbyEntities() - castingEntitiesBefore;
        String note = detail;
        if (manaDelta == 0 && entityDelta == 0 && castingManaCost > 0.0
                && "LIFECYCLE_COMPLETED".equals(outcome)) {
            // The honest limit of this audit: the lifecycle ran, and nothing we can see changed.
            note = note.isEmpty() ? "NO_OBSERVABLE_EFFECT" : note + "; NO_OBSERVABLE_EFFECT";
        }
        String id = cursor.current();
        session = null;
        clearCastState();
        record(now, id, outcome, note, manaDelta, entityDelta, castingStartedMillis,
                castingProvenance, castingCastType, castingManaCost);
    }

    /**
     * Read a pre-cast refusal against what this build claims about the spell, so the report can tell a
     * player-only spell doing exactly what it should from a verified spell contradicting the manifest.
     */
    private RefusalClassifier.Classified classifyRefusal(AbstractSpell spell,
                                                         MobCastSession.RefusalReason reason) {
        SpellCapability capability;
        SpellSupportResolver.Provenance provenance;
        try {
            SpellSupportResolver.Verdict verdict = SpellCompat.verdictOf(spell);
            capability = verdict.capability();
            provenance = verdict.provenance();
        } catch (Throwable t) {
            capability = null;
            provenance = null;
        }
        String detail = capability == SpellCapability.TARGET_ENTITY
                ? "target raycast missed (manifest: TARGET_ENTITY)"
                : (reason == null ? "Iron's refused the cast before it started" : reason.description());
        return RefusalClassifier.classify(capability, provenance, true, detail);
    }

    /**
     * The outcome column stays the vocabulary the report already had: a suspect refusal is a
     * {@code PRECAST_REFUSED} row carrying {@code [MANIFEST_SUSPECT]} in its detail.
     */
    private static String outcomeName(RefusalClassifier.Outcome outcome) {
        return outcome == RefusalClassifier.Outcome.PRECAST_REFUSED_SUSPECT
                ? RefusalClassifier.Outcome.PRECAST_REFUSED.name()
                : outcome.name();
    }

    /**
     * Leave Iron's casting state exactly as we found it between two spells: a channel Iron's still
     * believes in would refuse every remaining spell with {@code ALREADY_CASTING}, and cast data left
     * behind would be read by the next spell as its own.
     */
    private void clearCastState() {
        try {
            MagicData data = MagicData.getPlayerMagicData(caster);
            if (data.isCasting()) {
                data.resetCastingState();
            }
            if (data.getAdditionalCastData() != null) {
                data.resetAdditionalCastData();
            }
        } catch (Throwable t) {
            MagicNpcs.LOGGER.debug("[audit] could not reset casting state", t);
        }
    }

    private int countNearbyEntities() {
        try {
            AABB box = caster.getBoundingBox().inflate(OBSERVE_RADIUS);
            return level.getEntitiesOfClass(Entity.class, box).size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean safeIsEnabled(AbstractSpell spell) {
        try {
            return spell.isEnabled();
        } catch (Throwable t) {
            return true; // a spell whose own enabled check throws is still worth auditing
        }
    }

    private void record(int now, String id, String outcome, String detail, int manaDelta,
                        int entityDelta, long startedMillis, String provenance, String castType,
                        double manaCost) {
        rows.add(new Row(new AuditCursor.Result(id, outcome, detail, manaDelta, entityDelta,
                System.currentTimeMillis() - startedMillis), provenance, castType, manaCost));
        cursor.advance(now);
        if (rows.size() % PROGRESS_EVERY == 0) {
            tell("audit " + cursor.progress() + " …");
        }
    }

    // --- ending --------------------------------------------------------------------------------

    /** Cancel the run, discard the dummies, and still write the rows collected so far. */
    public void cancel() {
        tell("Audit cancelled at " + cursor.progress() + ".");
        end();
    }

    private void abort(String reason) {
        tell("Audit stopped: " + reason + " (" + cursor.progress() + ").");
        end();
    }

    private void complete() {
        Path report = end();
        if (report != null) {
            tell("Audit finished: " + rows.size() + " spell(s). Report: " + report);
        }
    }

    /**
     * Tear the run down exactly once and write both report files.
     *
     * @return the text report path, or {@code null} if it could not be written
     */
    private Path end() {
        if (session != null) {
            try {
                session.cancel(MobCastSession.CancelReason.GOAL_STOPPED);
            } catch (Throwable t) {
                MagicNpcs.LOGGER.debug("[audit] cancel threw while ending the run", t);
            }
            session = null;
        }
        clearCastState();
        caster.discard();
        target.discard();
        active = null;
        try {
            return writeReport();
        } catch (IOException e) {
            MagicNpcs.LOGGER.error("[audit] could not write the audit report", e);
            tell("Audit finished but the report could not be written: " + e.getMessage());
            return null;
        }
    }

    /** Cancel whatever run is active, if any. Called when the server stops. */
    public static void cancelActive() {
        if (active != null) {
            active.cancel();
        }
    }

    // --- reporting -----------------------------------------------------------------------------

    private Path writeReport() throws IOException {
        Path dir = FMLPaths.GAMEDIR.get().resolve("magicnpcs");
        Files.createDirectories(dir);
        Path text = dir.resolve("audit-spells-" + startedAt + ".txt");
        Files.writeString(text, renderText(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("audit-spells-" + startedAt + ".json"), renderJson(),
                StandardCharsets.UTF_8);
        return text;
    }

    private String renderText() {
        StringBuilder out = new StringBuilder();
        out.append("Magic NPCs spell audit\n");
        out.append("magicnpcs: ").append(modVersion("magicnpcs")).append('\n');
        out.append("irons_spellbooks: ").append(modVersion("irons_spellbooks")).append('\n');
        out.append("mode: ").append(mode).append('\n');
        out.append("namespace filter: ").append(namespace == null ? "(all)" : namespace).append('\n');
        out.append("started: ").append(startedAt).append('\n');
        out.append("spells audited: ").append(rows.size()).append(" of ").append(cursor.total())
                .append('\n');
        out.append(ManifestReconciler.summary()).append('\n');
        if (headerWarning != null) {
            out.append("WARNING: ").append(headerWarning).append('\n');
        }
        out.append("NOTE: this audit proves a spell resolves, passes Iron's pre-cast check and (in CAST\n")
                .append("mode) survives the whole cast lifecycle. It does NOT prove the spell does\n")
                .append("anything useful; the mana and entity deltas are hints, not verdicts.\n\n");

        int idWidth = Math.max(2, rows.stream().mapToInt(r -> r.result().id().length()).max().orElse(2));
        String format = "%-" + idWidth + "s | %-16s | %-10s | %6s | %-19s | %6s | %5s | %s%n";
        out.append(String.format(format, "id", "provenance", "cast type", "mana", "outcome",
                "manaD", "entD", "detail"));
        out.append("-".repeat(idWidth + 80)).append('\n');
        for (Row row : rows) {
            AuditCursor.Result r = row.result();
            out.append(String.format(format, r.id(), row.provenance(), row.castType(),
                    String.format(Locale.ROOT, "%.1f", row.manaCost()), r.outcome(),
                    String.valueOf(r.manaDelta()), String.valueOf(r.entityDelta()), r.detail()));
        }

        out.append("\nby outcome:\n");
        countBy(row -> row.result().outcome()).forEach(
                (key, count) -> out.append("  ").append(key).append(": ").append(count).append('\n'));
        out.append("\nby namespace:\n");
        countBy(row -> namespaceOf(row.result().id())).forEach(
                (key, count) -> out.append("  ").append(key).append(": ").append(count).append('\n'));

        List<String> suspects = suspectIds();
        out.append("\nrefusals contradicting the manifest: ").append(suspects.size()).append('\n');
        for (String suspect : suspects) {
            out.append("  ").append(suspect).append('\n');
        }
        out.append("expected player-only refusals: ").append(expectedPlayerOnly()).append('\n');
        out.append("expected unsupported refusals: ")
                .append(countOutcome(RefusalClassifier.Outcome.EXPECTED_UNSUPPORTED)).append('\n');
        return out.toString();
    }

    private String renderJson() {
        JsonObject root = new JsonObject();
        root.addProperty("magicnpcs", modVersion("magicnpcs"));
        root.addProperty("irons_spellbooks", modVersion("irons_spellbooks"));
        root.addProperty("mode", mode.name());
        root.addProperty("namespace", namespace);
        root.addProperty("started", startedAt);
        root.addProperty("audited", rows.size());
        root.addProperty("total", cursor.total());
        JsonArray array = new JsonArray();
        for (Row row : rows) {
            AuditCursor.Result r = row.result();
            JsonObject entry = new JsonObject();
            entry.addProperty("id", r.id());
            entry.addProperty("provenance", row.provenance());
            entry.addProperty("cast_type", row.castType());
            entry.addProperty("mana_cost", row.manaCost());
            entry.addProperty("outcome", r.outcome());
            entry.addProperty("detail", r.detail());
            entry.addProperty("mana_delta", r.manaDelta());
            entry.addProperty("entity_delta", r.entityDelta());
            entry.addProperty("millis", r.millis());
            array.add(entry);
        }
        root.add("rows", array);
        return GSON.toJson(root);
    }

    /**
     * The rows this build verified as mob-castable and Iron's refused anyway: either the manifest is
     * wrong about the spell, or Iron's changed under it. Both are worth a name, not just a count.
     */
    private List<String> suspectIds() {
        List<String> ids = new ArrayList<>();
        for (Row row : rows) {
            if (row.result().detail() != null && row.result().detail().contains("[MANIFEST_SUSPECT]")) {
                ids.add(row.result().id());
            }
        }
        return ids;
    }

    /** How many refusals this build already predicted, so they are not read as failures. */
    private long expectedPlayerOnly() {
        return countOutcome(RefusalClassifier.Outcome.EXPECTED_PLAYER_ONLY);
    }

    private long countOutcome(RefusalClassifier.Outcome outcome) {
        return rows.stream()
                .filter(r -> outcome.name().equals(r.result().outcome()))
                .count();
    }

    private Map<String, Integer> countBy(java.util.function.Function<Row, String> key) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Row row : rows) {
            counts.merge(key.apply(row), 1, Integer::sum);
        }
        return new LinkedHashMap<>(counts);
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "(none)" : id.substring(0, colon);
    }

    private static String modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("absent");
    }

    /**
     * An exception rendered small enough for a table cell but large enough to act on: the class, the
     * message, and the first frame — which is the line that names the add-on doing the throwing.
     */
    private static String describe(Throwable t) {
        StackTraceElement[] trace = t.getStackTrace();
        String frame = trace.length > 0 ? " at " + trace[0] : "";
        return t.getClass().getSimpleName() + ": " + t.getMessage() + frame;
    }

    private void tell(String message) {
        MagicNpcs.LOGGER.info("[audit] {}", message);
        if (source != null) {
            source.sendSystemMessage(Component.literal("[magicnpcs] " + message));
        }
    }

    // --- status --------------------------------------------------------------------------------

    public Mode mode() {
        return mode;
    }

    public String namespaceFilter() {
        return namespace;
    }

    /** @return {@code "n/total"} for {@code /magicnpcs audit status}. */
    public String progress() {
        return cursor.progress();
    }
}
