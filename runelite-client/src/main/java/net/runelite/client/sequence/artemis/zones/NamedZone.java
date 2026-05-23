package net.runelite.client.sequence.artemis.zones;

import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * Named, walkable area. Spec §9. {@code walkTo(NamedZone)} picks one
 * tile from {@link #tiles()} per the zone's default rotation
 * (UniformWithinRange per spec §7).
 *
 * <p><b>v1.0 placeholder state — read before wiring walkTo:</b> every
 * enum entry's {@link #tiles()} currently returns an empty list. This
 * is intentional for the Phase 1A.1 interface scaffold — a caller
 * that walks to an unpopulated zone gets an immediate empty-route
 * failure rather than silent wrong-place routing.
 *
 * <p><b>Zones needing real tile sets before walkTo(NamedZone) can be
 * considered implemented</b> (each lands with the dependent script's
 * migration; do not ship walkTo wiring without these):
 * <ul>
 *   <li>{@link #LUMBRIDGE_CASTLE_GROUND_FLOOR} — Phase 1A smoke plan
 *       per spec §19</li>
 *   <li>{@link #LUMBRIDGE_COW_FIELD} — CowKillerScript pilot (Phase 2)</li>
 *   <li>{@link #LUMBRIDGE_BANK} — bank-tier migrations (Phase 5+)</li>
 *   <li>{@link #LUMBRIDGE_BANK_P2} — bank-tier migrations (Phase 5+)</li>
 *   <li>{@link #LUMBRIDGE_CHICKEN_PEN} — ChickenFarmV4 (Phase 5)</li>
 * </ul>
 * Remaining zones populate per Phase 6 migration order.
 */
public enum NamedZone
{
	LUMBRIDGE_CASTLE_GROUND_FLOOR(0),
	LUMBRIDGE_CASTLE_P1(1),
	LUMBRIDGE_CASTLE_P2(2),
	LUMBRIDGE_BANK(0),
	LUMBRIDGE_BANK_P2(2),
	LUMBRIDGE_COW_FIELD(0),
	LUMBRIDGE_CHICKEN_PEN(0),
	GRAND_EXCHANGE(0),
	;

	private final int plane;

	NamedZone(int plane)
	{
		this.plane = plane;
	}

	public int plane()
	{
		return plane;
	}

	/** Walkable tile set for this zone. Empty until the first script
	 *  using {@code walkTo(this)} migrates and populates it (see class
	 *  Javadoc). */
	public List<WorldPoint> tiles()
	{
		return Collections.emptyList();
	}
}
