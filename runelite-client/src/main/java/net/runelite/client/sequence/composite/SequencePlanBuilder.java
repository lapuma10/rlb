package net.runelite.client.sequence.composite;

import net.runelite.client.sequence.Step;

/**
 * Fluent composer for sequence-engine plans. {@code Artemis.plan(name)}
 * returns one of these; scripts chain {@link #then(Step)} calls; the
 * sequence manager runs {@link #root()} to execute the composed plan.
 *
 * <p>Implementation lands with {@code ArtemisImpl} (Phase 1A.2 — not in
 * this slice). The interface alone exists here so Phase 1A.1's
 * {@code Artemis} interface compiles.
 */
public interface SequencePlanBuilder
{
	/** Append a {@link Step} to the plan; returns this builder for
	 *  fluent chaining. */
	SequencePlanBuilder then(Step step);

	/** The composed {@link Step} representing the whole plan — typically
	 *  a {@link LinearSequence} under the hood. Pass to
	 *  {@code SequenceManager.run(...)} to start execution. */
	Step root();
}
