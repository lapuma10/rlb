/*
 * Copyright (c) 2026, RuneLite
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
package net.runelite.client.sequence.login;

import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import javax.annotation.Nullable;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * Detects the post-login "Welcome screen" (the red Click-Here-to-Play screen
 * that appears after a successful login on some accounts/worlds) and provides
 * the click target.
 *
 * Uses InterfaceID.WelcomeScreen.CONTENT (0x017a_0002) which is the main
 * clickable surface. See spec §9 — confirm this is the right sub-component
 * via WidgetDumper before wiring the dismiss state in production.
 */
public final class WelcomeScreenDetector
{
	private WelcomeScreenDetector() {}

	public static boolean isVisible(Client client)
	{
		Widget w = client.getWidget(InterfaceID.WelcomeScreen.CONTENT);
		return w != null && !w.isHidden();
	}

	@Nullable
	public static Point clickTarget(Client client)
	{
		Widget w = client.getWidget(InterfaceID.WelcomeScreen.CONTENT);
		if (w == null || w.isHidden()) return null;
		Rectangle b = w.getBounds();
		if (b == null) return null;
		return new Point(b.x + b.width / 2, b.y + b.height / 2);
	}
}
