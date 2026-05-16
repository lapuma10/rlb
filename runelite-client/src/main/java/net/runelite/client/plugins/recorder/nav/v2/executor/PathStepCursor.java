package net.runelite.client.plugins.recorder.nav.v2.executor;

import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.runelite.client.plugins.recorder.nav.v2.PathStep;
import net.runelite.client.plugins.recorder.nav.v2.V2Path;

/** Lane 5 (plan Task 2): cursor that tracks the current {@link PathStep}
 *  index within a {@link V2Path}. Replaces the executor's prior bare
 *  {@code int legIdx} field with a typed API.
 *
 *  <p>Single-threaded; the executor's tick context owns the only
 *  instance. */
public final class PathStepCursor
{
    private final List<PathStep> steps;
    private int index;

    public PathStepCursor(V2Path path)
    {
        this.steps = path == null ? List.of() : path.steps();
        this.index = 0;
    }

    /** Current step (the one the executor should be driving), or empty
     *  when {@link #isAtEnd()} is true. */
    public Optional<PathStep> current()
    {
        if (isAtEnd()) return Optional.empty();
        return Optional.of(steps.get(index));
    }

    /** Step at {@code current + offset}, or empty if past the end. */
    public Optional<PathStep> peek(int offset)
    {
        int target = index + offset;
        if (target < 0 || target >= steps.size()) return Optional.empty();
        return Optional.of(steps.get(target));
    }

    /** Advance to the next step. No-op when {@link #isAtEnd()}. */
    public void advance()
    {
        if (index < steps.size()) index++;
    }

    /** True when the cursor has advanced past the last step. */
    public boolean isAtEnd() { return index >= steps.size(); }

    /** Number of steps remaining (including current). */
    public int remainingSteps() { return Math.max(0, steps.size() - index); }

    /** Zero-based current index. Exposed for diagnostics / log
     *  correlation; tests assert on this directly. */
    public int currentIndex() { return index; }

    /** Total step count in the underlying path. */
    public int totalSteps() { return steps.size(); }

    /** Convenience: the raw current step or null. Used by callers that
     *  already know {@code !isAtEnd()}. */
    @Nullable
    public PathStep currentOrNull()
    {
        if (isAtEnd()) return null;
        return steps.get(index);
    }
}
