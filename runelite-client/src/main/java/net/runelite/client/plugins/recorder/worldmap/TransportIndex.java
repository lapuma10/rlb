package net.runelite.client.plugins.recorder.worldmap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.coords.WorldPoint;

/** In-memory store of fully-resolved {@link TransportEdge}s, keyed by
 *  {@link TransportEdge#key()}. Adding an edge whose key matches an
 *  existing entry merges into a {@link TransportEdge#bumpSeen} of the
 *  existing edge — duplicates collapse, seenCount accumulates.
 *
 *  <p>Thread-safe: backed by {@link ConcurrentHashMap}. Writers are the
 *  TransportObserver (event-bus thread) and TransportIO (loader at
 *  startup); readers include the inspection dumper and the V2 planner.
 *
 *  <p>Round-1 scope is single-process. Cross-process consistency is the
 *  IO layer's responsibility (final flush at shutdown reads from this
 *  index and writes the JSON snapshot). */
public final class TransportIndex
{
    private final Map<String, TransportEdge> byKey = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    /** Insert or merge. If an edge with the same key already exists,
     *  its seenCount is bumped and lastSeenAtMs is updated to the
     *  incoming edge's timestamp. The remaining fields of the existing
     *  edge are preserved — they were captured from the first
     *  observation and we trust that capture. */
    public void add(TransportEdge edge)
    {
        if (edge == null) return;
        byKey.merge(edge.key(), edge, (existing, incoming) ->
            existing.bumpSeen(incoming.lastSeenAtMs()));
        dirty = true;
    }

    /** Returns whether the in-memory state diverged from the last
     *  persisted snapshot, then clears the flag. The {@link
     *  net.runelite.client.plugins.recorder.worldmap.FlushDaemon} uses
     *  this to skip writes when nothing changed since the last flush. */
    public boolean takeDirty()
    {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    public int size()
    {
        return byKey.size();
    }

    public Collection<TransportEdge> getAll()
    {
        return Collections.unmodifiableCollection(new ArrayList<>(byKey.values()));
    }

    /** All transport edges originating from {@code from}. Useful for the
     *  V2 planner's neighbor expansion and for inspection dumps. */
    public List<TransportEdge> getOutgoing(WorldPoint from)
    {
        if (from == null) return List.of();
        List<TransportEdge> out = new ArrayList<>();
        for (TransportEdge e : byKey.values())
        {
            if (e.fromTile().equals(from)) out.add(e);
        }
        return out;
    }

    /** Replace all in-memory contents with the given edges — used by
     *  TransportIO when loading the persisted snapshot at plugin
     *  startup. Atomic: clears then bulk-inserts under the underlying
     *  map's locking. Does NOT mark dirty: in-memory and on-disk are
     *  in sync immediately after a load. */
    public void replaceAll(Collection<TransportEdge> edges)
    {
        byKey.clear();
        if (edges == null) return;
        for (TransportEdge e : edges)
        {
            if (e != null) byKey.put(e.key(), e);
        }
    }
}
