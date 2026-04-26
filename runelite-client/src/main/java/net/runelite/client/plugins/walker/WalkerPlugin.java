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

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.HotkeyListener;

@Slf4j
@PluginDescriptor(
	name = "Walker",
	description = "Walk to configured world coordinates via hotkey",
	tags = {"walk", "coordinates", "movement"}
)
public class WalkerPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyManager keyManager;

	@Inject
	private WalkerConfig config;

	private final HotkeyListener hotkeyListener = new HotkeyListener(() -> config.hotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			walkToTarget();
		}
	};

	@Provides
	WalkerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WalkerConfig.class);
	}

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(hotkeyListener);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(hotkeyListener);
	}

	private void walkToTarget()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		final WorldPoint target = new WorldPoint(config.targetX(), config.targetY(), config.targetZ());

		clientThread.invoke(() ->
		{
			LocalPoint local = LocalPoint.fromWorld(client.getTopLevelWorldView(), target);
			if (local == null)
			{
				log.debug("Target ({}, {}, {}) is not in the current scene",
					target.getX(), target.getY(), target.getPlane());
				return;
			}

			client.menuAction(local.getSceneX(), local.getSceneY(), MenuAction.WALK, 0, 0, "Walk here", "");
		});
	}
}
