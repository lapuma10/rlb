package net.runelite.client.plugins.recorder.nav.v21;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies the TTL-only blacklist semantics, dirty bookkeeping, JSON
 *  round-trip, goal-bucket separation, and origin-equivalence of
 *  {@link GoalDeadEndMemory} / {@link GoalDeadEndKey}. */
public class GoalDeadEndMemoryTest
{
	@Rule public TemporaryFolder tmp = new TemporaryFolder();

	private static final int OBJ_ID = 1559;
	private static final String VERB = "Open";
	private static final WorldPoint FROM = new WorldPoint(3211, 3242, 0);
	private static final WorldPoint GOAL = new WorldPoint(3231, 3295, 0);

	private static GoalDeadEndKey sampleKey()
	{
		return GoalDeadEndKey.fromEdge(null, sampleEdge(), GOAL);
	}

	private static TransportEdge sampleEdge()
	{
		WorldPoint to = new WorldPoint(FROM.getX() + 1, FROM.getY(), FROM.getPlane());
		return new TransportEdge(FROM, to, OBJ_ID, "Gate", VERB,
			0, 0, "GameObject", FROM,
			FROM.getRegionID(), 1, 0L, 0L);
	}

	// 1. Empty memory → isDeadEnd returns false.
	@Test
	public void emptyMemoryReportsNotDeadEnd()
	{
		GoalDeadEndMemory mem = new GoalDeadEndMemory();
		assertFalse(mem.isDeadEnd(sampleKey(), 1_000L));
	}

	// 2. After markDeadEnd(k, reason, t) → isDeadEnd(k, t+1) returns true.
	@Test
	public void freshMarkIsDeadEndWithinTtl()
	{
		GoalDeadEndMemory mem = new GoalDeadEndMemory();
		GoalDeadEndKey k = sampleKey();
		mem.markDeadEnd(k, "approach-bfs-fail", 10_000L);
		assertTrue(mem.isDeadEnd(k, 10_001L));
	}

	// 3. After TTL elapses → false.
	@Test
	public void afterTtlIsNotDeadEnd()
	{
		GoalDeadEndMemory mem = new GoalDeadEndMemory();
		GoalDeadEndKey k = sampleKey();
		mem.markDeadEnd(k, "approach-bfs-fail", 0L);
		assertFalse(mem.isDeadEnd(k, GoalDeadEndMemory.TTL_MS + 1L));
	}

	// 4. takeDirty returns true after a mark, false on subsequent call.
	@Test
	public void takeDirtyTogglesAfterMark()
	{
		GoalDeadEndMemory mem = new GoalDeadEndMemory();
		assertFalse(mem.takeDirty());
		mem.markDeadEnd(sampleKey(), "reason", 0L);
		assertTrue(mem.takeDirty());
		assertFalse(mem.takeDirty());
	}

	// 5. JSON round-trip — key + memory through a temp file.
	@Test
	public void keyRoundTripsViaJson()
	{
		GoalDeadEndKey k = sampleKey();
		String s = k.toJsonString();
		GoalDeadEndKey parsed = GoalDeadEndKey.parseJson(s);
		assertEquals(k, parsed);
	}

	@Test
	public void keyWithRouteKeyRoundTrips()
	{
		GoalDeadEndKey k = GoalDeadEndKey.fromEdge("chicken-pen", sampleEdge(), GOAL);
		assertEquals(k, GoalDeadEndKey.parseJson(k.toJsonString()));
	}

	@Test
	public void memoryRoundTripsViaFile() throws Exception
	{
		Path file = tmp.newFile("v21-deadends.json").toPath();
		GoalDeadEndMemory orig = new GoalDeadEndMemory();
		GoalDeadEndKey k1 = sampleKey();
		GoalDeadEndKey k2 = GoalDeadEndKey.fromEdge("alt-route", sampleEdge(), GOAL);
		orig.markDeadEnd(k1, "approach-bfs-fail", 100L);
		orig.markDeadEnd(k1, "approach-bfs-fail", 200L); // bumps failCount
		orig.markDeadEnd(k2, "transport-clicked-twice", 300L);

		orig.flushTo(file);
		assertTrue(Files.exists(file));
		assertFalse("flush should clear dirty", orig.takeDirty());

		GoalDeadEndMemory loaded = new GoalDeadEndMemory();
		loaded.loadFrom(file);

		Collection<GoalDeadEndMemory.Entry> entries = loaded.snapshot();
		assertEquals(2, entries.size());

		assertTrue(loaded.isDeadEnd(k1, 201L));
		assertTrue(loaded.isDeadEnd(k2, 301L));

		// failCount survived the round-trip.
		GoalDeadEndMemory.Entry e1 = findEntry(entries, k1);
		assertEquals(2, e1.failCount());
		assertEquals(100L, e1.firstFailedAtMs());
		assertEquals(200L, e1.lastFailedAtMs());
		assertEquals("approach-bfs-fail", e1.reason());
	}

	@Test
	public void flushIsNoOpWhenNotDirty() throws Exception
	{
		Path file = tmp.newFile("clean.json").toPath();
		Files.delete(file); // ensure flushTo creates only when dirty
		GoalDeadEndMemory mem = new GoalDeadEndMemory();
		mem.flushTo(file);
		assertFalse("nothing dirty → file should not be created", Files.exists(file));
	}

	// 6. Two keys with same routeKey but different goal buckets are
	//    independent (one marked, the other not deadEnd).
	@Test
	public void differentGoalBucketsAreIndependent()
	{
		GoalDeadEndMemory mem = new GoalDeadEndMemory();

		WorldPoint goalA = new WorldPoint(3231, 3295, 0);
		// Different goal bucket — shift by > 16 tiles in X.
		WorldPoint goalB = new WorldPoint(3231 + (1 << GoalDeadEndKey.BUCKET_BITS), 3295, 0);

		GoalDeadEndKey kA = GoalDeadEndKey.fromEdge("route", sampleEdge(), goalA);
		GoalDeadEndKey kB = GoalDeadEndKey.fromEdge("route", sampleEdge(), goalB);
		assertNotEquals("differing goal buckets should produce different keys", kA, kB);

		mem.markDeadEnd(kA, "approach-bfs-fail", 0L);
		assertTrue(mem.isDeadEnd(kA, 1L));
		assertFalse(mem.isDeadEnd(kB, 1L));
	}

	// 7. fromEdge and fromBlocker for the same underlying interaction
	//    produce equal keys.
	@Test
	public void edgeAndBlockerProduceEqualKeysForSameInteraction()
	{
		TransportEdge edge = sampleEdge();

		// BlockerCandidate built with a mock TileObject whose id matches
		// the edge's objectId and whose worldLocation returns the edge's
		// fromTile (so objectTile() matches fromTile()).
		TileObject obj = mock(TileObject.class);
		when(obj.getId()).thenReturn(edge.objectId());
		when(obj.getWorldLocation()).thenReturn(edge.fromTile());

		BlockerCandidate blocker = new BlockerCandidate(obj, edge.verb(), edge.fromTile());

		GoalDeadEndKey kEdge = GoalDeadEndKey.fromEdge("route", edge, GOAL);
		GoalDeadEndKey kBlocker = GoalDeadEndKey.fromBlocker("route", blocker, GOAL);

		assertEquals(kEdge, kBlocker);

		// And anchor takes the same shape.
		InteractionAnchor anchor = new InteractionAnchor(
			edge.objectId(), edge.verb(), edge.fromTile(), edge.fromTile(),
			"GameObject", null);
		GoalDeadEndKey kAnchor = GoalDeadEndKey.fromAnchor("route", anchor, GOAL);
		assertEquals(kEdge, kAnchor);
	}

	private static GoalDeadEndMemory.Entry findEntry(
		Collection<GoalDeadEndMemory.Entry> entries, GoalDeadEndKey k)
	{
		for (GoalDeadEndMemory.Entry e : new ArrayList<>(entries))
		{
			if (e.key().equals(k)) return e;
		}
		throw new AssertionError("entry not found for key " + k);
	}
}
