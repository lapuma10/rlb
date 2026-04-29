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
 * <p>Mirrors {@code WithdrawItemStep.check} pattern (verification +
 * blackboard write inline; no separate Ensure*Recorded step).
 */
public final class ConfirmOfferStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("confirmOffer.precondition", GeBlockReason.class);
    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("confirmOffer.startTick", Integer.class);

    private final int itemId;
    private final OfferSide side;
    private final int quantity;
    private final int priceEach;
    private final GeActions ge;

    public ConfirmOfferStep(int itemId, OfferSide side, int quantity, int priceEach, GeActions ge) {
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
        if (!s.grandExchange().open()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeNotOpen());
            return;
        }
        step.put(K_START_TICK, s.tick());
        ge.confirmOffer();
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);

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
        // Mismatches and GeNotOpen — abort. User must reconcile.
        return new Recovery.Abort(f.reason());
    }
}
