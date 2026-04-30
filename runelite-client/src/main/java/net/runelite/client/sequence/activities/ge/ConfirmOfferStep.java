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
import net.runelite.client.sequence.dispatch.InputDispatcher;
import net.runelite.client.sequence.views.GrandExchangeOfferView;
import net.runelite.client.sequence.views.OfferSide;

/**
 * Final sub-step of CreateOffer: clicks "Confirm Offer", waits for an offer
 * matching {@code (itemId, side, quantity, priceEach)} to surface in
 * {@code ge.offers()}, and writes its slot index to
 * {@link GeBlackboardKeys#K_GE_OFFER_SLOT} (SEQUENCE scope).
 *
 * <p>Mismatch detection: if an offer surfaces with a different qty / item /
 * price than requested, fails with the corresponding typed reason
 * ({@link GeBlockReason.GeOfferQuantityMismatch} / {@code GeOfferItemMismatch}
 * / {@code GeOfferPriceMismatch}).
 *
 * <p>High-price warning popup: when the submitted price is much higher
 * (buy) or lower (sell) than the guide price, OSRS shows the
 * {@code Popupoverlay} "are you sure?" dialog. By default we click
 * {@code No} and fail with {@link GeBlockReason.GeOfferPriceTooHigh} —
 * the caller is expected to retry with a price closer to guide. Set
 * {@code acceptHighPriceWarning=true} (price-check probes) to click
 * {@code Yes} and proceed at the deliberately overpriced bid.
 */
public final class ConfirmOfferStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("confirmOffer.precondition", GeBlockReason.class);
    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("confirmOffer.startTick", Integer.class);
    /** True once we've dispatched the warning-popup dismiss; gates the
     *  one-shot dispatch in tick() so we don't spam the click while the
     *  popup is closing. */
    private static final BlackboardKey<Boolean> K_WARNING_DISMISSED =
        BlackboardKey.of("confirmOffer.warningDismissed", Boolean.class);

    private final int itemId;
    private final OfferSide side;
    private final int quantity;
    private final int priceEach;
    private final boolean acceptHighPriceWarning;
    private final GeActions ge;
    /** Captured from {@link StepContext#dispatcher()} in onStart so tick()
     *  can gate the {@code dismissPriceWarning} dispatch on the prior
     *  {@code confirmOffer} click chain having released the worker.
     *  Without this gate the dismiss is silently dropped by the busy
     *  guard, K_WARNING_DISMISSED stays set, and check() falsely fails
     *  GeOfferPriceTooHigh while the popup is still on screen. */
    private InputDispatcher dispatcher;

    /** Default behaviour: reject the high-price warning and abort with
     *  {@link GeBlockReason.GeOfferPriceTooHigh}. */
    public ConfirmOfferStep(int itemId, OfferSide side, int quantity, int priceEach, GeActions ge) {
        this(itemId, side, quantity, priceEach, ge, false);
    }

    /** Variant for price-check probes: pass {@code acceptHighPriceWarning=true}
     *  to click {@code Yes} and proceed at the deliberately overpriced bid. */
    public ConfirmOfferStep(int itemId, OfferSide side, int quantity, int priceEach,
                            GeActions ge, boolean acceptHighPriceWarning) {
        if (itemId <= 0)                throw new IllegalArgumentException("itemId must be > 0");
        if (side == null || side == OfferSide.NONE) {
            throw new IllegalArgumentException("side must be BUY or SELL");
        }
        if (quantity <= 0)              throw new IllegalArgumentException("quantity must be > 0");
        if (priceEach <= 0)             throw new IllegalArgumentException("priceEach must be > 0");
        if (ge == null)                 throw new IllegalArgumentException("GeActions must not be null");
        this.itemId = itemId;
        this.side = side;
        this.quantity = quantity;
        this.priceEach = priceEach;
        this.acceptHighPriceWarning = acceptHighPriceWarning;
        this.ge = ge;
    }

    @Override public String name()                              { return "ConfirmOffer(" + side + "," + quantity + "@" + priceEach + ")"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 8; }
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
        // Reset the warning-dismiss gate so a Recovery.Retry actually gets
        // a fresh attempt. The engine does NOT clear STEP scope on
        // Recovery.Retry (verified in StateDrivenEngine.applyRecovery), so
        // a stale K_WARNING_DISMISSED from a prior attempt would otherwise
        // short-circuit check() into GeOfferPriceTooHigh on the retry's
        // first tick before tick() ever fires the dismiss.
        step.remove(K_WARNING_DISMISSED);
        step.put(K_START_TICK, s.tick());
        ge.confirmOffer();
    }

    @Override public void onEvent(Object event, StepContext ctx) {}

    @Override public void tick(StepContext ctx) {
        // High-price warning popup arrived while we were waiting for the
        // offer to surface. Dispatch the dismiss exactly once.
        //
        // Two gates, in order:
        //   1. Don't re-dispatch — K_WARNING_DISMISSED set after a
        //      successful dispatch.
        //   2. Don't dispatch into a busy worker — the prior confirmOffer
        //      click chain (cursor humanization + park) holds busy for
        //      several hundred ms after onStart, and the popup typically
        //      appears at the same tick the click lands. Dispatching here
        //      while busy would silently drop the dismiss, leave the
        //      popup on screen, and (for !acceptHighPriceWarning) cause
        //      check() to falsely surface GeOfferPriceTooHigh. We set the
        //      gate AFTER the dispatch is accepted, not before — so a
        //      future tick can still fire the dismiss when the worker
        //      releases.
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        if (Boolean.TRUE.equals(step.get(K_WARNING_DISMISSED).orElse(null))) return;
        if (!ctx.snapshot().grandExchange().priceWarningOpen()) return;
        if (dispatcher != null && dispatcher.isBusy()) return;
        ge.dismissPriceWarning(acceptHighPriceWarning);
        step.put(K_WARNING_DISMISSED, true);
    }

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);

        // If we dismissed a "No" already, the offer was cancelled before it
        // ever reached the slot. Surface the typed failure so the caller
        // can decide whether to retry at a lower price.
        if (Boolean.TRUE.equals(step.get(K_WARNING_DISMISSED).orElse(null))
            && !acceptHighPriceWarning) {
            return Completion.failed(new GeBlockReason.GeOfferPriceTooHigh(priceEach));
        }

        // Look for a slot that holds OUR (itemId, side) and verify it matches.
        for (GrandExchangeOfferView o : s.grandExchange().offers()) {
            if (o.itemId() == itemId && o.side() == side && !o.isEmpty()) {
                if (o.requestedQuantity() != quantity) {
                    return Completion.failed(new GeBlockReason.GeOfferQuantityMismatch(
                        o.slot(), quantity, o.requestedQuantity()));
                }
                if (o.priceEach() != priceEach) {
                    return Completion.failed(new GeBlockReason.GeOfferPriceMismatch(
                        o.slot(), priceEach, o.priceEach()));
                }
                // Match — write slot to SEQUENCE scope and succeed.
                bb.scope(BlackboardScope.SEQUENCE)
                  .put(GeBlackboardKeys.K_GE_OFFER_SLOT, o.slot());
                return new Completion.Succeeded("offer surfaced in slot " + o.slot());
            }
        }

        // No (itemId, side) match. Check whether the slot we clicked into in
        // StartOfferStep now holds a non-empty offer with a DIFFERENT itemId
        // — that's a wrong-item surface, not a generic rejection. We can
        // detect that without timing out, so surface GeOfferItemMismatch
        // immediately. (Wrong-side mismatch is covered by the same check.)
        Integer tentativeSlot = bb.scope(BlackboardScope.SEQUENCE)
            .get(GeBlackboardKeys.K_GE_TENTATIVE_SLOT).orElse(null);
        if (tentativeSlot != null) {
            int slot = tentativeSlot;
            var offers = s.grandExchange().offers();
            if (slot >= 0 && slot < offers.size()) {
                GrandExchangeOfferView o = offers.get(slot);
                if (!o.isEmpty() && o.itemId() != itemId) {
                    return Completion.failed(new GeBlockReason.GeOfferItemMismatch(
                        slot, itemId, o.itemId()));
                }
            }
        }

        // No offer for our (itemId, side) yet — keep waiting until timeout.
        // If the warning popup is still up (we may be mid-dismiss), tick()
        // will fire the click; check() should not declare timeout while
        // the popup is still on screen.
        if (s.grandExchange().priceWarningOpen()) {
            return Completion.RUNNING;
        }
        int startTick = step.get(K_START_TICK).orElse(s.tick());
        if (s.tick() - startTick >= timeoutTicks()) {
            return Completion.failed(new GeBlockReason.GeOfferRejected("offer did not surface within " + timeoutTicks() + " ticks"));
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeOfferRejected) {
            return new Recovery.Retry(2);
        }
        // Mismatches, GeNotOpen, GeOfferPriceTooHigh — abort. Caller must
        // reconcile (lower price + retry, or surface to user).
        return new Recovery.Abort(f.reason());
    }
}
