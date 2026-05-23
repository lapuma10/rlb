package net.runelite.client.sequence.artemis.query;

import java.util.Set;

/**
 * Selection query for {@link net.runelite.client.sequence.artemis.Artemis#findNpc}.
 * Spec §6. Default rotation per spec §7 is {@code ClosestWithSlack(2)}.
 */
public record NpcQuery(
	String name,
	Integer id,
	int rangeTiles,
	int plane,
	Set<Integer> excludeIndices,
	boolean requireUnengaged,
	RotationPolicy rotation
)
{
	/** Sentinel for "any plane" — query does not filter by plane. */
	public static final int ANY_PLANE = -1;

	/** Default search range in tiles when {@code within(...)} not specified. */
	public static final int DEFAULT_RANGE = 14;

	private static final RotationPolicy DEFAULT_ROTATION = new RotationPolicy.ClosestWithSlack(2);

	/** Defensive copy on input — caller can't mutate the excludeIndices
	 *  set after handing it to NpcQuery. Records-as-truly-immutable. */
	public NpcQuery
	{
		excludeIndices = Set.copyOf(excludeIndices);
	}

	public static NpcQuery byName(String name)
	{
		return new NpcQuery(name, null, DEFAULT_RANGE, ANY_PLANE, Set.of(), false, DEFAULT_ROTATION);
	}

	public static NpcQuery byId(int id)
	{
		return new NpcQuery(null, id, DEFAULT_RANGE, ANY_PLANE, Set.of(), false, DEFAULT_ROTATION);
	}

	public NpcQuery within(int tiles)
	{
		return new NpcQuery(name, id, tiles, plane, excludeIndices, requireUnengaged, rotation);
	}

	public NpcQuery onPlane(int plane)
	{
		return new NpcQuery(name, id, rangeTiles, plane, excludeIndices, requireUnengaged, rotation);
	}

	public NpcQuery exclude(int npcIndex)
	{
		Set<Integer> next = new java.util.HashSet<>(excludeIndices);
		next.add(npcIndex);
		return new NpcQuery(name, id, rangeTiles, plane, Set.copyOf(next), requireUnengaged, rotation);
	}

	/** Convenience: returns a query that only matches NPCs not already
	 *  in combat with another player. The underlying field is named
	 *  {@code requireUnengaged} to avoid collision with this builder
	 *  method (records auto-generate an accessor named after the
	 *  field). */
	public NpcQuery unengagedOnly()
	{
		return new NpcQuery(name, id, rangeTiles, plane, excludeIndices, true, rotation);
	}

	public NpcQuery rotation(RotationPolicy policy)
	{
		return new NpcQuery(name, id, rangeTiles, plane, excludeIndices, requireUnengaged, policy);
	}
}
