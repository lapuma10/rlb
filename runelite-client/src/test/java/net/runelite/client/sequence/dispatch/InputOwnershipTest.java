package net.runelite.client.sequence.dispatch;

import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InputOwnershipTest {

    @Test
    public void firstAcquireWins() {
        InputOwnership io = new InputOwnership();
        assertTrue("first acquire must succeed", io.tryAcquire("alice"));
        assertEquals(Optional.of("alice"), io.currentOwner());
        assertTrue(io.isOwner("alice"));
    }

    @Test
    public void secondAcquireWhileHeldByDifferentTokenFails() {
        InputOwnership io = new InputOwnership();
        assertTrue(io.tryAcquire("alice"));
        assertFalse("second different-token acquire must fail", io.tryAcquire("bob"));
        // lease still held by alice
        assertEquals(Optional.of("alice"), io.currentOwner());
        assertFalse(io.isOwner("bob"));
    }

    @Test
    public void sameOwnerReAcquireIsIdempotent() {
        InputOwnership io = new InputOwnership();
        assertTrue(io.tryAcquire("alice"));
        assertTrue("same-owner re-acquire must return true", io.tryAcquire("alice"));
        assertEquals(Optional.of("alice"), io.currentOwner());
    }

    @Test
    public void onlyOwnerCanRelease() {
        InputOwnership io = new InputOwnership();
        io.tryAcquire("alice");
        assertFalse("non-owner release must return false", io.release("bob"));
        assertEquals(Optional.of("alice"), io.currentOwner());
        assertTrue("owner release must succeed", io.release("alice"));
        assertEquals(Optional.empty(), io.currentOwner());
    }

    @Test
    public void doubleReleaseIsHarmlessNoOp() {
        InputOwnership io = new InputOwnership();
        io.tryAcquire("alice");
        assertTrue(io.release("alice"));
        // second release: lease already null, compareAndSet("alice", null) returns false — no-op
        assertFalse("double-release must return false (no-op)", io.release("alice"));
        assertFalse("lease must remain free", io.currentOwner().isPresent());
    }

    @Test
    public void releaseByNonOwnerDoesNotClearLease() {
        InputOwnership io = new InputOwnership();
        assertTrue(io.tryAcquire("alice"));
        io.release("bob");   // no-op
        assertTrue("alice still owns after non-owner release attempt", io.isOwner("alice"));
    }

    @Test
    public void releaseAndReacquire() {
        InputOwnership io = new InputOwnership();
        io.tryAcquire("alice");
        io.release("alice");
        assertTrue(io.tryAcquire("bob"));
        assertEquals(Optional.of("bob"), io.currentOwner());
    }

    @Test
    public void noOwnerInitially() {
        InputOwnership io = new InputOwnership();
        assertEquals(Optional.empty(), io.currentOwner());
        assertFalse("isOwner returns false on empty lease", io.isOwner("alice"));
    }

    @Test
    public void nullTokenOperationsAreSafe() {
        // Regression for NPE: tryAcquire(null) / isOwner(null) / release(null) must
        // all be defined and return false rather than throw.
        InputOwnership io = new InputOwnership();
        assertFalse("tryAcquire(null) must return false", io.tryAcquire(null));
        assertFalse("isOwner(null) must return false on empty lease", io.isOwner(null));
        assertFalse("release(null) must return false on empty lease", io.release(null));
        assertFalse("lease still free after null operations", io.currentOwner().isPresent());

        // Same with a held lease
        assertTrue(io.tryAcquire("alice"));
        assertFalse("isOwner(null) must return false even when held", io.isOwner(null));
        assertFalse("release(null) must return false even when held", io.release(null));
        assertEquals("lease still held by alice", Optional.of("alice"), io.currentOwner());
    }
}
