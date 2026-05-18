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

/** Pen-route regression — pins the bank→pen route works end-to-end
 *  via collision-aware Dijkstra + cow-pen gate transport override.
 *
 *  <p>The route bank→pen crosses TWO collision-bound boundaries in
 *  static Skretzo data:
 *  <ol>
 *    <li>The Lumbridge town ↔ farm-area fence at y≈3263 — bridged by
 *        the cow-pen south gate (object 1559) at
 *        (3251, 3263)↔(3251, 3264). The gate is normally already open
 *        in-game; the override entry exists so the planner knows
 *        the opening is passable.</li>
 *    <li>The chicken pen south fence at y≈3295 — bridged by the
 *        chicken pen south gate (object 1560) at
 *        (3236, 3295)↔(3236, 3296).</li>
 *  </ol>
 *
 *  <p>This test pins:
 *  <ul>
 *    <li><b>With components</b>: Dijkstra returns OK and the skeleton
 *        uses the cow-pen gate transport to cross the fence.</li>
 *    <li><b>Without components</b>: Dijkstra picks the abstract-cheaper
 *        direct walk (zero transports) — captured as a regression
 *        guard against silently reverting to pre-fix behaviour.</li>
 *  </ul> */
public class LinkGraphDijkstraPenRouteRegressionTest
{
    private static final WorldPoint STAIR_BOTTOM = new WorldPoint(3205, 3228, 0);
    private static final WorldPoint PEN_TARGET = new WorldPoint(3235, 3295, 0);

    /** Cow-pen south gate (object 1559) — bridges town ↔ farm-area
     *  components per transports-overrides.tsv. */
    private static final int COW_PEN_GATE_OBJECT_ID = 1559;

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
    public void penRoute_withComponents_routesViaCowPenGate()
    {
        GlobalCollisionSnapshot snap = GlobalCollisionSnapshot.fromBundledResource();
        ConnectivityComponents components = ConnectivityComponents.fromSnapshot(snap);
        TransportTable table = TransportTable.loadDefaults();

        // Precondition: stair-bottom and pen-target are in different
        // components. The cow-pen gate transport in the override TSV
        // bridges them.
        int outsideId = components.componentOf(STAIR_BOTTOM);
        int insideId  = components.componentOf(PEN_TARGET);
        assertTrue("stair-bottom walkable", outsideId >= 0);
        assertTrue("pen target walkable", insideId >= 0);
        assertNotEquals(
            "premise: stair-bottom (town) and pen target (farm area) are in different components",
            outsideId, insideId);

        LinkGraphDijkstra.SkeletonResult r = LinkGraphDijkstra.findRouteSkeleton(
            anyCtx(), table, STAIR_BOTTOM, PEN_TARGET, components);

        assertEquals(
            "with components AND the cow-pen gate override, Dijkstra finds a valid route",
            LinkGraphDijkstra.Status.OK, r.status());

        boolean usesCowPenGate = r.nodes().stream()
            .filter(n -> n.kind() == LinkGraphDijkstra.NodeKind.TRANSPORT)
            .anyMatch(n -> n.transport() != null
                && n.transport().objectId().isPresent()
                && n.transport().objectId().get() == COW_PEN_GATE_OBJECT_ID);
        assertTrue(
            "skeleton must include the cow-pen south gate (object 1559) — it's the only "
                + "bridge across the town↔farm-area component boundary",
            usesCowPenGate);
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
