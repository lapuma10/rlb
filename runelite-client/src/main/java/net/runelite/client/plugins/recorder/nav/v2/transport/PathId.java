package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Spec §3 contract: stable identifier for a {@link V2Path}.
 *
 *  <p><b>Local mock note</b>: Lane 5 owns the canonical interface
 *  location. Lane 4 ships this concrete identifier so the planner
 *  can hand stable ids to consumers. */
public final class PathId
{
	private static final AtomicLong NEXT = new AtomicLong(1L);

	private final long id;

	public PathId(long id) { this.id = id; }

	public long value() { return id; }

	/** Allocate a fresh, process-unique id. */
	public static PathId allocate() { return new PathId(NEXT.getAndIncrement()); }

	@Override
	public boolean equals(Object o)
	{
		if (this == o) return true;
		if (!(o instanceof PathId)) return false;
		return id == ((PathId) o).id;
	}

	@Override
	public int hashCode() { return Objects.hashCode(id); }

	@Override
	public String toString() { return "PathId{" + id + "}"; }
}
