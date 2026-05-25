package net.runelite.client.sequence.dispatch;

import java.awt.Rectangle;
import net.runelite.api.Point;
import java.lang.reflect.Method;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1A.4d.2 — pins {@code PixelResolver}'s minimap projection
 * invariants:
 * <ul>
 *   <li>direct-clear rim point → returned as-is</li>
 *   <li>direct point overlay-blocked → angular fallback at same radius
 *       around the bearing (±10°, ±20°, ±30°, ±45°, smallest first)</li>
 *   <li>all candidates blocked → {@code null} (NEVER returns disc centre)</li>
 *   <li>non-self target → never resolves to exact {@code (cx, cy)} —
 *       Phase 1A.4d.2's load-bearing invariant. Centre maps to the
 *       player's own tile in minimap coords (silent walk-to-self
 *       no-op in OSRS), so returning it as a fallback (the original
 *       Phase 1 design) broke long-distance walks — Run 04 F-E1.</li>
 * </ul>
 *
 * <p>Drives {@code PixelResolver.pullOffMinimapOverlay} via reflection,
 * matching {@link PixelResolverDeadZoneTest}'s widget-mock pattern
 * ({@link Client#getWidget} returns a {@link Widget} mock with controlled
 * bounds → {@code UiDeadZones.hitsMinimapOverlay} reads from those).
 */
public class PixelResolverMinimapProjectionTest
{
    // Disc geometry from Run 04's actual layout:
    // PixelResolver log: "minimap disc: widget bounds=(1081,8 152x152) center=(1157,84) r=72"
    private static final int DISC_X = 1081;
    private static final int DISC_Y = 8;
    private static final int DISC_W = 152;
    private static final int DISC_H = 152;
    private static final int CX = DISC_X + DISC_W / 2;          // 1157
    private static final int CY = DISC_Y + DISC_H / 2;          // 84
    private static final int R  = Math.min(DISC_W, DISC_H) / 2 - 4; // 72

    // ── Mock factories ──────────────────────────────────────────────

    /** Build a Client mock with no overlays configured by default. Any
     *  {@code client.getWidget(<overlay-id>)} call returns null, so
     *  {@code UiDeadZones.hitsMinimapOverlay} returns false for every
     *  point. Tests call {@link #addOverlay} to control occlusion. */
    private static Client newClientWithoutOverlays()
    {
        return mock(Client.class);
    }

    /** Register an overlay widget at the given UI dead-zone id with
     *  bounds covering {@code (x, y, w, h)}. Any {@code (px, py)} inside
     *  this rectangle will make {@code inMinimapOverlay} return true. */
    private static void addOverlay(Client c, int widgetId, Rectangle bounds)
    {
        Widget overlay = mock(Widget.class);
        when(overlay.isHidden()).thenReturn(false);
        when(overlay.getParent()).thenReturn(null);
        when(overlay.getBounds()).thenReturn(bounds);
        when(c.getWidget(widgetId)).thenReturn(overlay);
    }

    /** Reflection invoker for the private helper. */
    private static Point invokePullOffMinimapOverlay(
        PixelResolver pr, Point input, int cx, int cy) throws Exception
    {
        Method m = PixelResolver.class.getDeclaredMethod(
            "pullOffMinimapOverlay", Point.class, int.class, int.class);
        m.setAccessible(true);
        return (Point) m.invoke(pr, input, cx, cy);
    }

    // ── 1. Direct clear rim point ──────────────────────────────────

    @Test
    public void directClearRimPoint_returnedAsIs() throws Exception
    {
        Client c = newClientWithoutOverlays();
        PixelResolver pr = new PixelResolver(c);
        // East rim point: (CX+R, CY) = (1229, 84). No overlay configured
        // anywhere → inMinimapOverlay returns false → direct return.
        Point input = new Point(CX + R, CY);

        Point result = invokePullOffMinimapOverlay(pr, input, CX, CY);

        assertNotNull("direct rim point should be returned when no overlay blocks", result);
        assertEquals("returned x must equal input x", input.getX(), result.getX());
        assertEquals("returned y must equal input y", input.getY(), result.getY());
    }

    // ── 2. Angular fallback at the same radius ─────────────────────

    @Test
    public void overlayOnDirectRadial_findsAngularFallback() throws Exception
    {
        Client c = newClientWithoutOverlays();
        // Tight overlay covering ONLY the direct east-rim point at (1229, 84).
        // The ±10° rim points are at:
        //   +10°: (CX + R·cos(10°), CY + R·sin(10°)) ≈ (1228, 96)
        //   -10°: (CX + R·cos(-10°), CY + R·sin(-10°)) ≈ (1228, 72)
        // Both have y far from 84, so a 6x4 overlay at (1226, 82, 6, 4)
        // covers x∈[1226..1231], y∈[82..85] — direct point in, ±10° out.
        addOverlay(c, InterfaceID.Orbs.UNIVERSE, new Rectangle(1226, 82, 6, 4));

        PixelResolver pr = new PixelResolver(c);
        Point input = new Point(CX + R, CY);   // east rim direct

        Point result = invokePullOffMinimapOverlay(pr, input, CX, CY);

        assertNotNull("angular fallback should find a clear candidate", result);
        assertFalse("must NOT return disc centre — Phase 1A.4d.2 invariant",
            result.getX() == CX && result.getY() == CY);

        // Result must be at approximately the same radius (R ± rounding).
        int dx = result.getX() - CX;
        int dy = result.getY() - CY;
        double dist = Math.sqrt((double) dx * dx + (double) dy * dy);
        assertTrue("angular fallback should keep the same radius (was "
            + String.format("%.1f", dist) + ", expected ~" + R + ")",
            Math.abs(dist - R) <= 2);

        // Bearing must be within ±45° of original (0° = east).
        double bearingDeg = Math.toDegrees(Math.atan2(dy, dx));
        assertTrue("angular fallback should be within ±45° of original bearing (was "
            + String.format("%.1f", bearingDeg) + "°)",
            Math.abs(bearingDeg) <= 45.1);

        // Smallest-deviation-first ordering: the first clear candidate
        // should be ±10°. The fallback list is [+10, -10, +20, -20, ...]
        // so +10° is tried before -10°. Both are clear here, so result
        // should be at +10°: y ≈ 96 (positive, downward in screen coords).
        assertTrue("smallest-deviation-first ordering: +10° (y > 0) tried before -10° "
            + "(got bearing " + String.format("%.1f", bearingDeg) + "°, expected ~10°)",
            bearingDeg > 0);
    }

    // ── 3. All candidates blocked → null, NEVER centre ─────────────

    @Test
    public void overlayOnEverySweptArc_returnsNull_notCenter() throws Exception
    {
        Client c = newClientWithoutOverlays();
        // Massive overlay covering the entire canvas — blocks every
        // possible candidate including the direct point and all 8 angular
        // offsets.
        addOverlay(c, InterfaceID.Orbs.UNIVERSE, new Rectangle(0, 0, 2000, 2000));

        PixelResolver pr = new PixelResolver(c);
        Point input = new Point(CX + R, CY);   // east rim

        Point result = invokePullOffMinimapOverlay(pr, input, CX, CY);

        assertNull("returns null when every angular candidate is occluded — "
            + "Phase 1A.4d.2 invariant: disc centre is NEVER returned",
            result);
    }

    // ── 4. Defensive: input is already at centre ───────────────────

    @Test
    public void centreInputThatIsOccluded_returnsNull_notCentre() throws Exception
    {
        Client c = newClientWithoutOverlays();
        // Overlay at the disc centre itself — covers (CX, CY).
        addOverlay(c, InterfaceID.Orbs.UNIVERSE, new Rectangle(CX - 2, CY - 2, 4, 4));

        PixelResolver pr = new PixelResolver(c);
        // Input AT the centre. Defensive: such an input is degenerate
        // (zero radius). The function must NOT loop forever and NOT
        // return centre — it returns null.
        Point input = new Point(CX, CY);

        Point result = invokePullOffMinimapOverlay(pr, input, CX, CY);

        assertNull("zero-radius input that is occluded must return null, not centre",
            result);
    }

    // ── 5. Fuzz across bearings × overlay configs ──────────────────

    @Test
    public void nonSelfTarget_neverReturnsDiscCentre_fuzz() throws Exception
    {
        // The load-bearing invariant. Across 16 bearings × 4 overlay
        // configs (none / all / top-half / bottom-half), the function
        // must NEVER return (CX, CY) for a non-centre input.
        for (int bearingIdx = 0; bearingIdx < 16; bearingIdx++)
        {
            double bearing = (bearingIdx * 2 * Math.PI) / 16.0;
            int inX = CX + (int) Math.round(R * Math.cos(bearing));
            int inY = CY + (int) Math.round(R * Math.sin(bearing));
            if (inX == CX && inY == CY) continue;   // defensive (R > 0)

            for (int overlayConfig = 0; overlayConfig < 4; overlayConfig++)
            {
                Client c = newClientWithoutOverlays();
                switch (overlayConfig)
                {
                    case 0:   // no overlay
                        break;
                    case 1:   // entire canvas blocked → null expected
                        addOverlay(c, InterfaceID.Orbs.UNIVERSE,
                            new Rectangle(0, 0, 2000, 2000));
                        break;
                    case 2:   // top half of disc blocked
                        addOverlay(c, InterfaceID.Orbs.UNIVERSE,
                            new Rectangle(DISC_X, DISC_Y, DISC_W, DISC_H / 2));
                        break;
                    case 3:   // bottom half of disc blocked
                        addOverlay(c, InterfaceID.Orbs.UNIVERSE,
                            new Rectangle(DISC_X, DISC_Y + DISC_H / 2,
                                DISC_W, DISC_H / 2));
                        break;
                }

                PixelResolver pr = new PixelResolver(c);
                Point result = invokePullOffMinimapOverlay(
                    pr, new Point(inX, inY), CX, CY);

                // Result is either a valid non-centre point OR null.
                // NEVER (CX, CY).
                if (result != null)
                {
                    assertFalse("pullOffMinimapOverlay must NEVER return disc centre "
                        + "— bearing=" + Math.round(Math.toDegrees(bearing)) + "°, "
                        + "overlayConfig=" + overlayConfig + ", "
                        + "got (" + result.getX() + "," + result.getY() + ")",
                        result.getX() == CX && result.getY() == CY);
                }
            }
        }
    }
}
