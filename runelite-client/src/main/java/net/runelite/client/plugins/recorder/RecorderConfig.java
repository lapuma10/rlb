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
package net.runelite.client.plugins.recorder;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup("recorder")
public interface RecorderConfig extends Config
{
	@ConfigItem(
		keyName = "markerHotkey",
		name = "Marker hotkey",
		description = "Press to start recording when idle, or to open the marker-label dialog while recording."
	)
	default Keybind markerHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "toggleHotkey",
		name = "Toggle hotkey",
		description = "Press to toggle recording on/off without opening the marker dialog."
	)
	default Keybind toggleHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "captureplayerchat",
		name = "Capture player chat",
		description = "If true, public/private/clan/friends chat is recorded. Default off."
	)
	default boolean capturePlayerChat()
	{
		return false;
	}

	@ConfigItem(
		keyName = "cameraSampleThresholdYaw",
		name = "Camera yaw threshold",
		description = "Yaw delta (jagex units) above which a camera event is emitted."
	)
	default int cameraSampleThresholdYaw()
	{
		return 16;
	}

	@ConfigItem(
		keyName = "cameraSampleThresholdPitch",
		name = "Camera pitch threshold",
		description = "Pitch delta above which a camera event is emitted."
	)
	default int cameraSampleThresholdPitch()
	{
		return 16;
	}

	@ConfigItem(
		keyName = "flushIntervalMs",
		name = "Flush interval (ms)",
		description = "How often the daemon thread flushes the buffer to disk."
	)
	default int flushIntervalMs()
	{
		return 500;
	}

	@ConfigItem(
		keyName = "mouseMoveDownsampleHz",
		name = "Mouse-move downsample (Hz)",
		description = "If > 0, downsample mouse moves to this rate. 0 = keep all."
	)
	default int mouseMoveDownsampleHz()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "recordingsDir",
		name = "Recordings directory",
		description = "Override storage location. Empty = ~/.runelite/sequencer/recordings."
	)
	default String recordingsDir()
	{
		return "";
	}
}
