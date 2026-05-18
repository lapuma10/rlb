package net.runelite.client.plugins.recorder.nav.v21;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Thread-marshalling helper for v2.1. Captures one
 *  {@link Snapshot} per navigator tick on the client thread:
 *  player tile, plane, dispatcher-busy flag, captured collision view.
 *  Everything downstream (planner, scanner, solver) reads from the
 *  immutable snapshot — no further client-thread hops mid-tick.
 *
 *  <p>The exception is the {@link BlockerScanner}, which reads live
 *  scene objects directly via {@link #onClient}. Scanner runs only
 *  when the planner returns a typed failure (BlockedEdge /
 *  PlaneMismatch), so it's at most one extra hop per tick, and only
 *  when we're already paused waiting for the world to respond.
 *
 *  <p>Inline fast-path: if {@link #onClient} is called while already
 *  on the client thread, it runs the supplier inline. Mirrors the
 *  same guard {@code V2ExecutorEnv.onClient} adopted after a
 *  production self-deadlock. */
public final class V21Env
{
	private static final Logger log = LoggerFactory.getLogger(V21Env.class);
	private static final long ON_CLIENT_TIMEOUT_MS = 1_500;

	private final Client client;
	private final ClientThread clientThread;
	private final HumanizedInputDispatcher dispatcher;
	private final BlockerScanner scanner;
	private final TransportIndex transports;
	private final TrailRegistry trails;
	private final GoalDeadEndMemory deadEnds;
	private final RouteSkeletonStore skeletons;

	public V21Env(Client client, ClientThread clientThread,
		HumanizedInputDispatcher dispatcher,
		TransportIndex transports,
		TrailRegistry trails,
		GoalDeadEndMemory deadEnds,
		RouteSkeletonStore skeletons)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.dispatcher = dispatcher;
		this.scanner = new BlockerScanner(client);
		this.transports = Objects.requireNonNull(transports, "transports");
		this.trails = Objects.requireNonNull(trails, "trails");
		this.deadEnds = Objects.requireNonNull(deadEnds, "deadEnds");
		this.skeletons = Objects.requireNonNull(skeletons, "skeletons");
	}

	public Client client() { return client; }
	public HumanizedInputDispatcher dispatcher() { return dispatcher; }
	public BlockerScanner scanner() { return scanner; }
	public TransportIndex transports() { return transports; }
	public TrailRegistry trails() { return trails; }
	public GoalDeadEndMemory deadEnds() { return deadEnds; }
	public RouteSkeletonStore skeletons() { return skeletons; }

	/** Capture the per-tick snapshot on the client thread. Single hop
	 *  reads player loc + plane + dispatcher-busy + live collision for
	 *  all four planes. The legacy {@link Snapshot#collision()} field
	 *  is the player-plane shorthand, equal to
	 *  {@code collisionByPlane[plane]}. */
	@Nullable
	public Snapshot snapshot()
	{
		return onClient(() ->
		{
			Player self = client.getLocalPlayer();
			WorldPoint here = self == null ? null : self.getWorldLocation();
			int plane = here == null ? -1 : here.getPlane();
			WorldView wv = client.getTopLevelWorldView();
			LiveCollisionView[] byPlane = LiveCollisionView.captureAllPlanes(wv);
			LiveCollisionView col = (plane < 0 || plane >= byPlane.length)
				? LiveCollisionView.EMPTY
				: byPlane[plane];
			return new Snapshot(here, plane, dispatcher.isBusy(),
				System.currentTimeMillis(), col, byPlane);
		});
	}

	/** Marshal {@code task} onto the client thread and return its
	 *  result. Inline fast-path when already on the client thread.
	 *  Returns null on timeout / interrupt — callers must handle. */
	@Nullable
	public <T> T onClient(Supplier<T> task)
	{
		if (client.isClientThread()) return task.get();
		AtomicReference<T> ref = new AtomicReference<>();
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invokeLater(() ->
		{
			try { ref.set(task.get()); }
			finally { latch.countDown(); }
		});
		try
		{
			if (!latch.await(ON_CLIENT_TIMEOUT_MS, TimeUnit.MILLISECONDS))
			{
				log.warn("v21.env: onClient TIMEOUT after {} ms", ON_CLIENT_TIMEOUT_MS);
				return null;
			}
		}
		catch (InterruptedException ie)
		{
			Thread.currentThread().interrupt();
			return null;
		}
		return ref.get();
	}

	/** Immutable per-tick world snapshot. All v2.1 components consume
	 *  this — no live client reads downstream of {@link V21Env#snapshot()}
	 *  except the {@link BlockerScanner} hop.
	 *
	 *  <p>{@link #collision} is the player-plane shorthand for
	 *  {@code collisionByPlane[plane]}; existing callers that only care
	 *  about the player's plane keep working. {@link #collisionByPlane}
	 *  is the new 4-element array indexed by plane (planes without flag
	 *  data are {@link LiveCollisionView#EMPTY}). */
	public record Snapshot(@Nullable WorldPoint playerTile,
		int plane,
		boolean dispatcherBusy,
		long nowMs,
		LiveCollisionView collision,
		LiveCollisionView[] collisionByPlane) {}
}
