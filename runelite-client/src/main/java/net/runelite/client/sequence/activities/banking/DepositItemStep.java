package net.runelite.client.sequence.activities.banking;

import java.util.Optional;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;

/**
 * Deposits all of a given item into the bank.
 *
 * <p>Already satisfied when {@code inv.count(itemId) == 0}.
 * Fatal precondition: bank not open.
 */
public final class DepositItemStep implements Step {

    private final int itemId;
    private final BankActions bank;

    public DepositItemStep(int itemId, BankActions bank) {
        this.itemId = itemId;
        this.bank = bank;
    }

    @Override public String name()                 { return "DepositItem(" + itemId + ")"; }
    @Override public int priority()                { return 100; }
    @Override public int timeoutTicks()            { return 6; }
    @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard bb) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard bb)       { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);

        // Already satisfied
        if (s.inventory().count(itemId) == 0) {
            step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
            return;
        }

        // Fatal: bank not open
        if (!s.bank().open()) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.BankNotOpen());
            return;
        }

        // Fresh work
        step.put(K_START_TICK, s.tick());
        try {
            bank.depositAll(itemId);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            step.put(K_PRECONDITION_FAILURE, new DiagnosticReason.Unknown("interrupted"));
        }
    }

    @Override public void onEvent(Object e, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);

        Optional<DiagnosticReason> pre = step.get(K_PRECONDITION_FAILURE);
        if (pre.isPresent()) return Completion.failed(pre.get());

        if (step.get(K_OUTCOME).orElse(Outcome.FRESH) == Outcome.ALREADY_SATISFIED) {
            return new Completion.Succeeded("item " + itemId + " not in inventory");
        }

        if (s.inventory().count(itemId) == 0) {
            return new Completion.Succeeded("deposited item " + itemId);
        }

        if (!s.bank().open()) return Completion.failed(new BlockReason.BankNotOpen());

        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof BlockReason.BankNotOpen) {
            return new Recovery.Abort(f.reason());
        }
        return new Recovery.Retry(3);
    }

    private enum Outcome { FRESH, ALREADY_SATISFIED }

    private static final BlackboardKey<DiagnosticReason> K_PRECONDITION_FAILURE =
        BlackboardKey.of("depositItem.preconditionFailure", DiagnosticReason.class);
    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("depositItem.startTick", Integer.class);
    private static final BlackboardKey<Outcome> K_OUTCOME =
        BlackboardKey.of("depositItem.outcome", Outcome.class);
}
