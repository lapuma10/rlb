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

public interface SequenceEngine {
    /** Start a new run with the given root step. Calling twice before stop() throws. */
    void start(Step rootStep);

    /** Pause execution; no ticks consumed until resume(). */
    void pause();

    /** Resume after pause. */
    void resume();

    /** Stop the run; clears all frames. Safe to call when idle. */
    void stop();

    /** Register an always-on reactive step considered each tick by the planner. */
    void registerReactive(Step reactive);
    void unregisterReactive(Step reactive);

    /** Convenience overload — the priority parameter documents intent at the
     *  call site (e.g. "I'm registering a high-priority preempting reactive").
     *  Selection still uses {@link Step#priority()}. */
    default void registerReactive(Step reactive, int priority) { registerReactive(reactive); }

    /** Remove all registered reactive steps. Idempotent; safe to call when
     *  none are registered. */
    void clearReactives();

    /** Drive one tick of the loop. Production: invoked by SequencerPlugin.onGameTick.
     *  Tests may call this directly to drive the engine synchronously. */
    void advanceTick();

    /** Convenience: drive {@code n} ticks back-to-back. Used by tests in lieu of
     *  a separate LinearEngine class — the spec mentions one but in this codebase
     *  StateDrivenEngine itself is synchronous-friendly. */
    default void advanceTicks(int n) { for (int i = 0; i < n; i++) advanceTick(); }

    /** Offer a RuneLite event to be routed to active frames on the next tick.
     *  Plugins call this from their @Subscribe handlers. */
    void offerEvent(Object event);

    SequenceState state();
}
