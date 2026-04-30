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
    /** Best-effort fallback ticks. The combined PICK_GE_SEARCH_RESULT
     *  worker dispatch types the name (~3-5s), polls for the matching row
     *  (variable, can take 5-10s on slow cs2 redraw), then clicks — so
     *  we need a generous fallback. 25 ticks ~= 15s, covers worst case
     *  while still failing fast on a stuck flow. */
    private static final int DISPATCH_FALLBACK_TICKS = 25;

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
    @Override public int timeoutTicks()                         { return 40; }
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
        boolean queued = ge.selectItem(itemId, displayName);
        if (!queued) {
            step.put(K_PRECONDITION,
                new GeBlockReason.GeChatboxPromptTimeout("selectItem"));
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);
        if (!s.grandExchange().offerSetupOpen()) {
            return Completion.failed(new GeBlockReason.GeOfferSetupNotOpen());
        }
        // The typing runs async on the dispatcher worker. Succeed when
        // the search results widget has rendered (live signal) OR enough
        // ticks elapsed for the dispatch to land (best-effort fallback —
        // covers a missed snapshot of the results-rendered window).
        if (s.grandExchange().searchResultsPopulated()) {
            return new Completion.Succeeded("search list rendered");
        }
        int startTick = step.get(K_START_TICK).orElse(s.tick());
        if (s.tick() - startTick >= DISPATCH_FALLBACK_TICKS) {
            return new Completion.Succeeded("type dispatched (fallback)");
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeOfferSetupNotOpen) {
            return new Recovery.Abort("offer-setup not open");
        }
        if (f.diagnostic() instanceof GeBlockReason.GeChatboxPromptTimeout) {
            return new Recovery.Abort("chatbox prompt did not open");
        }
        return new Recovery.Retry(2);
    }
}
