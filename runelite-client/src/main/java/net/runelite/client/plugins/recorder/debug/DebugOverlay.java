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
import net.runelite.api.MenuEntry;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hover-info debug overlay. Paints a small floating panel near the cursor
 * showing what the engine thinks is under the mouse: tile coords, top
 * left-click action, the full menu entry list, and (optionally) a marked
 * tile that the user pinned for testing the dispatcher.
 *
 * <p>Read-only — all data comes from {@code client.getMenu()} and
 * {@code client.getSelectedSceneTile()}. No side effects on the game.
 */
public final class DebugOverlay extends Overlay
{
    private final Client client;
    /** Marked tile (set/cleared by the side panel). Atomic ref so the EDT
     *  can update it while the render thread reads. */
    private final AtomicReference<WorldPoint> marked = new AtomicReference<>(null);

    public DebugOverlay(Client client)
    {
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_LOW);
    }

    public void setMarked(WorldPoint wp) { marked.set(wp); }
    public WorldPoint getMarked() { return marked.get(); }

    @Override
    public Dimension render(Graphics2D g)
    {
        // 1. Outline the marked tile in green (if any) and the selected
        //    scene tile in cyan, so the user can see them in the world.
        WorldPoint markedWp = marked.get();
        if (markedWp != null) drawTileOutline(g, markedWp, new Color(80, 220, 80, 230));
        Tile sel = client.getSelectedSceneTile();
        if (sel != null && sel.getWorldLocation() != null)
        {
            drawTileOutline(g, sel.getWorldLocation(), new Color(120, 200, 255, 200));
        }

        // 2. Build the hover-info text block.
        net.runelite.api.Point mouse = client.getMouseCanvasPosition();
        if (mouse == null || mouse.getX() < 0 || mouse.getY() < 0) return null;

        List<String> lines = new ArrayList<>();
        lines.add("mouse: " + mouse.getX() + "," + mouse.getY());
        if (sel != null && sel.getWorldLocation() != null)
        {
            WorldPoint wp = sel.getWorldLocation();
            lines.add("tile: " + wp.getX() + "," + wp.getY() + " p=" + wp.getPlane());
        }
        else
        {
            lines.add("tile: -");
        }
        if (markedWp != null)
        {
            lines.add("marked: " + markedWp.getX() + "," + markedWp.getY()
                + " p=" + markedWp.getPlane());
        }

        // 3. Menu entries — engine-computed for the current cursor pos.
        //    Top of menu (the left-click action) is the LAST entry.
        MenuEntry[] entries = client.getMenu() == null ? null : client.getMenu().getMenuEntries();
        if (entries != null && entries.length > 0)
        {
            int top = entries.length - 1;
            MenuEntry t = entries[top];
            lines.add("L-click: " + label(t));
            if (entries.length > 1)
            {
                lines.add("menu (" + entries.length + "):");
                int max = Math.min(entries.length, 7);   // cap so it doesn't fill the screen
                for (int i = entries.length - 1; i >= entries.length - max; i--)
                {
                    lines.add("  " + label(entries[i]));
                }
                if (entries.length > max) lines.add("  …");
            }
        }
        else
        {
            lines.add("(no menu entries)");
        }

        drawPanel(g, mouse.getX() + 14, mouse.getY() + 14, lines);
        return null;
    }

    private static String label(MenuEntry e)
    {
        if (e == null) return "?";
        String opt = e.getOption() == null ? "?" : stripTags(e.getOption());
        String tgt = e.getTarget() == null ? "" : stripTags(e.getTarget());
        String type = e.getType() == null ? "?" : e.getType().name();
        int id = e.getIdentifier();
        StringBuilder sb = new StringBuilder();
        sb.append(opt);
        if (!tgt.isEmpty()) sb.append(' ').append(tgt);
        sb.append("  [").append(type);
        if (id != 0) sb.append(" id=").append(id);
        sb.append(']');
        return sb.toString();
    }

    private static String stripTags(String s)
    {
        return s.replaceAll("<[^>]+>", "");
    }

    private void drawTileOutline(Graphics2D g, WorldPoint wp, Color colour)
    {
        var wv = client.getTopLevelWorldView();
        if (wv == null) return;
        var lp = net.runelite.api.coords.LocalPoint.fromWorld(wv, wp);
        if (lp == null) return;
        Polygon poly = net.runelite.api.Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return;
        Stroke prev = g.getStroke();
        g.setStroke(new BasicStroke(2f));
        g.setColor(colour);
        g.drawPolygon(poly);
        g.setStroke(prev);
    }

    private void drawPanel(Graphics2D g, int x, int y, List<String> lines)
    {
        Font prev = g.getFont();
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 11);
        g.setFont(mono);
        FontMetrics fm = g.getFontMetrics();
        int lineH = fm.getHeight();
        int width = 0;
        for (String s : lines) width = Math.max(width, fm.stringWidth(s));
        int padding = 6;
        int boxW = width + padding * 2;
        int boxH = lines.size() * lineH + padding * 2;
        int canvasW = client.getCanvas() == null ? 0 : client.getCanvas().getWidth();
        int canvasH = client.getCanvas() == null ? 0 : client.getCanvas().getHeight();
        // Keep on-screen.
        if (x + boxW > canvasW) x = Math.max(0, canvasW - boxW - 4);
        if (y + boxH > canvasH) y = Math.max(0, canvasH - boxH - 4);
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(x, y, boxW, boxH);
        g.setColor(new Color(180, 220, 255, 255));
        g.drawRect(x, y, boxW, boxH);
        g.setColor(Color.WHITE);
        int ty = y + padding + fm.getAscent();
        for (String s : lines)
        {
            g.drawString(s, x + padding, ty);
            ty += lineH;
        }
        g.setFont(prev);
    }
}
