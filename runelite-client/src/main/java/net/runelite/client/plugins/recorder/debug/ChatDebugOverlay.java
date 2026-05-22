package net.runelite.client.plugins.recorder.debug;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** "I can see the chatbox" proof overlay. Paints a translucent fill +
 *  outline over the entire chatbox root (UNIVERSE) and a second outline
 *  over just the message-list region (CHATAREA), with a label showing
 *  live bounds. */
public final class ChatDebugOverlay extends Overlay
{
    private static final Color FILL_ROOT    = new Color( 80, 180, 255,  60);
    private static final Color OUTLINE_ROOT = new Color( 80, 180, 255, 230);
    private static final Color OUTLINE_AREA = new Color(255, 200,  80, 230);
    private static final Color LABEL_BG     = new Color( 20,  30,  40, 200);
    private static final Color LABEL_FG     = new Color(220, 240, 255, 255);

    private static final Stroke STROKE = new BasicStroke(2.0f);

    private final Client client;

    public ChatDebugOverlay(Client client)
    {
        this.client = client;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_LOW);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        Widget root = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
        if (root == null || root.isHidden()) return null;
        Rectangle rb = root.getBounds();
        if (rb == null || rb.isEmpty()) return null;

        g.setStroke(STROKE);
        g.setColor(FILL_ROOT);
        g.fillRect(rb.x, rb.y, rb.width, rb.height);
        g.setColor(OUTLINE_ROOT);
        g.drawRect(rb.x, rb.y, rb.width - 1, rb.height - 1);

        Widget area = client.getWidget(InterfaceID.Chatbox.CHATAREA);
        Rectangle ab = area == null || area.isHidden() ? null : area.getBounds();
        if (ab != null && !ab.isEmpty())
        {
            g.setColor(OUTLINE_AREA);
            g.drawRect(ab.x, ab.y, ab.width - 1, ab.height - 1);
        }

        String label = "CHATBOX seen — UNIVERSE " + rb.width + "x" + rb.height
            + (ab != null ? " / CHATAREA " + ab.width + "x" + ab.height : "");
        int lw = g.getFontMetrics().stringWidth(label) + 8;
        int lh = g.getFontMetrics().getHeight();
        int lx = rb.x;
        int ly = rb.y - lh - 2;
        if (ly < 0) ly = rb.y + rb.height + 2;
        g.setColor(LABEL_BG);
        g.fillRect(lx, ly, lw, lh);
        g.setColor(LABEL_FG);
        g.drawString(label, lx + 4, ly + g.getFontMetrics().getAscent());
        return null;
    }
}
