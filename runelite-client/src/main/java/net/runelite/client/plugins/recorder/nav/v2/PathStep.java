package net.runelite.client.plugins.recorder.nav.v2;

/** Spec §3 (Lane 1 contract; Lane 5 file owner): sealed union of typed
 *  steps in a {@link V2Path}. A path is an ORDERED list of {@code
 *  PathStep}s — walk + transport interleaving is explicit, never
 *  implicit. */
public sealed interface PathStep permits WalkStep, TransportStep
{
}
