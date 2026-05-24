package net.runelite.client.sequence.composite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.runelite.client.sequence.Step;

/**
 * Mutable builder for {@code Artemis.plan(name).then(...).root()} chains
 * (Phase 1A.4e). Owns an internal {@link ArrayList} of {@link Step};
 * each {@link #root()} call snapshots the current state into a fresh
 * {@link LinearSequence} so subsequent {@link #then(Step)} calls cannot
 * leak into an already-returned sequence.
 *
 * <p>Validation is at the boundary: {@code null} or blank plan name
 * fails at construction; {@code null} Step in {@code then(...)} throws
 * immediately; the same Step <em>instance</em> added twice throws (by
 * identity, not {@code equals}) because engine Steps are stateful and
 * typically single-use. Failure short-circuits per spec §12.5, retries
 * are explicit per §12.6 — all driven by the engine's
 * {@link LinearSequence} contract, not this builder.
 */
public final class SequencePlanBuilderImpl implements SequencePlanBuilder
{
    private final String name;
    private final List<Step> steps = new ArrayList<>();

    public SequencePlanBuilderImpl(String name)
    {
        Objects.requireNonNull(name, "name");
        if (name.trim().isEmpty())
        {
            throw new IllegalArgumentException("plan name must not be blank");
        }
        this.name = name;
    }

    @Override
    public SequencePlanBuilder then(Step step)
    {
        Objects.requireNonNull(step, "step");
        // Reject same-instance duplicates by identity (==, not .equals).
        // Engine Steps are stateful and typically single-use —
        // WalkStepBase carries worker state, ArtemisActionStep tracks
        // lifecycle / failure state, action Steps pin dispatcher /
        // outcome state. Running the same instance in two plan slots
        // would re-enter onStart on already-spent state. Construct two
        // separate Steps for the same logical action instead.
        for (Step existing : steps)
        {
            if (existing == step)
            {
                throw new IllegalArgumentException(
                    "step instance already added to this plan; construct a "
                        + "fresh Step (engine Steps are stateful and single-use)");
            }
        }
        steps.add(step);
        return this;
    }

    @Override
    public Step root()
    {
        // Defensive copy — subsequent then(...) calls mutate the
        // builder's list, not the returned sequence's list. Verified
        // by SequencePlanBuilderImplTest.rootSnapshotsDoesNotLeakLaterThenCalls.
        return new LinearSequence(name, new ArrayList<>(steps));
    }
}
