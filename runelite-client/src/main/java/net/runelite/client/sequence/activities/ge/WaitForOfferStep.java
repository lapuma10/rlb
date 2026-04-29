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

/**
 * Passive wait for the in-flight offer (slot from {@code K_GE_OFFER_SLOT}
 * SEQUENCE-scope) to fill.
 *
 * <p>Owns its typed timeout in {@code check} so a partial-fill timeout can
 * surface {@link GeBlockReason.GeOfferIncomplete} or
 * {@link GeBlockReason.GeOfferTimeout} BEFORE the engine's generic
 * {@code Failure.timeout}.
 *
 * <p>Does NOT auto-abort the offer on timeout — the offer remains in flight
 * for the user to resolve. Spec scenarios 14 / 15 cover this.
 */
public final class WaitForOfferStep implements Step {

    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("waitOffer.startTick", Integer.class);

    private final OfferWaitPolicy waitPolicy;

    public WaitForOfferStep(OfferWaitPolicy waitPolicy) {
        if (waitPolicy == null) throw new IllegalArgumentException("waitPolicy must not be null");
        this.waitPolicy = waitPolicy;
    }

    @Override public String name()                              { return "WaitForOffer(" + waitPolicy + ")"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         {
        // Engine-generic timeout slightly higher than typed timeout so
        // typed reasons surface first.
        return Math.max(waitPolicy.timeoutTicks() + 4, 5);
    }
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

    @Override
    public void onStart(StepContext ctx) {
        ctx.bb().scope(BlackboardScope.STEP).put(K_START_TICK, ctx.snapshot().tick());
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Integer slotBox = bb.scope(BlackboardScope.SEQUENCE).get(GeBlackboardKeys.K_GE_OFFER_SLOT).orElse(null);
        if (slotBox == null) {
            return Completion.failed(new GeBlockReason.GeOfferRejected("no offer slot recorded — confirm-step must run first"));
        }
        int slot = slotBox;
        if (slot < 0 || slot >= s.grandExchange().offers().size()) {
            return Completion.failed(new GeBlockReason.GeOfferRejected("recorded slot out of range: " + slot));
        }
        GrandExchangeOfferView o = s.grandExchange().offers().get(slot);

        if (o.isComplete()) return new Completion.Succeeded("offer complete in slot " + slot);

        int startTick = bb.scope(BlackboardScope.STEP).get(K_START_TICK).orElse(s.tick());
        int elapsed = s.tick() - startTick;
        if (elapsed >= waitPolicy.timeoutTicks()) {
            int completed = o.completedQuantity();
            int requested = o.requestedQuantity();
            if (waitPolicy.acceptPartialOnTimeout() && completed > 0) {
                return new Completion.Succeeded("partial fill accepted: " + completed + "/" + requested);
            }
            if (completed > 0) {
                return Completion.failed(new GeBlockReason.GeOfferIncomplete(slot, completed, requested));
            }
            return Completion.failed(new GeBlockReason.GeOfferTimeout(slot, elapsed));
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Abort(f.reason());
    }
}
