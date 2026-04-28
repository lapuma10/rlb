package net.runelite.client.plugins.recorder.debug;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.sequence.login.LoginDebugBus;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact debug HUD for the most recent {@code resolveUsername} comparison.
 * Single-line on match (just decision + age); expands with hex dump only on
 * mismatch. Hidden entirely outside the login flow.
 */
public final class LoginDebugOverlay extends Overlay
{
    private static final long DISPLAY_MS = 8_000L;

    private final Client client;

    public LoginDebugOverlay(Client client)
    {
        this.client = client;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(Overlay.PRIORITY_HIGH);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        // Only render while we're actually on the login flow — once in-game
        // the panel is just noise.
        GameState gs = client.getGameState();
        if (gs != GameState.LOGIN_SCREEN && gs != GameState.LOGIN_SCREEN_AUTHENTICATOR) return null;

        LoginDebugBus.Snapshot snap = LoginDebugBus.latest();
        if (snap == null) return null;
        long age = System.currentTimeMillis() - snap.timestampMs;
        if (age > DISPLAY_MS) return null;

        boolean matches = !snap.current.isEmpty() && snap.current.equalsIgnoreCase(snap.target);

        List<String> lines = new ArrayList<>();
        if (matches)
        {
            // One line: just confirm the match and how long ago.
            lines.add("login: " + snap.decision + "  (" + (age / 1000) + "s)");
        }
        else
        {
            lines.add("login mismatch  (" + (age / 1000) + "s)");
            lines.add("  cur(" + snap.current.length() + "): \"" + snap.current + "\"");
            lines.add("  tgt(" + snap.target.length() + "): \"" + snap.target + "\"");
            int diff = firstDifferenceIgnoreCase(snap.current, snap.target);
            if (diff >= 0)
            {
                lines.add("  diff@" + diff
                    + ": cur=" + cpAt(snap.current, diff)
                    + " tgt=" + cpAt(snap.target, diff));
            }
        }
        return drawPanel(g, 8, 8, lines, matches);
    }

    private static int firstDifferenceIgnoreCase(String a, String b)
    {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++)
        {
            char ca = Character.toLowerCase(a.charAt(i));
            char cb = Character.toLowerCase(b.charAt(i));
            if (ca != cb) return i;
        }
        return a.length() == b.length() ? -1 : n;
    }

    private static String cpAt(String s, int idx)
    {
        if (idx < 0 || idx >= s.length()) return "<eos>";
        int cp = s.codePointAt(idx);
        char display = (cp >= 0x20 && cp < 0x7F) ? (char) cp : '?';
        return String.format("U+%04X(%c)", cp, display);
    }

    private Dimension drawPanel(Graphics2D g, int x, int y, List<String> lines, boolean matches)
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

        g.setColor(new Color(0, 0, 0, 220));
        g.fillRect(x, y, boxW, boxH);
        g.setColor(matches ? new Color(120, 230, 120, 255) : new Color(255, 140, 120, 255));
        g.drawRect(x, y, boxW, boxH);
        g.setColor(Color.WHITE);
        int ty = y + padding + fm.getAscent();
        for (String s : lines)
        {
            g.drawString(s, x + padding, ty);
            ty += lineH;
        }
        g.setFont(prev);
        return new Dimension(boxW, boxH);
    }
}
