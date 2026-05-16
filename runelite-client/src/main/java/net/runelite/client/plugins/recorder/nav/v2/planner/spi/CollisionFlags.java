package net.runelite.client.plugins.recorder.nav.v2.planner.spi;

/** Spec §3 contract: collision-flag bitmask wrapper.
 *
 *  <p><b>Local mock</b>: matches Lane 2's
 *  {@code nav/v2/collision/CollisionFlags}. Integration consolidates.
 *
 *  <p>The mask uses {@code net.runelite.api.CollisionDataFlag} bits.
 *  A non-zero mask doesn't necessarily mean blocked — it indicates
 *  one or more directional/full flags. The BFS kernel decodes the
 *  bits per-direction (see Lane 3 {@code SkretzoBfsKernel.canMove}). */
public final class CollisionFlags
{
	private final int mask;

	public CollisionFlags(int mask)
	{
		this.mask = mask;
	}

	public int mask() { return mask; }

	@Override
	public boolean equals(Object o)
	{
		if (this == o) return true;
		if (!(o instanceof CollisionFlags)) return false;
		return mask == ((CollisionFlags) o).mask;
	}

	@Override
	public int hashCode() { return Integer.hashCode(mask); }

	@Override
	public String toString() { return "CollisionFlags{0x" + Integer.toHexString(mask) + "}"; }
}
