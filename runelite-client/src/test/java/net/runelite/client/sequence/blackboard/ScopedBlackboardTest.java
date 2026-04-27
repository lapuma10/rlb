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
package net.runelite.client.sequence.blackboard;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScopedBlackboardTest {
    private static final BlackboardKey<String> NAME = BlackboardKey.of("name", String.class);
    private static final BlackboardKey<Integer> COUNT = BlackboardKey.of("count", Integer.class);

    @Test
    public void putThenGet_returnsValue() {
        ScopedBlackboard bb = new ScopedBlackboard();
        bb.scope(BlackboardScope.RUN).put(NAME, "alice");
        assertEquals("alice", bb.scope(BlackboardScope.RUN).get(NAME).orElse(null));
    }

    @Test
    public void valuesAreScopedSeparately() {
        ScopedBlackboard bb = new ScopedBlackboard();
        bb.scope(BlackboardScope.RUN).put(COUNT, 1);
        bb.scope(BlackboardScope.STEP).put(COUNT, 2);
        assertEquals(Integer.valueOf(1), bb.scope(BlackboardScope.RUN).get(COUNT).orElse(null));
        assertEquals(Integer.valueOf(2), bb.scope(BlackboardScope.STEP).get(COUNT).orElse(null));
    }

    @Test
    public void clear_removesOnlyThatScope() {
        ScopedBlackboard bb = new ScopedBlackboard();
        bb.scope(BlackboardScope.RUN).put(COUNT, 1);
        bb.scope(BlackboardScope.STEP).put(COUNT, 2);
        bb.clear(BlackboardScope.STEP);
        assertEquals(Integer.valueOf(1), bb.scope(BlackboardScope.RUN).get(COUNT).orElse(null));
        assertFalse(bb.scope(BlackboardScope.STEP).get(COUNT).isPresent());
    }

    @Test
    public void getMissingKey_returnsEmpty() {
        ScopedBlackboard bb = new ScopedBlackboard();
        assertFalse(bb.scope(BlackboardScope.RUN).get(NAME).isPresent());
    }
}
