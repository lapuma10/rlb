package net.runelite.client.plugins.recorder.nav.v2.qc.contracts;

/** Lane-6 scaffolding mirror of spec §3 {@code ReplanReason}. */
public enum ReplanReason
{
    NO_LOCAL_WALKABLE_TILE,
    TRANSPORT_UNAVAILABLE,
    REGION_NOT_LOADED,
    COLLISION_CHANGED,
    TARGET_UNREACHABLE,
    EXECUTOR_TIMEOUT,
    PREDICATE_DENIED_CORRIDOR
}
