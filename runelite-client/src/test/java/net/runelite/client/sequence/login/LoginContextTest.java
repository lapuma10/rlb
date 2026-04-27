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
package net.runelite.client.sequence.login;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class LoginContextTest
{
    @Test
    public void initialState_retryCountZero_lastErrorNull()
    {
        Consumer<String> sink = s -> {};
        LoginContext ctx = new LoginContext(null, null, null, null, new Random(0), sink, 308);
        assertEquals(0, ctx.getRetryCount());
        assertNull(ctx.getLastError());
        assertEquals(308, ctx.getCurrentWorldId());
    }

    @Test
    public void incrementRetry_increments()
    {
        LoginContext ctx = new LoginContext(null, null, null, null, new Random(0), s -> {}, 308);
        ctx.incrementRetry();
        assertEquals(1, ctx.getRetryCount());
    }

    @Test
    public void setLastError_persists()
    {
        LoginContext ctx = new LoginContext(null, null, null, null, new Random(0), s -> {}, 308);
        ctx.setLastError(LoginError.BAD_CREDS);
        assertEquals(LoginError.BAD_CREDS, ctx.getLastError());
    }

    @Test
    public void status_invokesSink()
    {
        List<String> received = new ArrayList<>();
        LoginContext ctx = new LoginContext(null, null, null, null, new Random(0), received::add, 308);
        ctx.status("hello");
        assertEquals(1, received.size());
        assertEquals("hello", received.get(0));
    }
}
