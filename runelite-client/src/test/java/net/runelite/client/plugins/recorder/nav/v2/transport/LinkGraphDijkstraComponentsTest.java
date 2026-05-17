package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.List;
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

/** Integration tests for {@link LinkGraphDijkstra}'s collision-aware
 *  walk-edge filter.
 *
 *  <p>Fixture layout (all in region 50,50 — base coords 3200,3200):
 *  <ul>
 *    <li>South half: y=0..4 walkable.</li>
 *    <li>Wall row: y=5 fully blocked across the test corridor.</li>
 *    <li>North half: y=6..10 walkable.</li>
 *    <li>One DOOR transport at (3210, 3204, p=0) → (3210, 3206, p=0)
 *        — the only edge that bridges the components.</li>
 *  </ul>
 *
 *  <p>With components: Dijkstra refuses to add a direct walk edge
 *  across the wall; the only path is via the transport.
 *  Without components: Dijkstra picks the (abstract-cheaper) direct
 *  walk and the BFS layer would later prove it impossible. */
public class LinkGraphDijkstraComponentsTest
{
    private static final int RX = 50;
    private static final int RY = 50;
    private static final int BASE_X = RX * 64;
    private static final int BASE_Y = RY * 64;

    private static TransportLink doorLink(WorldPoint from, WorldPoint to)
    {
        return new TransportLink(from, to, TransportType.DOOR,
            Optional.of(9398), Optional.of("Open Door"),
            TransportRequirement.NONE, 1, false, "test.tsv", 1);
    }

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

    /** Build the standard wall-and-bridge fixture. */
    private static GlobalCollisionSnapshot wallAndBridgeSnapshot()
    {
        boolean[][][] walkable = new boolean[1][64][64];
        for (int x = 0; x <= 20; x++)
        {
            for (int y = 0; y <= 10; y++)
            {
                if (y == 5) continue;  // wall row
                walkable[0][x][y] = true;
            }
        }
        return GlobalCollisionSnapshot.forTestingWalkable(RX, RY, walkable);
    }

    @Test
    public void withComponents_picksTransportAcrossWall()
    {
        GlobalCollisionSnapshot snap = wallAndBridgeSnapshot();
        ConnectivityComponents components = ConnectivityComponents.fromSnapshot(snap);

        WorldPoint start = new WorldPoint(BASE_X, BASE_Y, 0);
        WorldPoint target = new WorldPoint(BASE_X, BASE_Y + 10, 0);
        WorldPoint doorFrom = new WorldPoint(BASE_X + 10, BASE_Y + 4, 0);
        WorldPoint doorTo = new WorldPoint(BASE_X + 10, BASE_Y + 6, 0);

        // Sanity: start and target really are in different components.
        assertTrue("start in south component",
            components.componentOf(start) >= 0);
        assertTrue("target in north component",
            components.componentOf(target) >= 0);
        assertNotEquals("wall partitions start vs target",
            components.componentOf(start), components.componentOf(target));

        TransportTable table = new TransportTable(List.of(doorLink(doorFrom, doorTo)), 0);

        LinkGraphDijkstra.SkeletonResult r = LinkGraphDijkstra.findRouteSkeleton(
            anyCtx(), table, start, target, components);

        assertEquals(LinkGraphDijkstra.Status.OK, r.status());
        long transports = r.nodes().stream()
            .filter(n -> n.kind() == LinkGraphDijkstra.NodeKind.TRANSPORT)
            .count();
        assertEquals("with components, only path crosses the bridge",
            1L, transports);
        // First transport's from/to should be our bridge endpoints.
        LinkGraphDijkstra.SkeletonNode tNode = r.nodes().stream()
            .filter(n -> n.kind() == LinkGraphDijkstra.NodeKind.TRANSPORT)
            .findFirst().orElseThrow();
        assertEquals(doorFrom, tNode.transport().from());
        assertEquals(doorTo, tNode.transport().to());
    }

    @Test
    public void withoutComponents_picksDirectWalk()
    {
        // Same fixture, but pass null components. Dijkstra reverts to
        // collision-blind walk edges and picks the abstract-cheaper
        // direct walk. This is the regression guard that the new
        // overload truly preserves pre-fix behaviour when components
        // are absent (precompute window or test fixtures).
        GlobalCollisionSnapshot snap = wallAndBridgeSnapshot();
        WorldPoint start = new WorldPoint(BASE_X, BASE_Y, 0);
        WorldPoint target = new WorldPoint(BASE_X, BASE_Y + 10, 0);
        WorldPoint doorFrom = new WorldPoint(BASE_X + 10, BASE_Y + 4, 0);
        WorldPoint doorTo = new WorldPoint(BASE_X + 10, BASE_Y + 6, 0);

        TransportTable table = new TransportTable(List.of(doorLink(doorFrom, doorTo)), 0);

        // Direct walk cost = chebyshev(start, target) = 10.
        // Via transport = chebyshev(start, doorFrom) + 1 + chebyshev(doorTo, target)
        //               = max(10, 4) + 1 + max(10, 4) = 10 + 1 + 10 = 21.
        // Direct walk wins ⇒ skeleton has zero transports.
        LinkGraphDijkstra.SkeletonResult r = LinkGraphDijkstra.findRouteSkeleton(
            anyCtx(), table, start, target);

        assertEquals(LinkGraphDijkstra.Status.OK, r.status());
        long transports = r.nodes().stream()
            .filter(n -> n.kind() == LinkGraphDijkstra.NodeKind.TRANSPORT)
            .count();
        assertEquals("without components, Dijkstra picks the direct walk",
            0L, transports);
    }

    @Test
    public void withComponents_butNoBridgingTransport_returnsUnreachable()
    {
        // Wall-and-bridge fixture but with NO transports in the table.
        // Components say south != north; no transport bridges them;
        // Dijkstra must declare UNREACHABLE — there is genuinely no
        // walkable route. This pins the failure mode: components
        // refuse the impossible walk and Dijkstra correctly reports
        // it (instead of returning a bogus walk that BFS later rejects).
        GlobalCollisionSnapshot snap = wallAndBridgeSnapshot();
        ConnectivityComponents components = ConnectivityComponents.fromSnapshot(snap);

        WorldPoint start = new WorldPoint(BASE_X, BASE_Y, 0);
        WorldPoint target = new WorldPoint(BASE_X, BASE_Y + 10, 0);
        TransportTable table = new TransportTable(java.util.Collections.emptyList(), 0);

        LinkGraphDijkstra.SkeletonResult r = LinkGraphDijkstra.findRouteSkeleton(
            anyCtx(), table, start, target, components);

        assertEquals(LinkGraphDijkstra.Status.UNREACHABLE, r.status());
    }
}
