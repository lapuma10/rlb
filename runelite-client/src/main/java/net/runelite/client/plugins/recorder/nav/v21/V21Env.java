package net.runelite.client.plugins.recorder.nav.v21;

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

	public V21Env(Client client, ClientThread clientThread,
		HumanizedInputDispatcher dispatcher)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.dispatcher = dispatcher;
		this.scanner = new BlockerScanner(client);
	}

	public Client client() { return client; }
	public HumanizedInputDispatcher dispatcher() { return dispatcher; }
	public BlockerScanner scanner() { return scanner; }

	/** Capture the per-tick snapshot on the client thread. Single hop
	 *  reads player loc + plane + dispatcher-busy + live collision for
	 *  the player's plane. */
	@Nullable
	public Snapshot snapshot()
	{
		return onClient(() ->
		{
			Player self = client.getLocalPlayer();
			WorldPoint here = self == null ? null : self.getWorldLocation();
			int plane = here == null ? -1 : here.getPlane();
			WorldView wv = client.getTopLevelWorldView();
			LiveCollisionView col = (here == null)
				? LiveCollisionView.EMPTY
				: LiveCollisionView.capture(wv, plane);
			return new Snapshot(here, plane, dispatcher.isBusy(),
				System.currentTimeMillis(), col);
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
	 *  except the {@link BlockerScanner} hop. */
	public record Snapshot(@Nullable WorldPoint playerTile,
		int plane,
		boolean dispatcherBusy,
		long nowMs,
		LiveCollisionView collision) {}
}
