package net.runelite.client.plugins.recorder.nav.v2.transport;

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

/** Pen-route regression — pins the topological-honesty behaviour of
 *  the collision-aware Dijkstra against REAL bundled Skretzo data.
 *
 *  <p>The route bank→pen on plane 0 (stair-bottom (3205, 3228) →
 *  chicken pen (3235, 3295)) genuinely has NO bridging transport in
 *  Skretzo's bundled data: the cow-pen / chicken-pen fence at y≈3263
 *  partitions the Lumbridge town component from the pen-area
 *  component, and the existing transports.tsv has no gate entries
 *  there. Adding the missing gate transports is a follow-up task
 *  (the data fix), separate from the Dijkstra-logic fix tested here.
 *
 *  <p>What this regression pins:
 *  <ul>
 *    <li><b>Without components</b>: Dijkstra optimistically returns OK
 *        with a direct walk — the pre-2026-05-17 lie that BFS later
 *        had to invalidate. Status was wrong; the route was bogus.</li>
 *    <li><b>With components</b>: Dijkstra correctly returns UNREACHABLE
 *        immediately. No bogus skeleton handed downstream, no BFS
 *        round-trip on a path that can't exist. Honest topology.</li>
 *  </ul>
 *
 *  <p>Once the missing cow-pen gate transports are added to
 *  transports-overrides.tsv, this test will start failing on the
 *  "with components" branch (because the route will succeed). At that
 *  point: update the test to assert OK + the gate transport in the
 *  skeleton. */
public class LinkGraphDijkstraPenRouteRegressionTest
{
    private static final WorldPoint STAIR_BOTTOM = new WorldPoint(3205, 3228, 0);
    private static final WorldPoint PEN_TARGET = new WorldPoint(3235, 3295, 0);

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

    @Test
    public void penRoute_withComponents_returnsUnreachable_honestly()
    {
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
        ConnectivityComponents components = ConnectivityComponents.fromSnapshot(snap);
        TransportTable table = TransportTable.loadDefaults();

        // Precondition: stair-bottom and pen-target are in different
        // components. If this ever changes (e.g. data update), the
        // test premise is gone and someone should re-examine.
        int outsideId = components.componentOf(STAIR_BOTTOM);
        int insideId  = components.componentOf(PEN_TARGET);
        assertTrue("stair-bottom walkable", outsideId >= 0);
        assertTrue("pen target walkable", insideId >= 0);
        assertNotEquals(
            "premise: stair-bottom and pen target are in different components",
            outsideId, insideId);

        LinkGraphDijkstra.SkeletonResult r = LinkGraphDijkstra.findRouteSkeleton(
            anyCtx(), table, STAIR_BOTTOM, PEN_TARGET, components);

        // The honest answer: there is no walkable + transport route.
        // The override pen-gate (1560) has BOTH endpoints inside the
        // pen-area component, so it doesn't bridge. No other transport
        // crosses the cow-pen fence in current Skretzo data.
        assertEquals(
            "with components, Dijkstra is honest — UNREACHABLE when no bridging transport exists",
            LinkGraphDijkstra.Status.UNREACHABLE, r.status());
    }

    @Test
    public void penRoute_withoutComponents_returnsBogusWalk()
    {
        // Pre-fix behaviour: Dijkstra returns OK with a zero-transport
        // direct-walk skeleton. BFS at the next layer would then
        // discover the path is impossible (fence) and the planner
        // would report TARGET_UNREACHABLE — but only after wasting
        // a BFS round-trip. Captured here so future changes don't
        // silently regress to the pre-fix behaviour.
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
        TransportTable table = TransportTable.loadDefaults();

        LinkGraphDijkstra.SkeletonResult r = LinkGraphDijkstra.findRouteSkeleton(
            anyCtx(), table, STAIR_BOTTOM, PEN_TARGET);

        assertEquals(LinkGraphDijkstra.Status.OK, r.status());
        long transports = r.nodes().stream()
            .filter(n -> n.kind() == LinkGraphDijkstra.NodeKind.TRANSPORT)
            .count();
        assertEquals(
            "pre-fix Dijkstra picks the abstract direct walk — zero transports in the skeleton",
            0L, transports);
    }
}
