package net.runelite.client.plugins.recorder.nav.v2;

/** Spec §3 (Lane 1 contract): typed result of a single executor tick.
 *  Replaces the old bare-enum {@link V2Executor.Status} return for the
 *  per-tick result channel (the existing {@link V2Executor.Status} is
 *  kept for the executor's coarse lifecycle state). */
public enum ExecutorResult
{
    WAYPOINT_REACHED,
    TRANSPORT_COMPLETED,
    PATH_COMPLETED,
    NEEDS_REPLAN,
    STUCK,
    FAILED,
    /** Tick consumed without a terminal result — caller advances next tick. */
    IN_PROGRESS
}
