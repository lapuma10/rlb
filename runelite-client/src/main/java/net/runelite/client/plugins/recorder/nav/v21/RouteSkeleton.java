package net.runelite.client.plugins.recorder.nav.v21;

import java.util.List;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;

/** A coarse record of one successful navigation run: the ordered list
 *  of {@link net.runelite.client.plugins.recorder.worldmap.TransportEdge#key()}s
 *  the navigator actually traversed for a given goal bucket.
 *
 *  <p>Record-only this round — no replay. The artifact is a diagnostic
 *  plus future corpus for the replay branch.
 *
 *  <p>The {@code routeKey} is the optional caller-supplied label
 *  (e.g. "pen_to_lumby_bank"). {@code null} / empty {@code routeKey}
 *  is the "unlabelled" lane and is distinct from any named skeleton. */
public record RouteSkeleton(
	@Nullable String routeKey,
	WorldPoint goalCentroid,
	int goalPlane,
	List<String> transportEdgeKeys,
	long recordedAtMs)
{
	public RouteSkeleton
	{
		transportEdgeKeys = transportEdgeKeys == null ? List.of() : List.copyOf(transportEdgeKeys);
	}

	/** Bucket coordinate used for store deduplication. Same bucketing
	 *  scheme as {@link GoalDeadEndKey} so a moving goal in the same
	 *  area maps to one skeleton entry. */
	public int goalBucketX()
	{
		return goalCentroid.getX() >> GoalDeadEndKey.BUCKET_BITS;
	}

	public int goalBucketY()
	{
		return goalCentroid.getY() >> GoalDeadEndKey.BUCKET_BITS;
	}
}
