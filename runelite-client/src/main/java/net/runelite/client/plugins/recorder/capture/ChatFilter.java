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
import java.util.EnumSet;
import java.util.Set;

/** Pure decision: should this chat type be recorded? */
public final class ChatFilter
{
	private static final Set<ChatMessageType> ALWAYS_KEEP = EnumSet.of(
		ChatMessageType.GAMEMESSAGE,
		ChatMessageType.MESBOX,
		ChatMessageType.ENGINE,
		ChatMessageType.CONSOLE,
		ChatMessageType.BROADCAST,
		ChatMessageType.TRADEREQ,
		ChatMessageType.TRADE,
		ChatMessageType.TRADE_SENT
	);

	private static final Set<ChatMessageType> PLAYER_CHAT = EnumSet.of(
		ChatMessageType.PUBLICCHAT,
		ChatMessageType.PRIVATECHAT,
		ChatMessageType.PRIVATECHATOUT,
		ChatMessageType.FRIENDSCHAT,
		ChatMessageType.CLAN_CHAT,
		ChatMessageType.CLAN_GUEST_CHAT,
		ChatMessageType.CLAN_GIM_CHAT
	);

	private final boolean capturePlayerChat;

	public ChatFilter(boolean capturePlayerChat)
	{
		this.capturePlayerChat = capturePlayerChat;
	}

	public boolean keep(ChatMessageType type)
	{
		if (ALWAYS_KEEP.contains(type)) return true;
		if (capturePlayerChat && PLAYER_CHAT.contains(type)) return true;
		return false;
	}
}
