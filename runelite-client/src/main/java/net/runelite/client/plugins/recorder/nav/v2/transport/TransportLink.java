package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.Objects;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;

/** One transport edge, parsed from a Skretzo TSV row.
 *
 *  <p>This is the planner's data unit — distinct from
 *  {@link TransportLeg}, which is the spec §3 contract handed to the
 *  executor. The planner consults links via
 *  {@link TransportTable#linksFrom(WorldPoint)} during Dijkstra; when
 *  a link is part of the chosen route, the planner converts it to a
 *  {@link TransportLeg} for emission.
 *
 *  <p>The {@code sourceFile} + {@code sourceLine} pair is used by
 *  {@link TransportTableLoader} for loud per-row failure reporting
 *  per spec §4 Lane 4 ("never silently drop"). */
public final class TransportLink
{
	private final WorldPoint from;
	private final WorldPoint to;
	private final TransportType type;
	private final Optional<Integer> objectId;
	private final Optional<String> action;
	private final TransportRequirement requirement;
	private final int durationTicks;
	private final boolean bidirectional;
	private final String sourceFile;
	private final int sourceLine;

	public TransportLink(WorldPoint from, WorldPoint to, TransportType type,
						 Optional<Integer> objectId, Optional<String> action,
						 TransportRequirement requirement,
						 int durationTicks,
						 boolean bidirectional,
						 String sourceFile, int sourceLine)
	{
		if (type == null) throw new IllegalArgumentException("type null");
		this.from = from;
		this.to = to;
		this.type = type;
		this.objectId = objectId == null ? Optional.empty() : objectId;
		this.action = action == null ? Optional.empty() : action;
		this.requirement = requirement == null ? TransportRequirement.NONE : requirement;
		this.durationTicks = durationTicks < 1 ? 1 : durationTicks;
		this.bidirectional = bidirectional;
		this.sourceFile = sourceFile == null ? "<unknown>" : sourceFile;
		this.sourceLine = sourceLine;
	}

	/** Origin tile, or {@code null} for "anywhere" (teleport items). */
	public WorldPoint from() { return from; }

	/** Destination tile, or {@code null} for "configurable" (fairy
	 *  rings / spirit trees — destination chosen at use). */
	public WorldPoint to() { return to; }

	public TransportType type() { return type; }
	public Optional<Integer> objectId() { return objectId; }
	public Optional<String> action() { return action; }
	public TransportRequirement requirement() { return requirement; }

	/** Tick cost for traversing the link. Used by Dijkstra. */
	public int durationTicks() { return durationTicks; }

	/** True iff the link is two-way. Many doors / gates are emitted
	 *  as two separate one-way rows in the TSV (Skretzo's convention);
	 *  those are loaded as two distinct one-way links. {@code true}
	 *  here means the loader/composer determined symmetry. */
	public boolean bidirectional() { return bidirectional; }

	public String sourceFile() { return sourceFile; }
	public int sourceLine() { return sourceLine; }

	/** Convert this link to a spec §3 {@link TransportLeg} for emission. */
	public TransportLeg toLeg()
	{
		return new TransportLeg(from, to, type, objectId, action, requirement);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o) return true;
		if (!(o instanceof TransportLink)) return false;
		TransportLink t = (TransportLink) o;
		return durationTicks == t.durationTicks
			&& bidirectional == t.bidirectional
			&& type == t.type
			&& Objects.equals(from, t.from)
			&& Objects.equals(to, t.to)
			&& objectId.equals(t.objectId)
			&& action.equals(t.action);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(from, to, type, objectId, action, durationTicks, bidirectional);
	}

	@Override
	public String toString()
	{
		return "TransportLink{" + from + " -> " + to
			+ ", type=" + type
			+ (action.isPresent() ? ", \"" + action.get() + "\"" : "")
			+ (objectId.isPresent() ? ", obj=" + objectId.get() : "")
			+ ", dur=" + durationTicks + "t"
			+ ", " + sourceFile + ":" + sourceLine + "}";
	}
}
