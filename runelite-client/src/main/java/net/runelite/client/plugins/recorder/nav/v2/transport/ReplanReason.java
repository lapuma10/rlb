package net.runelite.client.plugins.recorder.nav.v2.transport;

/** Spec §3 contract: typed replan reasons returned by the executor
 *  and used by the planner to mark a path as failed.
 *
 *  <p><b>Local mock note</b>: Lane 5 owns the canonical location.
 *  Lane 4 ships this here so the planner can return typed failure
 *  paths. Integration consolidates. */
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
