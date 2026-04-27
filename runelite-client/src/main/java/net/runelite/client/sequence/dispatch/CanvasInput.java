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
package net.runelite.client.sequence.dispatch;

import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * Low-level synthetic input. Posts AWT events to the RuneLite canvas via
 * {@code dispatchEvent}, which reaches the same MouseListener / KeyListener
 * chain that real OS input hits. The engine's click resolver, our
 * MouseCapture, and the recorder's chat-style observers all see the
 * synthesized events as if they were physical.
 *
 * <p>Intentionally has zero policy of its own — no humanization, no path,
 * no jitter. {@link HumanizedInputDispatcher} layers those on top.
 */
@RequiredArgsConstructor
public final class CanvasInput
{
    private final Client client;
    /** Tracks the synthesized cursor position so callers can ask
     *  "where is the cursor right now?" without going to AWT. */
    private int cursorX = -1, cursorY = -1;

    public int cursorX() { return cursorX; }
    public int cursorY() { return cursorY; }

    /** Dispatch a MOUSE_MOVED at (x, y). Updates cached cursor position. */
    public void mouseMove(int x, int y)
    {
        Canvas c = client.getCanvas();
        long t = System.currentTimeMillis();
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_MOVED,
            t, 0, x, y, 0, false, MouseEvent.NOBUTTON));
        cursorX = x; cursorY = y;
    }

    /** Dispatch MOUSE_PRESSED at the current cursor pos. */
    public void mousePress(int button)
    {
        Canvas c = client.getCanvas();
        long t = System.currentTimeMillis();
        int mask = button == MouseEvent.BUTTON1
            ? MouseEvent.BUTTON1_DOWN_MASK : MouseEvent.BUTTON3_DOWN_MASK;
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED,
            t, mask, cursorX, cursorY, 1, false, button));
    }

    /** Dispatch MOUSE_RELEASED + MOUSE_CLICKED at the current cursor pos. */
    public void mouseRelease(int button)
    {
        Canvas c = client.getCanvas();
        long t = System.currentTimeMillis();
        int mask = button == MouseEvent.BUTTON1
            ? MouseEvent.BUTTON1_DOWN_MASK : MouseEvent.BUTTON3_DOWN_MASK;
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED,
            t, mask, cursorX, cursorY, 1, false, button));
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED,
            t, 0, cursorX, cursorY, 1, false, button));
    }

    /** Synth a key press → release pair with a held duration. */
    public void keyTap(int keyCode, int holdMs) throws InterruptedException
    {
        Canvas c = client.getCanvas();
        long t = System.currentTimeMillis();
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED,
            t, 0, keyCode, KeyEvent.CHAR_UNDEFINED));
        Thread.sleep(Math.max(0, holdMs));
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED,
            t, 0, keyCode, KeyEvent.CHAR_UNDEFINED));
    }

    /** Synth a key press → typed → release sequence for a printable character.
     *  The KEY_TYPED event carries the {@code keyChar} which is what text fields
     *  on the OSRS title screen actually consume. KEY_PRESSED + KEY_RELEASED
     *  carry the virtual key code so other listeners (focus tracker, capture)
     *  see a complete edge pair. Modifier-bearing keys (shift for caps) are
     *  the caller's responsibility — letters are routed via uppercase virtual
     *  codes here when needed. */
    public void keyType(char ch, int holdMs) throws InterruptedException
    {
        Canvas c = client.getCanvas();
        long t = System.currentTimeMillis();
        int vk = KeyEvent.getExtendedKeyCodeForChar(ch);
        if (vk == KeyEvent.VK_UNDEFINED) vk = Character.toUpperCase(ch);
        int mods = Character.isUpperCase(ch) ? KeyEvent.SHIFT_DOWN_MASK : 0;
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED,
            t, mods, vk, KeyEvent.CHAR_UNDEFINED));
        // KEY_TYPED is what the title-screen text input actually reads.
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_TYPED,
            t, mods, KeyEvent.VK_UNDEFINED, ch));
        Thread.sleep(Math.max(0, holdMs));
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED,
            t, mods, vk, KeyEvent.CHAR_UNDEFINED));
    }

    /** Synth a key press → release with modifier mask held throughout. Used
     *  for combos like Ctrl+V / Cmd+V. The modifier key's own press/release
     *  is the caller's responsibility (so a single combo can chord multiple
     *  letters under one modifier hold). */
    public void keyTapWithModifier(int keyCode, int modifierMask, int holdMs) throws InterruptedException
    {
        Canvas c = client.getCanvas();
        long t = System.currentTimeMillis();
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED,
            t, modifierMask, keyCode, KeyEvent.CHAR_UNDEFINED));
        Thread.sleep(Math.max(0, holdMs));
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED,
            t, modifierMask, keyCode, KeyEvent.CHAR_UNDEFINED));
    }
}
