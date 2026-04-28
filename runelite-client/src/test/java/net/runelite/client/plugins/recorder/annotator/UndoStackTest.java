package net.runelite.client.plugins.recorder.annotator;

import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.*;

public class UndoStackTest
{
    @Test
    public void emptyStackHasZeroSizeAndNoSnapshot()
    {
        UndoStack s = new UndoStack(3);
        assertEquals(0, s.size());
        assertEquals(Optional.empty(), s.pop());
    }

    @Test
    public void pushThenPopReturnsMostRecent()
    {
        UndoStack s = new UndoStack(3);
        s.push("v1");
        s.push("v2");
        s.push("v3");
        assertEquals(3, s.size());
        assertEquals(Optional.of("v3"), s.pop());
        assertEquals(Optional.of("v2"), s.pop());
        assertEquals(Optional.of("v1"), s.pop());
        assertEquals(Optional.empty(), s.pop());
    }

    @Test
    public void overflowEvictsOldest()
    {
        UndoStack s = new UndoStack(3);
        s.push("v1");
        s.push("v2");
        s.push("v3");
        s.push("v4"); // evicts v1
        assertEquals(3, s.size());
        assertEquals(Optional.of("v4"), s.pop());
        assertEquals(Optional.of("v3"), s.pop());
        assertEquals(Optional.of("v2"), s.pop());
        assertEquals(Optional.empty(), s.pop());
    }

    @Test
    public void capacityOneJustReplaces()
    {
        UndoStack s = new UndoStack(1);
        s.push("v1");
        s.push("v2");
        assertEquals(1, s.size());
        assertEquals(Optional.of("v2"), s.pop());
        assertEquals(Optional.empty(), s.pop());
    }

    @Test
    public void clearEmptiesTheStack()
    {
        UndoStack s = new UndoStack(3);
        s.push("v1");
        s.push("v2");
        s.clear();
        assertEquals(0, s.size());
        assertEquals(Optional.empty(), s.pop());
    }
}
