package net.runelite.client.sequence.artemis.query;

/**
 * Selection query for {@link net.runelite.client.sequence.artemis.Artemis#findItem}.
 * Spec §6. Default rotation per spec §7 is {@code ClosestWithSlack(1)}.
 */
public record ItemQuery(
	Integer itemId,
	String name,
	int rangeTiles,
	int plane,
	int minQuantity,
	RotationPolicy rotation
)
{
	public static final int ANY_PLANE = -1;
	public static final int DEFAULT_RANGE = 6;
	private static final RotationPolicy DEFAULT_ROTATION = new RotationPolicy.ClosestWithSlack(1);

	public static ItemQuery byId(int itemId)
	{
		return new ItemQuery(itemId, null, DEFAULT_RANGE, ANY_PLANE, 1, DEFAULT_ROTATION);
	}

	public static ItemQuery byName(String name)
	{
		return new ItemQuery(null, name, DEFAULT_RANGE, ANY_PLANE, 1, DEFAULT_ROTATION);
	}

	public ItemQuery within(int tiles)
	{
		return new ItemQuery(itemId, name, tiles, plane, minQuantity, rotation);
	}

	public ItemQuery onPlane(int plane)
	{
		return new ItemQuery(itemId, name, rangeTiles, plane, minQuantity, rotation);
	}

	public ItemQuery minQuantity(int qty)
	{
		return new ItemQuery(itemId, name, rangeTiles, plane, qty, rotation);
	}

	public ItemQuery rotation(RotationPolicy policy)
	{
		return new ItemQuery(itemId, name, rangeTiles, plane, minQuantity, policy);
	}
}
