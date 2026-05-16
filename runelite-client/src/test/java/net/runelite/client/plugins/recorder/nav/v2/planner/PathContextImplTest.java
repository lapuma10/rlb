package net.runelite.client.plugins.recorder.nav.v2.planner;

import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.CollisionFlags;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.CollisionView;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.NavigationContext;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.PlayerState;
import net.runelite.client.plugins.recorder.nav.v2.planner.spi.WorldSnapshot;
import net.runelite.client.plugins.recorder.nav.v2.transport.TransportTable;
import net.runelite.client.plugins.recorder.nav.v2.transport.Waypoint;
import net.runelite.client.plugins.recorder.nav.v2.transport.WaypointType;
import org.junit.Test;

import static org.junit.Assert.*;

public class PathContextImplTest
{
	private static WorldSnapshot stubWorld()
	{
		return new WorldSnapshot()
		{
			@Override public CollisionFlags collisionAt(WorldPoint p) { return new CollisionFlags(0); }
			@Override public CollisionView collisionView() { return p -> 0; }
			@Override public Set<WorldPoint> blockingActorTiles() { return Set.of(); }
			@Override public Set<WorldPoint> blockingObjectTiles() { return Set.of(); }
			@Override public TransportTable transports()
			{
				return new TransportTable(java.util.Collections.emptyList(), 0);
			}
			@Override public Object predicates() { return null; }
			@Override public long capturedAtMs() { return 0L; }
		};
	}

	private static PlayerState stubPlayer()
	{
		return new PlayerState()
		{
			@Override public int skillLevel(net.runelite.api.Skill s) { return 1; }
			@Override public int boostedLevel(net.runelite.api.Skill s) { return 1; }
			@Override public int varbit(int id) { return 0; }
			@Override public int varplayer(int id) { return 0; }
			@Override public net.runelite.api.ItemContainer inventory() { return null; }
			@Override public net.runelite.api.ItemContainer equipment() { return null; }
			@Override public boolean isMember() { return false; }
		};
	}

	private static NavigationContext ctx()
	{
		return new NavigationContextImpl(stubWorld(), stubPlayer(),
			NavRequest.toPoint(new WorldPoint(3200, 3200, 0), BehaviorMode.VARIED));
	}

	@Test
	public void planEntry_givesEmptyOptionals()
	{
		PathContextImpl pc = PathContextImpl.planEntry(ctx(), 42L);
		assertNotNull(pc.navigation());
		assertFalse(pc.currentPath().isPresent());
		assertFalse(pc.currentWaypoint().isPresent());
		assertEquals(42L, pc.routeSeed());
	}

	@Test
	public void withWaypoint_updatesOptionals_preservesSeed()
	{
		PathContextImpl pc = PathContextImpl.planEntry(ctx(), 12345L);
		Waypoint w = new Waypoint(new WorldPoint(3200, 3200, 0), 2, WaypointType.WALK);
		PathContextImpl pc2 = pc.withWaypoint(null, w);
		assertTrue(pc2.currentWaypoint().isPresent());
		assertEquals(w, pc2.currentWaypoint().get());
		assertEquals(12345L, pc2.routeSeed());
	}

	@Test
	public void navigationContextImpl_rejectsNullArgs()
	{
		try
		{
			new NavigationContextImpl(null, stubPlayer(),
				NavRequest.toPoint(new WorldPoint(0, 0, 0), BehaviorMode.VARIED));
			fail("expected IAE");
		}
		catch (IllegalArgumentException expected) {}
		try
		{
			new NavigationContextImpl(stubWorld(), null,
				NavRequest.toPoint(new WorldPoint(0, 0, 0), BehaviorMode.VARIED));
			fail("expected IAE");
		}
		catch (IllegalArgumentException expected) {}
		try
		{
			new NavigationContextImpl(stubWorld(), stubPlayer(), null);
			fail("expected IAE");
		}
		catch (IllegalArgumentException expected) {}
	}

	@Test
	public void pathContextImpl_rejectsNullNavigation()
	{
		try
		{
			new PathContextImpl(null, null, null, 0L);
			fail("expected IAE");
		}
		catch (IllegalArgumentException expected) {}
	}
}
