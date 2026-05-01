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
import net.runelite.client.sequence.views.InventoryView;

/**
 * Pure verifier: aborts with {@link GeBlockReason.InsufficientSellItems} if
 * the inventory holds none of the sell item (in any form). Uses
 * {@link InventoryView#countAnyForm} which counts both noted and unnoted
 * forms so bank-prep flows that withdraw via {@code withdrawAsNoteX} are
 * not wrongly rejected. Any positive count is accepted — the GE will list
 * whatever qty we have.
 */
public final class EnsureInventoryForSellStep implements Step {

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("ensureInvSell.precondition", GeBlockReason.class);

    private final int itemId;
    private final int quantity;

    public EnsureInventoryForSellStep(int itemId, int quantity) {
        if (itemId <= 0)   throw new IllegalArgumentException("itemId must be > 0");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be > 0");
        this.itemId = itemId;
        this.quantity = quantity;
    }

    @Override public String name()                              { return "EnsureInventoryForSell(item=" + itemId + ")"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 4; }
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.NEVER; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        int have = s.inventory().countAnyForm(itemId);
        if (have < 1) {
            step.put(K_PRECONDITION, new GeBlockReason.InsufficientSellItems(itemId, quantity, have));
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        return step.get(K_PRECONDITION)
            .<Completion>map(Completion::failed)
            .orElseGet(() -> new Completion.Succeeded("inventory has enough items to sell"));
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Abort(f.reason());
    }
}
