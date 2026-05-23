package net.runelite.client.sequence.artemis.zones;

import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * Named, walkable area. Spec §9. {@code walkTo(NamedZone)} picks one
 * tile from {@link #tiles()} per the zone's default rotation
 * (UniformWithinRange per spec §7).
 *
 * <p>v1.0 ships the enum entries with stub tile-set bodies; per-zone
 * tile lists land when the first script that depends on
 * {@code walkTo(NamedZone)} migrates (per CowKillerScript in Phase 2
 * for LUMBRIDGE_COW_FIELD, then per script via Phase 6 migrations for
 * the rest). Until then {@code tiles()} returns an empty list —
 * intentional: a caller that tries to walkTo an unpopulated zone gets
 * an immediate empty-route failure rather than silent wrong-place
 * routing.
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
