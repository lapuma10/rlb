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
     *  match-and-click. The check() guard requires this — without it a
     *  not-found result would silently cascade into the next step. */
    private static final BlackboardKey<Boolean> K_PICKED =
        BlackboardKey.of("pickResult.picked", Boolean.class);

    private final int itemId;
    private final String displayName;
    private final GeActions ge;

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
        if (!s.grandExchange().offerSetupOpen()) {
            step.put(K_PRECONDITION, new GeBlockReason.GeOfferSetupNotOpen());
            return;
        }
        boolean picked = ge.pickSearchResult(itemId);
        step.put(K_PICKED, picked);
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
        if (Boolean.TRUE.equals(step.get(K_PICKED).orElse(null))
            && s.grandExchange().offerSetupOpen()) {
            return new Completion.Succeeded("search result picked");
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
