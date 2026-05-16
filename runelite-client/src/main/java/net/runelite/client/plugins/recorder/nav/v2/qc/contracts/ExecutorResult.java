package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

/** Lane-6 scaffolding mirror of spec §3 {@code ExecutorResult}. */
public enum ExecutorResult
{
    WAYPOINT_REACHED,
    TRANSPORT_COMPLETED,
    PATH_COMPLETED,
    NEEDS_REPLAN,
    STUCK,
    FAILED
}
