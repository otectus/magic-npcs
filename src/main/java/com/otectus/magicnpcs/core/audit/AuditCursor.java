package com.otectus.magicnpcs.core.audit;

import java.util.List;

/**
 * The pacing state machine behind {@code /magicnpcs audit spells}: which spell is being exercised,
 * when the next one may start, and when the current one has had long enough.
 *
 * <p>Iron's-free and free of Minecraft on purpose. The runner around it is runtime-only — it needs a
 * server, a level and two live dummies — but "did the audit walk the whole list, one spell at a time,
 * without stalling on a spell whose cast never ends" is exactly the part that can go wrong silently on
 * a 378-spell list, so it is unit-tested here instead of being discovered in a play session.
 *
 * <p>Ticks are the server's absolute tick count, not a delta: the runner only ever asks "given the
 * clock now, may I step / has this spell had its budget", so the cursor never has to be ticked itself
 * and cannot drift when a server tick is skipped.
 */
public final class AuditCursor {

    /** One spell's result row, in the order the report prints them. */
    public record Result(String id, String outcome, String detail, int manaDelta, int entityDelta,
                         long millis) {}

    private final List<String> ids;
    private final int budgetTicks;
    private final int spacingTicks;
    private final int startedTick;

    private int index;
    /** When the current spell was stepped onto, for both the spacing and the budget question. */
    private int lastStepTick;

    /**
     * @param ids          the spell ids to walk, in the order they will be reported
     * @param budgetTicks  how long one spell may take before the runner gives up on it
     * @param spacingTicks how many ticks must pass between two spells, so one spell's leftovers
     *                     (a summon, a projectile, a channel Iron's is still tearing down) do not
     *                     land inside the next spell's measurement window
     * @param startedTick  the server tick the run began on
     */
    public AuditCursor(List<String> ids, int budgetTicks, int spacingTicks, int startedTick) {
        this.ids = List.copyOf(ids);
        this.budgetTicks = Math.max(1, budgetTicks);
        this.spacingTicks = Math.max(0, spacingTicks);
        this.startedTick = startedTick;
        this.lastStepTick = startedTick - Math.max(0, spacingTicks); // the first spell starts at once
    }

    /** @return the spell id being exercised, or {@code null} once the list is exhausted. */
    public String current() {
        return isDone() ? null : ids.get(index);
    }

    /** Move to the next spell. The caller passes the tick it happened on, which restarts both clocks. */
    public void advance(int now) {
        if (!isDone()) {
            index++;
        }
        lastStepTick = now;
    }

    public boolean isDone() {
        return index >= ids.size();
    }

    /** @return true once the spacing gap since the previous spell has elapsed. */
    public boolean shouldStep(int now) {
        return now - lastStepTick >= spacingTicks;
    }

    /** @return true once the current spell has held the runner longer than its budget. */
    public boolean budgetExceeded(int now) {
        return now - lastStepTick > budgetTicks;
    }

    /** @return {@code "n/total"} where {@code n} counts spells already finished. */
    public String progress() {
        return index + "/" + ids.size();
    }

    public int index() {
        return index;
    }

    public int total() {
        return ids.size();
    }

    public int startedTick() {
        return startedTick;
    }
}
