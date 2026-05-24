package net.runelite.client.sequence.composite;

import java.util.Objects;
import java.util.function.Supplier;
import net.runelite.client.sequence.Completion;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.WorldSnapshot;
import net.runelite.client.sequence.blackboard.Blackboard;

/**
 * Composite that materializes its child {@link Step} at execution time via
 * a caller-supplied {@link Supplier}. The bridge between Artemis read
 * methods (which return {@link java.util.Optional Optional&lt;Ref&gt;}, not
 * Step) and Artemis action methods (which return Step but need a pre-
 * resolved ref).
 *
 * <p>Lifecycle (engine-driven):
 * <ol>
 *   <li>Engine creates a {@link DynamicStepFrame} via
 *       {@code StateDrivenEngine.makeFrame}.</li>
 *   <li>Engine invokes {@code pushFirstChildIfComposite}, which calls
 *       {@link #factory()}.get(). On success: child Step pushed as the
 *       frame's child. On {@code null}: frame fails with
 *       {@code DYNAMIC_FACTORY_RETURNED_NULL}. On {@link RuntimeException}:
 *       frame fails with {@code DYNAMIC_FACTORY_THREW:&lt;message&gt;}.</li>
 *   <li>Engine ticks the child Step normally. When the child completes,
 *       engine calls {@link #onChildPopped(DynamicStepFrame, Completion,
 *       WorldSnapshot, Blackboard)} which propagates the child's status
 *       as the DynamicStep's status.</li>
 * </ol>
 *
 * <p><b>Frame-isolated child state.</b> The materialized child reference
 * is stored in {@link DynamicStepFrame}, not in this instance. When
 * DynamicStep is the body of a {@link RepeatStep}, each iteration the
 * engine creates a fresh frame and re-invokes the supplier — fresh child
 * per iteration. The {@link DynamicStep} instance itself is reusable.
 *
 * <p>Typical usage from an Artemis script:
 *
 * <pre>{@code
 * Step body = DynamicStep.of("find-and-attack-cow", () -> {
 *     Optional<NpcRef> cow = artemis.findNpc(query);
 *     if (cow.isEmpty()) return FailStep.of("NO_COW_FOUND");
 *     return artemis.click(cow.get(), "Attack");
 * });
 * }</pre>
 *
 * <p>The supplier MUST produce a fresh {@link Step} on each call. Engine
 * Steps are stateful and single-use (see
 * {@code SequencePlanBuilderImpl.then(...)} for the precedent that
 * rejects same-instance re-adds).
 *
 * <p><b>Exception handling:</b> the engine catches only
 * {@link RuntimeException} from the supplier and converts it to
 * {@code DYNAMIC_FACTORY_THREW:&lt;class&gt;:&lt;message&gt;}.
 * {@link Error} (e.g. {@code AssertionError}, {@code OutOfMemoryError})
 * propagates per JVM convention — those represent JVM-level conditions
 * that should not be swallowed by user-supplied lambdas.
 */
public final class DynamicStep extends CompositeStep
{
	private final String name;
	private final Supplier<Step> factory;

	public static DynamicStep of(String name, Supplier<Step> factory)
	{
		return new DynamicStep(name, factory);
	}

	public DynamicStep(String name, Supplier<Step> factory)
	{
		this.name = Objects.requireNonNull(name, "name");
		this.factory = Objects.requireNonNull(factory, "factory");
	}

	@Override
	public String name()
	{
		return name;
	}

	@Override
	public int priority()
	{
		// CompositeStep doesn't default priority; each concrete composite
		// declares its own. DynamicStep can't borrow from the body (the
		// body doesn't exist until the supplier runs in the engine's
		// pushFirstChildIfComposite); default to 0.
		return 0;
	}

	/** Engine-internal accessor for {@code StateDrivenEngine.
	 *  pushFirstChildIfComposite}. Not intended for script callers; the
	 *  engine catches exceptions / null and converts them to the
	 *  {@code DYNAMIC_FACTORY_*} diagnostics. */
	public Supplier<Step> factory()
	{
		return factory;
	}

	/** Frame-aware orchestration overload — the engine calls this directly
	 *  via the {@code invokeOrchestration} switch. Mirrors
	 *  {@code RepeatStep.onChildPopped(RepeatStepFrame, Completion)} in
	 *  shape and intent. */
	public NextAction onChildPopped(DynamicStepFrame frame, Completion status,
		WorldSnapshot snap, Blackboard bb)
	{
		if (status instanceof Completion.Succeeded s)
		{
			return new FinishWithSuccess(s.reason());
		}
		if (status instanceof Completion.Failed f)
		{
			return new FinishWithFailure(f.reason());
		}
		// Defensive — engine should never pop a child with Running.
		// If a new Completion variant lands later, force the author to
		// extend this switch rather than silently mis-treating it.
		throw new IllegalStateException(
			"DynamicStep: unexpected child completion "
				+ status.getClass().getSimpleName());
	}

	/** Abstract base contract — never invoked at runtime; engine routes
	 *  to {@link #onChildPopped(DynamicStepFrame, Completion, WorldSnapshot,
	 *  Blackboard)} via the {@code invokeOrchestration} switch (see
	 *  {@code RepeatStep.java:46-48} for the same pattern). */
	@Override
	public NextAction onChildPopped(Step child, Completion status,
		WorldSnapshot state, Blackboard bb)
	{
		throw new UnsupportedOperationException(
			"DynamicStep uses the frame-aware overload — see invokeOrchestration");
	}
}
