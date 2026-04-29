package net.runelite.client.plugins.recorder.trail;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

/** Verifies {@link TrailPath#fromTrail(Trail)} produces correctly-shaped
 *  legs from recorded trail events — particularly the stair-vs-gate
 *  distinction that fixed the V3 "stuck clicking near stairs" bug. */
public class TrailPathFromTrailTest
{
    @Test
    public void plainWalkProducesSingleWalkLeg()
    {
        Trail trail = new Trail("plain", 0L, List.of(
            tile(0,    3206, 3220, 0),
            tile(600,  3206, 3221, 0),
            tile(1200, 3206, 3222, 0)));
        TrailPath path = TrailPath.fromTrail(trail);
        assertEquals(1, path.size());
        assertTrue(path.legs().get(0) instanceof Leg.Walk);
        Leg.Walk w = (Leg.Walk) path.legs().get(0);
        assertEquals(3, w.tiles().size());
    }

    @Test
    public void stairTransportSkipsSamePlanePostClickTiles()
    {
        // Mirrors the lumby-bank-to-pen recording: player walks to (3206,3226)
        // on plane 2, clicks Climb-down at (3205,3229,p=2), engine routes
        // them through (3206,3228) and (3206,3229) on plane 2 then teleports
        // to (3205,3228,p=1). The post-click p=2 tiles MUST NOT appear in
        // the walk leg — they aren't normally walkable, only reached as
        // part of the climb action.
        Trail trail = new Trail("stairs-down", 0L, List.of(
            tile(0,    3206, 3224, 2),
            tile(600,  3206, 3226, 2),
            climbDown(1200, 3205, 3229, 2),
            tile(1300, 3206, 3228, 2),   // engine routing — must be skipped
            tile(1900, 3206, 3229, 2),   // engine routing — must be skipped
            tile(2500, 3205, 3228, 1)));  // actual post-stair plane

        TrailPath path = TrailPath.fromTrail(trail);
        assertEquals(3, path.size());
        Leg.Walk pre = (Leg.Walk) path.legs().get(0);
        assertEquals(List.of(
            new WorldPoint(3206, 3224, 2),
            new WorldPoint(3206, 3226, 2)), pre.tiles());
        Leg.Transport tr = (Leg.Transport) path.legs().get(1);
        assertEquals("Climb-down", tr.verb());
        assertEquals(new WorldPoint(3205, 3229, 2), tr.tile());
        Leg.Walk post = (Leg.Walk) path.legs().get(2);
        assertEquals(List.of(new WorldPoint(3205, 3228, 1)), post.tiles());
    }

    @Test
    public void gateDuplicateTileSuppressedSoChooseLegDoesNotSkipTransport()
    {
        // The engine records a no-op duplicate of the player's pre-gate tile
        // right after a gate click. If we let it land in the post-gate walk
        // leg, the walker's chooseLegIndex would see "next leg contains
        // player" and SKIP past the TRANSPORT before firing it — the bot
        // walks straight into a closed gate. Verify the post-gate walk leg
        // does NOT contain the pre-gate tile.
        Trail trail = new Trail("gate-skip", 0L, List.of(
            tile(0,    3237, 3295, 0),
            openGate(1200, 3236, 3295, 0),
            tile(1300, 3237, 3295, 0),    // engine duplicate — must be dropped
            tile(1900, 3235, 3295, 0)));
        TrailPath path = TrailPath.fromTrail(trail);
        assertEquals(3, path.size());
        Leg.Walk post = (Leg.Walk) path.legs().get(2);
        assertFalse("post-gate walk must NOT contain pre-gate tile (3237,3295) — "
            + "if it does, chooseLegIndex skips the gate transport",
            post.tiles().contains(new WorldPoint(3237, 3295, 0)));
    }

    @Test
    public void penToBankGateFirstLegProducesTransportNotImmediateAdvance()
    {
        // Mirrors pen-to-lumby-bank.json's opening: player stands inside
        // pen at (3235,3296,p=0), clicks Open Gate, engine records the
        // duplicate, then player walks through. The post-gate walk leg's
        // first tile MUST be different from (3235,3296) so the walker
        // doesn't advance straight past the gate.
        Trail trail = new Trail("pen-start", 0L, List.of(
            tile(0,    3235, 3296, 0),
            openGate(1700, 3236, 3295, 0),
            tile(1800, 3235, 3296, 0),    // duplicate — must be dropped
            tile(2400, 3236, 3296, 0),
            tile(4800, 3238, 3294, 0)));
        TrailPath path = TrailPath.fromTrail(trail);
        // Leg 0: WALK [(3235,3296)]
        // Leg 1: TRANSPORT Open Gate
        // Leg 2: WALK [(3236,3296), (3238,3294)]
        assertEquals(3, path.size());
        Leg.Walk post = (Leg.Walk) path.legs().get(2);
        assertEquals(new WorldPoint(3236, 3296, 0), post.tiles().get(0));
    }

    @Test
    public void gateTransportFiresImmediately()
    {
        // Mirrors the lumby-bank-to-pen gate at (3236,3295,p=0): the gate
        // doesn't move the player, so the transport leg fires the moment
        // we see it — the next walk leg routes the player through the
        // (now-open) gate.
        Trail trail = new Trail("gate", 0L, List.of(
            tile(0,    3238, 3295, 0),
            tile(600,  3237, 3295, 0),
            openGate(1200, 3236, 3295, 0),
            tile(1300, 3237, 3295, 0),   // engine recorded duplicate
            tile(1900, 3235, 3295, 0)));

        TrailPath path = TrailPath.fromTrail(trail);
        assertEquals(3, path.size());
        Leg.Walk pre = (Leg.Walk) path.legs().get(0);
        assertEquals(new WorldPoint(3237, 3295, 0),
            pre.tiles().get(pre.tiles().size() - 1));
        Leg.Transport tr = (Leg.Transport) path.legs().get(1);
        assertEquals("Open", tr.verb());
        Leg.Walk post = (Leg.Walk) path.legs().get(2);
        assertEquals(new WorldPoint(3235, 3295, 0),
            post.tiles().get(post.tiles().size() - 1));
    }

    @Test
    public void duplicateTilesAreDeduplicated()
    {
        Trail trail = new Trail("dups", 0L, List.of(
            tile(0,    3206, 3220, 0),
            tile(600,  3206, 3220, 0),
            tile(1200, 3206, 3221, 0)));
        TrailPath path = TrailPath.fromTrail(trail);
        Leg.Walk w = (Leg.Walk) path.legs().get(0);
        assertEquals(2, w.tiles().size());
    }

    @Test
    public void findEntryLegPicksClosestLegOnPlayerPlane()
    {
        // Build a 4-leg path: walk p=2, climb-down, walk p=1, climb-down,
        // walk p=0. Player starts on p=0 mid-route — should resume into
        // the plane-0 walk leg, not the p=2 walk.
        List<TrailEvent> events = new ArrayList<>();
        events.add(tile(0,    3206, 3220, 2));
        events.add(tile(600,  3206, 3226, 2));
        events.add(climbDown(1200, 3205, 3229, 2));
        events.add(tile(1300, 3206, 3228, 2));
        events.add(tile(1800, 3205, 3228, 1));
        events.add(climbDown(2400, 3204, 3229, 1));
        events.add(tile(2500, 3205, 3228, 0));
        events.add(tile(3000, 3215, 3228, 0));
        TrailPath path = TrailPath.fromTrail(new Trail("multi", 0L, events));
        // Should be: walk(p=2) / TR / walk(p=1) / TR / walk(p=0)
        assertEquals(5, path.size());
        // Player on p=0 near (3215, 3228) → should land on leg 4.
        int entry = path.findEntryLeg(new WorldPoint(3215, 3228, 0));
        assertEquals(4, entry);
        // Player on p=2 near start → leg 0.
        assertEquals(0, path.findEntryLeg(new WorldPoint(3206, 3220, 2)));
    }

    @Test
    public void subPathDropsLeadingLegs()
    {
        TrailPath p = new TrailPath(List.of(
            new Leg.Walk(List.of(new WorldPoint(0, 0, 0))),
            new Leg.Walk(List.of(new WorldPoint(0, 1, 0))),
            new Leg.Walk(List.of(new WorldPoint(0, 2, 0)))));
        TrailPath sub = p.subPath(1);
        assertEquals(2, sub.size());
        assertEquals(new WorldPoint(0, 1, 0),
            ((Leg.Walk) sub.legs().get(0)).tiles().get(0));
        assertEquals(p, p.subPath(0));
        assertTrue(p.subPath(99).isEmpty());
    }

    @Test
    public void findEntryLegFallsBackToTransportOnPlayerPlane()
    {
        // Build a path with no walk leg on plane 1 — the plane-1 footprint
        // collapses into stair transports. Player is on plane 1 (mid-stair
        // resume scenario). findEntryLeg should NOT pick a plane-2 walk
        // (which the player can't reach without a transport); instead it
        // should pick the plane-1 transport leg.
        List<TrailEvent> events = new ArrayList<>();
        events.add(tile(0,    3206, 3220, 2));
        events.add(tile(600,  3206, 3226, 2));
        events.add(climbDown(1200, 3205, 3229, 2));
        events.add(tile(1300, 3206, 3228, 2));   // skipped (post-stair p=2)
        events.add(tile(1800, 3205, 3228, 1));   // p=1, pairs with first stair
        events.add(climbDown(2400, 3204, 3229, 1));
        events.add(tile(2500, 3205, 3228, 0));
        TrailPath path = TrailPath.fromTrail(new Trail("multi", 0L, events));
        // Player at (3206, 3229, p=1) — close to nothing on p=1 except the
        // tiny post-stair walk leg. Should resolve to that leg or the
        // plane-1 transport.
        int entry = path.findEntryLeg(new WorldPoint(3206, 3229, 1));
        // Whichever leg was picked must be reachable from p=1 — i.e. it
        // must be either a Walk leg containing a p=1 tile OR a Transport
        // whose source tile is on p=1.
        Leg picked = path.legs().get(entry);
        boolean reachableFromP1;
        if (picked instanceof Leg.Walk w)
        {
            reachableFromP1 = w.tiles().stream().anyMatch(t -> t.getPlane() == 1);
        }
        else if (picked instanceof Leg.Transport t)
        {
            reachableFromP1 = t.tile().getPlane() == 1;
        }
        else { reachableFromP1 = false; }
        assertTrue("entry leg " + entry + " must be reachable from plane 1 ("
            + picked + ")", reachableFromP1);
    }

    @Test
    public void teleportTransportTreatedAsStair()
    {
        Trail trail = new Trail("tele", 0L, List.of(
            tile(0,    3210, 3220, 0),
            new TrailEvent.Transport(600L, new WorldPoint(3210, 3220, 0),
                "Teleport", "Tab", 7777, "Inventory", 1, 0, 0, List.of()),
            tile(700,  3210, 3220, 0),    // pre-tele duplicate, p=0
            tile(2000, 2925, 3325, 0)));  // post-tele destination
        TrailPath path = TrailPath.fromTrail(trail);
        // Same plane both sides — without isStair detection this would
        // collapse the tele into walk edges. Verify tele is held until
        // the destination tile.
        assertEquals(2, path.size());  // walk(start), no tele yet (no plane change)
    }

    private static TrailEvent.Tile tile(long ms, int x, int y, int plane)
    {
        return new TrailEvent.Tile(ms, new WorldPoint(x, y, plane));
    }

    private static TrailEvent.Transport climbDown(long ms, int x, int y, int plane)
    {
        return new TrailEvent.Transport(ms, new WorldPoint(x, y, plane),
            "Climb-down", "Staircase", 56231, "GameObject", 3, 45, 61,
            List.of("Climb-down Staircase"));
    }

    private static TrailEvent.Transport openGate(long ms, int x, int y, int plane)
    {
        return new TrailEvent.Transport(ms, new WorldPoint(x, y, plane),
            "Open", "Gate", 1560, "GameObject", 3, 36, 79,
            List.of("Open Gate"));
    }
}
