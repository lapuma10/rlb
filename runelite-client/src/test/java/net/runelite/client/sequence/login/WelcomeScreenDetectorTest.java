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
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import java.awt.Point;
import java.awt.Rectangle;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class WelcomeScreenDetectorTest
{
	@Test
	public void isVisible_returnsFalse_whenWidgetNull()
	{
		Client client = mock(Client.class);
		when(client.getWidget(anyInt())).thenReturn(null);
		assertFalse(WelcomeScreenDetector.isVisible(client));
	}

	@Test
	public void isVisible_returnsFalse_whenWidgetHidden()
	{
		Client client = mock(Client.class);
		Widget w = mock(Widget.class);
		when(w.isHidden()).thenReturn(true);
		when(client.getWidget(anyInt())).thenReturn(w);
		assertFalse(WelcomeScreenDetector.isVisible(client));
	}

	@Test
	public void isVisible_returnsTrue_whenWidgetVisible()
	{
		Client client = mock(Client.class);
		Widget w = mock(Widget.class);
		when(w.isHidden()).thenReturn(false);
		when(client.getWidget(anyInt())).thenReturn(w);
		assertTrue(WelcomeScreenDetector.isVisible(client));
	}

	@Test
	public void clickTarget_returnsCenterOfWidgetBounds()
	{
		Client client = mock(Client.class);
		Widget w = mock(Widget.class);
		when(w.isHidden()).thenReturn(false);
		when(w.getBounds()).thenReturn(new Rectangle(100, 200, 50, 30));
		when(client.getWidget(anyInt())).thenReturn(w);
		Point p = WelcomeScreenDetector.clickTarget(client);
		assertNotNull(p);
		assertEquals(125, p.x); // 100 + 50/2
		assertEquals(215, p.y); // 200 + 30/2
	}

	@Test
	public void clickTarget_returnsNull_whenNotVisible()
	{
		Client client = mock(Client.class);
		when(client.getWidget(anyInt())).thenReturn(null);
		assertNull(WelcomeScreenDetector.clickTarget(client));
	}
}
