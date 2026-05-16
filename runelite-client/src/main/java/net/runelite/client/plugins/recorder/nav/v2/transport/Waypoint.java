package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.Objects;
import net.runelite.api.coords.WorldPoint;

/** Spec §3 contract: a planner-emitted waypoint.
 *
 *  <p><b>Local mock note</b>: this concrete class is the spec §3
 *  {@code Waypoint} interface materialized as a record-like value
 *  type. Lane 5 will canonicalize the interface under flat {@code
 *  nav/v2/}; Lane 4 ships this here because the planner constructs
 *  instances.
 *
 *  <p>{@code exactRequired()} is a shortcut for {@code
 *  toleranceRadius() == 0}. */
public final class Waypoint
{
	private final WorldPoint target;
	private final int toleranceRadius;
	private final WaypointType type;

	public Waypoint(WorldPoint target, int toleranceRadius, WaypointType type)
	{
		if (target == null) throw new IllegalArgumentException("target null");
		if (toleranceRadius < 0) throw new IllegalArgumentException("toleranceRadius < 0");
		if (type == null) throw new IllegalArgumentException("type null");
		this.target = target;
		this.toleranceRadius = toleranceRadius;
		this.type = type;
	}

	public WorldPoint target() { return target; }
	public int toleranceRadius() { return toleranceRadius; }
	public WaypointType type() { return type; }
	public boolean exactRequired() { return toleranceRadius == 0; }

	@Override
	public boolean equals(Object o)
	{
		if (this == o) return true;
		if (!(o instanceof Waypoint)) return false;
		Waypoint w = (Waypoint) o;
		return toleranceRadius == w.toleranceRadius
			&& type == w.type
			&& target.equals(w.target);
	}

	@Override
	public int hashCode() { return Objects.hash(target, toleranceRadius, type); }

	@Override
	public String toString()
	{
		return "Waypoint{target=" + target
			+ ", tol=" + toleranceRadius
			+ ", type=" + type + "}";
	}
}
