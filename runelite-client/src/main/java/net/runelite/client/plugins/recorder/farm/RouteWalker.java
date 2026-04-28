package net.runelite.client.plugins.recorder.farm;

import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

/**
 * Drives a single waypoint per tick. The outer state machine pumps
 * {@link #tick(Waypoint)} repeatedly until {@link #arrived(Waypoint)}
 * returns true, then advances to the next waypoint.
 *
 * <p>For WALK / WALK_AREA: samples a random tile that passes the
 * (same plane, projects to canvas, in viewport) filter, minimap-clicks
 * it. Re-rolls every tick.
 *
 * <p>For TRANSPORT: pre-checks the verb via {@link TransportResolver}.
 * If the object exposing that verb isn't present (gate already open or
 * not yet loaded), arrival is left to the outer state machine. Otherwise
 * dispatches a click on the object hull.
 */
@Slf4j
public final class RouteWalker
{
    private final Client client;
    private final HumanizedInputDispatcher dispatcher;
    private final TransportResolver resolver;
    private final Random rng = new Random();

    /** The most recent TRANSPORT waypoint we ticked. {@link #arrived} for
     *  OPEN/INTERACT requires this to match the candidate waypoint, so a
     *  player approaching a closed gate isn't mistaken for "already crossed"
     *  before {@link #tick} has had a chance to click. */
    private Waypoint lastTickedTransport;

    public RouteWalker(Client client, HumanizedInputDispatcher dispatcher,
                       TransportResolver resolver)
    {
        this.client = client;
        this.dispatcher = dispatcher;
        this.resolver = resolver;
    }

    /** Drive {@code wp} once. Caller polls {@link #arrived(Waypoint)} to
     *  decide when to advance. */
    public void tick(Waypoint wp) throws InterruptedException
    {
        switch (wp.kind())
        {
            case WALK:
            case WALK_AREA:
                tickWalk(wp.area());
                break;
            case TRANSPORT:
                tickTransport(wp);
                break;
        }
    }

    public boolean arrived(Waypoint wp)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return false;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return false;
        switch (wp.kind())
        {
            case WALK:
            case WALK_AREA:
            {
                WorldArea a = wp.area();
                if (a == null) return false;
                return here.getPlane() == a.getPlane()
                    && here.getX() >= a.getX() && here.getX() < a.getX() + a.getWidth()
                    && here.getY() >= a.getY() && here.getY() < a.getY() + a.getHeight();
            }
            case TRANSPORT:
            {
                if (wp.transportKind() == Waypoint.TransportKind.CLIMB_UP
                    || wp.transportKind() == Waypoint.TransportKind.CLIMB_DOWN)
                {
                    // Plane changed = transit complete.
                    return here.getPlane() != wp.tile().getPlane();
                }
                // OPEN / INTERACT — require that we actually ticked this exact transport
                // at least once before we count "near the wall" as arrived. Otherwise a
                // player approaching a closed gate is silently advanced past the
                // waypoint without ever clicking it.
                if (lastTickedTransport != wp) return false;
                return Math.abs(here.getX() - wp.tile().getX()) <= 1
                    && Math.abs(here.getY() - wp.tile().getY()) <= 1
                    && here.getPlane() == wp.tile().getPlane();
            }
        }
        return false;
    }

    private void tickWalk(WorldArea area) throws InterruptedException
    {
        if (area == null) return;
        Player self = client.getLocalPlayer();
        if (self == null) return;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return;
        WorldPoint pick = sampleTile(area, rng,
            tile -> tile.getPlane() == here.getPlane() && projectsToCanvas(tile));
        if (pick == null) return; // try again next tick
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        LocalPoint lp = LocalPoint.fromWorld(wv, pick);
        if (lp == null) return;
        Point cp = Perspective.localToCanvas(client, lp, pick.getPlane());
        if (cp == null) return;
        dispatcher.clickCanvas(cp.getX(), cp.getY());
    }

    private boolean projectsToCanvas(WorldPoint wp)
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return false;
        LocalPoint lp = LocalPoint.fromWorld(wv, wp);
        if (lp == null) return false;
        Point cp = Perspective.localToCanvas(client, lp, wp.getPlane());
        if (cp == null) return false;
        int vx = client.getViewportXOffset();
        int vy = client.getViewportYOffset();
        int vw = client.getViewportWidth();
        int vh = client.getViewportHeight();
        return cp.getX() >= vx && cp.getX() < vx + vw
            && cp.getY() >= vy && cp.getY() < vy + vh;
    }

    private void tickTransport(Waypoint wp) throws InterruptedException
    {
        TransportResolver.Match m = resolver.findTransport(wp.tile(), wp.verb());
        if (!m.isSuccess())
        {
            // Verb absent → already-open / not yet loaded. Mark as handled
            // so arrived() can fire when the player crosses; the outer
            // state machine sees the player progress without us clicking.
            log.debug("transport at {} skipped (verb '{}' absent)", wp.tile(), wp.verb());
            lastTickedTransport = wp;
            return;
        }
        Rectangle b = clickTargetBounds(m);
        if (b == null) return;
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
        lastTickedTransport = wp;
    }

    private Rectangle clickTargetBounds(TransportResolver.Match m)
    {
        if (m.wallObject() != null)
        {
            Shape h = m.wallObject().getConvexHull();
            if (h != null) return h.getBounds();
        }
        if (m.gameObject() != null)
        {
            Shape h = m.gameObject().getConvexHull();
            if (h != null) return h.getBounds();
        }
        if (m.decorativeObject() != null)
        {
            var poly = m.decorativeObject().getCanvasTilePoly();
            if (poly != null) return poly.getBounds();
        }
        if (m.groundObject() != null)
        {
            var poly = m.groundObject().getCanvasTilePoly();
            if (poly != null) return poly.getBounds();
        }
        return null;
    }

    /** Pure tile sampler — package-private for tests. Returns null only
     *  when no tile in the area satisfies the predicate. Strategy: up to
     *  3*N random rolls, then fall back to a shuffled enumeration so we
     *  never miss a small accept set. */
    static WorldPoint sampleTile(WorldArea a, Random rng, Predicate<WorldPoint> accept)
    {
        int n = a.getWidth() * a.getHeight();
        int attempts = Math.max(8, n * 3);
        for (int i = 0; i < attempts; i++)
        {
            int x = a.getX() + rng.nextInt(a.getWidth());
            int y = a.getY() + rng.nextInt(a.getHeight());
            WorldPoint p = new WorldPoint(x, y, a.getPlane());
            if (accept.test(p)) return p;
        }
        // Last resort: enumerate every tile in random order.
        List<WorldPoint> all = new ArrayList<>(n);
        for (int dx = 0; dx < a.getWidth(); dx++)
            for (int dy = 0; dy < a.getHeight(); dy++)
                all.add(new WorldPoint(a.getX() + dx, a.getY() + dy, a.getPlane()));
        Collections.shuffle(all, rng);
        for (WorldPoint p : all) if (accept.test(p)) return p;
        return null;
    }
}
