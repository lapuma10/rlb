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
package net.runelite.client.sequence;

import lombok.Getter;
import net.runelite.client.sequence.activities.StepRegistry;
import net.runelite.client.sequence.blackboard.Blackboard;
import net.runelite.client.sequence.blackboard.ScopedBlackboard;
import net.runelite.client.sequence.dispatch.InputDispatcher;
import net.runelite.client.sequence.dispatch.InputOwnership;
import net.runelite.client.sequence.internal.*;
import net.runelite.client.sequence.telemetry.RingBufferTelemetry;
import net.runelite.client.sequence.telemetry.Telemetry;
import net.runelite.client.sequence.telemetry.TelemetryRecord;

import java.util.function.Consumer;

public final class SequenceManager {
    @Getter private SequenceEngine engine;
    @Getter private InputDispatcher dispatcher;
    @Getter private Telemetry telemetry;
    @Getter private Observer observer;
    @Getter private Planner planner;
    @Getter private Blackboard blackboard;
    @Getter private final StepRegistry registry = new StepRegistry();

    /** Scheduler used to marshal mutating engine calls (run/pause/resume/stop/register
     *  /unregister) onto the right thread. Default runs synchronously on the calling
     *  thread, which is what tests want. Plugins set this to ClientThread::invoke
     *  so EDT/AWT-key callers don't touch Client state from the wrong thread. */
    private Consumer<Runnable> scheduler = Runnable::run;

    private InputOwnership inputOwnership;
    private String inputOwnerToken;

    private SequenceManager() {}

    /** Build with all defaults except dispatcher and observer (no Client available in test). */
    public static SequenceManager withDefaults() {
        SequenceManager m = new SequenceManager();
        m.telemetry = new RingBufferTelemetry(2048);
        m.planner = new PriorityPlanner();
        m.blackboard = new ScopedBlackboard();
        // observer + dispatcher set by caller (Client-dependent)
        return m;
    }

    /** Override the engine entirely (e.g. tests use LinearEngine). Otherwise
     *  defaults wire up StateDrivenEngine when all subsystems are set. */
    public void setEngine(SequenceEngine e) { this.engine = e; }

    public void setDispatcher(InputDispatcher d) { this.dispatcher = d; rebuildEngineIfReady(); }
    public void setTelemetry(Telemetry t) { this.telemetry = t; rebuildEngineIfReady(); }
    public void setObserver(Observer o) { this.observer = o; rebuildEngineIfReady(); }
    public void setPlanner(Planner p) { this.planner = p; rebuildEngineIfReady(); }
    public void setBlackboard(Blackboard b) { this.blackboard = b; rebuildEngineIfReady(); }

    private void rebuildEngineIfReady() {
        if (engine != null) return;   // explicit setEngine takes precedence
        if (observer != null && dispatcher != null && planner != null
            && telemetry != null && blackboard != null) {
            // Wire telemetry into the planner so canStart-rejection records reach
            // the ring buffer alongside step-lifecycle events.
            if (planner instanceof PriorityPlanner pp) {
                pp.setTelemetry(telemetry);
            }
            StateDrivenEngine sde = new StateDrivenEngine(observer, planner, dispatcher, telemetry, blackboard);
            if (inputOwnership != null && inputOwnerToken != null) {
                sde.setInputOwnership(inputOwnership, inputOwnerToken);
            }
            engine = sde;
        }
    }

    public void setScheduler(Consumer<Runnable> s) { this.scheduler = s == null ? Runnable::run : s; }

    public void run(Step root) { scheduler.accept(() -> engine.start(root)); }
    public void pause()  { scheduler.accept(engine::pause); }
    public void resume() { scheduler.accept(engine::resume); }
    public void stop()   { scheduler.accept(engine::stop); }
    public SequenceState state() { return engine == null ? SequenceState.IDLE : engine.state(); }

    public void register(Step reactive)   { scheduler.accept(() -> engine.registerReactive(reactive)); }
    public void unregister(Step reactive) { scheduler.accept(() -> engine.unregisterReactive(reactive)); }

    /** Scheduler-marshalled passthrough to {@link SequenceEngine#registerReactive(Step, int)}. */
    public void registerReactive(Step reactive, int priority) {
        scheduler.accept(() -> engine.registerReactive(reactive, priority));
    }

    /** Scheduler-marshalled passthrough to {@link SequenceEngine#clearReactives()}. */
    public void clearReactives() {
        scheduler.accept(() -> engine.clearReactives());
    }

    /** Wire an input-ownership lease + token into the underlying engine. The
     *  engine verifies the lease is held at the start of every tick; if not,
     *  it fails the run with a typed diagnostic. Idempotent — set before
     *  {@link #run(Step)} or after, but the lease must already exist for it
     *  to take effect on the in-flight run. */
    public void setInputOwnership(InputOwnership ownership, String ownerToken) {
        this.inputOwnership = ownership;
        this.inputOwnerToken = ownerToken;
        if (engine instanceof StateDrivenEngine sde) {
            sde.setInputOwnership(ownership, ownerToken);
        }
    }

    /** Plugins forward RuneLite events here; the engine routes them on the next tick.
     *  Forwarded from @Subscribe handlers which already run on ClientThread, so no
     *  marshaling here. */
    public void offerEvent(Object event) { if (engine != null) engine.offerEvent(event); }

    public void subscribe(Consumer<TelemetryRecord> listener)   { telemetry.subscribe(listener); }
    public void unsubscribe(Consumer<TelemetryRecord> listener) { telemetry.unsubscribe(listener); }
}
