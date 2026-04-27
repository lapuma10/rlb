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

import lombok.Getter;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
public final class LinearSequence extends CompositeStep {
    private final String name;
    private final List<Step> children = new ArrayList<>();
    private final Map<String, Integer> anchors = new HashMap<>();   // name -> child index

    public LinearSequence(String name) { this.name = name; }

    public LinearSequence then(Step child) { children.add(child); return this; }
    public LinearSequence anchor(String anchorName) {
        anchors.put(anchorName, children.size());
        return this;
    }

    @Override public String name() { return name; }
    @Override public int priority() { return children.isEmpty() ? 0 : children.get(0).priority(); }

    @Override
    public NextAction onChildPopped(Step child, Completion status, WorldSnapshot state, Blackboard bb) {
        // The frame's own state (currentChildIndex) is held by the engine via LinearSequenceFrame.
        // We answer based on what the engine passes us — but the engine calls this *with* the frame so
        // we read currentChildIndex from there. To keep onChildPopped pure, the engine increments the
        // index BEFORE invoking us, then we just say "push child at that index" or "done".
        throw new UnsupportedOperationException("Engine drives index — use onChildPopped(frame, ...)");
    }

    /** Engine-friendly entry point. */
    public NextAction onChildPopped(LinearSequenceFrame frame, Completion status) {
        if (status instanceof Completion.Failed f) return new FinishWithFailure(f.reason());
        frame.setCurrentChildIndex(frame.getCurrentChildIndex() + 1);
        if (frame.getCurrentChildIndex() >= children.size()) {
            return new FinishWithSuccess("all children done");
        }
        return new PushChild(children.get(frame.getCurrentChildIndex()));
    }

    public int anchorIndex(String anchorName) {
        return anchors.getOrDefault(anchorName, -1);
    }
}
