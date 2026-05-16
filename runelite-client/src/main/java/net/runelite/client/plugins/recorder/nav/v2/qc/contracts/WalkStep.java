package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

/** Lane-6 scaffolding mirror of spec §3 {@code WalkStep}. */
public non-sealed interface WalkStep extends PathStep
{
    Waypoint waypoint();
}
