package net.runelite.client.plugins.recorder.nav.v2.transport;

import java.util.List;
import java.util.Optional;

/** Spec §3 contract: ordered list of {@link PathStep} entries
 *  (interleaved walks + transports) emitted by the planner.
 *
 *  <p><b>Local mock note</b>: Lane 5 owns the canonical interface
 *  location (flat {@code nav/v2/V2Path.java}). The existing
 *  {@code nav/v2/V2Path.java} is the OLD class that Lane 5 will
 *  replace with this interface shape at integration. Lane 4 ships
 *  this here so the planner can compile + emit {@code V2Path}
 *  instances now. Integration consolidates.
 *
 *  <p>Concrete implementation lives in
 *  {@link net.runelite.client.plugins.recorder.nav.v2.planner.V2PathImpl}. */
public interface V2Path
{
	/** Ordered steps. Walk/transport interleaving is explicit. */
	List<PathStep> steps();

	/** Stable identifier for caching / diagnostics. */
	PathId id();

	/** Wall-clock instant the plan was emitted. */
	long planEpochMs();

	/** True iff the planner returned a typed failure for this path. */
	boolean isFailed();

	/** If {@link #isFailed()}, the typed reason. Otherwise empty. */
	Optional<ReplanReason> failureReason();
}
