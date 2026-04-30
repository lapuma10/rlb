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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.runelite.client.plugins.recorder.flush;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.recorder.buffer.JsonlGzipWriter;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.plugins.recorder.buffer.RecordingBuffer;
import net.runelite.client.plugins.recorder.events.EventCodec;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Drains the buffer on a daemon thread and writes batched JSONL to the gzip
 *  writer. Stop() drains a final time and closes the writer. */
@Slf4j
public final class FlushDaemon
{
	private final RecordingBuffer buffer;
	private final JsonlGzipWriter writer;
	private final EventCodec codec;
	private final long flushIntervalMs;
	private final List<RecordedEvent> capturedInMemory; // retained for analysis on Stop

	private volatile boolean running = false;
	private Thread thread;

	public FlushDaemon(RecordingBuffer buffer, JsonlGzipWriter writer,
					   EventCodec codec, long flushIntervalMs)
	{
		this.buffer = buffer;
		this.writer = writer;
		this.codec = codec;
		this.flushIntervalMs = flushIntervalMs;
		this.capturedInMemory = new ArrayList<>();
	}

	public synchronized void start()
	{
		if (running) return;
		running = true;
		thread = new Thread(this::loop, "recorder-flush");
		thread.setDaemon(true);
		thread.start();
	}

	private void loop()
	{
		List<RecordedEvent> batch = new ArrayList<>();
		while (running)
		{
			try
			{
				SequenceSleep.sleep(null, flushIntervalMs);
			}
			catch (InterruptedException ie)
			{
				Thread.currentThread().interrupt();
				break;
			}
			drainAndWrite(batch);
		}
		// Final drain after stop() flips the flag
		drainAndWrite(batch);
	}

	private void drainAndWrite(List<RecordedEvent> batch)
	{
		batch.clear();
		buffer.drainTo(batch);
		if (batch.isEmpty()) return;
		try
		{
			for (RecordedEvent e : batch)
			{
				writer.writeLine(codec.toJsonLine(e));
			}
			writer.flush();
		}
		catch (IOException io)
		{
			log.error("flush failed", io);
		}
		capturedInMemory.addAll(batch);
	}

	public synchronized void stop() throws IOException
	{
		if (!running) return;
		running = false;
		if (thread != null)
		{
			thread.interrupt();
			try { thread.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
		}
		writer.close();
	}

	/** All events successfully written so far, in seq order. The returned list
	 *  is the daemon's own; do not mutate. Used by analysers on Stop. */
	public List<RecordedEvent> capturedInMemory() { return capturedInMemory; }
}
