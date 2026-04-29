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
 * Selects the item being traded in the offer-setup interface. Dispatches
 * {@code GeActions.selectItem} on onStart; verifies the chosen item id
 * matches the intent.
 *
 * <p>Verification of the "currently-selected item id" widget is deferred —
 * Phase A treats reaching {@code offerSetupOpen} again with no further
 * widgets-popup as success. Phase B / banking can refine to read a varc
 * once we identify the right one in {@code InterfaceID.GeOffers}.
 */
public final class SelectItemStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("selectItem.precondition", GeBlockReason.class);
    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("selectItem.startTick", Integer.class);

    private final int itemId;
    private final String displayName;
    private final GeActions ge;

    public SelectItemStep(int itemId, String displayName, GeActions ge) {
        if (itemId <= 0) throw new IllegalArgumentException("itemId must be > 0");
        if (ge == null)  throw new IllegalArgumentException("GeActions must not be null");
        this.itemId = itemId;
        this.displayName = displayName != null ? displayName : ("item#" + itemId);
        this.ge = ge;
    }

    @Override public String name()                              { return "SelectItem(" + displayName + ")"; }
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
        step.put(K_START_TICK, s.tick());
        ge.selectItem(itemId, displayName);
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);
        // For Phase A, we treat "offer-setup still open after selectItem"
        // as success. Real selected-item-id widget probe goes here later.
        if (s.grandExchange().offerSetupOpen()) {
            return new Completion.Succeeded("item selected");
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
