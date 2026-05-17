package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.Collections;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.v2.collision.ConnectivityComponents;
import net.runelite.client.plugins.recorder.nav.v2.collision.GlobalCollisionSnapshot;
import net.runelite.client.plugins.recorder.nav.v2.collision.PlayerState;
import net.runelite.client.plugins.recorder.nav.v2.collision.WorldSnapshot;
import net.runelite.client.plugins.recorder.nav.v2.predicate.NavigationContext;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Tripwire for the accepted live-overlay-passable regression (spec
 *  test plan #6 / spec §Tradeoffs accepted "Static topology only").
 *
 *  <p>Scenario: the GLOBAL collision snapshot says a tile is blocked
 *  (e.g. a closed door encoded into Skretzo's data). In real OSRS the
 *  door happens to be currently open and the live overlay would treat
 *  the tile as passable. The door is NOT in any transport TSV — it's
 *  a "passable opening that nobody modelled as a transport".
 *
 *  <p>Pre-2026-05-17 behaviour: BFS used the merged CollisionView (global
 *  + live) and would happily walk through, because the live overlay
 *  cleared the block. The planner succeeded.
 *
 *  <p>Post-fix behaviour: ConnectivityComponents is built from the
 *  STATIC global snapshot only. The door's tile is in a different
 *  component from its neighbours. LinkGraphDijkstra refuses to add
 *  a walk edge across; with no transport bridging the components,
 *  the planner returns UNREACHABLE — even though a live walk would
 *  have succeeded. This is the accepted regression.
 *
 *  <p>The test pins the trade-off so a future change can't silently
 *  re-broaden the Dijkstra layer's relationship with live overlays
 *  without acknowledging the design intent. If you find a real route
 *  hit by this regression in production, the fix is to add the door
 *  to {@code transports-overrides.tsv}, not to weaken the component
 *  filter. */
public class LinkGraphDijkstraLiveOverlayRegressionTest
{
    private static final int RX = 50;
    private static final int RY = 50;
    private static final int BASE_X = RX * 64;
    private static final int BASE_Y = RY * 64;

    private static NavigationContext anyCtx()
    {
        return new NavigationContext()
        {
            @Override public WorldSnapshot world() { return null; }
            @Override public PlayerState player()
            {
                return new TransportRequirementEvaluatorTest.StubPlayer();
            }
            @Override public NavRequest request() { return null; }
        };
    }

    /** Two walkable halves separated by a fully-blocked wall row at
     *  y=5. The static snapshot encodes the wall; components partition
     *  the two halves; no transport bridges them. We never construct
     *  the "live-passable" overlay here because it would be irrelevant:
     *  ConnectivityComponents reads the static snapshot directly and
     *  doesn't consult any live data by design. The test's purpose is
     *  to assert that even if such a configuration existed in
     *  production, the planner now refuses. */
    private static GlobalCollisionSnapshot staticBlockedSnapshot()
    {
        boolean[][][] walkable = new boolean[1][64][64];
        for (int x = 0; x <= 10; x++)
        {
            for (int y = 0; y <= 10; y++)
            {
                if (y == 5) continue;  // statically blocked wall
                walkable[0][x][y] = true;
            }
        }
        return GlobalCollisionSnapshot.forTestingWalkable(RX, RY, walkable);
    }

    @Test
    public void liveOverlayPassable_butStaticBlocked_noTransport_returnsUnreachable()
    {
        // The crux: components partition the two halves because the
        // STATIC data has a wall. Even if a hypothetical live overlay
        // would clear the wall tile, the planner refuses to add the
        // walk edge. With no transport bridging, the route is honestly
        // unreachable from the planner's perspective.
        GlobalCollisionSnapshot snap = staticBlockedSnapshot();
        ConnectivityComponents components = ConnectivityComponents.fromSnapshot(snap);

        WorldPoint south = new WorldPoint(BASE_X + 5, BASE_Y + 0, 0);
        WorldPoint north = new WorldPoint(BASE_X + 5, BASE_Y + 10, 0);

        assertTrue("south half walkable", components.componentOf(south) >= 0);
        assertTrue("north half walkable", components.componentOf(north) >= 0);
        assertNotEquals("static wall partitions the halves",
            components.componentOf(south), components.componentOf(north));

        TransportTable emptyTable = new TransportTable(Collections.emptyList(), 0);

        LinkGraphDijkstra.SkeletonResult r = LinkGraphDijkstra.findRouteSkeleton(
            anyCtx(), emptyTable, south, north, components);

        // Pre-fix: BFS-on-live would walk through, planner returned a
        // route. Post-fix: Dijkstra refuses because static components
        // disagree; no transport rescues it. Honest unreachable.
        assertEquals(
            "tripwire: components must not be widened to consult the live overlay "
                + "— if you change this, also remove the 'static topology only' note in the spec",
            LinkGraphDijkstra.Status.UNREACHABLE, r.status());
    }

    @Test
    public void mitigation_addingTransport_makesItReachableAgain()
    {
        // Mitigation path the spec recommends: add the missing opening
        // to transports-overrides.tsv. Once it's modelled as a transport,
        // Dijkstra bridges the components and the route succeeds. This
        // companion test pins the mitigation to make the spec's
        // recommendation visible in test form.
        GlobalCollisionSnapshot snap = staticBlockedSnapshot();
        ConnectivityComponents components = ConnectivityComponents.fromSnapshot(snap);

        WorldPoint south = new WorldPoint(BASE_X + 5, BASE_Y + 0, 0);
        WorldPoint north = new WorldPoint(BASE_X + 5, BASE_Y + 10, 0);
        // Bridge endpoints: one tile on each side of the wall.
        WorldPoint southOfWall = new WorldPoint(BASE_X + 5, BASE_Y + 4, 0);
        WorldPoint northOfWall = new WorldPoint(BASE_X + 5, BASE_Y + 6, 0);
        TransportLink bridge = new TransportLink(
            southOfWall, northOfWall, TransportType.DOOR,
            Optional.of(9398), Optional.of("Open Door"),
            TransportRequirement.NONE, 1, false, "test.tsv", 1);
        TransportTable table = new TransportTable(java.util.List.of(bridge), 0);

        LinkGraphDijkstra.SkeletonResult r = LinkGraphDijkstra.findRouteSkeleton(
            anyCtx(), table, south, north, components);

        assertEquals(LinkGraphDijkstra.Status.OK, r.status());
        long transports = r.nodes().stream()
            .filter(n -> n.kind() == LinkGraphDijkstra.NodeKind.TRANSPORT)
            .count();
        assertEquals("with the bridge transport, exactly one transport is in the skeleton",
            1L, transports);
    }
}
