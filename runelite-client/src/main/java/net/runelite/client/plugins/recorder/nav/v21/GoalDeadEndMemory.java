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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Goal-aware blacklist of edges/anchors/blockers that recently failed
 *  to make progress for a specific goal bucket. The navigator consults
 *  this before re-trying an approach so it doesn't loop on the same
 *  bad door / stair for a destination it already failed to reach.
 *
 *  <p>Expiry is TTL-only (7 days). A successful run via a <em>different</em>
 *  path doesn't retroactively validate the previously failed approach,
 *  so we deliberately do NOT clear on success.
 *
 *  <p>Persisted as a flat-line sidecar at
 *  {@code ~/.runelite/recorder/worldmap/v21-deadends.json} via
 *  {@link #flushTo(Path)}, called from the existing FlushDaemon
 *  callback. The sidecar mirrors {@link
 *  net.runelite.client.plugins.recorder.worldmap.TransportIO}'s
 *  simple file-overwrite strategy — the dead-end set stays small
 *  enough that per-entry diffing isn't worth the complexity. */
public final class GoalDeadEndMemory
{
	private static final Logger log = LoggerFactory.getLogger(GoalDeadEndMemory.class);

	public static final long TTL_MS = 7L * 24L * 60L * 60L * 1000L;

	public record Entry(
		GoalDeadEndKey key,
		String reason,
		int failCount,
		long firstFailedAtMs,
		long lastFailedAtMs) {}

	private final Map<GoalDeadEndKey, Entry> byKey = new ConcurrentHashMap<>();
	private volatile boolean dirty;

	public GoalDeadEndMemory() {}

	/** True if the key has an entry whose last-failure timestamp is
	 *  within the TTL window. Entries past the TTL stay in the map
	 *  until the next load/flush cycle but are reported as not
	 *  dead-end. */
	public boolean isDeadEnd(GoalDeadEndKey k, long nowMs)
	{
		Entry e = byKey.get(k);
		if (e == null) return false;
		return nowMs - e.lastFailedAtMs() < TTL_MS;
	}

	/** Insert or update the entry for {@code k}. {@code reason} is a
	 *  short tag for diagnostics ("approach-bfs-fail", "transport-clicked-twice",
	 *  etc.). The {@code firstFailedAtMs} is preserved across updates so
	 *  ageing decisions can compare first-fail vs last-fail. */
	public void markDeadEnd(GoalDeadEndKey k, String reason, long nowMs)
	{
		byKey.compute(k, (key, existing) ->
		{
			if (existing == null)
			{
				return new Entry(key, reason, 1, nowMs, nowMs);
			}
			return new Entry(key, reason, existing.failCount() + 1,
				existing.firstFailedAtMs(), nowMs);
		});
		dirty = true;
	}

	/** Returns whether the in-memory state has changed since the last
	 *  call; clears the dirty flag. Mirrors {@code TransportIndex.takeDirty}
	 *  so FlushDaemon can no-op when nothing changed. */
	public boolean takeDirty()
	{
		boolean was = dirty;
		dirty = false;
		return was;
	}

	public Collection<Entry> snapshot()
	{
		return Collections.unmodifiableCollection(new ArrayList<>(byKey.values()));
	}

	public void replaceAll(Collection<Entry> loaded)
	{
		byKey.clear();
		if (loaded != null)
		{
			for (Entry e : loaded)
			{
				if (e != null && e.key() != null) byKey.put(e.key(), e);
			}
		}
		dirty = false;
	}

	/** Load the sidecar from {@code file}. Missing file is a cold-start
	 *  (no error logged). Malformed lines are skipped with a warning.
	 *  IO errors leave the in-memory state empty. */
	public void loadFrom(Path file)
	{
		if (!Files.exists(file)) return;
		try
		{
			List<String> lines = Files.readAllLines(file);
			List<Entry> loaded = new ArrayList<>();
			for (String line : lines)
			{
				if (line.isBlank()) continue;
				String[] parts = line.split("\\|\\|", -1);
				if (parts.length != 5)
				{
					log.warn("v21.deadends: skipping malformed line '{}'", line);
					continue;
				}
				try
				{
					GoalDeadEndKey k = GoalDeadEndKey.parseJson(parts[0]);
					Entry e = new Entry(k, parts[1],
						Integer.parseInt(parts[2]),
						Long.parseLong(parts[3]),
						Long.parseLong(parts[4]));
					loaded.add(e);
				}
				catch (RuntimeException ex)
				{
					log.warn("v21.deadends: skipping unparseable line '{}': {}", line, ex.toString());
				}
			}
			replaceAll(loaded);
			log.info("v21.deadends: loaded {} entries from {}", loaded.size(), file);
		}
		catch (IOException e)
		{
			log.warn("v21.deadends: failed to load from {}", file, e);
		}
	}

	/** Flush the in-memory state to {@code file} when dirty. Each line is
	 *  {@code <key>||<reason>||<failCount>||<firstFailedAtMs>||<lastFailedAtMs>}.
	 *  No-op when {@link #takeDirty} reports nothing changed. */
	public void flushTo(Path file)
	{
		if (!takeDirty()) return;
		try
		{
			StringBuilder sb = new StringBuilder();
			for (Entry e : byKey.values())
			{
				sb.append(e.key().toJsonString())
					.append("||").append(e.reason() == null ? "" : e.reason())
					.append("||").append(e.failCount())
					.append("||").append(e.firstFailedAtMs())
					.append("||").append(e.lastFailedAtMs())
					.append("\n");
			}
			Path parent = file.getParent();
			if (parent != null) Files.createDirectories(parent);
			Files.writeString(file, sb.toString());
		}
		catch (IOException e)
		{
			log.warn("v21.deadends: failed to flush to {}", file, e);
			// Re-set dirty so the next flush retries.
			dirty = true;
		}
	}
}
