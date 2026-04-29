package net.runelite.client.sequence.activities.banking;

import java.util.Optional;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;

/**
 * Closes the bank widget.
 *
 * <p>Already satisfied when the bank is not open.
 * check() succeeds when {@code !bank.open() && interaction.worldInteractionAvailable()}.
 */
public final class CloseBankStep implements Step {

    private final BankActions bank;

    public CloseBankStep(BankActions bank) {
        this.bank = bank;
    }

    @Override public String name()                 { return "CloseBank"; }
    @Override public int priority()                { return 100; }
    @Override public int timeoutTicks()            { return 4; }
    @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard bb) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard bb)       { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);

        if (!s.bank().open()) {
            step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
            return;
        }

        step.put(K_START_TICK, s.tick());
        try {
            bank.closeBank();
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
            return new Completion.Succeeded("bank already closed");
        }

        if (!s.bank().open() && s.interaction().worldInteractionAvailable()) {
            return new Completion.Succeeded("bank closed");
        }

        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Retry(3);
    }

    private enum Outcome { FRESH, ALREADY_SATISFIED }

    private static final BlackboardKey<DiagnosticReason> K_PRECONDITION_FAILURE =
        BlackboardKey.of("closeBank.preconditionFailure", DiagnosticReason.class);
    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("closeBank.startTick", Integer.class);
    private static final BlackboardKey<Outcome> K_OUTCOME =
        BlackboardKey.of("closeBank.outcome", Outcome.class);
}
