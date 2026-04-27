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
import javax.annotation.Nullable;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * Finds the "Login" submit button on the credentials form (loginIndex=2).
 *
 * Scans widgets in the login interface group (0x017A) for a visible widget
 * whose text or right-click action matches "Login". Returns the click target
 * (center of bounds) so CLICK_LOGIN can click the actual button rather than
 * a hardcoded proportional coordinate.
 */
public final class LoginButtonDetector
{
    private LoginButtonDetector() {}

    private static final int LOGIN_INTERFACE_GROUP = 0x017A;
    private static final int MAX_DEPTH = 6;

    @Nullable
    public static Point clickTarget(Client client)
    {
        try
        {
            Widget[] roots = client.getWidgetRoots();
            if (roots == null) return null;
            for (Widget r : roots)
            {
                if (r == null) continue;
                int group = r.getId() >>> 16;
                if (group != LOGIN_INTERFACE_GROUP) continue;
                Point p = scan(r, 0);
                if (p != null) return p;
            }
        }
        catch (Exception ignored) {}
        return null;
    }

    @Nullable
    private static Point scan(Widget w, int depth)
    {
        if (w == null || w.isHidden() || depth > MAX_DEPTH) return null;
        if (matches(w))
        {
            Rectangle b = w.getBounds();
            if (b != null && b.width > 0 && b.height > 0)
            {
                return new Point(b.x + b.width / 2, b.y + b.height / 2);
            }
        }
        Widget[] children = w.getChildren();
        if (children != null) for (Widget c : children)
        {
            Point p = scan(c, depth + 1);
            if (p != null) return p;
        }
        Widget[] dynamic = w.getDynamicChildren();
        if (dynamic != null) for (Widget c : dynamic)
        {
            Point p = scan(c, depth + 1);
            if (p != null) return p;
        }
        return null;
    }

    private static boolean matches(Widget w)
    {
        // Match on widget text — exact "Login" or close variants
        String text = w.getText();
        if (text != null)
        {
            String t = text.trim().toLowerCase();
            // Avoid matching "login server offline" or similar error text
            if (t.equals("login") || t.equals("log in")) return true;
        }

        // Match on right-click action
        String[] actions = w.getActions();
        if (actions != null)
        {
            for (String a : actions)
            {
                if (a == null) continue;
                String lc = a.trim().toLowerCase();
                if (lc.equals("login") || lc.equals("log in") || lc.contains("submit")) return true;
            }
        }
        return false;
    }
}
