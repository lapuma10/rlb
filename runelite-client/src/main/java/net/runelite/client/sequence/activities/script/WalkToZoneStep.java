package net.runelite.client.sequence.activities.script;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.analyse.StepEvent;
import net.runelite.client.plugins.recorder.nav.Navigator;
import net.runelite.client.sequence.StepContext;
import net.runelite.client.sequence.artemis.Artemis;
import net.runelite.client.sequence.artemis.query.RotationPolicy;
import net.runelite.client.sequence.artemis.query.RotationPolicySelector;
import net.runelite.client.sequence.artemis.zones.NamedZone;

/**
 * Step backing {@code Artemis.walkTo(NamedZone)}. Gameplay Step;
 * navigation to a {@link NamedZone}'s tile set.
 *
 * <p>Concrete subclass of {@link WalkStepBase} — see that file for the
 * shared daemon-worker pattern, threading invariants, and the inherited
 * diagnostic vocabulary (NAVIGATOR_MISSING / NO_ROUTE / STUCK / TIMEOUT
 * / NAVIGATOR_FAILED / NAVIGATOR_EXCEPTION).
 *
 * <p>This subclass adds the {@code EMPTY_ZONE} diagnostic ({@link
 * #REASON_EMPTY_ZONE}) and a doPreFlight that fails loud — before any
 * worker is spawned and before the Navigator is touched — when the
 * zone's tile set is empty.
 *
 * <p><b>Tile pick.</b> One tile is chosen from {@code zone.tiles()} via
 * {@link RotationPolicySelector#pick} with {@link
 * RotationPolicy.UniformWithinRange} (spec §7 default for zones). The
 * Navigator drives toward this chosen tile, but the Step succeeds when
 * the player enters <em>any</em> tile in the zone — the chosen tile is
 * just the planner's target, not the only acceptable arrival.
 *
 * <p><b>Arrival check.</b> Player must be on the zone's plane AND inside
 * the zone tile set. Same-X/Y but different-plane does NOT count.
 */
public final class WalkToZoneStep extends WalkStepBase
{
	/** {@code zone.tiles()} was empty at doStart — no walkable tiles
	 *  populated for this NamedZone yet. Triggers {@link
	 *  net.runelite.client.sequence.Recovery.Abort} (populating tiles is
	 *  a data fix, not a runtime recovery). */
	static final String REASON_EMPTY_ZONE = "EMPTY_ZONE";

	private final NamedZone zone;
	private final RotationPolicySelector selector;

	/** Pinned at doPreFlight after the empty-zone check. {@code null}
	 *  until then — {@link #navigationTarget} is only consulted AFTER
	 *  doPreFlight returns true. */
	@Nullable private WorldPoint chosenTile = null;

	/** Snapshot of {@code zone.tiles()} as a {@link Set} for O(1)
	 *  {@code contains} in {@link #isAtArrival}. Pinned alongside
	 *  {@link #chosenTile} so arrival semantics use the same tile set
	 *  the planner was given. */
	@Nullable private Set<WorldPoint> zoneTilesSet = null;

	public WalkToZoneStep(Artemis artemis, Consumer<StepEvent> stepEventSink,
		NamedZone zone, @Nullable Navigator navigator, RotationPolicySelector selector)
	{
		super(artemis, stepEventSink, navigator);
		if (zone == null)
		{
			throw new IllegalArgumentException("NamedZone must not be null");
		}
		if (selector == null)
		{
			throw new IllegalArgumentException("RotationPolicySelector must not be null");
		}
		this.zone = zone;
		this.selector = selector;
	}

	@Override
	public String name()
	{
		return "WalkToZone(" + zone.name() + ")";
	}

	@Override public int timeoutTicks()         { return TIMEOUT_TICKS; }
	@Override protected String targetType()     { return "zone"; }
	@Override protected String targetId()       { return "zone:" + zone.name(); }
	@Override protected String targetName()     { return zone.name(); }
	@Override protected String verb()           { return "Walk"; }

	@Override
	protected boolean doPreFlight(StepContext ctx)
	{
		List<WorldPoint> tiles = zone.tiles();
		if (tiles.isEmpty())
		{
			failOnStart(ctx, REASON_EMPTY_ZONE,
				"NamedZone." + zone.name() + " has no tiles populated — see NamedZone.java");
			return false;
		}
		// Snapshot now so a concurrent mutation between pick + arrival
		// checks can't desync (NamedZone is immutable today but the
		// snapshot makes that explicit).
		this.zoneTilesSet = Set.copyOf(tiles);
		this.chosenTile = selector.pick(tiles, t -> 0,
			new RotationPolicy.UniformWithinRange());
		if (chosenTile == null)
		{
			// Defensive — selector should never return null for a
			// non-empty list, but if it does, surface a clean failure
			// rather than NPE'ing in the worker.
			failOnStart(ctx, REASON_EMPTY_ZONE,
				"RotationPolicySelector returned null tile for non-empty zone "
					+ zone.name() + " — unexpected");
			return false;
		}
		return true;
	}

	@Override
	protected WorldPoint navigationTarget()
	{
		return chosenTile;
	}

	/** Test-only: exposes the tile picked by {@link RotationPolicySelector}
	 *  in {@link #doPreFlight}. Returns {@code null} before doPreFlight
	 *  runs or when EMPTY_ZONE short-circuited it. */
	@Nullable
	WorldPoint chosenTileForTesting()
	{
		return chosenTile;
	}

	@Override
	protected boolean isAtArrival(@Nullable WorldPoint here)
	{
		if (here == null || zoneTilesSet == null) return false;
		// Explicit plane gate as a cheap short-circuit before the hash
		// lookup; Set.contains() via WorldPoint.equals also compares
		// plane, so the contains call alone would correctly reject a
		// cross-plane match — the explicit check just documents the
		// zone's plane invariant and saves the hashCode/equals cycle on
		// cross-plane snapshots.
		return here.getPlane() == zone.plane() && zoneTilesSet.contains(here);
	}

	@Override
	protected String workerThreadName()
	{
		return "artemis-walk-zone-" + zone.name();
	}
}
