package net.runelite.client.sequence.activities;

import java.awt.event.KeyEvent;
import java.util.Optional;
import java.util.Set;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.affordance.BlockReason;
import net.runelite.client.sequence.affordance.BlockingInterface;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.blackboard.SequenceBlackboardKeys;

/**
 * Reactive step that dismisses unexpected blocking interfaces (level-up
 * dialog, world-hop nag, account warnings) by sending Escape.
 *
 * <p>Lives at {@code sequence/activities/} (not under {@code ge/} or
 * {@code banking/}) because both verticals register an instance with
 * different allow-lists. Banking imports it without adding a dependency on
 * the GE package, and vice versa.
 *
 * <p>{@code allowList} contains the root widget ids of interfaces this
 * reactive should NOT dismiss — typically the bank-main and GE roots when
 * the active linear sequence is doing bank or GE work.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@code canStart=true} only when a blocking interface is present
 *       AND its rootWidgetId is NOT in {@code allowList} — so the planner
 *       picks this reactive only when there's something to dismiss.
 *   <li>{@code onStart} dispatches {@code KeyEvent.VK_ESCAPE} via
 *       {@code ctx.actions().sendKey(...)}. No-op if no blocker (defensive;
 *       canStart should have been false).
 *   <li>{@code check} succeeds when the blocker has cleared (or is now in
 *       the allow-list); fails with {@code WorldInteractionBlocked} on
 *       timeout.
 *   <li>{@code onFailure} returns {@code Retry(2)} then {@code Abort}.
 * </ul>
 */
public final class EnsureNoBlockingInterfaceStep implements Step {

    private final Set<Integer> allowList;

    public EnsureNoBlockingInterfaceStep(Set<Integer> allowList) {
        this.allowList = Set.copyOf(allowList);
    }

    @Override public String name()                              { return "EnsureNoBlockingInterface"; }
    /** Higher than the default linear-step priority so the planner picks
     *  this reactive over an in-flight linear step when canStart goes true. */
    @Override public int priority()                             { return 200; }
    @Override public int timeoutTicks()                         { return 6; }
    @Override public PreemptionPolicy preemptionPolicy()        { return PreemptionPolicy.NEVER; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }

    @Override
    public boolean canStart(WorldSnapshot s, Blackboard bb) {
        Optional<BlockingInterface> bi = s.interaction().blockingInterface();
        if (bi.isEmpty()) return false;
        BlockingInterface b = bi.get();
        if (allowList.contains(b.rootWidgetId())) return false;
        // Surface the typed reason for telemetry-on-reject (not strictly needed
        // here since canStart=true means we ARE starting, but kept for
        // symmetry with the LAST_BLOCK_REASON contract).
        bb.scope(BlackboardScope.STEP)
            .put(SequenceBlackboardKeys.LAST_BLOCK_REASON,
                 new BlockReason.WorldInteractionBlocked(b));
        return true;
    }

    @Override
    public void onStart(StepContext ctx) {
        // Defensive: only dispatch if there's actually a blocker.
        if (ctx.snapshot().interaction().blockingInterface().isPresent()) {
            ctx.actions().sendKey(KeyEvent.VK_ESCAPE);
        }
    }

    @Override public void onEvent(Object event, StepContext ctx) {}
    @Override public void tick(StepContext ctx) {}

    @Override
    public Completion check(WorldSnapshot s, Blackboard bb) {
        Optional<BlockingInterface> bi = s.interaction().blockingInterface();
        if (bi.isEmpty()) return new Completion.Succeeded("blocker cleared");
        if (allowList.contains(bi.get().rootWidgetId())) {
            return new Completion.Succeeded("blocker now allowed");
        }
        return Completion.RUNNING;
    }

    @Override
    public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard bb) {
        return new Recovery.Retry(2);
    }
}
