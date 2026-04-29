package net.runelite.client.sequence.activities.ge;

import java.util.OptionalInt;
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
import net.runelite.client.sequence.views.GrandExchangeView;
import net.runelite.client.sequence.views.OfferSide;

/**
 * First sub-step of the CreateOffer linear sequence: clicks the BUY or SELL
 * button on the first empty GE slot. The check waits until the offer-setup
 * interface is rendered.
 *
 * <p>Fatal preconditions detected in onStart:
 * <ul>
 *   <li>{@link GeBlockReason.GeNotOpen} when {@code ge.open()} is false.
 *   <li>{@link GeBlockReason.GeSlotsFull} when no slot is EMPTY.
 * </ul>
 */
public final class StartOfferStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("startOffer.precondition", GeBlockReason.class);

    private final OfferSide side;
    private final GeActions ge;

    public StartOfferStep(OfferSide side, GeActions ge) {
        if (side == null || side == OfferSide.NONE) {
            throw new IllegalArgumentException("side must be BUY or SELL");
        }
        if (ge == null) throw new IllegalArgumentException("GeActions must not be null");
        this.side = side;
        this.ge = ge;
    }

    @Override public String name()                              { return "StartOffer(" + side + ")"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 6; }
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
        OptionalInt slot = gx.firstEmptySlot();
        if (slot.isEmpty()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeSlotsFull());
            return;
        }
        ge.clickOfferSlotButton(slot.getAsInt(), side);
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);
        if (s.grandExchange().offerSetupOpen()) {
            return new Completion.Succeeded("offer-setup open");
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeNotOpen)    return new Recovery.Abort("ge not open");
        if (f.diagnostic() instanceof GeBlockReason.GeSlotsFull)  return new Recovery.Abort("ge slots full");
        return new Recovery.Retry(2);
    }
}
