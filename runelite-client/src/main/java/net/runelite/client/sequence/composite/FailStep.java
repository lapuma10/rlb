package net.runelite.client.sequence.composite;

import java.util.Objects;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Failure;
import net.runelite.client.sequence.PreemptionPolicy;
import net.runelite.client.sequence.Recovery;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.Blackboard;

/**
 * Leaf step that {@link #check(WorldSnapshot, Blackboard)}-fails immediately
 * with a configured reason. The intended caller is a {@link DynamicStep}
 * factory that needs to express "no target found" without reaching into
 * engine internals — e.g.:
 *
 * <pre>{@code
 * DynamicStep.of("find-and-attack-cow", () -> {
 *     Optional<NpcRef> cow = artemis.findNpc(query);
 *     if (cow.isEmpty()) return FailStep.of("NO_COW_FOUND");
 *     return artemis.click(cow.get(), "Attack");
 * });
 * }</pre>
 *
 * <p>{@link #onFailure} returns {@link Recovery.Abort} — FailStep is
 * definitive; the surrounding composite (typically {@link DynamicStep})
 * propagates the Failed status to its parent without retry. Retries belong
 * at higher levels (e.g., {@link RepeatStep}'s next iteration evaluates the
 * factory again).
 */
public final class FailStep implements Step
{
	private final String reason;

	public static FailStep of(String reason)
	{
		return new FailStep(reason);
	}

	public FailStep(String reason)
	{
		this.reason = Objects.requireNonNull(reason, "reason");
	}

	@Override
	public String name()
	{
		// The reason doubles as the step name so telemetry / StepEvent
		// dashboards show the failure cause at a glance.
		return reason;
	}

	@Override
	public int priority()
	{
		return 0;
	}

	@Override
	public int timeoutTicks()
	{
		// One tick is enough — check() fails immediately.
		return 1;
	}

	@Override
	public PreemptionPolicy preemptionPolicy()
	{
		return PreemptionPolicy.WHEN_SAFE;
	}

	@Override
	public boolean isSafeToPause(WorldSnapshot state, Blackboard bb)
	{
		return true;
	}

	@Override
	public boolean canStart(WorldSnapshot state, Blackboard bb)
	{
		return true;
	}

	@Override
	public void onStart(StepContext ctx)
	{
		// No-op. check() does all the work.
	}

	@Override
	public void onEvent(Object event, StepContext ctx)
	{
		// No-op.
	}

	@Override
	public void tick(StepContext ctx)
	{
		// No-op.
	}

	@Override
	public Completion check(WorldSnapshot state, Blackboard bb)
	{
		return new Completion.Failed(reason);
	}

	@Override
	public Recovery onFailure(Failure failure, WorldSnapshot state, Blackboard bb)
	{
		return new Recovery.Abort(reason);
	}
}
