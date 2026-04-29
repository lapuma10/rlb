package net.runelite.client.sequence.activities.ge;

import java.util.List;
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
 * Pure verifier: aborts with {@link GeBlockReason.GeExistingOfferConflict}
 * if any GE slot already holds a non-EMPTY offer for {@code (itemId, side)}.
 *
 * <p>Conservative rule: a previous run that wasn't collected, or any
 * unrelated active offer the user placed manually, blocks the new offer.
 * The user clears the conflict (collects or cancels the prior offer)
 * before re-running.
 */
public final class EnsureNoConflictingOfferStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("noConflict.precondition", GeBlockReason.class);

    private final int itemId;
    private final OfferSide side;

    public EnsureNoConflictingOfferStep(int itemId, OfferSide side) {
        if (itemId <= 0)        throw new IllegalArgumentException("itemId must be > 0");
        if (side == null || side == OfferSide.NONE) {
            throw new IllegalArgumentException("side must be BUY or SELL");
        }
        this.itemId = itemId;
        this.side = side;
    }

    @Override public String name()                              { return "EnsureNoConflictingOffer(" + itemId + "," + side + ")"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 4; }
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.NEVER; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        List<GrandExchangeOfferView> conflicts = s.grandExchange().offersFor(itemId, side);
        if (!conflicts.isEmpty()) {
            GrandExchangeOfferView first = conflicts.get(0);
            step.put(K_PRECONDITION, new GeBlockReason.GeExistingOfferConflict(
                first.slot(), side, itemId, first.status()));
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        return step.get(K_PRECONDITION)
            .<Completion>map(Completion::failed)
            .orElseGet(() -> new Completion.Succeeded("no conflicting offer"));
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Abort(f.reason());
    }
}
