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

import lombok.RequiredArgsConstructor;
import net.runelite.client.sequence.dispatch.InputDispatcher;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

@RequiredArgsConstructor
public final class Executor {
    private final InputDispatcher dispatcher;
    private final ActionBudget budget;

    /** Comparator: priority desc, frame depth desc (deeper first), insertion order asc. */
    private static final Comparator<ActionRequest> ORDER =
        Comparator.<ActionRequest>comparingInt(ActionRequest::getPriority).reversed()
            .thenComparing(Comparator.<ActionRequest>comparingInt(ActionRequest::getFrameDepth).reversed())
            .thenComparingLong(ActionRequest::getInsertionOrder);

    public void drain(DirectActions.Sink sink) {
        List<ActionRequest> all = sink.drain();
        all.sort(ORDER);

        EnumMap<ActionRequest.Channel, Integer> spent = new EnumMap<>(ActionRequest.Channel.class);
        for (ActionRequest.Channel ch : ActionRequest.Channel.values()) spent.put(ch, 0);

        for (ActionRequest r : all) {
            int s = spent.get(r.getChannel());
            if (s >= budget.limitFor(r.getChannel())) continue;
            dispatcher.dispatch(r);
            spent.put(r.getChannel(), s + 1);
        }
    }
}
