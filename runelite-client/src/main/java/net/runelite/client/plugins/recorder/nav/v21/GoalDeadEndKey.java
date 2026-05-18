package net.runelite.client.plugins.recorder.nav.v21;

import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;

/** Goal-aware key for {@link GoalDeadEndMemory}. Buckets the
 *  {@code fromTile} of the interaction and the goal centroid by
 *  {@link #BUCKET_BITS} so a goal that drifts by a few tiles still
 *  hits the same blacklist entry, while a goal in a different room
 *  / region maps to a distinct key.
 *
 *  <p>The {@code routeKey} is the optional route-skeleton identifier
 *  (see Task 8). Two attempts at the same edge for the same goal
 *  under different route skeletons are independent — fail one, the
 *  other can still try. {@code null}/empty {@code routeKey} is the
 *  "no skeleton" lane, also distinct from any named skeleton. */
public record GoalDeadEndKey(
	@Nullable String routeKey,
	int objectId,
	String verb,
	int fromBucketX, int fromBucketY, int fromPlane,
	int goalPlane,
	int goalBucketX, int goalBucketY)
{
	/** 4 bits ⇒ 16×16 tile buckets. A typical OSRS room is well
	 *  inside one bucket, so a player goal at the centre of a pen
	 *  and a goal at the edge of the same pen produce the same key. */
	public static final int BUCKET_BITS = 4;

	public static GoalDeadEndKey fromEdge(@Nullable String routeKey,
		TransportEdge edge,
		WorldPoint goalCentroid)
	{
		return new GoalDeadEndKey(
			routeKey,
			edge.objectId(),
			edge.verb(),
			edge.fromTile().getX() >> BUCKET_BITS,
			edge.fromTile().getY() >> BUCKET_BITS,
			edge.fromTile().getPlane(),
			goalCentroid.getPlane(),
			goalCentroid.getX() >> BUCKET_BITS,
			goalCentroid.getY() >> BUCKET_BITS);
	}

	public static GoalDeadEndKey fromAnchor(@Nullable String routeKey,
		InteractionAnchor a,
		WorldPoint goalCentroid)
	{
		return new GoalDeadEndKey(
			routeKey,
			a.objectId(),
			a.verb(),
			a.objectTile().getX() >> BUCKET_BITS,
			a.objectTile().getY() >> BUCKET_BITS,
			a.objectTile().getPlane(),
			goalCentroid.getPlane(),
			goalCentroid.getX() >> BUCKET_BITS,
			goalCentroid.getY() >> BUCKET_BITS);
	}

	public static GoalDeadEndKey fromBlocker(@Nullable String routeKey,
		BlockerCandidate b,
		WorldPoint goalCentroid)
	{
		return new GoalDeadEndKey(
			routeKey,
			b.objectId(),
			b.verb(),
			b.objectTile().getX() >> BUCKET_BITS,
			b.objectTile().getY() >> BUCKET_BITS,
			b.objectTile().getPlane(),
			goalCentroid.getPlane(),
			goalCentroid.getX() >> BUCKET_BITS,
			goalCentroid.getY() >> BUCKET_BITS);
	}

	/** Flat string encoding for the sidecar: pipe-separated,
	 *  {@code route|objId|verb|fbx|fby|fp|gp|gbx|gby}. Null
	 *  {@code routeKey} is serialised as the empty string. */
	public String toJsonString()
	{
		return (routeKey == null ? "" : routeKey)
			+ "|" + objectId + "|" + verb
			+ "|" + fromBucketX + "|" + fromBucketY + "|" + fromPlane
			+ "|" + goalPlane + "|" + goalBucketX + "|" + goalBucketY;
	}

	public static GoalDeadEndKey parseJson(String s)
	{
		String[] p = s.split("\\|", -1);
		if (p.length != 9) throw new IllegalArgumentException("invalid GoalDeadEndKey: " + s);
		String rk = p[0].isEmpty() ? null : p[0];
		return new GoalDeadEndKey(
			rk,
			Integer.parseInt(p[1]), p[2],
			Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]),
			Integer.parseInt(p[6]),
			Integer.parseInt(p[7]), Integer.parseInt(p[8]));
	}
}
