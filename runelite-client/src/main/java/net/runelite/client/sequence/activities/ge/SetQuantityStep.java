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

/**
 * Sets the quantity in the offer-setup widget. Dispatches via
 * {@code GeActions.setQuantity} on onStart. Phase A success criterion is
 * "offer-setup is still open after one chain"; Phase B / banking can wire
 * the real quantity-widget probe.
 */
public final class SetQuantityStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("setQty.precondition", GeBlockReason.class);

    private final int qty;
    private final GeActions ge;

    public SetQuantityStep(int qty, GeActions ge) {
        if (qty <= 0)   throw new IllegalArgumentException("qty must be > 0");
        if (ge == null) throw new IllegalArgumentException("GeActions must not be null");
        this.qty = qty;
        this.ge = ge;
    }

    @Override public String name()                              { return "SetQuantity(" + qty + ")"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 6; }
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        if (!s.grandExchange().offerSetupOpen()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeOfferSetupNotOpen());
            return;
        }
        ge.setQuantity(qty);
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);
        if (s.grandExchange().offerSetupOpen()) {
            return new Completion.Succeeded("quantity set");
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeOfferSetupNotOpen) {
            return new Recovery.Abort("offer-setup not open");
        }
        return new Recovery.Retry(2);
    }
}
