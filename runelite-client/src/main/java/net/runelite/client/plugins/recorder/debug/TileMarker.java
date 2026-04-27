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
package net.runelite.client.plugins.recorder.debug;

import net.runelite.api.Client;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.input.MouseListener;
import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * One-shot canvas-click trap. Stays registered with MouseManager always but
 * does nothing unless {@link #arm(Consumer)} has been called. Once armed,
 * the next left-click on the canvas:
 *  <ol>
 *    <li>captures the tile under the cursor (engine-resolved hover tile),</li>
 *    <li>consumes the click so the engine never processes it (no
 *        accidental walk / attack / take-item),</li>
 *    <li>fires the registered callback on the EDT,</li>
 *    <li>disarms itself.</li>
 *  </ol>
 *
 * <p>If the captured tile is null (cursor wasn't over a walkable tile when
 * the click fired), the callback receives null — caller decides what to do.
 */
public final class TileMarker implements MouseListener
{
    private final Client client;
    private volatile boolean armed = false;
    private volatile Consumer<WorldPoint> onMark;

    public TileMarker(Client client) { this.client = client; }

    public boolean isArmed() { return armed; }

    /** Arm for the next left-click. Replaces any prior armed callback. */
    public void arm(Consumer<WorldPoint> callback)
    {
        this.onMark = callback;
        this.armed = true;
    }

    public void disarm()
    {
        this.armed = false;
        this.onMark = null;
    }

    @Override
    public MouseEvent mousePressed(MouseEvent e)
    {
        if (!armed) return e;
        if (!SwingUtilities.isLeftMouseButton(e)) return e;
        Tile sel = client.getSelectedSceneTile();
        WorldPoint wp = sel == null ? null : sel.getWorldLocation();
        Consumer<WorldPoint> cb = onMark;
        armed = false;
        onMark = null;
        if (cb != null) SwingUtilities.invokeLater(() -> cb.accept(wp));
        // Consume both the press and (eventually) the release so the engine
        // never sees a "click" that would walk / interact.
        e.consume();
        return e;
    }

    @Override
    public MouseEvent mouseReleased(MouseEvent e)
    {
        // If we just consumed the press, also consume the release in the
        // same gesture (button is still down per AWT). Without this the
        // engine sees an orphan release and may mis-handle it.
        if (e.isConsumed()) return e;
        return e;
    }

    @Override public MouseEvent mouseClicked(MouseEvent e) { return e; }
    @Override public MouseEvent mouseEntered(MouseEvent e) { return e; }
    @Override public MouseEvent mouseExited(MouseEvent e)  { return e; }
    @Override public MouseEvent mouseDragged(MouseEvent e) { return e; }
    @Override public MouseEvent mouseMoved(MouseEvent e)   { return e; }
}
