package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.*;

public class TransportTableTest
{
	private static TransportLink doorLink(WorldPoint from, WorldPoint to)
	{
		return new TransportLink(
			from, to, TransportType.DOOR,
			Optional.of(9398), Optional.of("Open Door"),
			TransportRequirement.NONE,
			1, false, "test.tsv", 1);
	}

	private static TransportLink gatedDoorLink(WorldPoint from, WorldPoint to)
	{
		// "members only" — any non-NONE requirement makes the link "requirement-gated".
		TransportRequirement memberGate = TransportRequirementEvaluator.requireMember();
		return new TransportLink(
			from, to, TransportType.DOOR,
			Optional.of(1234), Optional.of("Open Door"),
			memberGate,
			1, false, "test.tsv", 2);
	}

	private static TransportLink stairLink(WorldPoint from, WorldPoint to)
	{
		// plane-changing → STAIRS_UP
		return new TransportLink(
			from, to, to.getPlane() > from.getPlane() ? TransportType.STAIRS_UP : TransportType.STAIRS_DOWN,
			Optional.of(16671), Optional.of("Climb-up Staircase"),
			TransportRequirement.NONE,
			1, false, "test.tsv", 3);
	}

	@Test
	public void startup_loadsFromClasspath_producesStatsLog()
	{
		TransportTable table = TransportTable.loadDefaults();
		TransportTable.LoadStats stats = table.stats();
		assertTrue("expected loaded count > 1000, got " + stats.loaded, stats.loaded > 1000);
		assertTrue("expected at most 50 invalid rows, got " + stats.invalid, stats.invalid <= 50);
		// The vendored corpus must have at least one plane-changing link
		// (ladders / stairs) and at least one requirement-gated link
		// (agility shortcuts).
		assertTrue("expected plane-changing > 0", stats.planeChanging > 0);
		assertTrue("expected requirement-gated > 0", stats.requirementGated > 0);
	}

	@Test
	public void linksFrom_returnsLinksWithMatchingOrigin()
	{
		WorldPoint a = new WorldPoint(3097, 3107, 0);
		WorldPoint b = new WorldPoint(3098, 3107, 0);
		WorldPoint c = new WorldPoint(3099, 3107, 0);
		List<TransportLink> seed = Arrays.asList(
			doorLink(a, b),
			doorLink(b, a),
			doorLink(c, b));
		TransportTable table = new TransportTable(seed, 0);
		List<TransportLink> fromA = table.linksFrom(a);
		assertEquals("expected 1 link from a", 1, fromA.size());
		assertEquals(b, fromA.get(0).to());
		List<TransportLink> fromB = table.linksFrom(b);
		assertEquals(1, fromB.size());
		assertEquals(a, fromB.get(0).to());
		List<TransportLink> empty = table.linksFrom(new WorldPoint(0, 0, 0));
		assertTrue("unknown origin returns empty", empty.isEmpty());
	}

	@Test
	public void appendLiveLink_appearsInLinksFrom()
	{
		WorldPoint a = new WorldPoint(3097, 3107, 0);
		WorldPoint b = new WorldPoint(3098, 3107, 0);
		WorldPoint c = new WorldPoint(3099, 3107, 0);
		TransportTable table = new TransportTable(List.of(doorLink(a, b)), 0);

		TransportLink live = doorLink(a, c);
		table.appendLiveLink(live);

		List<TransportLink> fromA = table.linksFrom(a);
		assertEquals(2, fromA.size());
		// static first, then delta
		assertEquals(b, fromA.get(0).to());
		assertEquals(c, fromA.get(1).to());

		// Only delta links exposed by deltaLinks()
		assertEquals(1, table.deltaLinks().size());
	}

	@Test
	public void stats_countsTwoWayAndPlaneChangingAndGated()
	{
		WorldPoint a = new WorldPoint(3097, 3107, 0);
		WorldPoint b = new WorldPoint(3098, 3107, 0);
		WorldPoint up0 = new WorldPoint(3084, 3125, 0);
		WorldPoint up1 = new WorldPoint(3084, 3124, 1);
		WorldPoint memberGate0 = new WorldPoint(5000, 5000, 0);
		WorldPoint memberGate1 = new WorldPoint(5001, 5000, 0);
		List<TransportLink> seed = Arrays.asList(
			doorLink(a, b),
			doorLink(b, a),
			stairLink(up0, up1),
			gatedDoorLink(memberGate0, memberGate1));
		TransportTable table = new TransportTable(seed, 2);
		TransportTable.LoadStats stats = table.stats();
		assertEquals(4, stats.loaded);
		assertEquals(2, stats.invalid);
		// One reciprocal pair (a↔b)
		assertEquals(1, stats.twoWay);
		// Two-way pair contributes 2 links; remainder is one-way
		assertEquals(seed.size() - 2, stats.oneWay);
		// One plane-changing
		assertEquals(1, stats.planeChanging);
		// One requirement-gated (the member-gated door)
		assertEquals(1, stats.requirementGated);

		// toString of stats includes all the keywords spec §4 mandates
		String s = stats.toString();
		assertTrue(s.contains("Loaded transports"));
		assertTrue(s.contains("Invalid"));
		assertTrue(s.contains("one-way"));
		assertTrue(s.contains("two-way"));
		assertTrue(s.contains("plane-changing"));
		assertTrue(s.contains("requirement-gated"));
	}

	@Test
	public void replace_invokedFromTestAccess_succeeds()
	{
		// Tests are whitelisted callers — replace from a test class
		// (caller frame ends in "Test") MUST succeed.
		WorldPoint a = new WorldPoint(3097, 3107, 0);
		WorldPoint b = new WorldPoint(3098, 3107, 0);
		TransportLink original = doorLink(a, b);
		TransportTable table = new TransportTable(List.of(original), 0);
		// Append the original as a delta so we can replace it (delta-layer path).
		table.appendLiveLink(original);
		TransportLink corrected = doorLink(a, new WorldPoint(3098, 3108, 0));
		table.replace(original, corrected);
		List<TransportLink> deltas = table.deltaLinks();
		assertEquals(1, deltas.size());
		assertEquals(corrected, deltas.get(0));
	}

	@Test
	public void replace_invokedFromExecutorClass_throwsForbidden() throws Exception
	{
		// Synthesize a caller frame from a class name that mimics V2Executor.
		// The TransportTable.replace stack-walk inspects the frame name; we
		// install a thread whose top frame matches and confirm the throw.
		WorldPoint a = new WorldPoint(3097, 3107, 0);
		WorldPoint b = new WorldPoint(3098, 3107, 0);
		TransportTable table = new TransportTable(List.of(doorLink(a, b)), 0);
		TransportLink corrected = doorLink(a, b);
		ExecutorImpostor impostor = new ExecutorImpostor(table, doorLink(a, b), corrected);
		Thread t = new Thread(impostor, "ExecutorImpostor");
		t.start();
		t.join(5000);
		assertNotNull("impostor expected to throw", impostor.thrown);
		assertTrue("expected forbidden message, got: " + impostor.thrown.getMessage(),
			impostor.thrown.getMessage().contains("restricted"));
	}

	@Test
	public void replace_invokedFromNavigatorClass_succeeds() throws Exception
	{
		WorldPoint a = new WorldPoint(3097, 3107, 0);
		WorldPoint b = new WorldPoint(3098, 3107, 0);
		TransportLink original = doorLink(a, b);
		TransportTable table = new TransportTable(List.of(original), 0);
		table.appendLiveLink(original);
		TransportLink corrected = doorLink(a, new WorldPoint(3098, 3108, 0));

		// Run from a thread whose top non-{TransportTable,java.*} frame
		// is V2Navigator. Use a runnable subclass that lives in the
		// approved package — for the test we route through a helper
		// that lives in the recorded approved-callers list.
		NavigatorImpostor impostor = new NavigatorImpostor(table, original, corrected);
		Thread t = new Thread(impostor, "NavigatorImpostor");
		t.start();
		t.join(5000);
		assertNull("navigator-named caller should not throw, got: " + impostor.thrown,
			impostor.thrown);
		// Replacement applied: corrected appears in delta layer.
		boolean found = table.deltaLinks().stream().anyMatch(l -> l.equals(corrected));
		assertTrue("corrected link must be present after replace", found);
	}

	/** Helper that fakes a V2Executor caller. The classloader sees this
	 *  class's fully qualified name as the caller in the stack trace. We
	 *  intentionally place it in a test-only package the table checks
	 *  against. */
	static final class ExecutorImpostor implements Runnable
	{
		final TransportTable table;
		final TransportLink oldLink;
		final TransportLink corrected;
		IllegalStateException thrown;

		ExecutorImpostor(TransportTable table, TransportLink oldLink, TransportLink corrected)
		{
			this.table = table;
			this.oldLink = oldLink;
			this.corrected = corrected;
		}

		@Override
		public void run()
		{
			// To simulate the executor-caller scenario, we delegate through
			// a class whose name does NOT contain "Test" so the test-class
			// whitelist doesn't accidentally allow it.
			SimulatedExecutor simulated = new SimulatedExecutor();
			try
			{
				simulated.invokeReplace(table, oldLink, corrected);
			}
			catch (IllegalStateException e)
			{
				thrown = e;
			}
		}
	}

	/** Same shape as ExecutorImpostor but the simulated caller class
	 *  is a fake V2Navigator (whitelisted). */
	static final class NavigatorImpostor implements Runnable
	{
		final TransportTable table;
		final TransportLink oldLink;
		final TransportLink corrected;
		IllegalStateException thrown;

		NavigatorImpostor(TransportTable table, TransportLink oldLink, TransportLink corrected)
		{
			this.table = table;
			this.oldLink = oldLink;
			this.corrected = corrected;
		}

		@Override
		public void run()
		{
			try
			{
				// Delegate via the simulated navigator class (also lives
				// in this test package).
				SimulatedNavigator nav = new SimulatedNavigator();
				nav.invokeReplace(table, oldLink, corrected);
			}
			catch (IllegalStateException e)
			{
				thrown = e;
			}
		}
	}
}
