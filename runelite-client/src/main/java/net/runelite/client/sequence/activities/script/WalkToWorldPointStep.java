package net.runelite.client.sequence.activities.script;

import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.nav.Navigator;
import net.runelite.client.sequence.artemis.Artemis;

/**
 * Step backing {@code Artemis.walkTo(WorldPoint)}. Gameplay Step;
 * navigation to a single target tile.
 *
 * <p>Concrete subclass of {@link WalkStepBase} — see that file for the
 * daemon-worker pattern, the threading invariants, the shared
 * diagnostic vocabulary (NAVIGATOR_MISSING / NO_ROUTE / STUCK / TIMEOUT
 * / NAVIGATOR_FAILED / NAVIGATOR_EXCEPTION), the JLS §17.4.4 write
 * ordering, and the single-use guard.
 *
 * <p>This subclass adds only:
 * <ul>
 *   <li>{@code target} field (the {@link WorldPoint} navigation target),</li>
 *   <li>arrival predicate: within {@link #ARRIVAL_RADIUS_TILES} (1 tile)
 *       of {@code target} on the same plane,</li>
 *   <li>the Artemis-surface metadata methods.</li>
 * </ul>
 */
public final class WalkToWorldPointStep extends WalkStepBase
{
	/** Player location within this many tiles of the target counts as
	 *  at-arrival. Combined with {@link WalkStepBase#ARRIVAL_DEBOUNCE_TICKS}
	 *  for the success condition. */
	private static final int ARRIVAL_RADIUS_TILES = 1;

	private final WorldPoint target;

	public WalkToWorldPointStep(Artemis artemis, Consumer<StepEvent> stepEventSink,
		WorldPoint target, @Nullable Navigator navigator)
	{
		super(artemis, stepEventSink, navigator);
		if (target == null)
		{
			throw new IllegalArgumentException("WorldPoint target must not be null");
		}
		this.target = target;
	}

	@Override
	public String name()
	{
		return "WalkToWorldPoint(" + target.getX() + "," + target.getY() + "," + target.getPlane() + ")";
	}

	@Override public int timeoutTicks()         { return TIMEOUT_TICKS; }
	@Override protected String targetType()     { return "tile"; }
	@Override protected String targetId()       { return "tile:" + target.getX() + "," + target.getY() + "," + target.getPlane(); }
	@Override protected String targetName()     { return null; }
	@Override protected String verb()           { return "Walk"; }

	@Override
	protected WorldPoint navigationTarget()
	{
		return target;
	}

	@Override
	protected boolean isAtArrival(@Nullable WorldPoint here)
	{
		if (here == null) return false;
		return here.getPlane() == target.getPlane()
			&& here.distanceTo(target) <= ARRIVAL_RADIUS_TILES;
	}

	@Override
	protected String workerThreadName()
	{
		return "artemis-walk-" + target.getX() + "," + target.getY() + "," + target.getPlane();
	}
}
