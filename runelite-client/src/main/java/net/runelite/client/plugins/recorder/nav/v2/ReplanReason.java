package net.runelite.client.plugins.recorder.nav.v2;

/** Spec §3 (Lane 1 contract): typed reason attached to a {@link
 *  ExecutorResult#NEEDS_REPLAN} (or terminal failure). The navigator
 *  inspects this to decide whether the failure is transient (replan
 *  within budget) or terminal (propagate FAILED). */
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
