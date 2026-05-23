package net.runelite.client.sequence.artemis.query;

/**
 * Selection query for {@link net.runelite.client.sequence.artemis.Artemis#findObject}.
 * Spec §6. Default rotation per spec §7 is {@code ClosestWithSlack(1)}.
 */
public record ObjectQuery(
	String name,
	Integer id,
	int rangeTiles,
	int plane,
	RotationPolicy rotation
)
{
	public static final int ANY_PLANE = -1;
	public static final int DEFAULT_RANGE = 12;
	private static final RotationPolicy DEFAULT_ROTATION = new RotationPolicy.ClosestWithSlack(1);

	public static ObjectQuery byName(String name)
	{
		return new ObjectQuery(name, null, DEFAULT_RANGE, ANY_PLANE, DEFAULT_ROTATION);
	}

	public static ObjectQuery byId(int id)
	{
		return new ObjectQuery(null, id, DEFAULT_RANGE, ANY_PLANE, DEFAULT_ROTATION);
	}

	public ObjectQuery within(int tiles)
	{
		return new ObjectQuery(name, id, tiles, plane, rotation);
	}

	public ObjectQuery onPlane(int plane)
	{
		return new ObjectQuery(name, id, rangeTiles, plane, rotation);
	}

	public ObjectQuery rotation(RotationPolicy policy)
	{
		return new ObjectQuery(name, id, rangeTiles, plane, policy);
	}
}
