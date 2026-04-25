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

import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.client.plugins.recorder.buffer.RecordingBuffer;
import net.runelite.client.plugins.recorder.events.Events;

/** Emits a camera event when yaw, pitch, or zoom move past a threshold.
 *  Also exposed for callers that want to force-emit before a click. */
@RequiredArgsConstructor
public final class CameraSampler
{
	private final int yawThreshold;
	private final int pitchThreshold;

	private int lastYaw = Integer.MIN_VALUE;
	private int lastPitch = Integer.MIN_VALUE;
	private int lastZoom = Integer.MIN_VALUE;

	public void sample(RecordingBuffer buf, Client client, int tick)
	{
		int yaw = client.getCameraYaw();
		int pitch = client.getCameraPitch();
		int zoom = client.getVarcIntValue(74); // VARC_CAMERA_ZOOM
		boolean changed = lastYaw == Integer.MIN_VALUE
			|| Math.abs(yaw - lastYaw) > yawThreshold
			|| Math.abs(pitch - lastPitch) > pitchThreshold
			|| zoom != lastZoom;
		if (!changed) return;
		lastYaw = yaw; lastPitch = pitch; lastZoom = zoom;
		buf.enqueue((seq, tMs) -> new Events.Camera(seq, tMs, tick, yaw, pitch, zoom));
	}

	public void forceSample(RecordingBuffer buf, Client client, int tick)
	{
		int yaw = client.getCameraYaw();
		int pitch = client.getCameraPitch();
		int zoom = client.getVarcIntValue(74);
		lastYaw = yaw; lastPitch = pitch; lastZoom = zoom;
		buf.enqueue((seq, tMs) -> new Events.Camera(seq, tMs, tick, yaw, pitch, zoom));
	}
}
