package net.runelite.client.plugins.recorder.nav.v2.predicate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/** Ordered registry of named {@link TilePredicate}s. All predicates must
 *  accept a tile for the registry to accept it; the first rejection
 *  short-circuits.
 *
 *  <p>Used by both the planner (during BFS expansion) and the executor
 *  (during tile pick). The registry is the only mutation seam — predicates
 *  themselves are pure functions per spec §3.
 *
 *  <p>Thread safety: read-mostly. A {@link ReentrantReadWriteLock} guards
 *  the underlying map so {@link #register} / {@link #unregister} can be
 *  called from any thread while {@link #accepts} is being evaluated by the
 *  planner. Mutating during a {@code plan(...)} call is allowed but
 *  produces an inconsistent partial result; downstream lanes should
 *  prefer to capture the registry contents into a {@link WorldSnapshot}
 *  at plan-call entry.
 *
 *  <p>This class is part of the Lane 2 deliverable for the
 *  observation-aware navigation engine (spec §4 Lane 2). */
@Slf4j
public final class PredicateRegistry
{
    /** Predicate entry holding name + the predicate itself. */
    public static final class Entry
    {
        public final String name;
        public final TilePredicate predicate;
        Entry(String name, TilePredicate predicate)
        {
            this.name = name;
            this.predicate = predicate;
        }
    }

    /** Insertion-ordered map for deterministic evaluation order. */
    private final Map<String, TilePredicate> entries = new LinkedHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public PredicateRegistry() {}

    /** Register a named predicate. Duplicate names throw. */
    public void register(String name, TilePredicate predicate)
    {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name");
        if (predicate == null) throw new IllegalArgumentException("predicate");
        lock.writeLock().lock();
        try
        {
            if (entries.containsKey(name))
            {
                throw new IllegalStateException("predicate already registered: " + name);
            }
            entries.put(name, predicate);
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }

    /** Unregister a previously-registered predicate. No-op if absent. */
    public void unregister(String name)
    {
        lock.writeLock().lock();
        try
        {
            entries.remove(name);
        }
        finally
        {
            lock.writeLock().unlock();
        }
    }

    /** True iff every registered predicate accepts {@code tile}. Short-
     *  circuits on first reject. A predicate that throws is treated as
     *  REJECT and logged at WARN. */
    public boolean accepts(WorldPoint tile, PathContext ctx)
    {
        return firstRejectorOf(tile, ctx).isEmpty();
    }

    /** Returns the name of the first predicate that rejected {@code tile},
     *  or empty if all accept. Used in route traces. */
    public Optional<String> firstRejectorOf(WorldPoint tile, PathContext ctx)
    {
        lock.readLock().lock();
        try
        {
            for (Map.Entry<String, TilePredicate> e : entries.entrySet())
            {
                boolean ok;
                try
                {
                    ok = e.getValue().accept(tile, ctx);
                }
                catch (Throwable t)
                {
                    log.warn("[nav-v2-predicate] '{}' threw on tile {}: {} — treating as REJECT",
                        e.getKey(), tile, t.toString());
                    return Optional.of(e.getKey());
                }
                if (!ok)
                {
                    return Optional.of(e.getKey());
                }
            }
            return Optional.empty();
        }
        finally
        {
            lock.readLock().unlock();
        }
    }

    /** Number of registered predicates. */
    public int size()
    {
        lock.readLock().lock();
        try { return entries.size(); }
        finally { lock.readLock().unlock(); }
    }

    /** Convenience: register a predicate that accepts {@code tile} iff
     *  {@code allow}, and accepts all other tiles unconditionally. Used
     *  by scripts to disallow individual tiles (spec §6 Test 6).
     *
     *  @return the auto-generated name; pass to {@link #unregister} to
     *          remove the condition. */
    public String addTileCondition(WorldPoint tile, boolean allow)
    {
        String name = "script-tile-cond-" + UUID.randomUUID();
        register(name, (t, ctx) -> !t.equals(tile) || allow);
        return name;
    }

    /** Convenience: register a custom predicate scoped to one tile.
     *  Returns the auto-generated name. */
    public String addTileCondition(WorldPoint tile, TilePredicate predicate)
    {
        if (predicate == null) throw new IllegalArgumentException("predicate");
        String name = "script-tile-cond-" + UUID.randomUUID();
        register(name, (t, ctx) -> !t.equals(tile) || predicate.accept(t, ctx));
        return name;
    }
}
