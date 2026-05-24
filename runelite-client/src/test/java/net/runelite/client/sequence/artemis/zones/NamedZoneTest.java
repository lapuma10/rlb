package net.runelite.client.sequence.artemis.zones;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins {@link NamedZone}'s contract:
 * <ul>
 *   <li>{@code LUMBRIDGE_CASTLE_GROUND_FLOOR} is populated with the
 *       Phase 1A.4d smoke tile set (81 tiles, 9×9). All other zones
 *       remain empty placeholders — their tile sets land with the
 *       script migration that needs them (Phase 5+).</li>
 *   <li>The returned tile list is immutable — callers cannot mutate
 *       it to forge a route. This holds for both empty and populated
 *       zones.</li>
 *   <li>Every zone declares a plane.</li>
 *   <li>The reconciled v1 set (per spec §9) includes LUMBRIDGE_BANK
 *       and LUMBRIDGE_BANK_P2 — the round-4 reconciliation addition.</li>
 * </ul>
 */
public class NamedZoneTest
{
	@Test
	public void everyZoneReturnsANonNullTileList()
	{
		for (NamedZone z : NamedZone.values())
		{
			assertNotNull("tiles() must never return null for " + z, z.tiles());
		}
	}

	@Test
	public void onlyLumbridgeCastleGroundFloorIsPopulatedInPhase1A4d()
	{
		// Phase 1A.4d ships LUMBRIDGE_CASTLE_GROUND_FLOOR as the smoke
		// zone (81 tiles). Every other zone stays empty until the
		// script migration that needs it populates it. Failing this
		// test means either (a) someone populated a zone they shouldn't
		// have in this slice, or (b) LUMBRIDGE_CASTLE_GROUND_FLOOR's
		// bounds shifted — both are real review-worthy changes.
		for (NamedZone z : NamedZone.values())
		{
			if (z == NamedZone.LUMBRIDGE_CASTLE_GROUND_FLOOR)
			{
				assertEquals("LUMBRIDGE_CASTLE_GROUND_FLOOR must have its 81-tile smoke set",
					81, z.tiles().size());
			}
			else
			{
				assertEquals("v1.0 placeholder: " + z + " must ship empty tiles",
					0, z.tiles().size());
			}
		}
	}

	@Test(expected = UnsupportedOperationException.class)
	public void tileListIsImmutable()
	{
		// Whether empty (v1.0) or populated (later), the returned list
		// must reject mutation so callers cannot forge a route.
		List<WorldPoint> tiles = NamedZone.LUMBRIDGE_BANK.tiles();
		tiles.add(new WorldPoint(3209, 3220, 2));   // expected to throw
	}

	@Test
	public void everyZoneHasAValidPlane()
	{
		// OSRS has 4 planes (0-3 inclusive): ground, 1st floor, 2nd floor,
		// 3rd floor. A plane outside this range is a config bug.
		for (NamedZone z : NamedZone.values())
		{
			int p = z.plane();
			assertTrue("plane for " + z + " must be in [0, 3] — was " + p,
				p >= 0 && p <= 3);
		}
	}

	// ─── round-4 reconciliation: LUMBRIDGE_BANK[_P2] must exist ─────

	@Test
	public void reconciledBankZonesExist()
	{
		// Round-4 reconciliation added these; would compile-fail if
		// someone removed them, but this asserts the lookup works.
		assertEquals(0, NamedZone.LUMBRIDGE_BANK.plane());
		assertEquals(2, NamedZone.LUMBRIDGE_BANK_P2.plane());
	}
}
