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
package net.runelite.client.plugins.recorder.buffer;

import net.runelite.client.plugins.recorder.events.Events;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;

public class RecordingBufferTest
{
	@Test
	public void enqueueAssignsMonotonicSeq()
	{
		RecordingBuffer buf = new RecordingBuffer();
		long s1 = buf.enqueue((seq, tMs) -> new Events.Tick(seq, tMs, 0, 0, 0, 0, 0, true, 100, false, 50, 99));
		long s2 = buf.enqueue((seq, tMs) -> new Events.MouseMove(seq, tMs, 0, 0, 0));
		assertTrue(s2 > s1);
	}

	@Test
	public void drainReturnsAllEnqueued_inOrder()
	{
		RecordingBuffer buf = new RecordingBuffer();
		for (int i = 0; i < 5; i++) {
			final int n = i;
			buf.enqueue((seq, tMs) -> new Events.MouseMove(seq, tMs, 0, n, n));
		}
		List<RecordedEvent> out = new ArrayList<>();
		buf.drainTo(out);
		assertEquals(5, out.size());
		assertEquals(0, ((Events.MouseMove) out.get(0)).x());
		assertEquals(4, ((Events.MouseMove) out.get(4)).x());
	}

	@Test
	public void drainEmptiesQueue()
	{
		RecordingBuffer buf = new RecordingBuffer();
		buf.enqueue((seq, tMs) -> new Events.MouseMove(seq, tMs, 0, 1, 1));
		List<RecordedEvent> out = new ArrayList<>();
		buf.drainTo(out);
		out.clear();
		buf.drainTo(out);
		assertTrue(out.isEmpty());
	}
}
