package net.runelite.client.sequence.composite;

import javax.annotation.Nullable;
import lombok.Getter;
import lombok.Setter;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.internal.StepFrame;

/**
 * Per-execution frame for {@link DynamicStep}. Mirrors {@link RepeatStepFrame}
 * / {@link SelectorFrame} / {@link LinearSequenceFrame} in shape: a thin
 * StepFrame subclass holding only what the engine needs to drive this
 * composite for one execution.
 *
 * <p>{@link #materializedChild} is the Step the supplier returned in
 * {@code StateDrivenEngine.pushFirstChildIfComposite}. Telemetry / post-
 * mortem only — the engine already has its own frame for the child on the
 * stack and does not consult this field for orchestration. Held to make
 * "what Step did the supplier produce this iteration?" answerable from a
 * frame dump.
 */
public final class DynamicStepFrame extends StepFrame
{
	@Getter @Setter @Nullable private Step materializedChild;

	public DynamicStepFrame(DynamicStep step, int depth)
	{
		super(step, depth);
	}
}
