package net.runelite.client.sequence.activities.banking;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.affordance.ItemDiff;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.views.InventoryView;

/**
 * Pure verification step: checks that the inventory matches the required loadout.
 * No dispatch — fatal if any required slot fails.
 */
public final class EnsureInventoryMatchesLoadoutStep implements Step {

    private final Loadout loadout;

    public EnsureInventoryMatchesLoadoutStep(Loadout loadout) {
        this.loadout = loadout;
    }

    @Override public String name()                 { return "EnsureInventoryMatchesLoadout"; }
    @Override public int priority()                { return 100; }
    @Override public int timeoutTicks()            { return 2; }
    @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.NEVER; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard bb) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard bb)       { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        InventoryView inv = s.inventory();

        List<ItemDiff> mismatches = new ArrayList<>();
        for (Loadout.Slot slot : loadout.slots()) {
            int have = inv.count(slot.itemId());
            boolean satisfied;
            if (slot.exact()) {
                satisfied = have == slot.qty();
            } else {
                satisfied = have >= 1;
            }
            if (!satisfied) {
                mismatches.add(new ItemDiff(slot.itemId(), String.valueOf(slot.itemId()), have, slot.qty()));
            }
        }

        if (!mismatches.isEmpty()) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.LoadoutMismatch(mismatches));
        }
    }

    @Override public void onEvent(Object e, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        Optional<DiagnosticReason> pre = step.get(K_PRECONDITION_FAILURE);
        if (pre.isPresent()) return Completion.failed(pre.get());
        return new Completion.Succeeded("loadout matches");
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Abort(f.reason());
    }

    private static final BlackboardKey<DiagnosticReason> K_PRECONDITION_FAILURE =
        BlackboardKey.of("ensureLoadout.preconditionFailure", DiagnosticReason.class);
}
