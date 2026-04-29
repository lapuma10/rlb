package net.runelite.client.sequence.activities.banking;

import java.util.Optional;
import java.util.Set;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.affordance.BlockingInterface;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.blackboard.SequenceBlackboardKeys;

/**
 * Clicks a bank booth to open the bank.
 *
 * <p>canStart=false (waitable) only when a closable blocker whose rootWidgetId is in
 * {@code closableAllowList} is present — the reactive {@link EnsureNoBlockingInterfaceStep}
 * will clear it. All other bad states are fatal preconditions detected in {@code onStart}.
 */
public final class OpenBankStep implements Step {

    private final Set<Integer> closableAllowList;
    private final BankActions bank;

    public OpenBankStep(Set<Integer> closableAllowList, BankActions bank) {
        this.closableAllowList = closableAllowList;
        this.bank = bank;
    }

    @Override public String name()                 { return "OpenBank"; }
    @Override public int priority()                { return 100; }
    @Override public int timeoutTicks()            { return 6; }
    @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard bb) { return true; }

    /**
     * Returns false (waitable) only when a closable allow-listed blocker is present.
     * The reactive EnsureNoBlockingInterfaceStep will clear it within bounded ticks.
     */
    @Override
    public boolean canStart(WorldSnapshot s, Blackboard bb) {
        Optional<BlockingInterface> blockerOpt = s.interaction().blockingInterface();
        if (blockerOpt.isEmpty()) return true;
        BlockingInterface by = blockerOpt.get();
        if (by.canBeClosed() && closableAllowList.contains(by.rootWidgetId())) {
            // Waitable — write block reason for planner telemetry (bb may be null in unit tests)
            if (bb != null) {
                bb.scope(BlackboardScope.STEP).put(SequenceBlackboardKeys.LAST_BLOCK_REASON,
                    new BlockReason.WorldInteractionBlocked(by));
            }
            return false;
        }
        return true;
    }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);

        // Already satisfied
        if (s.bank().open()) {
            step.put(K_OUTCOME, Outcome.ALREADY_SATISFIED);
            return;
        }

        // Fatal: pin keypad
        if (s.bank().pinUp()) {
            step.put(K_PRECONDITION_FAILURE, new BlockReason.PinKeypadUp());
            return;
        }

        // Fatal: blocking interface that can't be closed or isn't in allow-list
        Optional<BlockingInterface> blockerOpt = s.interaction().blockingInterface();
        if (blockerOpt.isPresent()) {
            BlockingInterface by = blockerOpt.get();
            if (!by.canBeClosed() || !closableAllowList.contains(by.rootWidgetId())) {
                step.put(K_PRECONDITION_FAILURE, new BlockReason.WorldInteractionBlocked(by));
                return;
            }
        }

        // Fresh work
        step.put(K_START_TICK, s.tick());
        try {
            bank.clickBankBoothRandom();
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
            return new Completion.Succeeded("bank already open");
        }

        if (s.bank().open()) return new Completion.Succeeded("bank opened");

        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        DiagnosticReason r = f.diagnostic();
        if (r instanceof BlockReason.PinKeypadUp || r instanceof BlockReason.WorldInteractionBlocked) {
            return new Recovery.Abort(f.reason());
        }
        return new Recovery.Retry(3);
    }

    private enum Outcome { FRESH, ALREADY_SATISFIED }

    private static final BlackboardKey<DiagnosticReason> K_PRECONDITION_FAILURE =
        BlackboardKey.of("openBank.preconditionFailure", DiagnosticReason.class);
    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("openBank.startTick", Integer.class);
    private static final BlackboardKey<Outcome> K_OUTCOME =
        BlackboardKey.of("openBank.outcome", Outcome.class);
}
