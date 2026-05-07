package net.runelite.client.plugins.recorder.nav;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.coords.WorldPoint;
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
