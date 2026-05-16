package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.*;

public class TransportTableLoaderTest
{
	private static ByteArrayInputStream tsv(String body)
	{
		return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	public void load_validTransportsTsv_returnsExpectedLinks()
	{
		// Two reciprocal door rows + one stair row.
		String body =
			"# Origin\tDestination\tmenuOption menuTarget objectID\tSkills\tItems\tQuests\tVarbits\tVarPlayers\tDuration\tDisplay info\n" +
			"3097 3107 0\t3098 3107 0\tOpen Door 9398\t\t\t\t\t\t1\t\n" +
			"3098 3107 0\t3097 3107 0\tOpen Door 9398\t\t\t\t\t\t1\t\n" +
			"3084 3125 0\t3084 3124 1\tClimb-up Staircase 16671\t\t\t\t\t\t1\t\n";

		TransportTableLoader.LoadResult result =
			TransportTableLoader.load(tsv(body), "transports.tsv");

		assertEquals("expected 3 valid links", 3, result.links().size());
		assertEquals("expected 0 invalid rows", 0, result.invalidRows().size());

		TransportLink first = result.links().get(0);
		assertEquals(new WorldPoint(3097, 3107, 0), first.from());
		assertEquals(new WorldPoint(3098, 3107, 0), first.to());
		assertEquals(TransportType.DOOR, first.type());
		assertEquals("Open Door", first.action().orElse(null));
		assertEquals(Integer.valueOf(9398), first.objectId().orElse(null));
		assertEquals(1, first.durationTicks());

		TransportLink stair = result.links().get(2);
		assertEquals(TransportType.STAIRS_UP, stair.type());
		assertEquals("Climb-up Staircase", stair.action().orElse(null));
		assertEquals(0, stair.from().getPlane());
		assertEquals(1, stair.to().getPlane());
	}

	@Test
	public void load_malformedRow_reportsFile_line_andContinues()
	{
		// Row 4 has nonsense coords; loader keeps going on row 5.
		String body =
			"# header\n" +
			"3097 3107 0\t3098 3107 0\tOpen Door 9398\t\t\t\t\t\t1\t\n" +
			"# comment after a data row, also skipped\n" +
			"not-a-coord row that should fail loud\n" +
			"3098 3107 0\t3097 3107 0\tOpen Door 9398\t\t\t\t\t\t1\t\n";

		TransportTableLoader.LoadResult result =
			TransportTableLoader.load(tsv(body), "transports.tsv");

		assertEquals(2, result.links().size());
		assertEquals(1, result.invalidRows().size());
		TransportTableLoader.InvalidRow bad = result.invalidRows().get(0);
		assertTrue("expected source file in invalid record: " + bad.sourceFile(),
			bad.sourceFile().contains("transports.tsv"));
		assertTrue("expected line number > 0 for invalid row: " + bad.sourceLine(),
			bad.sourceLine() > 0);
		assertNotNull(bad.reason());
		assertFalse(bad.reason().isEmpty());
	}

	@Test
	public void load_skillsRequirement_parsesCorrectly()
	{
		String body =
			"# Origin\tDestination\tmenuOption menuTarget objectID\tSkills\tItems\tVarbits\tVarPlayers\tDuration\n" +
			"2220 3155 0\t2220 3152 0\tStep-over Tripwire 3921\t1 Agility\t\t\t\t8\n";

		TransportTableLoader.LoadResult result =
			TransportTableLoader.load(tsv(body), "agility_shortcuts.tsv");

		assertEquals(1, result.links().size());
		TransportLink link = result.links().get(0);
		assertEquals(TransportType.AGILITY_SHORTCUT, link.type());
		assertNotNull("requirement must be non-null", link.requirement());
		// Cannot satisfy a level-1 requirement without a NavigationContext stub; just
		// confirm the loader assigned a non-NONE requirement.
		assertNotSame("skill-gated rows must not get TransportRequirement.NONE",
			TransportRequirement.NONE, link.requirement());
	}

	@Test
	public void load_bidirectionalPair_loadedAsTwoOneWayLinks()
	{
		// Skretzo emits doors as two rows. The loader keeps both;
		// composition into a logical bidirectional link is the table's job.
		String body =
			"# Origin\tDestination\tmenuOption menuTarget objectID\tSkills\tItems\tQuests\tVarbits\tVarPlayers\tDuration\tDisplay info\n" +
			"3097 3107 0\t3098 3107 0\tOpen Door 9398\t\t\t\t\t\t1\t\n" +
			"3098 3107 0\t3097 3107 0\tOpen Door 9398\t\t\t\t\t\t1\t\n";

		TransportTableLoader.LoadResult result =
			TransportTableLoader.load(tsv(body), "transports.tsv");

		assertEquals(2, result.links().size());
		// Both links are stored individually one-way; the bidirectional
		// flag on TransportLink reflects loader's awareness.
		// (Convention chosen: loader keeps them one-way and lets the
		// table compose; this keeps loader stateless.)
		assertFalse(result.links().get(0).bidirectional());
		assertFalse(result.links().get(1).bidirectional());
	}

	@Test
	public void load_fairyRingTsv_emptyDestinationParsesAsConfigure()
	{
		String body =
			"# Origin\tDestination\tmenuOption menuTarget objectID\tSkills\tQuests\tVarbits\tDuration\tDisplay info\n" +
			"2412 4434 0\t\tConfigure Fairy ring 29560\t\t\t\t5\t\n";

		TransportTableLoader.LoadResult result =
			TransportTableLoader.load(tsv(body), "fairy_rings.tsv");

		assertEquals(1, result.links().size());
		TransportLink link = result.links().get(0);
		assertEquals(TransportType.FAIRY_RING, link.type());
		assertNotNull("origin must be set", link.from());
		assertNull("destination is null for configure rows", link.to());
	}

	@Test
	public void load_teleportationItemsTsv_originIsNull()
	{
		// teleportation_items.tsv has no Origin column.
		String body =
			"# Destination\tmenuOption menuTarget objectID\tSkills\tItems\tQuests\tDuration\tDisplay info\tConsumable\tWilderness level\tVarbits\tVarPlayers\n" +
			"2607 3221 0\t\t\t13121=1\t\t4\tArdougne cloak: Kandarin Monastery\tF\t20\t\t\n";

		TransportTableLoader.LoadResult result =
			TransportTableLoader.load(tsv(body), "teleportation_items.tsv");

		assertEquals(1, result.links().size());
		TransportLink link = result.links().get(0);
		assertEquals(TransportType.TELEPORT_ITEM, link.type());
		assertNull("origin is null for teleport-from-anywhere", link.from());
		assertEquals(new WorldPoint(2607, 3221, 0), link.to());
	}

	@Test
	public void load_emptyOrCommentOnlyFile_returnsEmptyLinks()
	{
		String body =
			"# only header\n" +
			"# more comments\n" +
			"\n" +
			"# nothing else\n";

		TransportTableLoader.LoadResult result =
			TransportTableLoader.load(tsv(body), "empty.tsv");

		assertEquals(0, result.links().size());
		assertEquals(0, result.invalidRows().size());
	}

	@Test
	public void load_realTransportsTsv_loadsThousandsOfLinksWithFewInvalids()
	{
		// Smoke test against the actual vendored TSV: catches schema drift
		// at refresh time.
		List<TransportTableLoader.LoadResult> all =
			TransportTableLoader.loadAllFromClasspath();
		int total = 0;
		int invalid = 0;
		for (TransportTableLoader.LoadResult r : all)
		{
			total += r.links().size();
			invalid += r.invalidRows().size();
		}
		assertTrue("expected at least 1000 transport links from vendored TSVs, got " + total,
			total >= 1000);
		// Soft cap on invalid rows. Schema drift will surface as this rising.
		assertTrue("expected fewer than 50 invalid rows across all TSVs, got " + invalid,
			invalid < 50);
	}
}
