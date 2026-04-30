package net.runelite.client.sequence.activities.banking;

import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.Blackboard;

/**
 * Guard step that succeeds when {@code inventory.count(itemId) >= qty},
 * fails otherwise. Designed to be the first option of a {@code Selector}
 * wrapping a bank sub-flow — when we already have enough of an item,
 * this option succeeds and the Selector finishes without ever opening
 * the bank.
 *
 * <p>Common use: GE buy-with-prep skipping the coin withdraw when the
 * player already has enough coins to fund the trade.
 */
public final class HaveAtLeastInInventoryStep implements Step {

    private final int itemId;
    private final int qty;
    private final String displayName;

    public HaveAtLeastInInventoryStep(int itemId, int qty, String displayName) {
        if (itemId <= 0) throw new IllegalArgumentException("itemId must be > 0");
        if (qty <= 0)    throw new IllegalArgumentException("qty must be > 0");
        this.itemId = itemId;
        this.qty = qty;
        this.displayName = displayName != null ? displayName : ("item#" + itemId);
    }

    @Override public String name()                              { return "HaveAtLeast(" + qty + " " + displayName + ")"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 1; }
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.NEVER; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

    @Override public void onStart(StepContext ctx) {}
    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        int have = s.inventory().count(itemId);
        if (have >= qty) {
            return new Completion.Succeeded(
                "inventory has " + have + " " + displayName + " (need " + qty + ")");
        }
        // Diagnostic-less failure: this step is designed to live as the
        // first option of a Selector wrapping the bank sub-flow. The
        // Selector treats failure as "try next option" — exactly the
        // behaviour we want when we don't have enough yet.
        return new Completion.Failed(
            "inventory has " + have + " " + displayName + " (need " + qty + ") — bank required");
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Abort(f.reason());
    }
}
