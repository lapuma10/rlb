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
package net.runelite.client.sequence.activities;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class StepRegistryTest {
    @Test
    public void registerAndLookup() {
        StepRegistry reg = new StepRegistry();
        StepFactory f = stub("walk_to", "Walk To");
        reg.register(f);
        assertEquals(1, reg.all().size());
        assertSame(f, reg.byTypeId("walk_to"));
    }

    @Test
    public void duplicateTypeId_throws() {
        StepRegistry reg = new StepRegistry();
        reg.register(stub("walk_to", "Walk To"));
        try {
            reg.register(stub("walk_to", "Other"));
            fail("expected IllegalStateException");
        } catch (IllegalStateException ok) {}
    }

    @Test
    public void unregister_removes() {
        StepRegistry reg = new StepRegistry();
        reg.register(stub("walk_to", "Walk To"));
        reg.unregister("walk_to");
        assertEquals(0, reg.all().size());
        assertNull(reg.byTypeId("walk_to"));
    }

    private static StepFactory stub(String id, String name) {
        return new StepFactory() {
            public String typeId() { return id; }
            public String displayName() { return name; }
            public List<StepParam> params() { return List.of(); }
            public net.runelite.client.sequence.Step build(Map<String, Object> args) { return null; }
        };
    }
}
