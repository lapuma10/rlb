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
package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.Blackboard;

/** Composite steps wrap children and orchestrate them through the engine.
 *  They never tick or queue actions themselves — leaf primitives do that. */
public abstract class CompositeStep implements Step {

    /** Engine calls a frame-aware overload on each concrete composite. The
     *  abstract overload here exists so the engine can fall back if a
     *  composite type is registered but no engine branch handles it. */
    public abstract NextAction onChildPopped(Step child, Completion status,
                                             WorldSnapshot state, Blackboard bb);

    public sealed interface NextAction permits PushChild, FinishWithSuccess, FinishWithFailure {}
    public record PushChild(Step child) implements NextAction {}
    public record FinishWithSuccess(String reason) implements NextAction {}
    public record FinishWithFailure(String reason) implements NextAction {}

    // Composites never tick themselves and never queue actions
    @Override public final void tick(StepContext ctx) { /* no-op */ }
    @Override public void onEvent(Object e, StepContext ctx) { /* default */ }
    @Override public int timeoutTicks() { return Integer.MAX_VALUE; }
    @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.WHEN_SAFE; }
    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }
    @Override public boolean canStart(WorldSnapshot s, Blackboard b) { return true; }
    @Override public void onStart(StepContext ctx) { /* default */ }

    /** Composites stay RUNNING until the engine pops them via orchestration. */
    @Override public Completion check(WorldSnapshot s, Blackboard b) { return new Completion.Running(); }

    @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
        return new Recovery.Abort(f.reason());
    }
}
