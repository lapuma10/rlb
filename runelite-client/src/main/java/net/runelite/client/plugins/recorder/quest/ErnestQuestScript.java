package net.runelite.client.plugins.recorder.quest;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.recorder.nav.v2.V2Navigator;
import net.runelite.client.plugins.recorder.npc.NpcInteraction;
import net.runelite.client.plugins.recorder.scene.SceneScanner;
import net.runelite.client.plugins.recorder.trail.Trail;
import net.runelite.client.plugins.recorder.trail.TrailPath;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
import net.runelite.client.sequence.SequenceManager;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ClientObserver;

/**
 * Thin runner for the Ernest the Chicken quest. Holds the per-script
 * dispatcher + helpers + {@link SequenceManager}, builds the recipe
 * via {@link ErnestTheChicken#build}, and runs it on the engine.
 *
 * <p>Mirrors the {@code GrandExchangeScript} engine-wiring pattern —
 * own dispatcher, own SequenceManager, scheduler bound to the client
 * thread. Construction is cheap; the engine is built lazily on first
 * {@link #start}.
 *
 * <p>Required trail: {@code lumbridge_bank_to_draynor} (must be present
 * in the registry). Preflight refuses to start if the player isn't at
 * Draynor bank or Lumbridge bank P2 — see the spec.
 */
@Slf4j
public final class ErnestQuestScript {

    public static final String REQUIRED_TRAIL = "lumbridge_bank_to_draynor";
    /** Trail from Draynor bank to inside Draynor manor (ends at stairs base).
     *  Walks past Veronica — we interrupt the trail to talk to her when the
     *  player gets close. */
    public static final String MANOR_TRAIL = "draynor_bank_to_draynor_manor";

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    /** Separate dispatcher for the trail walker so its WALK clicks don't
     *  get silently dropped while the engine dispatcher is busy holding
     *  the RUN_TASK busy flag (the loop in {@link
     *  net.runelite.client.plugins.recorder.quest.steps.ReplayTrailStep}). */
    private final HumanizedInputDispatcher trailDispatcher;
    private final NpcInteraction npcInteraction;
    private final SceneScanner sceneScanner;
    private final TrailWalker trailWalker;
    /** Navigator used by NavWalkStep for cross-scene walks (bank → Veronica,
     *  manor approach, fountain, etc). In-scene basement walks in the lever
     *  puzzle still use the engine's vanilla {@code WalkStep}. */
    private final V2Navigator nav;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean tickInFlight = new AtomicBoolean(false);
    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private volatile SequenceManager manager;
    /** Race guard for {@link #onGameTick}. {@link SequenceManager#run}
     *  schedules {@code engine.start} on the client thread via the
     *  scheduler, so there's a window where {@link #running} is true but
     *  the engine state is still IDLE because the scheduled runnable
     *  hasn't fired yet. Without this flag, the first {@code onGameTick}
     *  after start would treat IDLE as "completed", set running=false,
     *  and the now-pushed RUN_TASK would see {@code abortRequested=true}
     *  and quit at 0 ms. We only treat IDLE as completion after observing
     *  RUNNING at least once. */
    private volatile boolean observedRunning = false;

    public ErnestQuestScript(Client client, ClientThread clientThread,
                             HumanizedInputDispatcher dispatcher, V2Navigator nav) {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.trailDispatcher = new HumanizedInputDispatcher(client, clientThread);
        this.npcInteraction = new NpcInteraction(client, clientThread, dispatcher);
        this.sceneScanner = new SceneScanner(client);
        this.trailWalker = new TrailWalker(client, clientThread, trailDispatcher);
        this.nav = nav;
    }

    /** True iff a run is in flight. */
    public boolean isRunning() { return running.get(); }

    /** Most-recent human-readable status line for the panel. */
    public String status() { return status.get(); }

    /**
     * Build the recipe and hand it to the engine. Returns true if the
     * run started, false if already running or a precondition (missing
     * trail / missing item id) failed loudly.
     */
    public boolean start(TrailRegistry trailRegistry) {
        if (!running.compareAndSet(false, true)) {
            log.info("ernest: start ignored — already running");
            return false;
        }
        observedRunning = false;    // reset race-guard for the new run
        try {
            Trail trail = trailRegistry.byName(REQUIRED_TRAIL);
            if (trail == null) {
                status.set("error: trail '" + REQUIRED_TRAIL + "' not found");
                log.warn("ernest: missing trail '{}' in registry", REQUIRED_TRAIL);
                running.set(false);
                return false;
            }
            Trail manorTrail = trailRegistry.byName(MANOR_TRAIL);
            if (manorTrail == null) {
                status.set("error: trail '" + MANOR_TRAIL + "' not found");
                log.warn("ernest: missing trail '{}' in registry", MANOR_TRAIL);
                running.set(false);
                return false;
            }
            TrailPath path = TrailPath.fromTrail(trail);
            TrailPath manorPath = TrailPath.fromTrail(manorTrail);

            LinearSequence seq = ErnestTheChicken.build(
                client, clientThread, npcInteraction,
                sceneScanner, dispatcher, trailWalker, nav, path, manorPath,
                () -> !running.get());     // abort = user clicked Stop

            manager = buildManager();
            manager.run(seq);
            status.set("running");
            log.info("ernest: started — {} children in root sequence", seq.getChildren().size());
            return true;
        } catch (RuntimeException e) {
            status.set("error: " + e.getMessage());
            log.warn("ernest: start threw", e);
            running.set(false);
            return false;
        }
    }

    /**
     * Cancel the engine hard. {@link SequenceManager#stop()} pops every
     * frame and clears the blackboard, so any leaf step that was about
     * to re-dispatch on the next tick gets evicted before tick() runs.
     * The dispatcher drains whatever it had queued (each request fails
     * fast on its own) and goes idle.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            status.set("stopped");
            log.info("ernest: stop requested — cancelling engine");
            if (manager != null) {
                try {
                    manager.stop();
                } catch (RuntimeException e) {
                    log.warn("ernest: manager.stop() threw", e);
                }
            }
            // Drop any in-flight nav plan so the executor stops issuing
            // minimap clicks on the next tick.
            try {
                nav.cancel();
            } catch (RuntimeException e) {
                log.warn("ernest: nav.cancel() threw", e);
            }
        }
    }

    private SequenceManager buildManager() {
        SequenceManager m = SequenceManager.withDefaults();
        m.setObserver(new ClientObserver(client));
        m.setDispatcher(dispatcher);
        m.setScheduler(clientThread::invoke);
        return m;
    }

    /** RecorderPlugin forwards game ticks here. We advance the engine
     *  whenever it has work to do — even after the user clicked Stop,
     *  so the in-flight RUN_TASK can drain and the engine can settle to
     *  IDLE / FAILED cleanly. The {@link #running} flag is the user-
     *  intent signal (start/stop), the engine's own state is the source
     *  of truth for whether to keep ticking. */
    @Subscribe
    public void onGameTick(GameTick e) {
        if (manager == null) return;

        SequenceState st = manager.state();
        if (st == SequenceState.RUNNING) {
            observedRunning = true;
            scheduleEngineTick();
            return;
        }
        // Engine settled. If we still think we're running, transition out —
        // but ONLY after we've actually seen RUNNING at least once. {@link
        // SequenceManager#run} schedules engine.start asynchronously, so the
        // first tick(s) after start can still observe IDLE while the engine
        // hasn't pushed its frame yet. Without this guard we'd flip
        // running=false and the very first RUN_TASK would abort immediately
        // (abortRequested = !running.get() = true).
        if (running.get() && observedRunning) {
            if (st == SequenceState.FAILED) {
                running.set(false);
                status.set("failed");
                log.warn("ernest: engine reported FAILED");
            } else if (st == SequenceState.IDLE) {
                running.set(false);
                status.set("completed");
                log.info("ernest: sequence completed");
            }
        }
    }

    private void scheduleEngineTick() {
        if (!tickInFlight.compareAndSet(false, true)) return;
        clientThread.invokeLater(() -> {
            try {
                if (manager != null && manager.getEngine() != null) {
                    manager.getEngine().advanceTick();
                }
            } finally {
                tickInFlight.set(false);
            }
        });
    }
}
