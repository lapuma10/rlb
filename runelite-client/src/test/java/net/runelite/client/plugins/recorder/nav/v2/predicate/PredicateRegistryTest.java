package net.runelite.client.plugins.recorder.nav.v2.predicate;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests for {@link PredicateRegistry}. */
public class PredicateRegistryTest
{
    /** Minimal PathContext for tests. */
    private static PathContext ctx()
    {
        return new PathContext()
        {
            @Override public Object navigation() { return null; }
            @Override public Optional<Object> currentPath() { return Optional.empty(); }
            @Override public Optional<Object> currentWaypoint() { return Optional.empty(); }
            @Override public long routeSeed() { return 42; }
        };
    }

    @Test
    public void accepts_allTrue_returnsTrue()
    {
        PredicateRegistry r = new PredicateRegistry();
        r.register("a", (t, c) -> true);
        r.register("b", (t, c) -> true);
        assertTrue(r.accepts(new WorldPoint(1, 2, 0), ctx()));
    }

    @Test
    public void accepts_oneFalse_returnsFalse_andShortCircuits()
    {
        PredicateRegistry r = new PredicateRegistry();
        AtomicInteger calls = new AtomicInteger();
        r.register("first", (t, c) -> {
            calls.incrementAndGet();
            return false;
        });
        r.register("second", (t, c) -> {
            calls.incrementAndGet();
            return true;
        });
        boolean ok = r.accepts(new WorldPoint(0, 0, 0), ctx());
        assertFalse(ok);
        assertEquals("only the first predicate should be evaluated", 1, calls.get());
    }

    @Test
    public void firstRejectorOf_returnsCorrectName()
    {
        PredicateRegistry r = new PredicateRegistry();
        r.register("alpha", (t, c) -> true);
        r.register("beta", (t, c) -> false);
        r.register("gamma", (t, c) -> false);

        Optional<String> rej = r.firstRejectorOf(new WorldPoint(0, 0, 0), ctx());
        assertTrue(rej.isPresent());
        assertEquals("beta", rej.get());
    }

    @Test
    public void firstRejectorOf_noRejector_returnsEmpty()
    {
        PredicateRegistry r = new PredicateRegistry();
        r.register("alpha", (t, c) -> true);
        Optional<String> rej = r.firstRejectorOf(new WorldPoint(0, 0, 0), ctx());
        assertFalse(rej.isPresent());
    }

    @Test
    public void register_duplicateName_throws()
    {
        PredicateRegistry r = new PredicateRegistry();
        r.register("a", (t, c) -> true);
        try
        {
            r.register("a", (t, c) -> true);
            fail("expected IllegalStateException for duplicate name");
        }
        catch (IllegalStateException expected)
        {
            // ok
        }
    }

    @Test
    public void unregister_existing_removes()
    {
        PredicateRegistry r = new PredicateRegistry();
        r.register("a", (t, c) -> false);
        r.unregister("a");
        assertTrue("after unregister, no predicates remain", r.accepts(new WorldPoint(0, 0, 0), ctx()));
    }

    @Test
    public void unregister_missing_isNoOp()
    {
        PredicateRegistry r = new PredicateRegistry();
        r.unregister("nope"); // should not throw
        assertTrue(r.accepts(new WorldPoint(0, 0, 0), ctx()));
    }

    @Test
    public void predicate_throwsException_treatsAsRejectedAndLogs()
    {
        PredicateRegistry r = new PredicateRegistry();
        r.register("throws", (t, c) -> { throw new RuntimeException("boom"); });
        Optional<String> rej = r.firstRejectorOf(new WorldPoint(0, 0, 0), ctx());
        assertTrue(rej.isPresent());
        assertEquals("throws", rej.get());
        assertFalse(r.accepts(new WorldPoint(0, 0, 0), ctx()));
        // Note: WARN log emission is verified by reading server logs;
        // we don't assert on the log message contents here.
    }

    @Test
    public void addTileCondition_disallowedTile_rejectedByAccepts()
    {
        PredicateRegistry r = new PredicateRegistry();
        WorldPoint blocked = new WorldPoint(3210, 3210, 0);
        String name = r.addTileCondition(blocked, false);

        assertNotNull(name);
        assertTrue("name follows the script-tile-cond- prefix",
            name.startsWith("script-tile-cond-"));

        assertFalse("blocked tile rejected", r.accepts(blocked, ctx()));
        assertTrue("other tile still accepted", r.accepts(new WorldPoint(0, 0, 0), ctx()));
    }

    @Test
    public void addTileCondition_returnsGeneratedName_allowsUnregister()
    {
        PredicateRegistry r = new PredicateRegistry();
        WorldPoint t = new WorldPoint(3210, 3210, 0);
        String name = r.addTileCondition(t, false);
        r.unregister(name);
        assertTrue("after unregister, tile accepted again", r.accepts(t, ctx()));
    }

    @Test
    public void addTileCondition_overload_customPredicateScopedToTile()
    {
        PredicateRegistry r = new PredicateRegistry();
        WorldPoint t = new WorldPoint(3210, 3210, 0);
        AtomicInteger calls = new AtomicInteger();
        TilePredicate custom = (tile, c) -> {
            calls.incrementAndGet();
            return false;
        };
        String name = r.addTileCondition(t, custom);
        assertNotNull(name);

        // Custom predicate runs only for the scoped tile.
        assertFalse(r.accepts(t, ctx()));
        assertEquals("custom predicate evaluated for scoped tile", 1, calls.get());

        // Other tile: predicate accepts (the wrapper short-circuits).
        assertTrue(r.accepts(new WorldPoint(1, 1, 0), ctx()));
        assertEquals("custom predicate NOT evaluated for other tile", 1, calls.get());
    }

    @Test
    public void size_reflectsRegistered()
    {
        PredicateRegistry r = new PredicateRegistry();
        assertEquals(0, r.size());
        r.register("a", (t, c) -> true);
        r.register("b", (t, c) -> true);
        assertEquals(2, r.size());
        r.unregister("a");
        assertEquals(1, r.size());
    }
}
