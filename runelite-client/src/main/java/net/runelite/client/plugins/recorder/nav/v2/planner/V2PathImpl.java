package net.runelite.client.plugins.recorder.nav.v2.planner;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.runelite.client.plugins.recorder.nav.v2.transport.PathId;
import net.runelite.client.plugins.recorder.nav.v2.transport.PathStep;
import net.runelite.client.plugins.recorder.nav.v2.transport.ReplanReason;
import net.runelite.client.plugins.recorder.nav.v2.transport.V2Path;

/** Concrete implementation of {@link V2Path} emitted by
 *  {@link WaypointPlanner#plan}.
 *
 *  <p>Two factory paths:
 *  <ul>
 *    <li>{@link #of(List)} — successful plan with ordered steps.</li>
 *    <li>{@link #failed(ReplanReason)} — failed plan; steps list is
 *        empty, {@link #failureReason()} carries the typed reason.</li>
 *  </ul>
 *
 *  <p>Both paths produce a fresh {@link PathId} and the current
 *  wall-clock time at construction. */
public final class V2PathImpl implements V2Path
{
	private final List<PathStep> steps;
	private final PathId id;
	private final long planEpochMs;
	private final ReplanReason failureReason;

	private V2PathImpl(List<PathStep> steps, ReplanReason failureReason)
	{
		this.steps = steps == null ? Collections.emptyList() : Collections.unmodifiableList(steps);
		this.id = PathId.allocate();
		this.planEpochMs = System.currentTimeMillis();
		this.failureReason = failureReason;
	}

	/** Successful plan. */
	public static V2PathImpl of(List<PathStep> steps)
	{
		if (steps == null) throw new IllegalArgumentException("steps null");
		return new V2PathImpl(steps, null);
	}

	/** Failed plan with a typed reason. */
	public static V2PathImpl failed(ReplanReason reason)
	{
		if (reason == null) throw new IllegalArgumentException("reason null");
		return new V2PathImpl(Collections.emptyList(), reason);
	}

	@Override public List<PathStep> steps() { return steps; }
	@Override public PathId id() { return id; }
	@Override public long planEpochMs() { return planEpochMs; }
	@Override public boolean isFailed() { return failureReason != null; }
	@Override public Optional<ReplanReason> failureReason() { return Optional.ofNullable(failureReason); }

	@Override
	public String toString()
	{
		return isFailed()
			? "V2PathImpl{FAILED " + failureReason + "}"
			: "V2PathImpl{" + steps.size() + " steps, id=" + id.value() + "}";
	}
}
