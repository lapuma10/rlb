package net.runelite.client.sequence.activities.ge;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.GeBlockReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.dispatch.InputDispatcher;
import net.runelite.client.sequence.views.GrandExchangeOfferView;
import net.runelite.client.sequence.views.GrandExchangeView;
import net.runelite.client.sequence.views.OfferStatus;

/**
 * Pre-flight: clear any COMPLETE offers parked in the GE slot grid so a
 * subsequent {@link StartOfferStep} can place a new offer in a freshly
 * empty slot. Without this, a completed offer from a prior session sits in
 * its slot forever — F2P is especially sensitive (only 3 slots), but
 * members hit it too once they've run a few sessions.
 *
 * <p>Two collect strategies, picked randomly per onStart for humanization
 * (real players don't always tap the toolbar shortcut):
 * <ul>
 *   <li>{@code COLLECT_ALL} (~63%): one click on
 *       {@code GeOffers.COLLECTALL} drains every completed offer (items
 *       AND leftover coins) into the inventory.</li>
 *   <li>{@code PER_SLOT} (~37%): click the first complete slot's container
 *       to open the offer-detail / collect view, then click
 *       {@code COLLECT_INV} to drain.</li>
 * </ul>
 */
public final class CollectAllCompletedOffersStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("collectAll.precondition", GeBlockReason.class);
    private static final BlackboardKey<Outcome> K_OUTCOME =
        BlackboardKey.of("collectAll.outcome", Outcome.class);
    private static final BlackboardKey<Strategy> K_STRATEGY =
        BlackboardKey.of("collectAll.strategy", Strategy.class);
    private static final BlackboardKey<Phase> K_PHASE =
        BlackboardKey.of("collectAll.phase", Phase.class);
    private static final BlackboardKey<Integer> K_LAST_DISPATCH_TICK =
        BlackboardKey.of("collectAll.lastDispatchTick", Integer.class);
    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("collectAll.startTick", Integer.class);

    /** Default coin flip — 63% returns true (use COLLECTALL toolbar). The
     *  remainder uses the per-slot detail-view dance. Tuned per user
     *  preference: COLLECTALL is the natural human shortcut for already-
     *  completed leftovers, so it dominates; the per-slot path adds tells-
     *  variation for a meaningful minority of sessions. */
    private static final BooleanSupplier DEFAULT_USE_COLLECT_ALL =
        () -> ThreadLocalRandom.current().nextInt(100) < 63;
    private static final int REDISPATCH_COOLDOWN_TICKS = 2;

    private final GeActions ge;
    private final BooleanSupplier useCollectAll;
    private InputDispatcher dispatcher;

    public CollectAllCompletedOffersStep(GeActions ge) {
        this(ge, DEFAULT_USE_COLLECT_ALL);
    }

    /** Test/override constructor: pin the strategy via a deterministic
     *  {@link BooleanSupplier}. Production uses
     *  {@link #DEFAULT_USE_COLLECT_ALL}. */
    public CollectAllCompletedOffersStep(GeActions ge, BooleanSupplier useCollectAll) {
        if (ge == null) throw new IllegalArgumentException("GeActions must not be null");
        if (useCollectAll == null) throw new IllegalArgumentException("useCollectAll must not be null");
        this.ge = ge;
        this.useCollectAll = useCollectAll;
    }

    @Override public String name()                              { return "CollectAllCompletedOffers"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 12; }
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        this.dispatcher = ctx.dispatcher();

        GrandExchangeView gx = s.grandExchange();
        if (!gx.open()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeNotOpen());
            return;
        }

        int firstComplete = firstCompleteSlot(gx);
        if (firstComplete < 0) {
            step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
            return;
        }

        Strategy strategy = useCollectAll.getAsBoolean() ? Strategy.COLLECT_ALL : Strategy.PER_SLOT;
        step.put(K_STRATEGY, strategy);
        step.put(K_START_TICK, s.tick());

        if (strategy == Strategy.COLLECT_ALL) {
            // One click drains every completed offer.
            ge.collectAll();
            step.put(K_PHASE, Phase.AWAIT_DRAIN);
            step.put(K_LAST_DISPATCH_TICK, s.tick());
            return;
        }

        // PER_SLOT: open the slot's detail view to expose COLLECT_INV.
        if (gx.collectOpen()) {
            step.put(K_PHASE, Phase.CLICK_COLLECT_INV);
            ge.collect(firstComplete);
            step.put(K_LAST_DISPATCH_TICK, s.tick());
        } else {
            step.put(K_PHASE, Phase.OPEN_COLLECT);
            ge.openOfferDetail(firstComplete);
            step.put(K_LAST_DISPATCH_TICK, s.tick());
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}

    @Override public void tick(StepContext ctx) {
        // PER_SLOT phase machine: once the collect view opens, dispatch
        // the inventory-collect click exactly once.
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        if (step.get(K_STRATEGY).orElse(null) != Strategy.PER_SLOT) return;
        if (step.get(K_PHASE).orElse(null) == Phase.AWAIT_COLLECT_VIEW
            && ctx.snapshot().grandExchange().collectOpen()) {
            int firstComplete = firstCompleteSlot(ctx.snapshot().grandExchange());
            // Skip the dispatch entirely if no slot is COMPLETE — the prior
            // Math.max(firstComplete, 0) fallback would silently click slot
            // 0 against an empty/wrong slot whenever the snapshot drained
            // externally between onStart and tick. Letting check() succeed
            // on the next tick (firstComplete < 0 path) is the right move.
            if (firstComplete < 0) return;
            step.put(K_PHASE, Phase.CLICK_COLLECT_INV);
            ge.collect(firstComplete);
            step.put(K_LAST_DISPATCH_TICK, ctx.snapshot().tick());
        }
    }

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);
        if (step.get(K_OUTCOME).orElse(null) == Outcome.ALREADY_SATISFIED) {
            return new Completion.Succeeded("no completed offers to collect");
        }
        // Wait for the click chain to release the worker before declaring
        // success — otherwise the next step onStarts into a busy guard.
        if (dispatcher != null && dispatcher.isBusy()) {
            return Completion.RUNNING;
        }
        if (firstCompleteSlot(s.grandExchange()) < 0) {
            return new Completion.Succeeded("all completed offers collected");
        }

        // Step-level timeout — same shape as CollectOfferStep.check(). The
        // engine's outer StepFrame.timedOut doesn't catch a stuck COLLECT_ALL
        // because re-dispatching every REDISPATCH_COOLDOWN_TICKS keeps
        // `lastBusyTick` refreshing, so accumulated idle never reaches
        // `timeoutTicks()`. Without this check a persistent silent-miss
        // turns into an infinite loop on a step that pre-flights every GE
        // session (CLAUDE.md §8 — bot freeze).
        Integer startTick = step.get(K_START_TICK).orElse(null);
        if (startTick != null && s.tick() - startTick >= timeoutTicks()) {
            return Completion.failed(new GeBlockReason.GeOfferRejected(
                "collect-all-completed: did not drain within "
                    + timeoutTicks() + " ticks"));
        }

        if (step.get(K_STRATEGY).orElse(null) == Strategy.COLLECT_ALL) {
            Integer lastDispatch = step.get(K_LAST_DISPATCH_TICK).orElse(null);
            if (lastDispatch == null
                || s.tick() - lastDispatch >= REDISPATCH_COOLDOWN_TICKS) {
                ge.collectAll();
                step.put(K_LAST_DISPATCH_TICK, s.tick());
            }
            return Completion.RUNNING;
        }
        // PER_SLOT phase progression: collect view opened.
        Phase ph = step.get(K_PHASE).orElse(Phase.OPEN_COLLECT);
        if (ph == Phase.OPEN_COLLECT && s.grandExchange().collectOpen()) {
            step.put(K_PHASE, Phase.AWAIT_COLLECT_VIEW);
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeNotOpen) return new Recovery.Abort("ge not open");
        // Engine timeout: retry once. STEP scope is NOT cleared on Retry
        // by the engine (verified in StateDrivenEngine.applyRecovery), but
        // onStart unconditionally overwrites K_STRATEGY / K_PHASE, so the
        // retry effectively re-rolls anyway — if PER_SLOT got stuck on a
        // hidden INDEX_N, the retry can land on COLLECT_ALL and recover.
        return new Recovery.Retry(1);
    }

    private static int firstCompleteSlot(GrandExchangeView gx) {
        for (GrandExchangeOfferView o : gx.offers()) {
            if (o.status() == OfferStatus.COMPLETE) return o.slot();
        }
        return -1;
    }

    private enum Outcome { ALREADY_SATISFIED }
    private enum Strategy { COLLECT_ALL, PER_SLOT }
    private enum Phase { OPEN_COLLECT, AWAIT_COLLECT_VIEW, CLICK_COLLECT_INV, AWAIT_DRAIN }
}
