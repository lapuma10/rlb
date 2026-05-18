package net.runelite.client.plugins.recorder.nav.v21;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Single round-trip test for the route-skeleton store per the spec
 *  ("no tests beyond round-trip"). Covers dirty bookkeeping, dedup
 *  by (routeKey, goalPlane, goalBucketX, goalBucketY), and JSON
 *  load-after-flush parity. */
public class RouteSkeletonStoreTest
{
	@Test
	public void recordAndRoundTripViaFile() throws Exception
	{
		Path tmp = Files.createTempFile("v21-skeletons-test", ".json");
		try
		{
			RouteSkeletonStore store = new RouteSkeletonStore();
			RouteSkeleton a = new RouteSkeleton(
				"pen_to_lumby_bank",
				new WorldPoint(3208, 3220, 2), 2,
				List.of("3204,3209,0|Climb-up|16683", "3204,3229,1|Climb-up|16672"),
				123456789L);
			store.recordSuccess(a);

			assertTrue(store.takeDirty());           // dirty after record
			assertFalse(store.takeDirty());          // cleared by take

			store.recordSuccess(a);                  // re-record (dedupe — same DedupKey)
			assertTrue(store.takeDirty());           // dirty again
			assertEquals(1, store.snapshot().size()); // dedupe keeps 1 entry

			store.flushTo(tmp);

			RouteSkeletonStore loaded = new RouteSkeletonStore();
			loaded.loadFrom(tmp);
			assertEquals(1, loaded.snapshot().size());
			RouteSkeleton parsed = loaded.snapshot().iterator().next();
			assertEquals("pen_to_lumby_bank", parsed.routeKey());
			assertEquals(new WorldPoint(3208, 3220, 2), parsed.goalCentroid());
			assertEquals(2, parsed.transportEdgeKeys().size());
			assertEquals("3204,3209,0|Climb-up|16683", parsed.transportEdgeKeys().get(0));
		}
		finally
		{
			Files.deleteIfExists(tmp);
		}
	}
}
