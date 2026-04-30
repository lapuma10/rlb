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

/**
 * Validate-then-click step for the GE search result list. Runs immediately
 * after {@link SelectItemStep} (which types the name without Enter) and
 * before {@link SetQuantityStep}. The pre-validation is the whole point —
 * we never trust the engine's auto-pick because the search will partial-
 * match unintended items ("25" picks "Team cape 25", "bread" picks the
 * first bread-something).
 *
 * <p>Mechanism: walks {@code Chatbox.MES_LAYER_SCROLLCONTENTS} dynamic
 * children in groups of 3 (icon / name / price), finds the row whose
 * icon's {@code getItemId()} equals the intent's {@code itemId}, clicks
 * that row's combined bounds via {@code CLICK_BOUNDS}. Aborts with
 * {@link GeBlockReason.GeSearchResultNotFound} when no row matches —
 * better to fail loudly than buy/sell the wrong item.
 */
public final class PickSearchResultStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("pickResult.precondition", GeBlockReason.class);
    /** Set to true after {@code GeActions.pickSearchResult} confirms a
     *  match-and-click. The check() guard requires this AND state-
     *  advancement evidence — without that, a not-found result or a
     *  click that landed on the wrong widget would silently cascade. */
    private static final BlackboardKey<Boolean> K_PICKED =
        BlackboardKey.of("pickResult.picked", Boolean.class);
    /** Tick at which the click was dispatched. Used by check() to bound
     *  the post-click verification window — if we don't see the search
     *  results close within {@link #POST_CLICK_VERIFY_TICKS} ticks, the
     *  click didn't take effect and we abort with a clear reason. */
    private static final BlackboardKey<Integer> K_CLICK_TICK =
        BlackboardKey.of("pickResult.clickTick", Integer.class);

    /** Ticks to wait after the click for the search results to close.
     *  cs2 transitions usually finish in 1–2 ticks but server lag can
     *  stretch it; 5 ticks ~= 3s of game time. After this elapses without
     *  seeing results-closed we treat as success (best-effort) since the
     *  click was dispatched and the snapshot observer can miss the brief
     *  results-rendered window between cs2 ticks. */
    private static final int POST_CLICK_VERIFY_TICKS = 5;

    private final int itemId;
    private final String displayName;
    private final GeActions ge;
    /** Captured from {@link StepContext#dispatcher()} in onStart so check()
     *  can wait for the prior step's bundled type+pick RUN_TASK to finish
     *  before declaring success. */
    private InputDispatcher dispatcher;

    public PickSearchResultStep(int itemId, String displayName, GeActions ge) {
        if (itemId <= 0) throw new IllegalArgumentException("itemId must be > 0");
        if (ge == null)  throw new IllegalArgumentException("GeActions must not be null");
        this.itemId = itemId;
        this.displayName = displayName != null ? displayName : ("item#" + itemId);
        this.ge = ge;
    }

    @Override public String name()                              { return "PickSearchResult(" + displayName + ")"; }
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
        if (!s.grandExchange().offerSetupOpen()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeOfferSetupNotOpen());
            return;
        }
        boolean picked = ge.pickSearchResult(itemId);
        step.put(K_PICKED, picked);
        step.put(K_CLICK_TICK, s.tick());
        if (!picked) {
            step.put(K_PRECONDITION,
                new GeBlockReason.GeSearchResultNotFound(itemId, displayName));
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        GeBlockReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);
        if (!Boolean.TRUE.equals(step.get(K_PICKED).orElse(null))) {
            return Completion.RUNNING;
        }
        // Don't trust K_PICKED alone — verify the click actually moved the
        // GE state forward. Two acceptable outcomes:
        //   1. Search results closed (cs2 transitioned to the quantity
        //      prompt — most common path).
        //   2. Offer-setup is still open AND results still rendering BUT
        //      we're inside the verify window — keep waiting.
        // If the verify window expires with results still visible, the
        // click landed on something that didn't advance the GE; treat as
        // GeSearchResultNotFound so the script doesn't barrel into typing
        // the quantity into the still-open search box.
        if (!s.grandExchange().offerSetupOpen()) {
            return Completion.failed(new GeBlockReason.GeOfferSetupNotOpen());
        }
        // The prior SelectItemStep bundled type + pick into one RUN_TASK
        // that's still running on the worker. Wait for it to finish
        // before declaring success — otherwise SetQuantity dispatches
        // into a busy worker and gets dropped.
        if (dispatcher != null && dispatcher.isBusy()) {
            return Completion.RUNNING;
        }
        boolean stillPopulated = s.grandExchange().searchResultsPopulated();
        int clickTick = step.get(K_CLICK_TICK).orElse(s.tick());
        int elapsed = s.tick() - clickTick;
        // Success when results closed (live signal) OR enough idle ticks
        // elapsed (fallback only after worker is idle, see above).
        if (!stillPopulated || elapsed >= POST_CLICK_VERIFY_TICKS) {
            return new Completion.Succeeded("results closed; offer-setup advanced");
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof GeBlockReason.GeOfferSetupNotOpen) {
            return new Recovery.Abort("offer-setup not open");
        }
        if (f.diagnostic() instanceof GeBlockReason.GeSearchResultNotFound) {
            return new Recovery.Abort("search result not found");
        }
        return new Recovery.Retry(2);
    }
}
