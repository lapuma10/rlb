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
 * Pure verifier: aborts with {@link GeBlockReason.InsufficientCoins} if
 * {@code inv.count(COINS) < quantity * priceEach}.
 *
 * <p>Coin item id is hard-coded to 995 (the OSRS "Coins" item).
 */
public final class EnsureInventoryForBuyStep implements Step {

    private static final int COINS_ITEM_ID = 995;

    private static final BlackboardKey<GeBlockReason> K_PRECONDITION =
        BlackboardKey.of("ensureInvBuy.precondition", GeBlockReason.class);

    private final int quantity;
    private final int priceEach;
    private final long totalCost;

    public EnsureInventoryForBuyStep(int quantity, int priceEach) {
        if (quantity <= 0)  throw new IllegalArgumentException("quantity must be > 0");
        if (priceEach <= 0) throw new IllegalArgumentException("priceEach must be > 0");
        this.quantity = quantity;
        this.priceEach = priceEach;
        this.totalCost = (long) quantity * (long) priceEach;
    }

    @Override public String name()                              { return "EnsureInventoryForBuy(qty=" + quantity + ", priceEach=" + priceEach + ")"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 4; }
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.NEVER; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        int have = s.inventory().count(COINS_ITEM_ID);
        if (have < totalCost) {
            // Cap the recorded "needed" at Integer.MAX_VALUE if totalCost overflows.
            int needed = totalCost > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalCost;
            step.put(K_PRECONDITION, new GeBlockReason.InsufficientCoins(needed, have));
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        return step.get(K_PRECONDITION)
            .<Completion>map(Completion::failed)
            .orElseGet(() -> new Completion.Succeeded("inventory has enough coins"));
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Abort(f.reason());
    }
}
