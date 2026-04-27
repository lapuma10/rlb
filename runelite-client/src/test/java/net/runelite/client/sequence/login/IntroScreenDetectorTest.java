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

public class IntroScreenDetectorTest
{
    @Test
    public void existingUserClickTarget_findsButton_byText()
    {
        Client client = mock(Client.class);
        Widget root = mock(Widget.class);
        when(root.getId()).thenReturn(0x017A0001);
        when(root.isHidden()).thenReturn(false);
        when(root.getText()).thenReturn(null);
        Widget btn = mock(Widget.class);
        when(btn.getId()).thenReturn(0x017A0042);
        when(btn.isHidden()).thenReturn(false);
        when(btn.getText()).thenReturn("Existing User");
        when(btn.getBounds()).thenReturn(new Rectangle(800, 400, 200, 60));
        when(root.getChildren()).thenReturn(new Widget[]{ btn });
        when(client.getWidgetRoots()).thenReturn(new Widget[]{ root });

        Point p = IntroScreenDetector.existingUserClickTarget(client);
        assertNotNull(p);
        assertEquals(900, p.x);  // 800 + 200/2
        assertEquals(430, p.y);  // 400 + 60/2
    }

    @Test
    public void existingUserClickTarget_returnsNull_whenWidgetHidden()
    {
        Client client = mock(Client.class);
        Widget root = mock(Widget.class);
        when(root.getId()).thenReturn(0x017A0001);
        when(root.isHidden()).thenReturn(false);
        Widget btn = mock(Widget.class);
        when(btn.getId()).thenReturn(0x017A0042);
        when(btn.isHidden()).thenReturn(true);
        when(btn.getText()).thenReturn("Existing User");
        when(root.getChildren()).thenReturn(new Widget[]{ btn });
        when(client.getWidgetRoots()).thenReturn(new Widget[]{ root });

        assertNull(IntroScreenDetector.existingUserClickTarget(client));
    }

    @Test
    public void existingUserClickTarget_returnsNull_whenNotInLoginGroup()
    {
        Client client = mock(Client.class);
        Widget root = mock(Widget.class);
        when(root.getId()).thenReturn(0x01230001);  // wrong group
        when(client.getWidgetRoots()).thenReturn(new Widget[]{ root });

        assertNull(IntroScreenDetector.existingUserClickTarget(client));
    }

    @Test
    public void existingUserClickTarget_findsButton_byAction()
    {
        Client client = mock(Client.class);
        Widget root = mock(Widget.class);
        when(root.getId()).thenReturn(0x017A0001);
        when(root.isHidden()).thenReturn(false);
        Widget btn = mock(Widget.class);
        when(btn.getId()).thenReturn(0x017A0042);
        when(btn.isHidden()).thenReturn(false);
        when(btn.getText()).thenReturn("");
        when(btn.getActions()).thenReturn(new String[]{ "Login as existing user" });
        when(btn.getBounds()).thenReturn(new Rectangle(0, 0, 100, 50));
        when(root.getChildren()).thenReturn(new Widget[]{ btn });
        when(client.getWidgetRoots()).thenReturn(new Widget[]{ root });

        Point p = IntroScreenDetector.existingUserClickTarget(client);
        assertNotNull(p);
    }
}
