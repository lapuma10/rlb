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

import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;
import java.util.ArrayList;
import java.util.List;

public final class Selector extends CompositeStep {
    private final String name;
    private final List<Step> children = new ArrayList<>();

    public Selector(String name) { this.name = name; }
    public Selector option(Step s) { children.add(s); return this; }

    public List<Step> children() { return children; }

    @Override public String name() { return name; }
    @Override public int priority() { return 50; }

    @Override
    public NextAction onChildPopped(Step child, Completion status, WorldSnapshot s, Blackboard b) {
        throw new UnsupportedOperationException("use frame-aware overload");
    }

    public NextAction onChildPopped(SelectorFrame frame, Completion status,
                                    WorldSnapshot s, Blackboard b) {
        if (status instanceof Completion.Succeeded sc) return new FinishWithSuccess(sc.reason());
        // Failed -> try next eligible
        for (int i = frame.getNextChildIndex(); i < children.size(); i++) {
            Step candidate = children.get(i);
            if (candidate.canStart(s, b)) {
                frame.setNextChildIndex(i + 1);
                return new PushChild(candidate);
            }
        }
        return new FinishWithFailure("no selector child succeeded");
    }
}
