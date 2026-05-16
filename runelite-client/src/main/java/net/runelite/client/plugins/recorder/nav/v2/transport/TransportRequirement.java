package net.runelite.client.plugins.recorder.nav.v2.transport;

import net.runelite.client.plugins.recorder.nav.v2.planner.spi.NavigationContext;

/** Spec §3 contract: a gate that must be satisfied before the
 *  planner considers a {@link TransportLink} usable.
 *
 *  <p><b>Local mock note</b>: this interface is the spec §3
 *  {@code TransportRequirement} contract. Lane 1 owns the shape;
 *  Lane 4 ships builder instances in
 *  {@link TransportRequirementEvaluator}. Integration consolidates
 *  this with the canonical Lane 1 location.
 *
 *  <p>Contract:
 *  <ul>
 *    <li>{@link #satisfiedBy(NavigationContext)} is a pure read of
 *        the {@code NavigationContext}. No side effects. Must return
 *        the same answer for the same context.</li>
 *    <li>Composers ({@code requireAnd}, {@code requireOr}) live in
 *        {@link TransportRequirementEvaluator} as factory methods.</li>
 *  </ul>
 */
@FunctionalInterface
public interface TransportRequirement
{
	/** True iff the requirement is met for {@code ctx}. */
	boolean satisfiedBy(NavigationContext ctx);

	/** A requirement that is always satisfied (e.g. unrestricted door). */
	TransportRequirement NONE = ctx -> true;
}
