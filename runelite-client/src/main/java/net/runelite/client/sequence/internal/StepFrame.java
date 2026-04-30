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

import lombok.Getter;
import lombok.Setter;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Step;

@Getter
public class StepFrame {
    private static final java.util.concurrent.atomic.AtomicInteger ID = new java.util.concurrent.atomic.AtomicInteger();

    private final int id = ID.incrementAndGet();
    private final Step step;
    private final int depth;       // distance from root frame; 0 = root
    @Setter private int startedTick;
    @Setter private int retryCount;
    @Setter private Completion status = new Completion.Running();
    @Setter private boolean started;   // false until onStart() has run
    /**
     * Most recent tick on which the engine observed the dispatcher worker
     * busy on this leaf's behalf. {@code -1} means the dispatcher has not
     * been busy since this frame started.
     *
     * <p>Used to keep {@link #timedOut} patient: while the dispatcher is
     * actively running a multi-step click chain (RUN_TASK, walk, type,
     * etc.), the step's timeout timer is held off. {@code timeoutTicks}
     * counts ticks of <i>idle waiting</i> AFTER the most recent dispatcher
     * activity, which matches what each step is actually trying to bound
     * — "how long should I let the server propagate state after my click
     * landed?" — rather than "how fast does the click chain itself
     * complete?".
     *
     * <p>RuneScape ticks are 600ms; a humanized right-click + verb-pick
     * + chatbox numeric prompt + multi-digit typing easily takes 5-10s
     * on the worker. With the old "currentTick - startedTick" timer the
     * engine would fire a spurious {@code Failure.timeout} mid-chain,
     * Recovery.Retry would re-enter onStart, and the duplicate dispatch
     * would be silently dropped by the busy guard — cf. the
     * 2026-04-30 1.5M coins withdraw regression.
     */
    @Setter private int lastBusyTick = -1;

    public StepFrame(Step step, int depth) {
        this.step = step;
        this.depth = depth;
    }

    /**
     * @return true if {@link Step#timeoutTicks()} have elapsed of <i>idle</i>
     *         time since this frame started or the dispatcher last went
     *         idle on this leaf's behalf, whichever is later. The dispatcher's
     *         own multi-step chains (clicks, typing, walking) do NOT count
     *         toward the timeout.
     */
    public boolean timedOut(int currentTick) {
        if (!started) return false;
        int idleStartTick = Math.max(startedTick, lastBusyTick + 1);
        return currentTick - idleStartTick >= step.timeoutTicks();
    }
}
