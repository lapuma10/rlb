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
import net.runelite.client.sequence.views.OfferSide;

/**
 * Collects the proceeds of the in-flight offer (slot from
 * {@code K_GE_OFFER_SLOT} SEQUENCE-scope) into the player's inventory.
 *
 * <p>Reads the slot's {@link OfferSide} from the snapshot to compute which
 * inventory delta to expect (BUY → itemId increases; SELL → coins
 * increase). Persists the pre-collect inventory count to STEP scope and
 * waits for the slot to become EMPTY AND the inventory count to rise above
 * that baseline.
 *
 * <p>Two collect strategies, picked randomly per onStart for humanization
 * (real players don't always tap the toolbar shortcut):
 * <ul>
 *   <li>{@code COLLECT_ALL} (~63%): one click on
 *       {@code GeOffers.COLLECTALL} drains every completed offer (items
 *       AND leftover coins from partial fills) into the inventory.</li>
 *   <li>{@code PER_SLOT} (~37%): click the slot's container to open the
 *       offer-detail / collect view, then click {@code COLLECT_INV}.</li>
 * </ul>
 *
 * <p>Already-satisfied: slot already EMPTY (collect happened externally).
 *
 * <p>Failure: {@link GeBlockReason.GeCollectFailed(slot, expectedDeltaItemId,
 * observedDelta)} when the slot transitions to EMPTY but the expected
 * inventory delta is not observed within the timeout.
 */
public final class CollectOfferStep implements Step {

    private static final int COINS_ITEM_ID = 995;

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("collectOffer.precondition", GeBlockReason.class);
    private static final BlackboardKey<Integer> K_EXPECTED_DELTA_ITEM_ID =
        BlackboardKey.of("collectOffer.expectedDeltaItemId", Integer.class);
    private static final BlackboardKey<Integer> K_EXPECTED_DELTA_START =
        BlackboardKey.of("collectOffer.expectedDeltaStart", Integer.class);
    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("collectOffer.startTick", Integer.class);
    private static final BlackboardKey<Boolean> K_ALREADY_EMPTY =
        BlackboardKey.of("collectOffer.alreadyEmpty", Boolean.class);
    private static final BlackboardKey<Strategy> K_STRATEGY =
        BlackboardKey.of("collectOffer.strategy", Strategy.class);
    private static final BlackboardKey<Phase> K_PHASE =
        BlackboardKey.of("collectOffer.phase", Phase.class);
    /** SEQUENCE-scope so it survives Retry(N) STEP-scope clears: the expected
     *  item id from the FIRST attempt. Used when a retry encounters an
     *  already-empty slot — we still need to verify delta from the original
     *  baseline rather than treating it as already-collected. */
    private static final BlackboardKey<Integer> K_DELTA_ITEM_ID_PERSISTED =
        BlackboardKey.of("collectOffer.deltaItemIdPersisted", Integer.class);
    private static final BlackboardKey<Integer> K_DELTA_START_PERSISTED =
        BlackboardKey.of("collectOffer.deltaStartPersisted", Integer.class);
    /** Set true in onStart when the offer is PARTIALLY_COMPLETE (e.g.
     *  WaitForOfferStep accepted a buy-limit-stalled fill). The slot
     *  WILL NOT empty — collecting from a partial offer extracts the
     *  filled portion to inventory but the offer continues running for
     *  the unfilled remainder. Success criterion in this mode is "click
     *  dispatched + worker idle + a couple of grace ticks", not slot
     *  empty. The remaining active portion is left for the next session
     *  to revisit (per user spec). */
    private static final BlackboardKey<Boolean> K_PARTIAL_MODE =
        BlackboardKey.of("collectOffer.partialMode", Boolean.class);
    private static final BlackboardKey<Integer> K_PARTIAL_DISPATCH_TICK =
        BlackboardKey.of("collectOffer.partialDispatchTick", Integer.class);
    /** Engine ticks of "worker idle, post-dispatch" we wait before declaring
     *  the partial collect done. Generous — the in-grid detail collect can
     *  take 2–3 ticks for both DETAILS_COLLECT children to land. */
    private static final int PARTIAL_GRACE_TICKS = 5;

    /** 63% COLLECT_ALL / 37% PER_SLOT — see CollectAllCompletedOffersStep
     *  for the humanization rationale. */
    private static final BooleanSupplier DEFAULT_USE_COLLECT_ALL =
        () -> ThreadLocalRandom.current().nextInt(100) < 63;

    private final GeActions ge;
    private final BooleanSupplier useCollectAll;
    private InputDispatcher dispatcher;

    public CollectOfferStep(GeActions ge) {
        this(ge, DEFAULT_USE_COLLECT_ALL);
    }

    /** Test/override constructor: pin the strategy via a deterministic
     *  {@link BooleanSupplier}. */
    public CollectOfferStep(GeActions ge, BooleanSupplier useCollectAll) {
        if (ge == null) throw new IllegalArgumentException("GeActions must not be null");
        if (useCollectAll == null) throw new IllegalArgumentException("useCollectAll must not be null");
        this.ge = ge;
        this.useCollectAll = useCollectAll;
    }

    @Override public String name()                              { return "CollectOffer"; }
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

        if (!s.grandExchange().open()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeNotOpen());
            return;
        }

        Integer slotBox = ctx.bb().scope(BlackboardScope.SEQUENCE)
            .get(GeBlackboardKeys.K_GE_OFFER_SLOT).orElse(null);
        if (slotBox == null) {
            step.put(K_PRECONDITION, new GeBlockReason.GeOfferRejected("no offer slot recorded"));
            return;
        }
        int slot = slotBox;
        var offers = s.grandExchange().offers();
        if (slot < 0 || slot >= offers.size()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeOfferRejected("slot out of range: " + slot));
            return;
        }
        GrandExchangeOfferView o = offers.get(slot);

        // If a previous attempt persisted expected item / baseline (Retry path),
        // reuse them instead of re-deriving from the now-empty slot.
        Integer persistedItemId = ctx.bb().scope(BlackboardScope.SEQUENCE)
            .get(K_DELTA_ITEM_ID_PERSISTED).orElse(null);
        Integer persistedStart = ctx.bb().scope(BlackboardScope.SEQUENCE)
            .get(K_DELTA_START_PERSISTED).orElse(null);
        if (persistedItemId != null && persistedStart != null) {
            step.put(K_EXPECTED_DELTA_ITEM_ID, persistedItemId);
            step.put(K_EXPECTED_DELTA_START, persistedStart);
            step.put(K_START_TICK, s.tick());
            // Retry: re-fire if the slot still holds an offer. Re-roll the
            // strategy — if the prior PER_SLOT attempt got stuck on a hidden
            // INDEX_N, the retry might land on COLLECT_ALL and recover.
            if (!o.isEmpty()) {
                dispatchStrategy(ctx, s, slot);
            }
            return;
        }

        // First attempt: compute expected delta target from the live slot.
        int expectedItemId = (o.side() == OfferSide.SELL) ? COINS_ITEM_ID : o.itemId();
        if (expectedItemId == 0 && o.isEmpty()) {
            step.put(K_ALREADY_EMPTY, true);
            return;
        }
        int startCount = s.inventory().count(expectedItemId);
        step.put(K_EXPECTED_DELTA_ITEM_ID, expectedItemId);
        step.put(K_EXPECTED_DELTA_START, startCount);
        step.put(K_START_TICK, s.tick());
        ctx.bb().scope(BlackboardScope.SEQUENCE).put(K_DELTA_ITEM_ID_PERSISTED, expectedItemId);
        ctx.bb().scope(BlackboardScope.SEQUENCE).put(K_DELTA_START_PERSISTED, startCount);

        if (o.isEmpty()) {
            step.put(K_ALREADY_EMPTY, true);
            return;
        }

        // Partial-fill mode: the offer is still ACTIVE with a partial fill
        // (typical cause: WaitForOfferStep saw the buy-limit-cap stall and
        // accepted the partial). The slot will NOT empty — collecting from
        // a partial offer extracts the filled portion to inventory but
        // leaves the remainder running. Different success criterion in
        // check() — see K_PARTIAL_MODE handling.
        if (o.status() == net.runelite.client.sequence.views.OfferStatus.PARTIALLY_COMPLETE) {
            step.put(K_PARTIAL_MODE, true);
        }

        dispatchStrategy(ctx, s, slot);
    }

    /** Pick COLLECT_ALL vs PER_SLOT and dispatch the first click. */
    private void dispatchStrategy(StepContext ctx, WorldSnapshot s, int slot) {
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        // COLLECT_ALL clicks the toolbar button on the main GE grid — it is
        // hidden while the per-slot detail view is open. If we're already in
        // the detail view (collectOpen), force PER_SLOT so we stay in the
        // right context; re-rolling to COLLECT_ALL here would always no-op.
        boolean detailOpen = s.grandExchange().collectOpen();
        Strategy strategy = (!detailOpen && useCollectAll.getAsBoolean())
            ? Strategy.COLLECT_ALL : Strategy.PER_SLOT;
        step.put(K_STRATEGY, strategy);

        if (strategy == Strategy.COLLECT_ALL) {
            ge.collectAll();
            step.put(K_PHASE, Phase.AWAIT_DRAIN);
            return;
        }

        // PER_SLOT: open the slot's detail view if not already there.
        if (s.grandExchange().collectOpen()) {
            step.put(K_PHASE, Phase.CLICK_COLLECT_INV);
            ge.collect(slot);
        } else {
            step.put(K_PHASE, Phase.OPEN_COLLECT);
            ge.openOfferDetail(slot);
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}

    @Override public void tick(StepContext ctx) {
        // PER_SLOT phase machine only.
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        if (step.get(K_STRATEGY).orElse(null) != Strategy.PER_SLOT) return;
        if (step.get(K_PHASE).orElse(null) == Phase.AWAIT_COLLECT_VIEW
            && ctx.snapshot().grandExchange().collectOpen()) {
            Integer slotBox = ctx.bb().scope(BlackboardScope.SEQUENCE)
                .get(GeBlackboardKeys.K_GE_OFFER_SLOT).orElse(null);
            if (slotBox != null) {
                step.put(K_PHASE, Phase.CLICK_COLLECT_INV);
                ge.collect(slotBox);
            }
        }
    }

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);

        if (Boolean.TRUE.equals(step.get(K_ALREADY_EMPTY).orElse(null))) {
            // Clear SEQUENCE-scope retry-baseline keys so a follow-up
            // CollectOfferStep in a longer sequence doesn't mis-read them
            // as "this is a retry, reuse the prior baseline" (P2 MINOR).
            clearPersistedBaseline(bb);
            return new Completion.Succeeded("slot already empty");
        }

        Integer slotBox = bb.scope(BlackboardScope.SEQUENCE)
            .get(GeBlackboardKeys.K_GE_OFFER_SLOT).orElse(null);
        if (slotBox == null) {
            return Completion.failed(new GeBlockReason.GeOfferRejected("slot key missing during check"));
        }
        int slot = slotBox;
        GrandExchangeOfferView o = s.grandExchange().offers().get(slot);

        // Slot draining is the success signal. We deliberately do NOT
        // gate on `inventory.count(unnotedItemId) > startCount` because
        // the bot collects items as NOTES (left-click default action
        // "Collect-notes") — the noted item id differs from the unnoted
        // form, so the unnoted count never rises and a delta-gated
        // success would always time out into GeCollectFailed. The slot
        // can only empty if the items left the GE (collected by us, by
        // the player externally, or by abort), and in all of those
        // cases the player's wealth is conserved. Telemetry-only delta
        // included in the success message for diagnostic value.
        boolean slotEmpty = o.isEmpty();
        if (slotEmpty) {
            int expectedItemId = step.get(K_EXPECTED_DELTA_ITEM_ID).orElse(-1);
            int startCount = step.get(K_EXPECTED_DELTA_START).orElse(0);
            int now = (expectedItemId > 0) ? s.inventory().count(expectedItemId) : 0;
            int delta = now - startCount;
            clearPersistedBaseline(bb);
            return new Completion.Succeeded("collected (slot empty, unnoted-delta=" + delta + ")");
        }

        // Partial-fill mode: slot will NOT empty (offer is still active for
        // the unfilled remainder). Success when the dispatcher worker has
        // gone idle post-dispatch and a brief grace window has passed —
        // the click landed, items extracted to inventory, the offer keeps
        // running for the rest. Per user spec: "collect what we can, leave
        // the rest for later".
        if (Boolean.TRUE.equals(step.get(K_PARTIAL_MODE).orElse(null))) {
            // Stamp the first-idle tick once the click chain releases. Use
            // a separate key so the normal full-collect path (which expects
            // slot-empty) isn't affected.
            if (dispatcher == null || !dispatcher.isBusy()) {
                Integer firstIdle = step.get(K_PARTIAL_DISPATCH_TICK).orElse(null);
                if (firstIdle == null) {
                    step.put(K_PARTIAL_DISPATCH_TICK, s.tick());
                    return Completion.RUNNING;
                }
                if (s.tick() - firstIdle >= PARTIAL_GRACE_TICKS) {
                    clearPersistedBaseline(bb);
                    return new Completion.Succeeded(
                        "partial collected (slot still ACTIVE: "
                            + o.completedQuantity() + "/" + o.requestedQuantity() + ")");
                }
            }
            return Completion.RUNNING;
        }

        // PER_SLOT phase progression must NOT advance while the prior
        // dispatch (openOfferDetail click chain) is still parking the
        // cursor — tick() would dispatch the second click into the busy
        // guard and the request would be silently dropped (mirror the
        // 2026-04-30 StartOfferStep race fix). Mirrors
        // CollectAllCompletedOffersStep.check() at the same gate site.
        if (dispatcher != null && dispatcher.isBusy()) {
            return Completion.RUNNING;
        }
        if (step.get(K_STRATEGY).orElse(null) == Strategy.PER_SLOT) {
            Phase ph = step.get(K_PHASE).orElse(Phase.OPEN_COLLECT);
            if (ph == Phase.OPEN_COLLECT && s.grandExchange().collectOpen()) {
                step.put(K_PHASE, Phase.AWAIT_COLLECT_VIEW);
            }
        }

        int startTick = step.get(K_START_TICK).orElse(s.tick());
        if (s.tick() - startTick >= timeoutTicks()) {
            // Click-missed timeout: slot still occupied. Reason fields are
            // best-effort — observed delta is meaningless when collecting
            // notes since `count(unnotedItemId)` won't move regardless.
            int expectedItemId = step.get(K_EXPECTED_DELTA_ITEM_ID).orElse(-1);
            int startCount = step.get(K_EXPECTED_DELTA_START).orElse(0);
            int now = (expectedItemId > 0) ? s.inventory().count(expectedItemId) : 0;
            return Completion.failed(new GeBlockReason.GeCollectFailed(
                slot, expectedItemId, now - startCount));
        }
        return Completion.RUNNING;
    }

    /** Clear the SEQUENCE-scope persistence keys this step uses to survive
     *  Recovery.Retry. Called on success / already-satisfied so a downstream
     *  CollectOfferStep in the same sequence re-derives its baseline. */
    private static void clearPersistedBaseline(Blackboard bb) {
        bb.scope(BlackboardScope.SEQUENCE).remove(K_DELTA_ITEM_ID_PERSISTED);
        bb.scope(BlackboardScope.SEQUENCE).remove(K_DELTA_START_PERSISTED);
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeNotOpen)        return new Recovery.Abort("ge not open");
        if (f.diagnostic() instanceof GeBlockReason.GeOfferRejected)  return new Recovery.Abort("offer slot missing");
        if (f.diagnostic() instanceof GeBlockReason.GeCollectFailed)  return new Recovery.Retry(2);
        return new Recovery.Retry(2);
    }

    private enum Strategy { COLLECT_ALL, PER_SLOT }
    private enum Phase { OPEN_COLLECT, AWAIT_COLLECT_VIEW, CLICK_COLLECT_INV, AWAIT_DRAIN }
}
