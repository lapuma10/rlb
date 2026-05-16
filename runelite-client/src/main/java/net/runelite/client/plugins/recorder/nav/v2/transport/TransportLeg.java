package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.Objects;
import java.util.Optional;
import net.runelite.api.coords.WorldPoint;

/** Spec §3 contract: a typed transport edge in the planned path.
 *
 *  <p><b>Local mock note</b>: spec §3 defines {@code TransportLeg}
 *  as an interface. Lane 4 ships this concrete class because the
 *  planner constructs instances from {@link TransportLink} entries.
 *  Integration consolidates with the canonical Lane 1 location.
 *
 *  <p>A leg is the planner's typed handoff to Lane 5's executor —
 *  the executor reads {@link #type()}, {@link #objectId()}, and
 *  {@link #action()} to dispatch the correct verb click. */
public final class TransportLeg
{
	private final WorldPoint from;
	private final WorldPoint to;
	private final TransportType type;
	private final Optional<Integer> objectId;
	private final Optional<String> action;
	private final TransportRequirement requirement;

	public TransportLeg(WorldPoint from, WorldPoint to, TransportType type,
						Optional<Integer> objectId, Optional<String> action,
						TransportRequirement requirement)
	{
		if (from == null) throw new IllegalArgumentException("from null");
		if (to == null) throw new IllegalArgumentException("to null");
		if (type == null) throw new IllegalArgumentException("type null");
		this.from = from;
		this.to = to;
		this.type = type;
		this.objectId = objectId == null ? Optional.empty() : objectId;
		this.action = action == null ? Optional.empty() : action;
		this.requirement = requirement == null ? TransportRequirement.NONE : requirement;
	}

	public WorldPoint from() { return from; }
	public WorldPoint to() { return to; }
	public TransportType type() { return type; }
	public Optional<Integer> objectId() { return objectId; }
	public Optional<String> action() { return action; }
	public TransportRequirement requirement() { return requirement; }

	@Override
	public boolean equals(Object o)
	{
		if (this == o) return true;
		if (!(o instanceof TransportLeg)) return false;
		TransportLeg t = (TransportLeg) o;
		return from.equals(t.from)
			&& to.equals(t.to)
			&& type == t.type
			&& objectId.equals(t.objectId)
			&& action.equals(t.action);
	}

	@Override
	public int hashCode() { return Objects.hash(from, to, type, objectId, action); }

	@Override
	public String toString()
	{
		return "TransportLeg{" + from + " -> " + to
			+ ", type=" + type
			+ (objectId.isPresent() ? ", obj=" + objectId.get() : "")
			+ (action.isPresent() ? ", action=\"" + action.get() + "\"" : "")
			+ "}";
	}
}
