package net.runelite.client.sequence.activities.ge;

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
import net.runelite.client.sequence.views.GrandExchangeOfferView;
import net.runelite.client.sequence.views.GrandExchangeView;
import net.runelite.client.sequence.views.OfferSide;
import net.runelite.client.sequence.views.OfferStatus;

/**
 * Pre-flight: clear any COMPLETE offers parked in the GE slot grid so a
 * subsequent {@link StartOfferStep} can place a new offer in a freshly
 * empty slot. Without this, a completed offer from a prior session sits in
 * its slot forever — F2P is especially sensitive (only 3 slots), but
 * members hit it too once they've run a few sessions.
 *
 * <p>Flow:
 * <ol>
 *   <li>If no slot has status {@link OfferStatus#COMPLETE}, the step is
 *       already satisfied — first {@code check} returns Succeeded.</li>
 *   <li>Otherwise click the first COMPLETE slot to surface the GE collect
 *       interface, then dispatch {@link GeActions#collect} which clicks
 *       {@code COLLECT_INV} — pulls EVERY completed offer's items / coins
 *       into the inventory in one click.</li>
 *   <li>Wait until the snapshot reports zero COMPLETE slots.</li>
 * </ol>
 */
public final class CollectAllCompletedOffersStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("collectAll.precondition", GeBlockReason.class);
    private static final BlackboardKey<Outcome> K_OUTCOME =
        BlackboardKey.of("collectAll.outcome", Outcome.class);
    private static final BlackboardKey<Phase> K_PHASE =
        BlackboardKey.of("collectAll.phase", Phase.class);

    private final GeActions ge;

    public CollectAllCompletedOffersStep(GeActions ge) {
        if (ge == null) throw new IllegalArgumentException("GeActions must not be null");
        this.ge = ge;
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

        if (gx.collectOpen()) {
            // Collect view already open — go straight to the inventory-collect.
            step.put(K_PHASE, Phase.CLICK_COLLECT_INV);
            ge.collect(firstComplete);
        } else {
            // Open the collect view by clicking the first complete slot. Side
            // is ignored by the impl, which just clicks the slot widget.
            step.put(K_PHASE, Phase.OPEN_COLLECT);
            ge.clickOfferSlotButton(firstComplete, OfferSide.BUY);
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}

    @Override public void tick(StepContext ctx) {
        // When we transition into CLICK_COLLECT_INV we want to dispatch the
        // inventory-collect click exactly once; this is recorded by check()
        // updating K_PHASE.
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        if (step.get(K_PHASE).orElse(null) == Phase.AWAIT_COLLECT_VIEW
            && ctx.snapshot().grandExchange().collectOpen()) {
            step.put(K_PHASE, Phase.CLICK_COLLECT_INV);
            int firstComplete = firstCompleteSlot(ctx.snapshot().grandExchange());
            ge.collect(Math.max(firstComplete, 0));
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
        if (firstCompleteSlot(s.grandExchange()) < 0) {
            return new Completion.Succeeded("all completed offers collected");
        }
        // Phase progression: once the collect interface opens, switch to
        // waiting for the COLLECT_INV click to drain everything. tick() does
        // the actual dispatch.
        Phase ph = step.get(K_PHASE).orElse(Phase.OPEN_COLLECT);
        if (ph == Phase.OPEN_COLLECT && s.grandExchange().collectOpen()) {
            step.put(K_PHASE, Phase.AWAIT_COLLECT_VIEW);
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeNotOpen) return new Recovery.Abort("ge not open");
        // Engine timeout: retry once — the click might have been dropped by
        // a busy dispatcher.
        return new Recovery.Retry(1);
    }

    private static int firstCompleteSlot(GrandExchangeView gx) {
        for (GrandExchangeOfferView o : gx.offers()) {
            if (o.status() == OfferStatus.COMPLETE) return o.slot();
        }
        return -1;
    }

    private enum Outcome { ALREADY_SATISFIED }
    private enum Phase { OPEN_COLLECT, AWAIT_COLLECT_VIEW, CLICK_COLLECT_INV }
}
