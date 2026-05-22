package net.runelite.client.sequence.dispatch;

import java.awt.Rectangle;
import java.util.List;
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

/** Unit tests for {@link UiDeadZones}. Covers null-client tolerance,
 *  layout-swap (one layout's widgets present at a time, others null),
 *  ancestor-hidden skipping, and the minimap vs. minimap-overlay
 *  split.
 *
 *  <p>Each widget id we care about can be in one of three states:
 *  not registered (Client returns null), registered but hidden via
 *  an ancestor, or registered + visible with a real bounds. The
 *  helpers below build a Client stub by mapping ids to that state. */
public class UiDeadZonesTest
{
    // Widget id sentinels — small subset of the real coverage,
    // enough to exercise the conditional shapes.
    private static final int CHAT       = InterfaceID.Chatbox.UNIVERSE;
    private static final int FIXED_SIDE = InterfaceID.Toplevel.SIDE_PANELS;
    private static final int STRETCH_SIDE = InterfaceID.ToplevelOsrsStretch.SIDE_PANELS;
    private static final int FIXED_COMPASS = InterfaceID.Toplevel.COMPASSCLICK;
    private static final int FIXED_MINIMAP = InterfaceID.Toplevel.MINIMAP;
    private static final int ORBS_UNIVERSE = InterfaceID.Orbs.UNIVERSE;

    @Test
    public void nullClientReturnsEmptyAndNullIntersect()
    {
        // Regression coverage for the existing PixelResolverRepeatTest
        // which constructs PixelResolver(null) and then exercises
        // sampleInsidePolygon → dead-zone check. Without the null
        // guard in UiDeadZones.collect, the dead-zone lookup NPEs.
        assertTrue(UiDeadZones.worldDeadZones(null).isEmpty());
        assertTrue(UiDeadZones.minimapOverlayDeadZones(null).isEmpty());
        assertNull(UiDeadZones.worldIntersectsAt(null, 100, 100));
        assertFalse(UiDeadZones.hitsMinimapOverlay(null, 100, 100));
    }

    @Test
    public void fixedLayoutReturnsFixedSidePanelOnly()
    {
        // Fixed layout: Toplevel.SIDE_PANELS exists, ToplevelOsrsStretch
        // is absent (widget returns null). Verify only the fixed-layout
        // widget bounds appear in worldDeadZones.
        Client c = mock(Client.class);
        Rectangle fixedRect = new Rectangle(550, 0, 250, 600);
        registerVisible(c, FIXED_SIDE, fixedRect);
        // All other widget ids return null automatically (Mockito default).

        List<Rectangle> zones = UiDeadZones.worldDeadZones(c);
        assertEquals(1, zones.size());
        assertEquals(fixedRect, zones.get(0));
    }

    @Test
    public void resizableModernLayoutReturnsStretchSidePanelOnly()
    {
        // Same widget id family, different layout — only the stretch
        // widget exists. Confirms the static id list iterates safely
        // regardless of which layout is active.
        Client c = mock(Client.class);
        Rectangle stretchRect = new Rectangle(600, 0, 200, 800);
        registerVisible(c, STRETCH_SIDE, stretchRect);

        List<Rectangle> zones = UiDeadZones.worldDeadZones(c);
        assertEquals(1, zones.size());
        assertEquals(stretchRect, zones.get(0));
    }

    @Test
    public void hiddenAncestorSkipsWidget()
    {
        // Side-panel widget reports isHidden=false but its parent
        // reports isHidden=true. Must be skipped — engine renders
        // the chain visibility, not the leaf's flag alone.
        Client c = mock(Client.class);
        Widget hiddenParent = mock(Widget.class);
        when(hiddenParent.isHidden()).thenReturn(true);

        Widget child = mock(Widget.class);
        when(child.isHidden()).thenReturn(false);
        when(child.getParent()).thenReturn(hiddenParent);
        when(child.getBounds()).thenReturn(new Rectangle(10, 10, 100, 100));
        when(c.getWidget(FIXED_SIDE)).thenReturn(child);

        assertTrue(UiDeadZones.worldDeadZones(c).isEmpty());
    }

    @Test
    public void minimapOverlayExcludesMinimapDrawable()
    {
        // Both the minimap drawable and the orb cluster (overlay)
        // are present and visible. worldDeadZones contains both;
        // minimapOverlayDeadZones excludes the minimap drawable so
        // a legitimate minimap-walk pixel isn't pulled off the disc.
        Client c = mock(Client.class);
        Rectangle minimap = new Rectangle(560, 4, 200, 200);
        Rectangle orbs    = new Rectangle(540, 40, 30, 200);
        registerVisible(c, FIXED_MINIMAP, minimap);
        registerVisible(c, ORBS_UNIVERSE, orbs);

        List<Rectangle> world   = UiDeadZones.worldDeadZones(c);
        List<Rectangle> overlay = UiDeadZones.minimapOverlayDeadZones(c);

        assertTrue("world dead-zones must include minimap rect",
            world.contains(minimap));
        assertTrue("world dead-zones must include orb cluster",
            world.contains(orbs));
        assertFalse("minimap-overlay must NOT include the minimap drawable",
            overlay.contains(minimap));
        assertTrue("minimap-overlay must include the orb cluster",
            overlay.contains(orbs));
    }

    @Test
    public void worldIntersectsAtReturnsFirstHitOrNull()
    {
        Client c = mock(Client.class);
        Rectangle chat    = new Rectangle(0, 400, 500, 200);
        Rectangle compass = new Rectangle(560, 0, 40, 40);
        registerVisible(c, CHAT, chat);
        registerVisible(c, FIXED_COMPASS, compass);

        // Pixel inside the chatbox rectangle → returns that rectangle.
        Rectangle hit = UiDeadZones.worldIntersectsAt(c, 100, 500);
        assertNotNull(hit);
        assertEquals(chat, hit);

        // Pixel inside the compass → returns the compass rectangle.
        Rectangle hit2 = UiDeadZones.worldIntersectsAt(c, 575, 20);
        assertNotNull(hit2);
        assertEquals(compass, hit2);

        // Pixel clear of every dead-zone → null.
        assertNull(UiDeadZones.worldIntersectsAt(c, 300, 100));
    }

    @Test
    public void emptyOrZeroSizedBoundsSkipped()
    {
        // A widget whose bounds report 0x0 is non-rendered — must
        // not produce a dead-zone (would otherwise reject every
        // pixel inside (0,0,0,0), which is no pixels, but also
        // would clutter the returned list).
        Client c = mock(Client.class);
        Widget zero = mock(Widget.class);
        when(zero.isHidden()).thenReturn(false);
        when(zero.getParent()).thenReturn(null);
        when(zero.getBounds()).thenReturn(new Rectangle(0, 0, 0, 0));
        when(c.getWidget(CHAT)).thenReturn(zero);

        assertTrue(UiDeadZones.worldDeadZones(c).isEmpty());
    }

    // ─── helpers ────────────────────────────────────────────────────────────────

    private static void registerVisible(Client c, int id, Rectangle bounds)
    {
        Widget w = mock(Widget.class);
        when(w.isHidden()).thenReturn(false);
        when(w.getParent()).thenReturn(null);
        when(w.getBounds()).thenReturn(bounds);
        when(c.getWidget(id)).thenReturn(w);
    }
}
