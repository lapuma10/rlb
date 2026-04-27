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
package net.runelite.client.plugins.recorder.combat;

import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
import javax.annotation.Nullable;

/**
 * Tiny seam over {@link HumanizedInputDispatcher} that the combat loop talks
 * to. The concrete dispatcher class is {@code final}, which Mockito can't
 * mock directly — wrapping it behind this interface lets us inject test
 * doubles that record dispatch calls without touching the real cursor/canvas
 * plumbing.
 *
 * <p>Methods here are exactly the surface the loop needs: dispatch a click,
 * check single-flight busy, read the most-recent error. Everything else
 * (camera rotation, key taps, cursor parking) stays on the concrete
 * dispatcher and is not relevant to combat orchestration.
 */
public interface CombatDispatcher
{
    void dispatch(ActionRequest req);

    boolean isBusy();

    @Nullable
    String lastErrorMessage();

    /**
     * Adapter over the concrete humanized dispatcher.
     */
    static CombatDispatcher forHumanized(HumanizedInputDispatcher d)
    {
        return new CombatDispatcher()
        {
            @Override public void dispatch(ActionRequest req) { d.dispatch(req); }
            @Override public boolean isBusy() { return d.isBusy(); }
            @Override @Nullable public String lastErrorMessage() { return d.lastErrorMessage(); }
        };
    }
}
