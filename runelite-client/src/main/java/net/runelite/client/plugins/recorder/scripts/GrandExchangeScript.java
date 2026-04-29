package net.runelite.client.plugins.recorder.scripts;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.sequence.SequenceManager;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.activities.banking.BankActions;
import net.runelite.client.sequence.activities.ge.BuyItemIntent;
import net.runelite.client.sequence.activities.ge.GeActions;
import net.runelite.client.sequence.activities.ge.GeInteraction;
import net.runelite.client.sequence.activities.ge.GrandExchangeSequenceFactory;
import net.runelite.client.sequence.activities.ge.GrandExchangeSequencePlan;
import net.runelite.client.sequence.activities.ge.SellItemIntent;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.InputOwnership;
import net.runelite.client.sequence.internal.ClientObserver;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;

/**
 * Engine-only single-task controller for GE Core buy / sell. The recorder
 * plugin constructs one of these and forwards {@link GameTick} into
 * {@link #onGameTick()}; the panel calls {@link #startBuy} /
 * {@link #startSell} from the button handlers.
 *
 * <p>No daemon thread, no enum FSM. The engine's own state machine drives
 * the in-flight task. {@link InputOwnership} coordinates with other
 * scripts (e.g., a future {@code CookingScript} bank-prep variant) so two
 * scripts can't both push clicks at the same time.
 */
@Slf4j
public final class GrandExchangeScript {

    /** Lease token used to coordinate with other scripts via {@link InputOwnership}. */
    public static final String OWNER_TOKEN = "ge-script";

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final InputOwnership inputOwnership;
    private final WorldArea geArea;
    private final GeActions geActions;
    /** Optional — set by Phase B integration once BankInteraction is wired.
     *  null means bank-prep variants are not supported in this instance. */
    private final BankActions bankActions;

    private SequenceManager geManager;          // lazy
    private final AtomicBoolean intentStartRequested = new AtomicBoolean();
    private final AtomicBoolean tickInFlight = new AtomicBoolean();
    private final AtomicReference<String> status = new AtomicReference<>("idle");

    /** Phase A constructor — no bank-prep support. */
    public GrandExchangeScript(Client client,
                               ClientThread clientThread,
                               HumanizedInputDispatcher dispatcher,
                               InputOwnership inputOwnership,
                               WorldArea geArea) {
        this(client, clientThread, dispatcher, inputOwnership, geArea, null);
    }

    /** Phase B constructor — pass a BankActions impl to enable bank-prep variants. */
    public GrandExchangeScript(Client client,
                               ClientThread clientThread,
                               HumanizedInputDispatcher dispatcher,
                               InputOwnership inputOwnership,
                               WorldArea geArea,
                               BankActions bankActions) {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.inputOwnership = inputOwnership;
        this.geArea = geArea;
        this.geActions = new GeInteraction(client, clientThread, dispatcher);
        this.bankActions = bankActions;
    }

    /** True iff this script was constructed with a {@link BankActions} impl,
     *  enabling {@link #startBuyWithPrep} / {@link #startSellWithPrep}. */
    public boolean bankPrepAvailable() { return bankActions != null; }

    // ─── Public API (called from RecorderPanel buttons) ─────────────────

    public boolean startBuy(BuyItemIntent intent) {
        if (intent == null) return false;
        return startPlan(GrandExchangeSequenceFactory.buyCore(intent, geArea, geActions),
            "buy " + intent.quantity() + "x " + intent.displayName() + " @ "
                + ((net.runelite.client.sequence.activities.ge.PricePolicy.Exact)
                    intent.pricePolicy()).coinsEach());
    }

    public boolean startSell(SellItemIntent intent) {
        if (intent == null) return false;
        return startPlan(GrandExchangeSequenceFactory.sellCore(intent, geArea, geActions),
            "sell " + intent.quantity() + "x " + intent.displayName() + " @ "
                + ((net.runelite.client.sequence.activities.ge.PricePolicy.Exact)
                    intent.pricePolicy()).coinsEach());
    }

    /** Phase B: buy with bank-prep — withdraws coins from a bank booth at
     *  the GE first, then runs the GE Core buy sequence. Requires a
     *  {@link BankActions} impl to have been provided at construction time. */
    public boolean startBuyWithPrep(BuyItemIntent intent) {
        if (intent == null) return false;
        if (bankActions == null) {
            status.set("bank-prep unavailable: BankActions not wired");
            return false;
        }
        return startPlan(GrandExchangeSequenceFactory.buyWithBankPrep(intent, geArea, bankActions, geActions),
            "buy-with-prep " + intent.quantity() + "x " + intent.displayName() + " @ "
                + ((net.runelite.client.sequence.activities.ge.PricePolicy.Exact)
                    intent.pricePolicy()).coinsEach());
    }

    /** Phase B: sell with bank-prep — withdraws the sell items from a bank
     *  booth at the GE first, then runs the GE Core sell sequence. */
    public boolean startSellWithPrep(SellItemIntent intent) {
        if (intent == null) return false;
        if (bankActions == null) {
            status.set("bank-prep unavailable: BankActions not wired");
            return false;
        }
        return startPlan(GrandExchangeSequenceFactory.sellWithBankPrep(intent, geArea, bankActions, geActions),
            "sell-with-prep " + intent.quantity() + "x " + intent.displayName() + " @ "
                + ((net.runelite.client.sequence.activities.ge.PricePolicy.Exact)
                    intent.pricePolicy()).coinsEach());
    }

    public void stop() {
        SequenceManager m = geManager;
        if (m != null) m.stop();
        intentStartRequested.set(false);
        taskInFlight.set(false);
        if (inputOwnership != null) inputOwnership.release(OWNER_TOKEN);
        status.set("stopped");
    }

    public SequenceState state()                  { return geManager == null ? SequenceState.IDLE : geManager.state(); }
    public String status()                        { return status.get(); }
    public List<TelemetryRecord> recentTelemetry() {
        SequenceManager m = geManager;
        if (m == null) return List.of();
        if (m.getTelemetry() instanceof RingBufferTelemetry rb) return rb.tail(8);
        return List.of();
    }

    public Optional<DiagnosticReason> lastFailureReason() {
        for (TelemetryRecord r : recentTelemetry()) {
            // No direct DiagnosticReason on TelemetryRecord — payload string
            // is the closest thing for now. Callers parse if they need typed.
            if (r.payload() != null && r.payload().contains("FAILED")) {
                return Optional.empty();   // typed reason not preserved through telemetry
            }
        }
        return Optional.empty();
    }

    // ─── Plugin hookup ──────────────────────────────────────────────────

    /** RecorderPlugin forwards GameTick here. Advances the engine if a task
     *  is in flight; updates status. */
    @Subscribe
    public void onGameTick(GameTick e) {
        if (geManager == null) return;
        SequenceState st = geManager.state();
        // SequenceState has only IDLE / RUNNING / PAUSED / FAILED. The engine
        // returns to IDLE on successful sequence completion, so we infer
        // "done" by IDLE-after-pending. The `intentStartRequested` flag
        // discriminates "newly idle (just finished)" from "haven't started yet".
        if (st == SequenceState.RUNNING) {
            intentStartRequested.set(false);
            scheduleEngineTick();
            String last = recentTelemetryLine();
            status.set("running" + (last.isEmpty() ? "" : ": " + last));
            taskInFlight.set(true);
        } else if (st == SequenceState.FAILED) {
            intentStartRequested.set(false);
            if (inputOwnership != null) inputOwnership.release(OWNER_TOKEN);
            String last = recentTelemetryLine();
            status.set("failed" + (last.isEmpty() ? "" : ": " + last));
            taskInFlight.set(false);
        } else if (st == SequenceState.IDLE && taskInFlight.get()
                   && !intentStartRequested.get()) {
            // Just transitioned from RUNNING → IDLE = success.
            // We wait for intentStartRequested to clear (set false on the
            // first RUNNING tick) before treating IDLE as "done", so a tick
            // that fires BEFORE the scheduler-marshalled engine.start has
            // run doesn't get misinterpreted as success.
            if (inputOwnership != null) inputOwnership.release(OWNER_TOKEN);
            status.set("done");
            taskInFlight.set(false);
        }
        // IDLE (idle since boot) / PAUSED → no-op
    }

    /** True iff a task is currently in flight (between startBuy/startSell
     *  and the engine's terminal transition). Used by the IDLE-after-RUNNING
     *  detection in {@link #onGameTick} since SequenceState lacks COMPLETED. */
    private final AtomicBoolean taskInFlight = new AtomicBoolean();

    // ─── Internals ──────────────────────────────────────────────────────

    private boolean startPlan(GrandExchangeSequencePlan plan, String description) {
        if (geManager == null) geManager = buildGeManager();

        if (intentStartRequested.get()) {
            status.set("start pending — wait one tick");
            return false;
        }
        if (geManager.state() != SequenceState.IDLE) {
            status.set("busy: " + geManager.state());
            return false;
        }
        if (inputOwnership != null && !inputOwnership.tryAcquire(OWNER_TOKEN)) {
            status.set("input owned by " + inputOwnership.currentOwner().orElse("?"));
            return false;
        }

        try {
            geManager.clearReactives();
            for (Step r : plan.reactiveSteps()) geManager.registerReactive(r, 200);
            intentStartRequested.set(true);
            taskInFlight.set(true);
            geManager.run(plan.root());
            status.set("starting: " + description);
            log.info("ge-script: starting {}", description);
            return true;
        } catch (Exception e) {
            intentStartRequested.set(false);
            if (inputOwnership != null) inputOwnership.release(OWNER_TOKEN);
            status.set("error: " + e.getMessage());
            log.warn("ge-script: start threw", e);
            return false;
        }
    }

    private SequenceManager buildGeManager() {
        SequenceManager m = SequenceManager.withDefaults();
        m.setObserver(new ClientObserver(client));
        m.setDispatcher(dispatcher);
        m.setScheduler(clientThread::invoke);
        if (inputOwnership != null) m.setInputOwnership(inputOwnership, OWNER_TOKEN);
        return m;
    }

    private void scheduleEngineTick() {
        if (!tickInFlight.compareAndSet(false, true)) return;
        clientThread.invokeLater(() -> {
            try {
                if (geManager != null && geManager.getEngine() != null) {
                    geManager.getEngine().advanceTick();
                }
            } finally {
                tickInFlight.set(false);
            }
        });
    }

    private String recentTelemetryLine() {
        List<TelemetryRecord> recent = recentTelemetry();
        if (recent.isEmpty()) return "";
        TelemetryRecord r = recent.get(recent.size() - 1);
        return r.stepName() + " " + r.event()
            + (r.payload() == null || r.payload().isEmpty() ? "" : " " + r.payload());
    }
}
