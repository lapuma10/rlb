package net.runelite.client.plugins.recorder.scripts;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.sequence.SequenceManager;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.activities.banking.BankActions;
import net.runelite.client.sequence.activities.ge.BuyItemIntent;
import net.runelite.client.sequence.activities.ge.BuyLimitLedger;
import net.runelite.client.sequence.activities.ge.BuyLimitTable;
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

    /** Per-account 4-hour buy-limit ledger. Loaded on construction; appended
     *  to via {@link #onGrandExchangeOfferChanged(GrandExchangeOfferChanged)}
     *  when an offer reaches a terminal state; queried in
     *  {@link #applyBuyLimitCap(BuyItemIntent)} to clip incoming buys to
     *  what the rolling 4-hour window still allows. */
    private final BuyLimitLedger buyLimitLedger = new BuyLimitLedger();
    /** Per-slot last-recorded {@code completedQuantity}. Lets us record
     *  incremental progress (terminal state events sometimes batch — we
     *  want each delta accounted for, not just the final value). */
    private final java.util.Map<Integer, Integer> lastRecordedFilled = new java.util.concurrent.ConcurrentHashMap<>();

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
        GeInteraction ge = new GeInteraction(client, clientThread, dispatcher);
        this.geActions = ge;
        this.bankActions = bankActions;
        // Register the worker-thread picker so PICK_GE_SEARCH_RESULT
        // dispatches route back into GeInteraction's runPickSearchResult.
        // Without this the dispatcher logs "no picker registered" and the
        // entire GE select flow no-ops.
        dispatcher.setGeSearchResultPicker((itemId, name) -> {
            try { ge.runPickSearchResult(itemId, name); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        });
        // Lazy-load the buy-limit ledger from disk. Failure here is logged
        // and the bot starts with an empty ledger — better to lose
        // history than to refuse to start.
        try { buyLimitLedger.load(client); }
        catch (Exception ex) { log.warn("buy-limits: load failed — starting empty", ex); }
    }

    /** True iff this script was constructed with a {@link BankActions} impl,
     *  enabling {@link #startBuyWithPrep} / {@link #startSellWithPrep}. */
    public boolean bankPrepAvailable() { return bankActions != null; }

    // ─── Public API (called from RecorderPanel buttons) ─────────────────

    public boolean startBuy(BuyItemIntent intent) {
        if (intent == null) return false;
        BuyItemIntent capped = applyBuyLimitCap(intent);
        if (capped == null) return false;   // limit fully consumed — refused
        return startPlan(GrandExchangeSequenceFactory.buyCore(capped, geArea, geActions, client),
            "buy " + capped.quantity() + "x " + capped.displayName() + " @ "
                + ((net.runelite.client.sequence.activities.ge.PricePolicy.Exact)
                    capped.pricePolicy()).coinsEach());
    }

    public boolean startSell(SellItemIntent intent) {
        if (intent == null) return false;
        return startPlan(GrandExchangeSequenceFactory.sellCore(intent, geArea, geActions, client),
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
        BuyItemIntent capped = applyBuyLimitCap(intent);
        if (capped == null) return false;
        return startPlan(GrandExchangeSequenceFactory.buyWithBankPrep(capped, geArea, bankActions, geActions, client),
            "buy-with-prep " + capped.quantity() + "x " + capped.displayName() + " @ "
                + ((net.runelite.client.sequence.activities.ge.PricePolicy.Exact)
                    capped.pricePolicy()).coinsEach());
    }

    /** Phase B: sell with bank-prep — withdraws the sell items from a bank
     *  booth at the GE first, then runs the GE Core sell sequence. */
    public boolean startSellWithPrep(SellItemIntent intent) {
        if (intent == null) return false;
        if (bankActions == null) {
            status.set("bank-prep unavailable: BankActions not wired");
            return false;
        }
        return startPlan(GrandExchangeSequenceFactory.sellWithBankPrep(intent, geArea, bankActions, geActions, client),
            "sell-with-prep " + intent.quantity() + "x " + intent.displayName() + " @ "
                + ((net.runelite.client.sequence.activities.ge.PricePolicy.Exact)
                    intent.pricePolicy()).coinsEach());
    }

    /** Pre-flight: cap the buy quantity to whatever the rolling 4-hour
     *  GE buy limit still allows for this item. Returns:
     *  <ul>
     *    <li>{@code intent} unchanged if the requested qty fits.</li>
     *    <li>A NEW intent with {@code quantity = quotaRemaining} when
     *        the request exceeds the cap. Status reports the clip.</li>
     *    <li>{@code null} when the limit is fully consumed and no
     *        further buys can be placed in this window. Status reports
     *        "limit reached"; caller refuses the start.</li>
     *  </ul>
     *  Buy limit per item from {@link BuyLimitTable}; per-account
     *  history from {@link BuyLimitLedger}. */
    private BuyItemIntent applyBuyLimitCap(BuyItemIntent intent) {
        int itemId = intent.itemId();
        int requested = intent.quantity();
        int limit = BuyLimitTable.limitFor(itemId);
        Instant now = Instant.now();
        int used = buyLimitLedger.quotaUsed(itemId, now);
        int remaining = Math.max(0, limit - used);
        if (remaining <= 0) {
            String msg = "buy-limit: " + intent.displayName() + " quota exhausted ("
                + used + "/" + limit + " in last 4h) — refused";
            log.info(msg);
            status.set(msg);
            return null;
        }
        if (requested <= remaining) {
            // Within budget — no clip.
            log.debug("buy-limit: itemId={} qty={} fits within remaining {} (limit {}, used {})",
                itemId, requested, remaining, limit, used);
            return intent;
        }
        // Clip down to whatever's left.
        log.info("buy-limit: clipping {} from {} → {} (used {}/{} in last 4h)",
            intent.displayName(), requested, remaining, used, limit);
        status.set("buy-limit: clipped to " + remaining + "x " + intent.displayName());
        return new BuyItemIntent(intent.itemId(), intent.displayName(),
            remaining, intent.pricePolicy(), intent.waitPolicy());
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

    /** Record buy-side fill increments into the per-account 4-hour
     *  buy-limit ledger. We listen to {@code GrandExchangeOfferChanged}
     *  on every state transition (BUYING progress, BOUGHT terminal,
     *  CANCELLED_BUY) and append the delta in {@code quantitySold} since
     *  the last observation for that slot. Recording on the delta (not
     *  the raw final value) means we don't miss a partial that gets
     *  collected before the offer reaches BOUGHT, AND we don't double-
     *  count when the same slot reports the same fill twice during
     *  state transitions.
     *
     *  <p>Sell-side offers are not recorded — the OSRS GE buy limit
     *  applies only to buys. */
    @Subscribe
    public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e) {
        GrandExchangeOffer o = e.getOffer();
        if (o == null) return;
        GrandExchangeOfferState st = o.getState();
        if (st == null) return;
        boolean isBuySide =
            st == GrandExchangeOfferState.BUYING
                || st == GrandExchangeOfferState.BOUGHT
                || st == GrandExchangeOfferState.CANCELLED_BUY;
        if (!isBuySide) return;
        int slot = e.getSlot();
        int filled = o.getQuantitySold();
        Integer prior = lastRecordedFilled.get(slot);
        int delta = filled - (prior == null ? 0 : prior);
        if (delta > 0 && o.getItemId() > 0) {
            buyLimitLedger.record(o.getItemId(), delta, Instant.now());
            log.debug("buy-limits: recorded itemId={} qty={} (slot {} filled={}, prior={})",
                o.getItemId(), delta, slot, filled, prior);
        }
        // On EMPTY, the slot has been collected and re-armed — reset the
        // per-slot watermark so the next offer in this slot starts from 0.
        if (st == GrandExchangeOfferState.EMPTY
            || st == GrandExchangeOfferState.CANCELLED_BUY
            || st == GrandExchangeOfferState.BOUGHT) {
            // Persist now so a crash mid-session doesn't lose the record.
            try { buyLimitLedger.save(client); }
            catch (Exception ex) { log.warn("buy-limits: save failed", ex); }
            if (st == GrandExchangeOfferState.EMPTY) {
                lastRecordedFilled.remove(slot);
            } else {
                lastRecordedFilled.put(slot, filled);
            }
        } else {
            lastRecordedFilled.put(slot, filled);
        }
    }

    /** Public read-only view of the ledger, for diagnostics or a future UI. */
    public BuyLimitLedger buyLimitLedger() { return buyLimitLedger; }

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
        for (int i = recent.size() - 1; i >= 0; i--) {
            TelemetryRecord candidate = recent.get(i);
            if (candidate.event() == TelemetryRecord.Event.CHECK
                && candidate.payload() != null
                && candidate.payload().startsWith("canStart=false")) {
                continue;
            }
            r = candidate;
            break;
        }
        return r.stepName() + " " + r.event()
            + (r.payload() == null || r.payload().isEmpty() ? "" : " " + r.payload());
    }
}
