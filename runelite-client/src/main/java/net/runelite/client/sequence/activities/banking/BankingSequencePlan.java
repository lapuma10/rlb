package net.runelite.client.sequence.activities.banking;

import net.runelite.client.sequence.Step;

import java.util.List;

/**
 * Immutable plan produced by {@link BankingSequenceFactory}.
 *
 * <p>Holds a root {@link Step} (typically a {@link net.runelite.client.sequence.composite.LinearSequence})
 * and the reactive steps that should be registered on the engine before the root is started.
 *
 * @param root          the top-level step to pass to {@code SequenceManager.run(Step)}
 * @param reactiveSteps reactive steps to register via {@code SequenceManager.register(Step)}
 *                      before calling {@code run()}; these preempt the linear chain when their
 *                      {@code canStart()} fires
 */
public record BankingSequencePlan(Step root, List<Step> reactiveSteps)
{
    public BankingSequencePlan
    {
        reactiveSteps = List.copyOf(reactiveSteps);   // defensive immutable copy
    }
}
