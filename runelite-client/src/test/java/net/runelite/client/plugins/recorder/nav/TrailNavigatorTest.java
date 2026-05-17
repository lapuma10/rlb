package net.runelite.client.plugins.recorder.nav;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.trail.Leg;
import net.runelite.client.plugins.recorder.trail.Trail;
import net.runelite.client.plugins.recorder.trail.TrailEvent;
import net.runelite.client.plugins.recorder.trail.TrailPath;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Unit tests for {@link TrailNavigator} — the V1 adapter that maps the
 *  Navigator interface onto the existing {@link TrailWalker} without
 *  modifying the walker. The walker's collaborator graph (Client,
 *  ClientThread, dispatcher) is heavy to stand up, so we drop in via
 *  {@link TrailNavigator.WalkerHook} and let the navigator's adapter
 *  layer be exercised in isolation. */
public class TrailNavigatorTest
{
    private TrailRegistry registry;
    private RecordingWalkerHook walker;
    private TrailNavigator nav;

    @Before
    public void setup() throws IOException
    {
        Path dir = Files.createTempDirectory("trail-nav-test-");
        registry = new TrailRegistry(dir);
        walker = new RecordingWalkerHook();
        nav = new TrailNavigator(registry, walker);
    }

    @Test
    public void tick_withTrailName_resolvesAndDelegatesToWalker() throws Exception
    {
        registry.save(sampleTrail("foo"));
        walker.nextStatus = TrailWalker.Status.IN_PROGRESS;

        NavStatus s = nav.tick(NavRequest.byTrail("foo", BehaviorMode.VARIED));

        assertEquals(NavStatus.RUNNING, s);
        assertEquals(1, walker.tickCalls.get());
        assertNotNull(walker.lastPath);
        assertFalse("walker should have received a non-empty path",
            walker.lastPath.isEmpty());
        assertTrue(nav.isBusy());
    }

    @Test
    public void tick_withoutTrailName_failsWithModeNotSupported() throws Exception
    {
        NavStatus s = nav.tick(NavRequest.toPoint(
            new WorldPoint(3206, 3220, 0), BehaviorMode.VARIED));

        assertEquals(NavStatus.FAILED, s);
        assertEquals("V1 must not delegate to the walker without a trail name",
            0, walker.tickCalls.get());
    }

    @Test
    public void tick_unknownTrail_returnsFailed() throws Exception
    {
        NavStatus s = nav.tick(NavRequest.byTrail("missing", BehaviorMode.VARIED));

        assertEquals(NavStatus.FAILED, s);
        assertEquals(0, walker.tickCalls.get());
    }

    @Test
    public void tick_walkerArrived_returnsArrivedAndClearsBusy() throws Exception
    {
        registry.save(sampleTrail("foo"));
        walker.nextStatus = TrailWalker.Status.ARRIVED;

        NavStatus s = nav.tick(NavRequest.byTrail("foo", BehaviorMode.VARIED));

        assertEquals(NavStatus.ARRIVED, s);
        assertFalse(nav.isBusy());
    }

    @Test
    public void tick_walkerStuck_returnsFailed() throws Exception
    {
        registry.save(sampleTrail("foo"));
        walker.nextStatus = TrailWalker.Status.STUCK;

        NavStatus s = nav.tick(NavRequest.byTrail("foo", BehaviorMode.VARIED));

        assertEquals(NavStatus.FAILED, s);
        assertFalse(nav.isBusy());
    }

    @Test
    public void tick_walkerError_returnsFailed() throws Exception
    {
        registry.save(sampleTrail("foo"));
        walker.nextStatus = TrailWalker.Status.ERROR;

        NavStatus s = nav.tick(NavRequest.byTrail("foo", BehaviorMode.VARIED));

        assertEquals(NavStatus.FAILED, s);
        assertFalse(nav.isBusy());
    }

    @Test
    public void cancel_propagatesToWalker() throws Exception
    {
        registry.save(sampleTrail("foo"));
        walker.nextStatus = TrailWalker.Status.IN_PROGRESS;
        nav.tick(NavRequest.byTrail("foo", BehaviorMode.VARIED));
        assertTrue(nav.isBusy());

        nav.cancel();

        assertEquals(1, walker.resetCalls.get());
        assertFalse(nav.isBusy());
    }

    @Test
    public void name_returnsTrailV1()
    {
        assertEquals("trail-v1", nav.name());
    }

    @Test
    public void tick_differentTrailName_resetsWalkerAndReResolves() throws Exception
    {
        registry.save(sampleTrail("a"));
        registry.save(sampleTrail("b"));
        walker.nextStatus = TrailWalker.Status.IN_PROGRESS;

        nav.tick(NavRequest.byTrail("a", BehaviorMode.VARIED));
        TrailPath pathA = walker.lastPath;

        nav.tick(NavRequest.byTrail("b", BehaviorMode.VARIED));
        TrailPath pathB = walker.lastPath;

        assertNotSame("switching trails must surface a fresh TrailPath",
            pathA, pathB);
        assertEquals("walker must be reset on trail switch",
            1, walker.resetCalls.get());
    }

    @Test
    public void tick_sameTrailName_reusesResolvedPath() throws Exception
    {
        registry.save(sampleTrail("a"));
        walker.nextStatus = TrailWalker.Status.IN_PROGRESS;

        nav.tick(NavRequest.byTrail("a", BehaviorMode.VARIED));
        TrailPath first = walker.lastPath;
        nav.tick(NavRequest.byTrail("a", BehaviorMode.VARIED));
        TrailPath second = walker.lastPath;

        assertSame("repeated ticks on the same trail must not re-resolve",
            first, second);
        assertEquals(0, walker.resetCalls.get());
    }

    // ── entry-leg selection tests ────────────────────────────────────────────

    /** Multi-leg trail. Player close to the walk segment starting at y=20.
     *  Walker should receive a subpath starting at that segment, not leg 0.
     *
     *  Trail leg layout (fromTrail output):
     *   0 walk  y=0..9
     *   1 transport  y=9 (Open gate)
     *   2 walk  y=10..19
     *   3 transport  y=19 (Open gate)
     *   4 walk  y=20..29
     *   5 transport  y=29 (Open gate)
     *   6 walk  y=30..39
     */
    @Test
    public void startsFromNearestLegWhenMidTrail() throws Exception
    {
        registry.save(multiLegTrail("mid"));
        walker.nextStatus = TrailWalker.Status.IN_PROGRESS;

        // Player at (100, 22, 0) is 2 tiles into the y=20..29 walk (leg 4).
        WorldPoint playerPos = new WorldPoint(100, 22, 0);
        TrailNavigator nav2 = new TrailNavigator(registry, walker, () -> playerPos);

        nav2.tick(NavRequest.byTrail("mid", BehaviorMode.VARIED));

        assertNotNull(walker.lastPath);
        // subPath(4): first leg is the walk starting at y=20.
        Leg firstLeg = walker.lastPath.legs().get(0);
        assertTrue("subpath must start at the y=20 walk leg or later",
            firstLeg instanceof Leg.Walk w && w.tiles().get(0).getY() >= 20);
    }

    /** Same trail but player 50 tiles away — no leg is within 15 tiles. */
    @Test
    public void failsWhenPlayerFarFromAnyLeg() throws Exception
    {
        registry.save(multiLegTrail("far"));
        walker.nextStatus = TrailWalker.Status.IN_PROGRESS;

        // Trail spans y=0..39 at x=100; player at (100, 200) is >15 tiles from any leg tile.
        WorldPoint farAway = new WorldPoint(100, 200, 0);
        TrailNavigator nav2 = new TrailNavigator(registry, walker, () -> farAway);

        NavStatus status = nav2.tick(NavRequest.byTrail("far", BehaviorMode.VARIED));

        assertEquals(NavStatus.FAILED, status);
        assertEquals("walker must not be invoked when player is off-trail",
            0, walker.tickCalls.get());
    }

    /** Player at the trail's first tile — must start at leg 0. */
    @Test
    public void startsFromLeg0WhenPlayerAtTrailStart() throws Exception
    {
        registry.save(multiLegTrail("start"));
        walker.nextStatus = TrailWalker.Status.IN_PROGRESS;

        // Leg 0 walk starts at (100, 0) — player stands on the first tile.
        WorldPoint atStart = new WorldPoint(100, 0, 0);
        TrailNavigator nav2 = new TrailNavigator(registry, walker, () -> atStart);

        nav2.tick(NavRequest.byTrail("start", BehaviorMode.VARIED));

        assertNotNull(walker.lastPath);
        Leg firstLeg = walker.lastPath.legs().get(0);
        assertTrue("must start at leg 0 (y=0)",
            firstLeg instanceof Leg.Walk w && w.tiles().get(0).getY() == 0);
    }

    /** Build a multi-leg trail as a Trail with TrailEvent entries.
     *  Pattern: walk(y=0..9) → Open gate at (100,10) → walk(y=10..19)
     *           → Open gate at (100,20) → walk(y=20..29) → Open gate at (100,30)
     *           → walk(y=30..39).
     *  fromTrail() emits each gate as a TRANSPORT leg, producing 7 legs total
     *  (alternating WALK / TRANSPORT / WALK / TRANSPORT / ...).
     *  Leg indices: 0=walk y0-9, 1=transport y10, 2=walk y10-19,
     *               3=transport y20, 4=walk y20-29, 5=transport y30,
     *               6=walk y30-39. */
    private static Trail multiLegTrail(String name)
    {
        List<TrailEvent> events = new ArrayList<>();
        long t = 0;
        int gateY = 10;
        for (int seg = 0; seg < 4; seg++)
        {
            // Walk segment: 10 tiles from y = seg*10 to y = seg*10+9
            for (int i = 0; i < 10; i++)
            {
                events.add(new TrailEvent.Tile(t, new WorldPoint(100, seg * 10 + i, 0)));
                t += 600;
            }
            if (seg < 3)
            {
                // Gate transport at the boundary tile
                WorldPoint gTile = new WorldPoint(100, seg * 10 + 9, 0);
                events.add(new TrailEvent.Transport(t, gTile, "Open", "gate",
                    1234 + seg, "GameObject", 0, 0, 0, List.of("Open")));
                t += 600;
            }
        }
        return new Trail(name, 0L, events);
    }

    private static Trail sampleTrail(String name)
    {
        return new Trail(name, 0L, List.of(
            new TrailEvent.Tile(0L, new WorldPoint(3206, 3220, 0)),
            new TrailEvent.Tile(600L, new WorldPoint(3206, 3221, 0)),
            new TrailEvent.Tile(1200L, new WorldPoint(3206, 3222, 0))));
    }

    private static final class RecordingWalkerHook implements TrailNavigator.WalkerHook
    {
        TrailWalker.Status nextStatus = TrailWalker.Status.IN_PROGRESS;
        TrailPath lastPath;
        final AtomicInteger tickCalls = new AtomicInteger();
        final AtomicInteger resetCalls = new AtomicInteger();

        @Override
        public TrailWalker.Status tick(TrailPath path)
        {
            tickCalls.incrementAndGet();
            lastPath = path;
            return nextStatus;
        }

        @Override
        public void reset()
        {
            resetCalls.incrementAndGet();
        }
    }
}
