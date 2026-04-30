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
import net.runelite.client.sequence.dispatch.InputDispatcher;
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
    /** Captured from {@link StepContext#dispatcher()} in onStart so check()
     *  can gate Succeeded on the slot-click chain having released the
     *  dispatcher worker. The post-click humanization (cursor parking,
     *  ~50–2000ms tail) outlives the SETUP-screen open by an unbounded
     *  margin; declaring success while busy lets the next step onStart
     *  dispatch into the busy guard and silently drop. */
    private InputDispatcher dispatcher;

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
        this.dispatcher = ctx.dispatcher();
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
        // Persist the slot we click into so ConfirmOfferStep can detect a
        // wrong-item offer landing in our slot (and surface
        // GeOfferItemMismatch) instead of timing out as GeOfferRejected.
        ctx.bb().scope(BlackboardScope.SEQUENCE)
            .put(GeBlackboardKeys.K_GE_TENTATIVE_SLOT, slot.getAsInt());
        ge.clickOfferSlotButton(slot.getAsInt(), side);
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);
        if (!s.grandExchange().offerSetupOpen()) {
            return Completion.RUNNING;
        }
        // SETUP is up — but the slot-click chain may still be running its
        // post-click humanization (cursor park) and holding the dispatcher
        // busy. Declaring Succeeded here would let SelectItemStep.onStart
        // dispatch PICK_GE_SEARCH_RESULT, the busy guard would silently
        // drop it, the search would never run, and the bot would later
        // click "Enter quantity" on an item-less SETUP — observed as the
        // 17:47 / 17:48 GE_SET_QUANTITY chatbox-prompt timeouts.
        if (dispatcher != null && dispatcher.isBusy()) {
            return Completion.RUNNING;
        }
        return new Completion.Succeeded("offer-setup open");
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeNotOpen)    return new Recovery.Abort("ge not open");
        if (f.diagnostic() instanceof GeBlockReason.GeSlotsFull)  return new Recovery.Abort("ge slots full");
        return new Recovery.Retry(2);
    }
}
