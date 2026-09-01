package com.otectus.magicnpcs.core.caster;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The combat state Magic NPCs owns for one managed caster, held <em>outside</em> the {@code Goal}
 * instance so replacing the goal does not reset it.
 *
 * <p>0.6.1 kept the per-spell cooldown map and the decision deadline as fields on
 * {@code NpcSpellAttackGoal}, and refilled mana through {@code IronsBridge.initMana} on every
 * {@code applyLoadout}. A {@code /reload} removed and recreated the goal, so it silently healed every
 * caster's mana to full, cleared every cooldown, and reset the decision cadence — a datapack edit
 * changed the fight in progress, and a burst of casts could follow immediately (audit RCN-002).
 * Reconciliation now updates this record in place and only initialises mana on a caster's
 * <em>first</em> activation.
 *
 * <p>Keyed by entity UUID and in-memory only: cooldown deadlines are {@code mob.tickCount} values,
 * which are not saved, so persisting them would be meaningless. That is the right scope — an entity
 * that unloads is no longer in a fight. Entries are dropped when the mob is removed.
 *
 * <p>Vanilla-only, so the core and the reconciler share it without touching Iron's.
 */
public final class ManagedCasterState {

    private static final Map<UUID, ManagedCasterState> STATES = new ConcurrentHashMap<>();

    /** Spell id → the {@code mob.tickCount} at which it comes off cooldown. */
    private final Map<ResourceLocation, Integer> readyAtTick = new HashMap<>();

    private int catalogGeneration = -1;
    private ResourceLocation loadoutSource;
    private String loadoutHash;
    private boolean manaInitialised;
    private boolean nativeAttackSuppressed;
    private boolean selfDefenseGranted;
    private boolean channelling;
    private int nextDecisionTick;
    private boolean idleScheduled;
    private ReconcileResult lastResult;

    private ManagedCasterState() {}

    /** @return the state for {@code mob}, creating an empty one on first use. */
    public static ManagedCasterState of(Mob mob) {
        return STATES.computeIfAbsent(mob.getUUID(), id -> new ManagedCasterState());
    }

    /** @return the state for {@code mob}, or {@code null} if it has never been managed. */
    public static ManagedCasterState peek(Mob mob) {
        return STATES.get(mob.getUUID());
    }

    /** Drop a mob's state when it leaves the world, so the map cannot grow without bound. */
    public static void forget(Mob mob) {
        STATES.remove(mob.getUUID());
    }

    /** Drop everything — server shutdown, or a world unload. */
    public static void clearAll() {
        STATES.clear();
    }

    /** How many mobs currently have managed state, for the {@code /magicnpcs config} report. */
    public static int trackedCount() {
        return STATES.size();
    }

    // --- identity -----------------------------------------------------------------------------

    /** The catalog generation this caster's installed loadout came from. */
    public int catalogGeneration() {
        return catalogGeneration;
    }

    public ResourceLocation loadoutSource() {
        return loadoutSource;
    }

    public String loadoutHash() {
        return loadoutHash;
    }

    /**
     * @return true when {@code hash} is exactly what this caster is already running, so a reconcile
     *         can leave the goal, mana and cooldowns alone. This is the check that makes a
     *         no-op {@code /reload} genuinely a no-op.
     */
    public boolean matches(ResourceLocation source, String hash) {
        return hash != null && hash.equals(loadoutHash)
                && (source == null ? loadoutSource == null : source.equals(loadoutSource));
    }

    /** Record the loadout identity this caster is now running. */
    public void adopt(ResourceLocation source, String hash, int generation) {
        this.loadoutSource = source;
        this.loadoutHash = hash;
        this.catalogGeneration = generation;
    }

    // --- mana ---------------------------------------------------------------------------------

    /**
     * @return true the first time this is called for a caster. Mana is filled once, on first
     *         activation; every later reconcile leaves the current value alone (capped to the new
     *         maximum by the caller), so a reload is not free healing.
     */
    public boolean claimManaInitialisation() {
        if (manaInitialised) {
            return false;
        }
        manaInitialised = true;
        return true;
    }

    // --- cooldowns ----------------------------------------------------------------------------

    /** @return ticks until {@code spell} comes off cooldown, or 0 if it is ready. */
    public int cooldownRemaining(ResourceLocation spell, int now) {
        Integer readyAt = readyAtTick.get(spell);
        return readyAt == null ? 0 : Math.max(0, readyAt - now);
    }

    public void startCooldown(ResourceLocation spell, int readyAt) {
        readyAtTick.put(spell, readyAt);
    }

    /**
     * Drop cooldowns for spells the new loadout no longer contains, and keep the rest.
     *
     * <p>Preserving a still-present spell's cooldown across a loadout change is the whole point: an
     * author retuning a datapack mid-fight should not hand every caster a free volley.
     */
    public void retainCooldownsFor(java.util.Set<ResourceLocation> stillPresent) {
        readyAtTick.keySet().retainAll(stillPresent);
    }

    // --- decision cadence ---------------------------------------------------------------------

    public int nextDecisionTick() {
        return nextDecisionTick;
    }

    public boolean idleScheduled() {
        return idleScheduled;
    }

    public void scheduleDecision(int at, boolean fromIdleCadence) {
        this.nextDecisionTick = at;
        this.idleScheduled = fromIdleCadence;
    }

    public void pullDecisionForward(int at) {
        this.nextDecisionTick = Math.min(this.nextDecisionTick, at);
        this.idleScheduled = false;
    }

    // --- native attack lease ------------------------------------------------------------------

    /** @return true while this caster is holding the mob's own attack goals inert. */
    public boolean nativeAttackSuppressed() {
        return nativeAttackSuppressed;
    }

    public void setNativeAttackSuppressed(boolean suppressed) {
        this.nativeAttackSuppressed = suppressed;
    }

    // --- live cast --------------------------------------------------------------------------------

    /**
     * @return true while this caster is part-way through a spell.
     *
     *         <p>Published here, rather than read off the cast session, so the caster-movement goal
     *         can stay vanilla-only: {@code core/} imports no Iron's types, and a movement goal that
     *         had to ask {@code MobCastSession} directly would break that. The session sets and
     *         clears it; a channelling caster must stand still, because it re-aims at its target
     *         every tick and strafing through a channel would spoil the aim it just took.
     */
    public boolean channelling() {
        return channelling;
    }

    public void setChannelling(boolean channelling) {
        this.channelling = channelling;
    }

    // --- villager self-defence lease ------------------------------------------------------------

    /**
     * @return true while the {@code HurtByTargetGoal} on this villager is one <em>we</em> added.
     *
     *         <p>0.6.1 added the goal and never tracked it, so turning
     *         {@code schools.villagers.selfDefense} off or clearing a villager's school left it
     *         retaliating for the rest of its life. Tracking it also means we never remove a goal
     *         another mod put there.
     */
    public boolean selfDefenseGranted() {
        return selfDefenseGranted;
    }

    public void setSelfDefenseGranted(boolean granted) {
        this.selfDefenseGranted = granted;
    }

    // --- last outcome -------------------------------------------------------------------------

    /** The last reconcile decision, so {@code /magicnpcs why} can report a failure truthfully. */
    public ReconcileResult lastResult() {
        return lastResult;
    }

    public void recordResult(ReconcileResult result) {
        this.lastResult = result;
    }
}
