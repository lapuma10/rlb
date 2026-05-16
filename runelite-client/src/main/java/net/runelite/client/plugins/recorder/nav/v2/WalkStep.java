package net.runelite.client.plugins.recorder.nav.v2;

/** Spec §3 (Lane 1 contract; Lane 5 file owner): a walk segment in a
 *  {@link V2Path}. Carries one {@link Waypoint} which the executor's
 *  {@code SidestepResolver} fills with concrete tile picks. */
public non-sealed interface WalkStep extends PathStep
{
    Waypoint waypoint();
}
