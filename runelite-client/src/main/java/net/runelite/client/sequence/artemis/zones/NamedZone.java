package net.runelite.client.sequence.artemis.zones;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/**
 * Named, walkable area. Spec §9. {@code walkTo(NamedZone)} picks one
 * tile from {@link #tiles()} per the zone's default rotation
 * (UniformWithinRange per spec §7).
 *
 * <p><b>Tile population status:</b>
 * <ul>
 *   <li>{@link #LUMBRIDGE_CASTLE_GROUND_FLOOR} — populated (Phase 1A.4d
 *       smoke zone; 81-tile 9×9 rectangle around the castle interior).</li>
 *   <li>All other zones — empty placeholder. {@code WalkToZoneStep} fails
 *       with the {@code EMPTY_ZONE} diagnostic when targeted; the script
 *       migration that needs the zone populates it at that time.</li>
 * </ul>
 *
 * <p>Pending populations (with the script that drives the need):
 * <ul>
 *   <li>{@link #LUMBRIDGE_COW_FIELD} — CowKillerScript pilot (Phase 2)</li>
 *   <li>{@link #LUMBRIDGE_BANK} — bank-tier migrations (Phase 5+)</li>
 *   <li>{@link #LUMBRIDGE_BANK_P2} — bank-tier migrations (Phase 5+)</li>
 *   <li>{@link #LUMBRIDGE_CHICKEN_PEN} — ChickenFarmV4 (Phase 5)</li>
 * </ul>
 * Remaining zones populate per Phase 6 migration order.
 */
public enum NamedZone
{
	LUMBRIDGE_CASTLE_GROUND_FLOOR(0)
	{
		@Override public List<WorldPoint> tiles() { return LUMBRIDGE_CASTLE_GROUND_FLOOR_TILES; }
	},
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

	// ── Per-zone tile sets ──────────────────────────────────────────
	// Populated once at class init via buildRect(...). Static-field
	// references from enum-constant bodies are safe: the constant's
	// {@code tiles()} method is called at runtime (after <clinit>
	// finishes), not during constant construction.

	/** 9×9 rectangle covering the central Lumbridge Castle ground floor.
	 *  Bounds: x ∈ [3217, 3225], y ∈ [3215, 3223], plane 0. Generous
	 *  arrival window — favors "any reasonable arrival counts" over
	 *  exact-center. Phase 1A.4d smoke target. */
	private static final List<WorldPoint> LUMBRIDGE_CASTLE_GROUND_FLOOR_TILES =
		buildRect(3217, 3225, 3215, 3223, 0);

	private static List<WorldPoint> buildRect(int x0, int x1, int y0, int y1, int plane)
	{
		List<WorldPoint> out = new ArrayList<>((x1 - x0 + 1) * (y1 - y0 + 1));
		for (int x = x0; x <= x1; x++)
		{
			for (int y = y0; y <= y1; y++)
			{
				out.add(new WorldPoint(x, y, plane));
			}
		}
		return List.copyOf(out);
	}
}
