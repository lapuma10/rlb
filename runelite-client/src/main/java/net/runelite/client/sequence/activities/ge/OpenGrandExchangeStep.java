package net.runelite.client.sequence.activities.ge;

import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.affordance.GeBlockReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.blackboard.SequenceBlackboardKeys;

/**
 * Opens the Grand Exchange offers interface.
 *
 * <p>Already-satisfied: {@code ge.open()} already true → onStart is a no-op
 * and the first {@code check} returns {@code Succeeded}.
 *
 * <p>Fatal preconditions (recorded in onStart, surfaced from first
 * {@code check}):
 * <ul>
 *   <li>{@link BlockReason.PinKeypadUp} — should not happen at the GE clerk
 *       but defended against (proof has no PIN handler).
 * </ul>
 *
 * <p>Otherwise dispatches {@code geActions.openGrandExchange()} in onStart
 * and the {@code check} polls {@code ge.open()} until success or timeout.
 */
public final class OpenGrandExchangeStep implements Step {

    private static final BlackboardKey<DiagnosticReason> K_PRECONDITION =
        BlackboardKey.of("openGE.precondition", DiagnosticReason.class);

    private final GeActions ge;

    public OpenGrandExchangeStep(GeActions ge) {
        if (ge == null) throw new IllegalArgumentException("GeActions must not be null");
        this.ge = ge;
    }

    @Override public String name()                              { return "OpenGrandExchange"; }
    @Override public int priority()                             { return 100; }
    @Override public int timeoutTicks()                         { return 10; }
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);

        // Already open → no dispatch; first check returns Succeeded.
        if (s.grandExchange().open()) return;

        // Fatal preconditions get recorded; check returns Failed first.
        // (PinKeypadUp would require a domain-specific handler the proof
        // doesn't include.)
        // No bank-pin detection in Phase A; banking will refine.

        ge.openGrandExchange();
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);
        DiagnosticReason pre = step.get(K_PRECONDITION).orElse(null);
        if (pre != null) return Completion.failed(pre);
        if (s.grandExchange().open()) return new Completion.Succeeded("GE open");

        // Surface the typed reason for telemetry; engine timeout will fall
        // through to GeNotOpen via onFailure.
        bb.scope(BlackboardScope.STEP)
            .put(SequenceBlackboardKeys.LAST_BLOCK_REASON, new GeBlockReason.GeNotOpen());
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        DiagnosticReason d = f.diagnostic();
        if (d instanceof BlockReason.PinKeypadUp) return new Recovery.Abort("PinKeypadUp");
        if (d instanceof GeBlockReason.GeNotOpen)  return new Recovery.Retry(3);
        // Engine timeout (no diagnostic) → retry a couple of times.
        return new Recovery.Retry(3);
    }
}
