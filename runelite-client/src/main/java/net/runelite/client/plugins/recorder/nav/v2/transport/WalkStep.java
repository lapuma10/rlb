package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.Objects;

/** Spec §3 contract: walk leg of a planned path.
 *
 *  <p><b>Local mock note</b>: Lane 5 owns the canonical
 *  {@code nav/v2/WalkStep.java}. Lane 4 ships this here.
 *  Integration consolidates. */
public final class WalkStep implements PathStep
{
	private final Waypoint waypoint;

	public WalkStep(Waypoint waypoint)
	{
		if (waypoint == null) throw new IllegalArgumentException("waypoint null");
		this.waypoint = waypoint;
	}

	public Waypoint waypoint() { return waypoint; }

	@Override
	public boolean equals(Object o)
	{
		if (this == o) return true;
		if (!(o instanceof WalkStep)) return false;
		return waypoint.equals(((WalkStep) o).waypoint);
	}

	@Override
	public int hashCode() { return Objects.hash(waypoint); }

	@Override
	public String toString() { return "WalkStep{" + waypoint + "}"; }
}
