package net.runelite.client.plugins.recorder.annotator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * Bounded LIFO stack of route snapshots. Push appends to the head; pop
 * removes from the head; on overflow the OLDEST entry is evicted (the
 * tail). Capacity is set at construction; the chicken-farm-bot annotator
 * uses 3.
 *
 * <p>Not thread-safe — meant to be driven from the EDT.
 */
public final class UndoStack
{
    private final int capacity;
    private final Deque<String> stack = new ArrayDeque<>();

    public UndoStack(int capacity)
    {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
        this.capacity = capacity;
    }

    public int size() { return stack.size(); }

    public void push(String snapshot)
    {
        stack.addFirst(snapshot);
        while (stack.size() > capacity) stack.removeLast();
    }

    public Optional<String> pop()
    {
        return Optional.ofNullable(stack.pollFirst());
    }

    public void clear()
    {
        stack.clear();
    }
}
