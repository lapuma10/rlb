/*
 * Copyright (c) 2025, Mantas <mantas@runelite.net>
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
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.runelite.client.plugins.recorder.capture;

import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.plugins.recorder.buffer.RecordingBuffer;
import net.runelite.client.plugins.recorder.events.Events;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/** Implements RuneLite's MouseAdapter to capture every mouse event. The
 *  buffer field is volatile so EDT writes and the flusher's reads stay
 *  consistent. The plugin registers/deregisters this with MouseManager.
 *  Also implements MouseWheelListener so the same instance can be registered
 *  via {@code mouseManager.registerMouseWheelListener}. */
public final class MouseCapture extends MouseAdapter implements MouseWheelListener
{
	private volatile RecordingBuffer buffer;
	private final long[] downAtMs = new long[8];   // per-button timestamps for hold duration
	private final int downsampleHz;
	private long lastMoveMs = 0;

	public MouseCapture(int downsampleHz)
	{
		this.downsampleHz = downsampleHz;
	}

	public void setBuffer(RecordingBuffer buf) { this.buffer = buf; }

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		RecordingBuffer b = buffer;
		if (b == null) return e;
		int btn = e.getButton();
		if (btn >= 0 && btn < downAtMs.length) downAtMs[btn] = System.currentTimeMillis();
		int x = e.getX(), y = e.getY();
		b.enqueue((seq, tMs) -> new Events.MouseDown(seq, tMs, 0, btn, x, y));
		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		RecordingBuffer b = buffer;
		if (b == null) return e;
		int btn = e.getButton();
		long down = btn >= 0 && btn < downAtMs.length ? downAtMs[btn] : 0;
		long hold = down == 0 ? 0 : System.currentTimeMillis() - down;
		int x = e.getX(), y = e.getY();
		b.enqueue((seq, tMs) -> new Events.MouseUp(seq, tMs, 0, btn, x, y, hold));
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e) { return enqueueMove(e); }

	@Override
	public MouseEvent mouseDragged(MouseEvent e) { return enqueueMove(e); }

	private MouseEvent enqueueMove(MouseEvent e)
	{
		RecordingBuffer b = buffer;
		if (b == null) return e;
		if (downsampleHz > 0)
		{
			long now = System.currentTimeMillis();
			long minGap = 1000L / downsampleHz;
			if (now - lastMoveMs < minGap) return e;
			lastMoveMs = now;
		}
		int x = e.getX(), y = e.getY();
		b.enqueue((seq, tMs) -> new Events.MouseMove(seq, tMs, 0, x, y));
		return e;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e)
	{
		RecordingBuffer b = buffer;
		if (b == null) return e;
		int x = e.getX(), y = e.getY(), d = e.getWheelRotation();
		b.enqueue((seq, tMs) -> new Events.Wheel(seq, tMs, 0, x, y, d));
		return e;
	}
}
