/*
 * Copyright (c) 2026, RuneLite
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
package net.runelite.client.plugins.recorder.mining;

/**
 * Strategy for emptying the inventory when the mining loop fills it.
 *
 * <p>Two implementations planned:
 * <ul>
 *   <li>{@link PowerMineStrategy} — drop ores in place via right-click "Drop"
 *       menu flow. The simpler / first-build option.</li>
 *   <li>{@code BankDepositStrategy} — walk to a configured bank, deposit
 *       ores, walk back. Stub today; the chicken-routine bank flow will
 *       inform the eventual implementation.</li>
 * </ul>
 *
 * <p>Implementations dispatch through the {@link
 * net.runelite.client.sequence.dispatch.HumanizedInputDispatcher} only — no
 * {@code menuAction} shortcuts, all clicks synthesized through the canvas.
 */
public interface BankingStrategy
{
    /**
     * Empty the inventory. Returns when the strategy considers itself done
     * (inventory drained / banked / or it gave up). Throws
     * {@link InterruptedException} if the loop's stop signal was raised.
     */
    void empty(MiningLoopContext ctx) throws InterruptedException;

    /** Human-readable label for status surfaces ("PowerMine" / "Bank"). */
    String label();
}
