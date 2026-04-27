/*
 * Copyright (c) 2024, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.sequence.internal;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.SequenceEngine;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.BlackboardScope;
import net.runelite.client.sequence.composite.CompositeStep;
import net.runelite.client.sequence.dispatch.InputDispatcher;
import net.runelite.client.sequence.telemetry.Telemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
public final class StateDrivenEngine implements SequenceEngine {

    private final Observer observer;
    private final Planner planner;
    private final InputDispatcher dispatcher;
    private final Telemetry telemetry;
    private final Blackboard blackboard;

    private final FrameStack frames = new FrameStack();
    /** Stack of suspended frame chains. Topmost is restored when current chain empties. */
    private final Deque<List<StepFrame>> suspended = new ArrayDeque<>();
    private final Set<Step> reactives = new LinkedHashSet<>();
    private final DirectActions.Sink sink = new DirectActions.Sink();
    private final Executor executor;
    private final Deque<Object> eventQueue = new ArrayDeque<>();

    private SequenceState state = SequenceState.IDLE;
    private int currentTick = 0;

    public StateDrivenEngine(Observer observer, Planner planner, InputDispatcher dispatcher,
                             Telemetry telemetry, Blackboard blackboard) {
        this.observer = observer;
        this.planner = planner;
        this.dispatcher = dispatcher;
        this.telemetry = telemetry;
        this.blackboard = blackboard;
        this.executor = new Executor(dispatcher, new ActionBudget());
    }

    @Override
    public synchronized void start(Step rootStep) {
        if (state != SequenceState.IDLE) throw new IllegalStateException("engine not idle");
        StepFrame frame = makeFrame(rootStep, 0);
        frames.push(frame);
        state = SequenceState.RUNNING;
        // onStart deferred to first advanceTick (so a real snapshot is available)
        telemetry.record(rec(frame, TelemetryRecord.Event.SELECTED, "priority=" + rootStep.priority()));
        // Composites: also push first child top-down so the leaf is correct on first tick.
        // pushFirstChildIfComposite doesn't use the snapshot — composite first-child push
        // is unconditional (children's canStart is checked when they're activated as leaves).
        pushFirstChildIfComposite(frame, null);
    }

    @Override public synchronized void pause()  { if (state == SequenceState.RUNNING) state = SequenceState.PAUSED; }
    @Override public synchronized void resume() { if (state == SequenceState.PAUSED)  state = SequenceState.RUNNING; }

    @Override
    public synchronized void stop() {
        while (!frames.isEmpty()) frames.pop();
        suspended.clear();
        eventQueue.clear();
        blackboard.clear(BlackboardScope.RUN);
        blackboard.clear(BlackboardScope.SEQUENCE);
        blackboard.clear(BlackboardScope.STEP);
        state = SequenceState.IDLE;
    }

    @Override public synchronized void registerReactive(Step r)   { reactives.add(r); }
    @Override public synchronized void unregisterReactive(Step r) { reactives.remove(r); }
    @Override public synchronized SequenceState state() { return state; }
    @Override public synchronized void offerEvent(Object event)   { eventQueue.add(event); }

    @Override
    public synchronized void advanceTick() {
        if (state != SequenceState.RUNNING) return;
        currentTick++;
        WorldSnapshot snap = observer.snapshot(currentTick);

        // Drain pending events to all active frames
        drainEventsTo(frames.all(), snap);

        // Run pending onStarts (deferred from start() / push)
        runPendingOnStarts(snap);

        // 1. Verify and pop completed/failed leaves (orchestration interleaves via popAndOrchestrate)
        for (StepFrame leaf : new ArrayList<>(frames.leaves())) {
            if (!frames.all().contains(leaf)) continue;   // popped by recursion already
            Completion c;
            try {
                c = leaf.getStep().check(snap, blackboard);
            } catch (Throwable t) {
                c = new Completion.Failed(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            telemetry.record(rec(leaf, TelemetryRecord.Event.CHECK, completionDesc(c)));
            if (c instanceof Completion.Succeeded s) {
                leaf.setStatus(s);
                telemetry.record(rec(leaf, TelemetryRecord.Event.SUCCEEDED, s.reason()));
                popAndOrchestrate(leaf, s, snap);
            } else if (c instanceof Completion.Failed f) {
                int elapsed = currentTick - leaf.getStartedTick();
                handleLeafFailure(leaf, Failure.fromCheck(f.reason(), elapsed), snap);
            } else if (c instanceof Completion.Running) {
                if (leaf.timedOut(currentTick)) {
                    int elapsed = currentTick - leaf.getStartedTick();
                    handleLeafFailure(leaf, Failure.timeout(elapsed), snap);
                }
            }
        }

        // 3. Planner & preemption
        if (frames.isEmpty()) {
            // Try to resume a suspended chain first
            if (!suspended.isEmpty()) {
                List<StepFrame> resumed = suspended.pop();
                for (StepFrame f : resumed) frames.push(f);
                telemetry.record(rec(frames.top(), TelemetryRecord.Event.RESUMED, ""));
            } else {
                Step next = planner.select(snap, blackboard, eligibleReactives());
                if (next != null) {
                    StepFrame f = makeFrame(next, 0);
                    frames.push(f);
                    telemetry.record(rec(f, TelemetryRecord.Event.SELECTED, "priority=" + next.priority()));
                    pushFirstChildIfComposite(f, snap);
                } else {
                    state = SequenceState.IDLE;
                    blackboard.clear(BlackboardScope.RUN);
                    blackboard.clear(BlackboardScope.SEQUENCE);
                }
            }
        } else {
            tryPreempt(snap);
        }

        // Run onStarts for any frames pushed during step 1 (orchestration) or step 3
        // (planner/preempt). Without this second pass, a freshly-pushed leaf would
        // tick before its onStart fires.
        runPendingOnStarts(snap);

        // 4. Tick leaves
        for (StepFrame leaf : frames.leaves()) {
            try {
                leaf.getStep().tick(makeCtx(snap, leaf));
            } catch (Throwable t) {
                Recovery r = new Recovery.Abort("tick threw: " + t.getMessage());
                applyRecovery(leaf, r, snap);
            }
        }

        // 5. Drain action queue
        executor.drain(sink);
    }

    private void runPendingOnStarts(WorldSnapshot snap) {
        for (StepFrame f : new ArrayList<>(frames.all())) {
            if (!f.isStarted()) {
                f.setStartedTick(currentTick);
                f.setStarted(true);
                guarded(f, () -> f.getStep().onStart(makeCtx(snap, f)));
                telemetry.record(rec(f, TelemetryRecord.Event.STARTED, ""));
            }
        }
    }

    /** Route a leaf failure. If the immediate parent is a Selector, propagate
     *  Failed directly so the Selector can try the next child — Selector children
     *  defer the "what to do on failure" decision to the parent, not the leaf's
     *  onFailure. For all other parents, run the leaf's onFailure -> applyRecovery
     *  pipeline as usual. */
    private void handleLeafFailure(StepFrame leaf, Failure f, WorldSnapshot snap) {
        if (parentIsSelector(leaf)) {
            Completion.Failed propagated = new Completion.Failed(f.reason());
            leaf.setStatus(propagated);
            popAndOrchestrate(leaf, propagated, snap);
            return;
        }
        Recovery r;
        try {
            r = leaf.getStep().onFailure(f, snap, blackboard);
        } catch (Throwable t) {
            r = new Recovery.Abort("onFailure threw: " + t.getMessage());
        }
        applyRecovery(leaf, r, snap);
    }

    private boolean parentIsSelector(StepFrame leaf) {
        List<StepFrame> all = frames.all();
        int idx = all.indexOf(leaf);
        if (idx <= 0) return false;
        return all.get(idx - 1).getStep() instanceof net.runelite.client.sequence.composite.Selector;
    }

    // ---- helpers ----

    private void drainEventsTo(List<StepFrame> targets, WorldSnapshot snap) {
        if (eventQueue.isEmpty() || targets.isEmpty()) { eventQueue.clear(); return; }
        // Innermost first per spec §7
        List<StepFrame> reversed = new ArrayList<>(targets);
        Collections.reverse(reversed);
        Object ev;
        while ((ev = eventQueue.poll()) != null) {
            for (StepFrame f : reversed) {
                final Object evf = ev;
                guarded(f, () -> f.getStep().onEvent(evf, makeCtx(snap, f)));
                telemetry.record(rec(f, TelemetryRecord.Event.EVENT, ev.getClass().getSimpleName()));
            }
        }
    }

    private Set<Step> eligibleReactives() {
        Set<Step> onStack = new HashSet<>();
        for (StepFrame f : frames.all()) onStack.add(f.getStep());
        Set<Step> out = new LinkedHashSet<>(reactives);
        out.removeAll(onStack);
        return out;
    }

    private void tryPreempt(WorldSnapshot snap) {
        StepFrame top = frames.top();
        if (top.getStep().preemptionPolicy() == PreemptionPolicy.NEVER) return;
        if (!top.getStep().isSafeToPause(snap, blackboard)) return;
        Step candidate = planner.select(snap, blackboard, eligibleReactives());
        if (candidate == null || candidate.priority() <= top.getStep().priority()) return;

        // Suspend whole chain, push reactive at depth 0
        List<StepFrame> chain = new ArrayList<>(frames.all());
        suspended.push(chain);
        while (!frames.isEmpty()) frames.pop();
        StepFrame f = makeFrame(candidate, 0);
        frames.push(f);
        telemetry.record(rec(top, TelemetryRecord.Event.PREEMPTED, "by " + candidate.name()));
        telemetry.record(rec(f, TelemetryRecord.Event.SELECTED, "preempting"));
        pushFirstChildIfComposite(f, snap);
    }

    private void popAndOrchestrate(StepFrame popped, Completion status, WorldSnapshot snap) {
        frames.pop();
        blackboard.clear(BlackboardScope.STEP);

        if (frames.isEmpty()) {
            // Root finished. Suspended-chain resumption happens in advanceTick step 3.
            if (status instanceof Completion.Failed) {
                failRun();
            } else {
                blackboard.clear(BlackboardScope.SEQUENCE);
            }
            return;
        }

        StepFrame parent = frames.top();
        if (!(parent.getStep() instanceof CompositeStep)) return;   // shouldn't happen
        CompositeStep.NextAction next = invokeOrchestration(parent, popped, status, snap);

        if (next instanceof CompositeStep.PushChild pc) {
            StepFrame child = makeFrame(pc.child(), parent.getDepth() + 1);
            frames.push(child);
            telemetry.record(rec(child, TelemetryRecord.Event.SELECTED, "child of " + parent.getStep().name()));
            pushFirstChildIfComposite(child, snap);
        } else if (next instanceof CompositeStep.FinishWithSuccess fs) {
            Completion.Succeeded ps = new Completion.Succeeded(fs.reason());
            parent.setStatus(ps);
            telemetry.record(rec(parent, TelemetryRecord.Event.SUCCEEDED, fs.reason()));
            popAndOrchestrate(parent, ps, snap);
        } else if (next instanceof CompositeStep.FinishWithFailure ff) {
            // Failure propagates up the composite chain. The parent composite
            // has already decided to fail (e.g. LinearSequence saw a failed
            // child, or Selector exhausted its options); we don't ask its own
            // onFailure — that's only for the root-level Failure handler. Let
            // the grandparent's onChildPopped decide what to do.
            Completion.Failed propagated = new Completion.Failed(ff.reason());
            parent.setStatus(propagated);
            telemetry.record(rec(parent, TelemetryRecord.Event.RECOVERY, "FinishWithFailure: " + ff.reason()));
            popAndOrchestrate(parent, propagated, snap);
        }
    }

    /** Type-dispatch onto the composite's frame-aware orchestration overload.
     *  Tasks 17–19 add per-composite branches via incremental edits. */
    private CompositeStep.NextAction invokeOrchestration(
        StepFrame parent, StepFrame popped, Completion status, WorldSnapshot snap) {
        Step parentStep = parent.getStep();
        if (parentStep instanceof net.runelite.client.sequence.composite.LinearSequence ls
            && parent instanceof net.runelite.client.sequence.composite.LinearSequenceFrame lsf) {
            return ls.onChildPopped(lsf, status);
        }
        if (parentStep instanceof net.runelite.client.sequence.composite.Selector sel
            && parent instanceof net.runelite.client.sequence.composite.SelectorFrame sf) {
            return sel.onChildPopped(sf, status, snap, blackboard);
        }
        if (parentStep instanceof net.runelite.client.sequence.composite.RepeatStep rs
            && parent instanceof net.runelite.client.sequence.composite.RepeatStepFrame rsf) {
            return rs.onChildPopped(rsf, status);
        }
        return new CompositeStep.FinishWithFailure(
            "unknown composite type " + parentStep.getClass().getSimpleName());
    }

    private void applyRecovery(StepFrame frame, Recovery r, WorldSnapshot snap) {
        telemetry.record(rec(frame, TelemetryRecord.Event.RECOVERY, r.toString()));
        if (r instanceof Recovery.Retry retry) {
            frame.setRetryCount(frame.getRetryCount() + 1);
            if (frame.getRetryCount() >= retry.maxAttempts()) {
                // Exhausted — propagate Failed to parent (or fail run if root)
                Completion.Failed propagated = new Completion.Failed("retry exhausted");
                frame.setStatus(propagated);
                popAndOrchestrate(frame, propagated, snap);
            } else {
                frame.setStartedTick(currentTick);
                frame.setStarted(false);   // onStart will re-fire at tick start of next advance
            }
        } else if (r instanceof Recovery.Skip s) {
            Completion.Succeeded synthetic = new Completion.Succeeded("skipped: " + s.reason());
            if (frames.size() == 1) {
                // Skip at root = fail run
                frames.pop();
                failRun();
            } else {
                popAndOrchestrate(frame, synthetic, snap);
            }
        } else if (r instanceof Recovery.Abort a) {
            // Abort means "this frame gives up; let the parent decide." That
            // lets a Selector ancestor (even several levels up) try its next
            // option without needing to know about the deeper failure. If
            // there is no recovering parent, propagation reaches the root and
            // popAndOrchestrate falls into failRun() naturally.
            Completion.Failed propagated = new Completion.Failed(a.reason());
            frame.setStatus(propagated);
            popAndOrchestrate(frame, propagated, snap);
        } else if (r instanceof Recovery.JumpToAnchor j) {
            resolveJumpToAnchor(frame, j, snap);
        }
    }

    /** Walks outward through the frame stack to find an enclosing composite that
     *  declared the named anchor. The body is filled in by Task 17 (LinearSequence
     *  is the only composite that supports anchors). */
    private void resolveJumpToAnchor(StepFrame failed, Recovery.JumpToAnchor j, WorldSnapshot snap) {
        List<StepFrame> all = new ArrayList<>(frames.all());
        for (int i = all.size() - 1; i >= 0; i--) {
            StepFrame f = all.get(i);
            if (f.getStep() instanceof net.runelite.client.sequence.composite.LinearSequence ls
                && f instanceof net.runelite.client.sequence.composite.LinearSequenceFrame lsf) {
                int idx = ls.anchorIndex(j.anchorName());
                if (idx >= 0) {
                    while (frames.size() > i + 1) frames.pop();
                    blackboard.clear(net.runelite.client.sequence.blackboard.BlackboardScope.STEP);
                    lsf.setCurrentChildIndex(idx);
                    Step child = ls.getChildren().get(idx);
                    StepFrame childFrame = makeFrame(child, lsf.getDepth() + 1);
                    frames.push(childFrame);
                    telemetry.record(rec(childFrame, TelemetryRecord.Event.SELECTED,
                        "jump to " + j.anchorName()));
                    pushFirstChildIfComposite(childFrame, snap);
                    return;
                }
            }
        }
        applyRecovery(failed, new Recovery.Abort("anchor '" + j.anchorName() + "' not found"), snap);
    }

    /** When a composite frame is pushed, we push its first eligible child too,
     *  recursing in case the child is itself a composite. Per-composite branches
     *  are added in Tasks 17–19. */
    private void pushFirstChildIfComposite(StepFrame composite, WorldSnapshot snap) {
        Step s = composite.getStep();
        if (s instanceof net.runelite.client.sequence.composite.LinearSequence ls
            && !ls.getChildren().isEmpty()) {
            StepFrame child = makeFrame(ls.getChildren().get(0), composite.getDepth() + 1);
            frames.push(child);
            telemetry.record(rec(child, TelemetryRecord.Event.SELECTED, "child #0"));
            pushFirstChildIfComposite(child, snap);
            return;
        }
        if (s instanceof net.runelite.client.sequence.composite.Selector sel && !sel.children().isEmpty()) {
            // Skip ineligible options up-front so the Selector doesn't depend on
            // child #0 failing-fast just to advance. snap is null when called
            // from start() (no snapshot yet); in that case we push #0 and rely on
            // the canStart check at run-time inside the Selector's onChildPopped.
            int chosen = 0;
            if (snap != null && composite instanceof net.runelite.client.sequence.composite.SelectorFrame sf) {
                while (chosen < sel.children().size()
                    && !sel.children().get(chosen).canStart(snap, blackboard)) {
                    chosen++;
                }
                if (chosen >= sel.children().size()) {
                    // No eligible options; let orchestration finish the Selector.
                    Completion.Failed pf = new Completion.Failed("no selector child eligible");
                    composite.setStatus(pf);
                    popAndOrchestrate(composite, pf, snap);
                    return;
                }
                sf.setNextChildIndex(chosen + 1);
            }
            StepFrame child = makeFrame(sel.children().get(chosen), composite.getDepth() + 1);
            frames.push(child);
            telemetry.record(rec(child, TelemetryRecord.Event.SELECTED, "selector option #" + chosen));
            pushFirstChildIfComposite(child, snap);
            return;
        }
        if (s instanceof net.runelite.client.sequence.composite.RepeatStep rs) {
            StepFrame child = makeFrame(rs.body(), composite.getDepth() + 1);
            frames.push(child);
            telemetry.record(rec(child, TelemetryRecord.Event.SELECTED, "repeat body"));
            pushFirstChildIfComposite(child, snap);
            return;
        }
    }

    /** Choose the right StepFrame subclass for a Step. Tasks 17–19 add their
     *  per-composite branches. */
    private StepFrame makeFrame(Step s, int depth) {
        if (s instanceof net.runelite.client.sequence.composite.LinearSequence ls) {
            return new net.runelite.client.sequence.composite.LinearSequenceFrame(ls, depth);
        }
        if (s instanceof net.runelite.client.sequence.composite.Selector sel) {
            return new net.runelite.client.sequence.composite.SelectorFrame(sel, depth);
        }
        if (s instanceof net.runelite.client.sequence.composite.RepeatStep rs) {
            return new net.runelite.client.sequence.composite.RepeatStepFrame(rs, depth);
        }
        return new StepFrame(s, depth);
    }

    private void guarded(StepFrame frame, Runnable r) {
        try { r.run(); }
        catch (Throwable t) {
            log.warn("step {} threw: {}", frame.getStep().name(), t.toString());
            applyRecovery(frame, new Recovery.Abort(t.getClass().getSimpleName() + ": " + t.getMessage()),
                observer.snapshot(currentTick));
        }
    }

    /** Centralized terminal-failure cleanup. Used by Skip-at-root, Abort, and
     *  any other path that ends a run with FAILED. Clears all scopes so the
     *  engine is fully reset before sitting in FAILED state. */
    private void failRun() {
        suspended.clear();
        blackboard.clear(BlackboardScope.STEP);
        blackboard.clear(BlackboardScope.SEQUENCE);
        blackboard.clear(BlackboardScope.RUN);
        state = SequenceState.FAILED;
    }

    private DefaultStepContext makeCtx(WorldSnapshot snap, StepFrame frame) {
        DirectActions actions = new DirectActions(sink, frame.getId(), frame.getDepth(), frame.getStep().priority());
        return new DefaultStepContext(actions, blackboard, snap, currentTick, dispatcher.mode());
    }

    private TelemetryRecord rec(StepFrame frame, TelemetryRecord.Event ev, String payload) {
        return new TelemetryRecord(currentTick, frame.getDepth(), frame.getStep().name(), ev, payload);
    }

    private static String completionDesc(Completion c) {
        if (c instanceof Completion.Succeeded s) return "SUCCEEDED " + s.reason();
        if (c instanceof Completion.Failed f) return "FAILED " + f.reason();
        return "RUNNING";
    }
}
