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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Lock-free MPSC queue for events. Producers (event-bus subscribers, AWT
 *  listeners) call {@link #enqueue(EventBuilder)}; the daemon flusher calls
 *  {@link #drainTo(List)}. Tracks per-type counters so the side panel can show
 *  live progress without scanning the queue. */
public final class RecordingBuffer
{
	@FunctionalInterface
	public interface EventBuilder
	{
		RecordedEvent build(long seq, long tMs);
	}

	/** Live "interesting" event ring shown in the side panel. Mousemove/tick/
	 *  camera/wheel etc. are filtered out; clicks, world interactions, inv
	 *  changes, chat, markers, and xp drops are kept. Capped at RECENT_LIMIT. */
	private static final int RECENT_LIMIT = 50;

	private final ConcurrentLinkedQueue<RecordedEvent> queue = new ConcurrentLinkedQueue<>();
	private final AtomicLong nextSeq = new AtomicLong(0);
	private final ConcurrentHashMap<String, AtomicLong> typeCounts = new ConcurrentHashMap<>();
	private final ArrayDeque<RecordedEvent> recentInteresting = new ArrayDeque<>(RECENT_LIMIT);
	private final long startMs;

	public RecordingBuffer()
	{
		this.startMs = System.currentTimeMillis();
	}

	public RecordingBuffer(long startMs)
	{
		this.startMs = startMs;
	}

	public long enqueue(EventBuilder b)
	{
		long seq = nextSeq.getAndIncrement();
		long tMs = System.currentTimeMillis() - startMs;
		RecordedEvent ev = b.build(seq, tMs);
		queue.add(ev);
		typeCounts.computeIfAbsent(ev.type(), k -> new AtomicLong()).incrementAndGet();
		if (isInteresting(ev))
		{
			synchronized (recentInteresting)
			{
				if (recentInteresting.size() >= RECENT_LIMIT) recentInteresting.removeFirst();
				recentInteresting.addLast(ev);
			}
		}
		return seq;
	}

	private static boolean isInteresting(RecordedEvent ev)
	{
		return ev instanceof Events.MenuClick
			|| ev instanceof Events.WorldClick
			|| ev instanceof Events.WidgetClick
			|| ev instanceof Events.InvChange
			|| ev instanceof Events.EquipChange
			|| ev instanceof Events.BankChange
			|| ev instanceof Events.Chat
			|| ev instanceof Events.Marker
			|| ev instanceof Events.MarkerDialog
			|| ev instanceof Events.XpChange;
	}

	/** Snapshot of the most recent (up to {@value #RECENT_LIMIT}) interesting
	 *  events, oldest first. Safe to call from any thread. */
	public List<RecordedEvent> recentInterestingSnapshot()
	{
		synchronized (recentInteresting)
		{
			return new ArrayList<>(recentInteresting);
		}
	}

	public void drainTo(List<RecordedEvent> sink)
	{
		RecordedEvent e;
		while ((e = queue.poll()) != null)
		{
			sink.add(e);
		}
	}

	public int size() { return queue.size(); }
	public long startMs() { return startMs; }
	public long totalEvents() { return nextSeq.get(); }

	/** Snapshot of per-type counts. Iteration order: most frequent first. */
	public Map<String, Long> typeCountsSnapshot()
	{
		Map<String, Long> out = new LinkedHashMap<>();
		typeCounts.entrySet().stream()
			.sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
			.forEach(en -> out.put(en.getKey(), en.getValue().get()));
		return out;
	}
}
