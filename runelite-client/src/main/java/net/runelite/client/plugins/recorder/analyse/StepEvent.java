package net.runelite.client.plugins.recorder.analyse;

/**
 * Script-facing record produced when a sequence-engine Step transitions
 * lifecycle phase. {@link net.runelite.client.plugins.recorder.RecorderManager#recordStepEvent}
 * translates this into a persistence-shaped {@code Events.Step} and
 * enqueues it on the recording buffer alongside every other session
 * event, so post-hoc analyse code reads one stream of typed fields
 * (not stringified blobs).
 *
 * <p>The structured fields here are the data contract Phase 7's
 * diversity dashboard will read. Keep them typed and nullable — boxed
 * primitives ({@link Long} / {@link Integer}) so "absent" survives
 * serialization as a JSON {@code null} or omitted key, rather than
 * being indistinguishable from a real zero.
 *
 * @param name              identifier of the Step
 *                          (e.g. {@code "ClickNpc(cow)"},
 *                          {@code "OpenBank"}). Must not be null.
 * @param phase             lifecycle phase. Conventional values
 *                          (lower-case to match the wider event-type
 *                          vocabulary): {@code "started"},
 *                          {@code "succeeded"}, {@code "failed"},
 *                          {@code "aborted"}. Must not be null.
 * @param targetType        kind of target the Step acted on
 *                          (e.g. {@code "npc"}, {@code "object"},
 *                          {@code "item"}, {@code "widget"},
 *                          {@code "tile"}). Null when the Step has no
 *                          target.
 * @param targetId          structured target identity
 *                          (e.g. {@code "cow:1234"}, {@code "bank-booth:25808"}).
 *                          Format is producer-defined but should
 *                          encode both kind and disambiguator so the
 *                          dashboard can group correctly. Null when
 *                          targetType is null.
 * @param targetName        human-readable target name (e.g. {@code "Cow"},
 *                          {@code "Bank booth"}). Null when targetType
 *                          is null.
 * @param verb              menu verb dispatched against the target
 *                          (e.g. {@code "Attack"}, {@code "Bank"},
 *                          {@code "Open"}). Null for Steps that don't
 *                          dispatch a verb (e.g. walk-only,
 *                          idle, decision-only).
 * @param ticksElapsed      ticks between Step.onStart and this phase.
 *                          Null on {@code "started"} events; non-null
 *                          on all later phases.
 * @param diagnosticReason  machine-readable reason code for
 *                          {@code "failed"} / {@code "aborted"} phases
 *                          (e.g. {@code "STALE_REF"},
 *                          {@code "TARGET_NOT_FOUND"},
 *                          {@code "DISPATCHER_TIMEOUT"}). Null for
 *                          {@code "started"} / {@code "succeeded"}.
 * @param clickX            screen X of the dispatched click, post-resolution
 *                          via PixelResolver. Null when the Step did
 *                          not produce a click (e.g. walk-here, idle)
 *                          or when click resolution failed.
 * @param clickY            screen Y of the dispatched click. See
 *                          {@code clickX} for null semantics.
 * @param detail            free-form notes (retry attempt, additional
 *                          context). Should not duplicate the
 *                          structured fields above — this is for what
 *                          they cannot capture.
 */
public record StepEvent(
	String name,
	String phase,
	String targetType,
	String targetId,
	String targetName,
	String verb,
	Long ticksElapsed,
	String diagnosticReason,
	Integer clickX,
	Integer clickY,
	String detail
)
{
}
