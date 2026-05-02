package net.runelite.client.plugins.recorder.trail;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.walker.Reachability;
import net.runelite.client.plugins.recorder.walker.Reachability.ReachabilityMap;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Per-stage cull tests for {@link ObjectVisibility}. The pipeline is
 *  the v2 mirror of combat/{@code TargetVisibility}; we pin each
 *  stage's failure mode and the rotation-fixability classification. */
public class ObjectVisibilityTest
{
    private static final WorldPoint PLAYER_TILE = new WorldPoint(100, 100, 0);
    private static final WorldPoint TARGET_TILE = new WorldPoint(105, 100, 0);

    private Client client;
    private Player player;
    private ObjectVisibility visibility;

    @Before
    public void setUp()
    {
        client = mock(Client.class);
        player = mock(Player.class);
        when(player.getWorldLocation()).thenReturn(PLAYER_TILE);
        // Default viewport: a 600x400 box at (10, 10).
        when(client.getViewportXOffset()).thenReturn(10);
        when(client.getViewportYOffset()).thenReturn(10);
        when(client.getViewportWidth()).thenReturn(600);
        when(client.getViewportHeight()).thenReturn(400);
        when(client.isMenuOpen()).thenReturn(false);
        when(client.isResized()).thenReturn(false);
        visibility = ObjectVisibility.forClient(client);
    }

    /** Tile poly whose bounding-box centroid lands at the requested
     *  pixel — gives us deterministic on-canvas projections for
     *  viewport / menu / HUD tests. */
    private static Shape hullAt(int cx, int cy)
    {
        Polygon p = new Polygon();
        p.addPoint(cx - 5, cy - 5);
        p.addPoint(cx + 5, cy - 5);
        p.addPoint(cx + 5, cy + 5);
        p.addPoint(cx - 5, cy + 5);
        return p;
    }

    @Test
    public void alwaysVisibleStubReturnsNullForEverything()
    {
        ObjectVisibility stub = ObjectVisibility.alwaysVisible();
        assertNull(stub.whyHidden(TARGET_TILE, null, null, null));
        assertNull(stub.whyHidden(TARGET_TILE, hullAt(0, 0), player, null));
    }

    @Test
    public void nullInputsReturnNullInput()
    {
        assertEquals(ObjectVisibility.Reason.NULL_INPUT,
            visibility.whyHidden(null, hullAt(100, 100), player, null));
        assertEquals(ObjectVisibility.Reason.NULL_INPUT,
            visibility.whyHidden(TARGET_TILE, hullAt(100, 100), null, null));
    }

    @Test
    public void planeMismatchCulled()
    {
        WorldPoint differentPlane = new WorldPoint(105, 100, 1);
        assertEquals(ObjectVisibility.Reason.PLANE_MISMATCH,
            visibility.whyHidden(differentPlane, hullAt(100, 100), player, null));
    }

    @Test
    public void notReachableCulledWhenReachMapEmpty()
    {
        // Reachability.compute with depth < 0 returns an empty map
        // (no visited tiles). The target tile is therefore unreachable.
        ReachabilityMap empty = Reachability.compute(null, PLAYER_TILE, -1);
        assertEquals(ObjectVisibility.Reason.NOT_REACHABLE,
            visibility.whyHidden(TARGET_TILE, hullAt(100, 100), player, empty));
    }

    @Test
    public void nullReachSkipsReachabilityStage()
    {
        // null reach map = "can't validate, allow" — the rest of the
        // pipeline should still run and accept (visible hull inside
        // viewport, no menu/HUD).
        assertNull(visibility.whyHidden(TARGET_TILE, hullAt(100, 100), player, null));
    }

    @Test
    public void offCanvasWhenHullNull()
    {
        assertEquals(ObjectVisibility.Reason.OFF_CANVAS,
            visibility.whyHidden(TARGET_TILE, null, player, null));
    }

    @Test
    public void offCanvasWhenHullEmpty()
    {
        // A hull with empty bounds also can't be projected onto
        // anything useful.
        Polygon empty = new Polygon();
        assertEquals(ObjectVisibility.Reason.OFF_CANVAS,
            visibility.whyHidden(TARGET_TILE, empty, player, null));
    }

    @Test
    public void outsideViewportCulled()
    {
        // Viewport: x=[10,610), y=[10,410). Centroid at (5, 5) is left
        // of and above the viewport.
        Shape farLeft = hullAt(5, 5);
        assertEquals(ObjectVisibility.Reason.OUTSIDE_VIEWPORT,
            visibility.whyHidden(TARGET_TILE, farLeft, player, null));
    }

    @Test
    public void underOpenMenuCulled()
    {
        // Centroid at (200, 200), inside viewport. Open menu covers
        // (150, 150) → (300, 300).
        when(client.isMenuOpen()).thenReturn(true);
        Menu menu = mock(Menu.class);
        when(menu.getMenuX()).thenReturn(150);
        when(menu.getMenuY()).thenReturn(150);
        when(menu.getMenuWidth()).thenReturn(150);
        when(menu.getMenuHeight()).thenReturn(150);
        when(client.getMenu()).thenReturn(menu);
        assertEquals(ObjectVisibility.Reason.UNDER_OPEN_MENU,
            visibility.whyHidden(TARGET_TILE, hullAt(200, 200), player, null));
    }

    @Test
    public void menuCheckSkippedWhenMenuClosed()
    {
        // isMenuOpen=false → don't even consult the menu. Centroid
        // inside viewport → visible.
        when(client.isMenuOpen()).thenReturn(false);
        assertNull(visibility.whyHidden(TARGET_TILE, hullAt(200, 200), player, null));
    }

    @Test
    public void hudOcclusionSkippedInFixedMode()
    {
        // Resized=false → fixed mode, viewport rect already excludes
        // chrome. HUD walk is a no-op.
        when(client.isResized()).thenReturn(false);
        assertNull(visibility.whyHidden(TARGET_TILE, hullAt(200, 200), player, null));
    }

    // — Reason classification —

    @Test
    public void rotationFixesPureProjectionFailures()
    {
        assertTrue(ObjectVisibility.Reason.OFF_CANVAS.fixableByRotation());
        assertTrue(ObjectVisibility.Reason.OUTSIDE_VIEWPORT.fixableByRotation());
    }

    @Test
    public void rotationCannotFixOcclusionOrUnreachable()
    {
        assertFalse(ObjectVisibility.Reason.UNDER_OPEN_MENU.fixableByRotation());
        assertFalse(ObjectVisibility.Reason.UNDER_HUD.fixableByRotation());
        assertFalse(ObjectVisibility.Reason.NOT_REACHABLE.fixableByRotation());
        assertFalse(ObjectVisibility.Reason.PLANE_MISMATCH.fixableByRotation());
        assertFalse(ObjectVisibility.Reason.NULL_INPUT.fixableByRotation());
    }

    @Test
    public void canSeeShortcutEqualsWhyHiddenIsNull()
    {
        // canSee is just a Boolean wrapper around whyHidden — pin
        // the equivalence so callers can rely on it.
        assertTrue(visibility.canSee(TARGET_TILE, hullAt(200, 200), player, null));
        assertFalse(visibility.canSee(TARGET_TILE, null, player, null));
    }
}
