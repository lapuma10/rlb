package net.runelite.client.plugins.recorder.nav.v21;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.coords.WorldPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records {@link RouteSkeleton}s for successful navigation runs.
 *  Record-only this round — no replay. The persisted sidecar is a
 *  diagnostic plus future corpus for the replay branch.
 *
 *  <p>Deduplicated by {@code (routeKey, goalPlane, goalBucketX,
 *  goalBucketY)} so a re-success of the same route replaces the prior
 *  skeleton. Path mirrors {@link GoalDeadEndMemory}'s sidecar
 *  ({@code ~/.runelite/recorder/worldmap/v21-skeletons.json}) and is
 *  flushed by the same {@code FlushDaemon} callback. */
public final class RouteSkeletonStore
{
	private static final Logger log = LoggerFactory.getLogger(RouteSkeletonStore.class);

	/** Deduplication key: {@code (routeKey-or-empty, goalPlane,
	 *  goalBucketX, goalBucketY)}. A re-success of the same route
	 *  replaces the prior skeleton. */
	private record DedupKey(String routeKey, int goalPlane, int gx, int gy)
	{
		static DedupKey of(RouteSkeleton s)
		{
			return new DedupKey(
				s.routeKey() == null ? "" : s.routeKey(),
				s.goalPlane(),
				s.goalBucketX(),
				s.goalBucketY());
		}
	}

	private final Map<DedupKey, RouteSkeleton> byKey = new ConcurrentHashMap<>();
	private volatile boolean dirty;

	public RouteSkeletonStore() {}

	public void recordSuccess(RouteSkeleton s)
	{
		if (s == null) return;
		byKey.put(DedupKey.of(s), s);
		dirty = true;
	}

	public Collection<RouteSkeleton> snapshot()
	{
		return Collections.unmodifiableCollection(new ArrayList<>(byKey.values()));
	}

	public boolean takeDirty()
	{
		boolean was = dirty;
		dirty = false;
		return was;
	}

	public void replaceAll(Collection<RouteSkeleton> loaded)
	{
		byKey.clear();
		if (loaded != null)
		{
			for (RouteSkeleton s : loaded)
			{
				if (s != null) byKey.put(DedupKey.of(s), s);
			}
		}
		dirty = false;
	}

	/** Line format (split by {@code "||"}):
	 *  <pre>
	 *  &lt;routeKeyOrEmpty&gt;||&lt;goalX&gt;||&lt;goalY&gt;||&lt;goalPlane&gt;||&lt;recordedAtMs&gt;||&lt;edgeKey1&gt;;;&lt;edgeKey2&gt;;;...
	 *  </pre>
	 *  Edge keys themselves use single {@code '|'} internally (matching
	 *  {@link net.runelite.client.plugins.recorder.worldmap.TransportEdge#key()}),
	 *  so the inner-list separator is {@code ";;"} to disambiguate. */
	public void loadFrom(Path file)
	{
		if (!Files.exists(file)) return;
		try
		{
			List<String> lines = Files.readAllLines(file);
			List<RouteSkeleton> loaded = new ArrayList<>();
			for (String line : lines)
			{
				if (line.isBlank()) continue;
				String[] parts = line.split("\\|\\|", -1);
				if (parts.length != 6)
				{
					log.warn("v21.skeletons: skipping malformed line '{}'", line);
					continue;
				}
				try
				{
					String rk = parts[0].isEmpty() ? null : parts[0];
					int gx = Integer.parseInt(parts[1]);
					int gy = Integer.parseInt(parts[2]);
					int gp = Integer.parseInt(parts[3]);
					long t = Long.parseLong(parts[4]);
					List<String> edges = parts[5].isEmpty()
						? List.of()
						: List.of(parts[5].split(";;", -1));
					loaded.add(new RouteSkeleton(rk, new WorldPoint(gx, gy, gp), gp, edges, t));
				}
				catch (RuntimeException ex)
				{
					log.warn("v21.skeletons: skipping unparseable line '{}': {}", line, ex.toString());
				}
			}
			replaceAll(loaded);
			log.info("v21.skeletons: loaded {} skeletons from {}", loaded.size(), file);
		}
		catch (IOException e)
		{
			log.warn("v21.skeletons: failed to load from {}", file, e);
		}
	}

	public void flushTo(Path file)
	{
		if (!takeDirty()) return;
		try
		{
			StringBuilder sb = new StringBuilder();
			for (RouteSkeleton s : byKey.values())
			{
				sb.append(s.routeKey() == null ? "" : s.routeKey())
					.append("||").append(s.goalCentroid().getX())
					.append("||").append(s.goalCentroid().getY())
					.append("||").append(s.goalPlane())
					.append("||").append(s.recordedAtMs())
					.append("||").append(String.join(";;", s.transportEdgeKeys()))
					.append("\n");
			}
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			Files.writeString(file, sb.toString());
		}
		catch (IOException e)
		{
			log.warn("v21.skeletons: failed to flush to {}", file, e);
			// Re-set dirty so the next flush retries.
			dirty = true;
		}
	}
}
