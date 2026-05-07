package net.runelite.client.plugins.recorder.worldmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.coords.WorldPoint;

/** In-memory NPC + object sighting index. Mirrors persisted state from
 *  entities/*.json + regions/*.json (objects[]).
 *
 *  Thread-safety: scraper writes on the client thread; planner reads on
 *  the worker thread. byKey is a ConcurrentHashMap; byName uses
 *  CopyOnWriteArrayList per name (writes are infrequent compared to
 *  reads, so the COW cost is dominated by the read benefit); byRegion
 *  uses ConcurrentHashMap with COW lists. All reads return a stable view. */
public final class EntityIndex
{
    /** Keyed by (kind, id) — one entry per concrete NPC/object id, updated
     *  in-place. */
    private final Map<Long, EntitySighting> byKey = new ConcurrentHashMap<>();
    /** Name → list of byKey keys, most-recent first. */
    private final Map<String, java.util.concurrent.CopyOnWriteArrayList<Long>> byName
        = new ConcurrentHashMap<>();
    /** RegionId → list of byKey keys whose lastTile is in that region.
     *  Used by the flush daemon to write entities/<regionId>.json. */
    private final Map<Integer, java.util.concurrent.CopyOnWriteArrayList<Long>> byRegion
        = new ConcurrentHashMap<>();
    /** Region IDs whose entity list has changed since the last flush. */
    private final java.util.Set<Integer> dirtyRegions
        = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void recordNpcSighting(int id, String name, WorldPoint tile, long now)
    {
        record(EntitySighting.Kind.NPC, id, name, tile, now);
    }

    public void recordObjectSighting(int id, String name, WorldPoint tile, long now)
    {
        record(EntitySighting.Kind.OBJECT, id, name, tile, now);
    }

    private void record(EntitySighting.Kind kind, int id, String name,
                        WorldPoint tile, long now)
    {
        long key = ((long) kind.ordinal() << 32) | (id & 0xffffffffL);
        EntitySighting prev = byKey.get(key);
        EntitySighting next = (prev == null)
            ? new EntitySighting(kind, id, name, tile, 1, now)
            : prev.withUpdatedSighting(tile, now);
        byKey.put(key, next);
        byName.computeIfAbsent(name,
            k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(0, key);
        int regionId = RegionIds.regionIdFor(tile.getX(), tile.getY());
        byRegion.computeIfAbsent(regionId,
            k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        if (!byRegion.get(regionId).contains(key))
        {
            byRegion.get(regionId).add(key);
        }
        dirtyRegions.add(regionId);
    }

    public List<EntitySighting> findNpcsByName(String name)
    {
        return findByName(name, EntitySighting.Kind.NPC);
    }

    public List<EntitySighting> findObjectsByName(String name)
    {
        return findByName(name, EntitySighting.Kind.OBJECT);
    }

    private List<EntitySighting> findByName(String name, EntitySighting.Kind kind)
    {
        List<Long> keys = byName.get(name);
        if (keys == null) return List.of();
        // byName tracks every record() call, including repeat sightings of
        // the same (kind,id) — dedupe via a Set so the caller sees one
        // entry per concrete entity. Without this, two recordNpcSighting
        // calls for the same Cook would surface as two list entries even
        // though byKey has a single in-place-updated record.
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<EntitySighting> out = new ArrayList<>();
        for (Long k : keys)
        {
            if (!seen.add(k)) continue;
            EntitySighting s = byKey.get(k);
            if (s != null && s.kind == kind) out.add(s);
        }
        out.sort(Comparator.comparingLong((EntitySighting s) -> s.lastSeenAt).reversed());
        return out;
    }

    /** All NPC sightings in {@code regionId}. Used by the flush daemon. */
    public List<EntitySighting> npcsInRegion(int regionId)
    {
        List<Long> keys = byRegion.get(regionId);
        if (keys == null) return List.of();
        List<EntitySighting> out = new ArrayList<>();
        for (Long k : keys)
        {
            EntitySighting s = byKey.get(k);
            if (s != null && s.kind == EntitySighting.Kind.NPC) out.add(s);
        }
        return out;
    }

    /** All object sightings in {@code regionId}. Mirror of {@link #npcsInRegion}
     *  for the OBJECT kind — used by the inspection dumper. */
    public List<EntitySighting> objectsInRegion(int regionId)
    {
        List<Long> keys = byRegion.get(regionId);
        if (keys == null) return List.of();
        List<EntitySighting> out = new ArrayList<>();
        for (Long k : keys)
        {
            EntitySighting s = byKey.get(k);
            if (s != null && s.kind == EntitySighting.Kind.OBJECT) out.add(s);
        }
        return out;
    }

    /** All region IDs with at least one recorded sighting. Snapshot, safe to
     *  iterate without holding a lock — the underlying map is concurrent. */
    public java.util.Set<Integer> knownRegionIds()
    {
        return new java.util.HashSet<>(byRegion.keySet());
    }

    /** Atomically take and clear the dirty-region set for entities. */
    public java.util.Set<Integer> takeDirtyRegionIds()
    {
        java.util.Set<Integer> snapshot = new java.util.HashSet<>(dirtyRegions);
        dirtyRegions.removeAll(snapshot);
        return snapshot;
    }

    public Optional<EntitySighting> nearestNpc(String name, WorldPoint from)
    {
        return nearest(findNpcsByName(name), from);
    }

    public Optional<EntitySighting> nearestObject(String name, WorldPoint from)
    {
        return nearest(findObjectsByName(name), from);
    }

    private Optional<EntitySighting> nearest(List<EntitySighting> sightings, WorldPoint from)
    {
        if (sightings.isEmpty()) return Optional.empty();
        EntitySighting best = null;
        int bestDist = Integer.MAX_VALUE;
        long bestSeen = -1;
        for (EntitySighting s : sightings)
        {
            int d = chebyshev(s.lastTile, from);
            if (d < bestDist || (d == bestDist && s.lastSeenAt > bestSeen))
            {
                best = s; bestDist = d; bestSeen = s.lastSeenAt;
            }
        }
        return Optional.of(best);
    }

    private static int chebyshev(WorldPoint a, WorldPoint b)
    {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }
}
