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
        assertTrue(io.tryAcquire("alice"));
        assertEquals(Optional.of("alice"), io.currentOwner());
        assertTrue(io.isOwner("alice"));
    }

    @Test
    public void secondAcquireWhileHeldFails() {
        InputOwnership io = new InputOwnership();
        assertTrue(io.tryAcquire("alice"));
        assertFalse(io.tryAcquire("bob"));
        assertEquals(Optional.of("alice"), io.currentOwner());
        assertFalse(io.isOwner("bob"));
    }

    @Test
    public void onlyOwnerCanRelease() {
        InputOwnership io = new InputOwnership();
        io.tryAcquire("alice");
        assertFalse(io.release("bob"));      // wrong owner
        assertEquals(Optional.of("alice"), io.currentOwner());
        assertTrue(io.release("alice"));
        assertEquals(Optional.empty(), io.currentOwner());
    }

    @Test
    public void doubleReleaseIsHarmlessNoOp() {
        InputOwnership io = new InputOwnership();
        io.tryAcquire("alice");
        assertTrue(io.release("alice"));
        assertFalse(io.release("alice"));    // already released
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
        assertFalse(io.isOwner("alice"));
    }
}
