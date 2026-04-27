/*
 * Copyright (c) 2025, https://github.com/runelite/runelite
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
 * LOSS OF USE, DATA, OR PROFITS; HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.capture;

import net.runelite.api.ChatMessageType;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatFilterTest
{
	@Test
	public void systemMessages_kept_byDefault()
	{
		ChatFilter f = new ChatFilter(false);
		assertTrue(f.keep(ChatMessageType.GAMEMESSAGE));
		assertTrue(f.keep(ChatMessageType.MESBOX));
		assertTrue(f.keep(ChatMessageType.ENGINE));
		assertTrue(f.keep(ChatMessageType.CONSOLE));
		assertTrue(f.keep(ChatMessageType.BROADCAST));
	}

	@Test
	public void tradeFamily_kept_byDefault()
	{
		ChatFilter f = new ChatFilter(false);
		assertTrue(f.keep(ChatMessageType.TRADEREQ));
		assertTrue(f.keep(ChatMessageType.TRADE));
		assertTrue(f.keep(ChatMessageType.TRADE_SENT));
	}

	@Test
	public void playerChat_droppedByDefault()
	{
		ChatFilter f = new ChatFilter(false);
		assertFalse(f.keep(ChatMessageType.PUBLICCHAT));
		assertFalse(f.keep(ChatMessageType.PRIVATECHAT));
		assertFalse(f.keep(ChatMessageType.PRIVATECHATOUT));
		assertFalse(f.keep(ChatMessageType.FRIENDSCHAT));
		assertFalse(f.keep(ChatMessageType.CLAN_CHAT));
		assertFalse(f.keep(ChatMessageType.CLAN_GUEST_CHAT));
	}

	@Test
	public void playerChat_kept_whenToggleOn()
	{
		ChatFilter f = new ChatFilter(true);
		assertTrue(f.keep(ChatMessageType.PUBLICCHAT));
		assertTrue(f.keep(ChatMessageType.PRIVATECHAT));
		assertTrue(f.keep(ChatMessageType.FRIENDSCHAT));
		assertTrue(f.keep(ChatMessageType.CLAN_CHAT));
	}
}
