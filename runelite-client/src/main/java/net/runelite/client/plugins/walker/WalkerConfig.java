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
package net.runelite.client.plugins.walker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup("walker")
public interface WalkerConfig extends Config
{
	@ConfigItem(
		keyName = "targetX",
		name = "Target X",
		description = "World X coordinate to walk to",
		position = 0
	)
	default int targetX()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "targetY",
		name = "Target Y",
		description = "World Y coordinate to walk to",
		position = 1
	)
	default int targetY()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "targetZ",
		name = "Target Plane",
		description = "Plane (0-3) to walk to",
		position = 2
	)
	default int targetZ()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "hotkey",
		name = "Walk hotkey",
		description = "Press to walk to the configured coordinates",
		position = 3
	)
	default Keybind hotkey()
	{
		return Keybind.NOT_SET;
	}
}
