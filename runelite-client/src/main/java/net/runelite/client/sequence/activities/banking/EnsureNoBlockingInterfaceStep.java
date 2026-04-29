package net.runelite.client.sequence.activities.banking;

import java.awt.event.KeyEvent;
import java.util.Optional;
import java.util.Set;

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.affordance.BlockingInterface;
import net.runelite.client.sequence.affordance.DiagnosticReason;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardKey;
import net.runelite.client.sequence.blackboard.BlackboardScope;

/**
 * Reactive step: if a blocking interface is present that is NOT in the allowedRoots
 * set, dismiss it with an Escape key press.
 *
 * <p>{@code canStart=true} ONLY when a blocker is present AND its rootWidgetId is NOT
 * in {@code allowedRoots}. This prevents the step from preempting the bank widget itself.
 */
public final class EnsureNoBlockingInterfaceStep implements Step {

    private final Set<Integer> allowedRoots;

    public EnsureNoBlockingInterfaceStep(Set<Integer> allowedRoots) {
        this.allowedRoots = allowedRoots;
    }

    @Override public String name()                 { return "EnsureNoBlockingInterface"; }
    @Override public int priority()                { return 200; }
    @Override public int timeoutTicks()            { return 3; }
    @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard bb) { return true; }

    /**
     * canStart=true only when a non-allow-listed blocker is present.
     * Allow-listed roots (e.g. the bank widget itself) must NOT trigger preemption.
     */
    @Override
    public boolean canStart(WorldSnapshot s, Blackboard bb) {
        Optional<BlockingInterface> blocker = s.interaction().blockingInterface();
        if (blocker.isEmpty()) return false;
        return !allowedRoots.contains(blocker.get().rootWidgetId());
    }

    @Override
    public void onStart(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        Blackboard step = ctx.bb().scope(BlackboardScope.STEP);
        step.put(K_START_TICK, s.tick());
        // Record blocker for telemetry
        s.interaction().blockingInterface().ifPresent(b ->
            step.put(K_BLOCKER_NAME, b.name())
        );
        // Dispatch Escape to dismiss the blocking interface
        ctx.actions().sendKey(KeyEvent.VK_ESCAPE);
    }

    @Override public void onEvent(Object e, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Blackboard step = bb.scope(BlackboardScope.STEP);

        // Blocker cleared or now in allow-list → succeeded
        Optional<BlockingInterface> blocker = s.interaction().blockingInterface();
        if (blocker.isEmpty() || allowedRoots.contains(blocker.get().rootWidgetId())) {
            return new Completion.Succeeded("blocking interface cleared");
        }

        // Typed timeout check
        int startTick = step.get(K_START_TICK).orElse(s.tick());
        if (s.tick() - startTick >= timeoutTicks()) {
            return Completion.failed(new DiagnosticReason.ActionTimedOut(name(), timeoutTicks()));
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        if (f.diagnostic() instanceof DiagnosticReason.ActionTimedOut) {
            return new Recovery.Retry(2);
        }
        return new Recovery.Abort(f.reason());
    }

    private static final BlackboardKey<Integer> K_START_TICK =
        BlackboardKey.of("ensureNoBlocker.startTick", Integer.class);
    private static final BlackboardKey<String> K_BLOCKER_NAME =
        BlackboardKey.of("ensureNoBlocker.blockerName", String.class);
}
