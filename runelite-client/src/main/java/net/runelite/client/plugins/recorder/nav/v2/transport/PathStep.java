package net.runelite.client.plugins.recorder.nav.v2.transport;

/** Spec §3 contract: sealed root for the two step shapes the planner
 *  emits in interleaving order.
 *
 *  <p><b>Local mock note</b>: spec §3 declares this as
 *  {@code sealed interface PathStep permits WalkStep, TransportStep}.
 *  Lane 5 owns the canonical location ({@code nav/v2/PathStep.java}).
 *  Lane 4 ships it here because the planner constructs instances.
 *  Integration consolidates by deleting this and importing Lane 5's. */
public sealed interface PathStep permits WalkStep, TransportStep
{
}
