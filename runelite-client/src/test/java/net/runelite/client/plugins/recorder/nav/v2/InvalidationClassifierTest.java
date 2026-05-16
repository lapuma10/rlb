package net.runelite.client.plugins.recorder.nav.v2;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class InvalidationClassifierTest
{
    private static final WorldPoint TILE = new WorldPoint(3208, 3217, 0);
    private static final WorldPoint PLAYER = new WorldPoint(3207, 3217, 0);

    private static InvalidationClassifier.FailureContext staticMismatch(WorldPoint tile)
    {
        return new InvalidationClassifier.FailureContext(
            tile, PLAYER, PLAYER, /*snapshotSaysWalkable*/ true,
            /*liveCollisionAllows*/ false, /*dynamicEntityOnTile*/ false,
            /*targetWasTransport*/ false, /*edge*/ null,
            /*expectedVerbStillPresent*/ true);
    }

    private static InvalidationClassifier.FailureContext dynamicBlocker(WorldPoint tile)
    {
        return new InvalidationClassifier.FailureContext(
            tile, PLAYER, PLAYER, true, true, /*dynamicEntityOnTile*/ true,
            false, null, true);
    }

    private static InvalidationClassifier.FailureContext transportMismatch(WorldPoint tile,
                                                                          TransportEdge edge)
    {
        return new InvalidationClassifier.FailureContext(
            tile, PLAYER, PLAYER, true, true, false,
            /*targetWasTransport*/ true, edge,
            /*expectedVerbStillPresent*/ false);
    }

    private static InvalidationClassifier.FailureContext unknown(WorldPoint tile)
    {
        // Snapshot agrees with live, no entity, no transport — only signal
        // is "click happened, no progress."
        return new InvalidationClassifier.FailureContext(
            tile, PLAYER, PLAYER, true, true, false, false, null, true);
    }

    private static TransportEdge sampleEdge()
    {
        return new TransportEdge(
            new WorldPoint(3208, 3216, 0), new WorldPoint(3208, 3218, 0),
            1530, "Door", "Open", 0, 0, "GameObject",
            new WorldPoint(3208, 3216, 0), 12850, 1, 1L, 200L);
    }

    @Test
    public void staticCollisionMismatch_returnsStaticClass_andBlacklists()
    {
        InvalidationClassifier c = new InvalidationClassifier();
        InvalidationClassifier.FailureClass cls = c.classify(staticMismatch(TILE));
        assertEquals(InvalidationClassifier.FailureClass.STATIC_COLLISION_MISMATCH, cls);
        assertTrue("static collision mismatch must mark tile dirty (blacklist)",
            c.isBlacklisted(TILE));
    }

    @Test
    public void dynamicBlocker_returnsDynamicClass_addsTransientPenalty_notPersisted()
    {
        InvalidationClassifier c = new InvalidationClassifier();
        InvalidationClassifier.FailureClass cls = c.classify(dynamicBlocker(TILE));
        assertEquals(InvalidationClassifier.FailureClass.DYNAMIC_BLOCKER, cls);
        assertTrue("dynamic blocker must add a transient penalty",
            c.hasTransientPenalty(TILE));
        assertFalse("dynamic blocker must NOT permanently blacklist the tile",
            c.isBlacklisted(TILE));
    }

    @Test
    public void transportStateMismatch_returnsTransportClass_marksEdgeStale()
    {
        InvalidationClassifier c = new InvalidationClassifier();
        TransportEdge edge = sampleEdge();
        InvalidationClassifier.FailureClass cls = c.classify(transportMismatch(TILE, edge));
        assertEquals(InvalidationClassifier.FailureClass.TRANSPORT_STATE_MISMATCH, cls);
        assertTrue("transport state mismatch must mark the edge stale",
            c.isTransportStale(edge.key()));
    }

    @Test
    public void unknownFailure_incrementsCounter()
    {
        InvalidationClassifier c = new InvalidationClassifier();
        InvalidationClassifier.FailureClass cls = c.classify(unknown(TILE));
        assertEquals(InvalidationClassifier.FailureClass.UNKNOWN_FAILURE, cls);
        assertEquals(1, c.failureCount(TILE));
        assertFalse("one unknown failure does not blacklist", c.isBlacklisted(TILE));
    }

    @Test
    public void twoFailures_blacklistTileForCurrentRoute()
    {
        InvalidationClassifier c = new InvalidationClassifier();
        c.classify(unknown(TILE));
        c.classify(unknown(TILE));
        assertTrue("2nd failure on same tile must blacklist for the route attempt",
            c.isBlacklisted(TILE));
        assertEquals(2, c.failureCount(TILE));
    }

    @Test
    public void resetForNewRoute_clearsBlacklistAndTransientPenalties()
    {
        InvalidationClassifier c = new InvalidationClassifier();
        c.classify(staticMismatch(TILE));         // blacklists TILE
        c.classify(dynamicBlocker(new WorldPoint(3209, 3217, 0)));   // dynamic penalty
        c.resetForNewRoute();
        assertFalse(c.isBlacklisted(TILE));
        assertFalse(c.hasTransientPenalty(new WorldPoint(3209, 3217, 0)));
        assertEquals(0, c.failureCount(TILE));
    }

    @Test
    public void staleTransport_persistsAcrossRouteReset()
    {
        // Stale transports describe in-world state (door closed/locked); they
        // should persist across the attempt's blacklist reset because the
        // staleness is environmental, not attempt-local. The seed-pass /
        // re-observation step refreshes them.
        InvalidationClassifier c = new InvalidationClassifier();
        TransportEdge edge = sampleEdge();
        c.classify(transportMismatch(TILE, edge));
        c.resetForNewRoute();
        assertTrue("stale transport keys persist across route attempts",
            c.isTransportStale(edge.key()));
    }

    @Test
    public void classify_nullContext_returnsUnknown()
    {
        InvalidationClassifier c = new InvalidationClassifier();
        InvalidationClassifier.FailureClass cls = c.classify(null);
        assertNotNull(cls);
        assertEquals(InvalidationClassifier.FailureClass.UNKNOWN_FAILURE, cls);
    }

    // ─────────────────────────────────────────────────────────────────
    // Lane 5 plan Task 6: predicate-provider + correction-request shape.
    // ─────────────────────────────────────────────────────────────────

    @Test
    public void asTilePredicate_blacklistedTile_returnsFalse()
    {
        InvalidationClassifier c = new InvalidationClassifier();
        c.blacklistTile(TILE);
        java.util.function.Predicate<WorldPoint> pred = c.asTilePredicate();
        assertFalse("blacklisted tile must be rejected by the predicate",
            pred.test(TILE));
        assertTrue("non-blacklisted tile must be accepted",
            pred.test(new WorldPoint(3209, 3217, 0)));
    }

    @Test
    public void asTilePredicate_dynamicBlocker_addsTransientPenaltyToBlacklist()
    {
        // Lane 5 plan Task 6 — dynamic blocker adds a transient penalty.
        // The predicate must reject the tile while the penalty is live.
        InvalidationClassifier c = new InvalidationClassifier();
        c.classify(dynamicBlocker(TILE));
        java.util.function.Predicate<WorldPoint> pred = c.asTilePredicate();
        assertFalse("dynamic-blocker tile must be rejected by the predicate",
            pred.test(TILE));
    }

    @Test
    public void asTilePredicate_nullTile_returnsFalse()
    {
        InvalidationClassifier c = new InvalidationClassifier();
        java.util.function.Predicate<WorldPoint> pred = c.asTilePredicate();
        assertFalse(pred.test(null));
    }

    @Test
    public void buildCorrectionRequest_returnsTypedRequest()
    {
        // Lane 5 plan Task 6: classifier builds a typed
        // TransportCorrectionRequest the executor surfaces via tickResult.
        TransportEdge edge = sampleEdge();
        WorldPoint plannedTo = new WorldPoint(3208, 3218, 0);
        WorldPoint actualTo = new WorldPoint(3208, 3219, 1);
        net.runelite.client.plugins.recorder.nav.v2.TransportCorrectionRequest req =
            InvalidationClassifier.buildCorrectionRequest(
                edge, plannedTo, actualTo, edge.fromTile());
        assertNotNull(req);
        assertEquals(plannedTo, req.plannedTo());
        assertEquals(actualTo, req.actualTo());
        assertNotNull(req.edge());
        assertEquals(edge.fromTile(), req.edge().from());
        assertEquals(edge.toTile(), req.edge().to());
        assertEquals("Open", req.edge().action().orElse(null));
        assertEquals(1530, req.edge().objectId().orElseThrow().intValue());
        assertEquals(
            net.runelite.client.plugins.recorder.nav.v2.ReplanReason.TRANSPORT_UNAVAILABLE,
            req.inferredReason());
    }

    @Test
    public void buildCorrectionRequest_isPureBuilder_noSideEffects()
    {
        // The builder must NOT mutate the classifier's blacklist or any
        // transport store. It's a pure factory called by the executor.
        InvalidationClassifier c = new InvalidationClassifier();
        TransportEdge edge = sampleEdge();
        InvalidationClassifier.buildCorrectionRequest(
            edge, new WorldPoint(1, 2, 0), new WorldPoint(1, 2, 1), edge.fromTile());
        assertEquals(0, c.failureCount(edge.fromTile()));
        assertFalse(c.isBlacklisted(edge.fromTile()));
        assertFalse(c.isTransportStale(edge.key()));
    }
}
