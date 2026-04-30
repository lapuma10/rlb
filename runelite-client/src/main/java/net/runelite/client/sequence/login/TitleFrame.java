package net.runelite.client.sequence.login;

import net.runelite.api.Client;
import java.awt.Point;
import java.util.Map;

/**
 * Title-screen geometry helper for Login V2.
 *
 * The OSRS title sprite is rendered at fixed 765x503 logical pixels,
 * centered in the canvas regardless of canvas size. Resizing the
 * client only moves the centering offset; the sprite (and the buttons
 * within it) stays the same size. This helper computes the centered
 * frame from canvas dims and returns canvas-pixel click targets at
 * fixed offsets within the frame.
 *
 * Threading: {@link #current} reads canvas dims and must be called on
 * the client thread (wrap via dispatcher.runOnClient). {@link #button}
 * is pure math.
 *
 * The offset table is empirical — tune by clicking each spot in the
 * client and reading frame-relative deltas off the [click-inspector]
 * log when capture mode is enabled (ClickInspectorCapture).
 */
public final class TitleFrame
{
    public static final int FRAME_W = 765;
    public static final int FRAME_H = 503;

    public enum ButtonId
    {
        EXISTING_USER,
        WORLDS_BUTTON,
        USERNAME_FIELD,
        PASSWORD_FIELD
    }

    public record Frame(int x, int y, int w, int h) {}

    /** Client thread. */
    public static Frame current(Client client)
    {
        int cw = client.getCanvasWidth();
        int ch = client.getCanvasHeight();
        int x = Math.max(0, (cw - FRAME_W) / 2);
        int y = Math.max(0, (ch - FRAME_H) / 2);
        return new Frame(x, y, FRAME_W, FRAME_H);
    }

    /** Pure. Returns canvas-pixel coords (logical). */
    public static Point button(Frame f, ButtonId id)
    {
        Offset o = OFFSETS.get(id);
        if (o == null) throw new IllegalArgumentException("no offset for " + id);
        return new Point(f.x + o.dx, f.y + o.dy);
    }

    private record Offset(int dx, int dy) {}

    // TUNE ME: rough guesses based on standard OSRS title-screen layout.
    // Re-measure via ClickInspectorCapture if any click misses the button.
    private static final Map<ButtonId, Offset> OFFSETS = Map.of(
        ButtonId.EXISTING_USER,  new Offset(450, 280),
        ButtonId.WORLDS_BUTTON,  new Offset(708,  20),
        ButtonId.USERNAME_FIELD, new Offset(382, 244),
        ButtonId.PASSWORD_FIELD, new Offset(382, 263)
    );

    private TitleFrame() {}
}
