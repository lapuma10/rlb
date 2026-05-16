package net.runelite.client.plugins.recorder.nav.v2.transport;

/** Spec §3 contract: classification of a {@link Waypoint}'s role in
 *  the planned route.
 *
 *  <p><b>Local mock note</b>: this enum is the spec §3 contract.
 *  Lane 1 owns the canonical location; Lane 4 ships it here because
 *  it is needed at planner-construct time. Integration moves to its
 *  canonical Lane 1 location. */
public enum WaypointType
{
	/** Normal walking. Sidestep allowed, tolerance ≥ 1. */
	WALK,

	/** Exact tile required to interact with a transport object
	 *  (door, gate, stairs). Tolerance = 0. */
	TRANSPORT_APPROACH,

	/** Executor performs an action verb at this tile. Tolerance = 0. */
	OBJECT_INTERACTION,

	/** Anchor at region / scene boundary. Tolerance ≥ 0 depending
	 *  on geometry. */
	REGION_BRIDGE,

	/** User / trail-injected required-touch tile. SPARSE ONLY per
	 *  spec §8 guardrail — never used to force exact replay. */
	SAFETY_ANCHOR
}
