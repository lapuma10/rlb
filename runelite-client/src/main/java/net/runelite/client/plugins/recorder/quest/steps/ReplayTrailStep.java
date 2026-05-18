package net.runelite.client.plugins.recorder.quest.steps;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.plugins.recorder.trail.TrailPath;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Replay a recorded trail by feeding it to {@link TrailWalker}. Runs the
 * walker's tick loop on the dispatcher worker under one RUN_TASK; the
 * walker handles per-tick click dispatch + transport handling.
 *
 * <p>For Ernest: the preflight step that walks Lumbridge bank P2 → Draynor
 * bank using the {@code lumbridge_bank_to_draynor} trail.
 *
 * <p>Completion is gated by {@code doneWhen} — typically "player is inside
 * the trail's end-zone tile". If the trail walker's tick returns ARRIVED
 * but the doneWhen predicate is still false, the step times out and
 * retries — defends against stale TrailWalker state from a previous run.
 */
@Slf4j
public final class ReplayTrailStep implements Step {

    /** Cadence between TrailWalker.tick() calls inside the RUN_TASK loop. */
    private static final long TICK_LOOP_INTERVAL_MS = 300L;
    /** Hard cap on RUN_TASK duration so a broken trail doesn't hold the
     *  dispatcher busy flag indefinitely. */
    private static final long MAX_RUN_TASK_MS = 180_000L;

    private final Client client;
    private final TrailWalker trailWalker;
    private final TrailPath trailPath;
    private final String displayName;
    private final Predicate<WorldSnapshot> doneWhen;
    /** Polled inside the RUN_TASK loop; when it returns true the loop
     *  exits at the next iteration. Lets the panel Stop button cut a
     *  long trail replay short instead of waiting for the 180 s
     *  deadline. */
    private final BooleanSupplier abortRequested;

    /** Signalled by {@link #check} when {@link #doneWhen} fires. The
     *  RUN_TASK loop polls it and exits at the next iteration. Critical
     *  for steps that stop mid-trail (e.g. talk to Veronica halfway to
     *  the manor) — without this, the loop only exits when the trail
     *  fully completes, and the next step's dispatch hits "dispatcher
     *  busy" silently. */
    private final AtomicBoolean doneSignal = new AtomicBoolean(false);

    /** Set by the RUN_TASK loop when it exits (any reason). check() gates
     *  Succeeded on this so the dispatcher is genuinely free before the
     *  engine pops this step and starts the next one. */
    private final AtomicBoolean taskExited = new AtomicBoolean(false);

    public ReplayTrailStep(Client client,
                           TrailWalker trailWalker,
                           TrailPath trailPath,
                           String displayName,
                           Predicate<WorldSnapshot> doneWhen,
                           BooleanSupplier abortRequested) {
        this.client = client;
        this.trailWalker = trailWalker;
        this.trailPath = trailPath;
        this.displayName = displayName != null ? displayName : "trail";
        this.doneWhen = doneWhen;
        this.abortRequested = abortRequested != null ? abortRequested : () -> false;
    }

    @Override public String name()                                          { return "ReplayTrail(" + displayName + ")"; }
    @Override public int priority()                                         { return 50; }
    @Override public int timeoutTicks()                                     { return 350; }  // ~3.5 min
    @Override public PreemptionPolicy preemptionPolicy()                    { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b)   { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b)        { return s.player() != null; }

    @Override
    public void onStart(StepContext ctx) {
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);

        if (doneWhen.test(ctx.snapshot())) {
            step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
            log.info("quest-trail: {} — already at destination, skipping replay", displayName);
            return;
        }

        // Reset walker state so a previous run's leg index / stuck timers
        // don't carry over.
        trailWalker.reset();
        doneSignal.set(false);
        taskExited.set(false);

        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.RUN_TASK)
            .channel(ActionRequest.Channel.MOUSE)
            .task(() -> {
                long deadline = System.currentTimeMillis() + MAX_RUN_TASK_MS;
                TrailWalker.Status status = TrailWalker.Status.IN_PROGRESS;
                try {
                    while (System.currentTimeMillis() < deadline) {
                        if (abortRequested.getAsBoolean()) {
                            log.info("quest-trail: {} aborted by user/stop", displayName);
                            break;
                        }
                        if (doneSignal.get()) {
                            log.info("quest-trail: {} doneWhen fired — exiting loop early", displayName);
                            break;
                        }
                        status = trailWalker.tick(trailPath);
                        if (status != TrailWalker.Status.IN_PROGRESS) break;
                        SequenceSleep.sleep(client, TICK_LOOP_INTERVAL_MS);
                    }
                    log.info("quest-trail: {} replay loop exited with status={}", displayName, status);
                } finally {
                    taskExited.set(true);
                }
            })
            .taskName("QUEST_TRAIL_" + displayName.toUpperCase().replace(' ', '_'))
            .build();
        ctx.dispatcher().dispatch(req);
    }

    @Override public void onEvent(Object e, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        if (step.get(K_OUTCOME).orElse(Outcome.FRESH) == Outcome.ALREADY_SATISFIED) {
            return new Completion.Succeeded("trail destination already satisfied");
        }
        if (doneWhen.test(s)) {
            // Tell the RUN_TASK loop to stop on its next iteration so the
            // walker doesn't keep clicking past the doneWhen tile (e.g.
            // walking PAST Veronica because the trail's actual endpoint
            // is the manor stairs).
            doneSignal.set(true);
            if (taskExited.get()) {
                // Loop has drained — dispatcher is free for the next step.
                return new Completion.Succeeded(displayName + " — arrived");
            }
            // Hold off Succeeded until the loop actually exits, otherwise
            // the next step's dispatch hits "dispatcher busy, dropping ..."
            // because the trail RUN_TASK is still in flight.
            return Completion.RUNNING;
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Retry(2);
    }

    private enum Outcome { FRESH, ALREADY_SATISFIED }

    private static final BlackboardKey<Outcome> K_OUTCOME =
        BlackboardKey.of("replayTrail.outcome", Outcome.class);
}
