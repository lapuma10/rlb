package net.runelite.client.plugins.recorder.nav.v2;

/** Spec §3 (Lane 1 contract): typed waypoint categories. Sidestepping is
 *  restricted to {@link #WALK} waypoints; the others either require exact
 *  tile presence or are anchors the executor must reach before
 *  proceeding. */
public enum WaypointType
{
    /** Tolerance >= 1; sidestep allowed within the bucket. */
    WALK,
    /** Exact tile required to interact with a transport object. */
    TRANSPORT_APPROACH,
    /** Executor performs an action verb here; exact tile required. */
    OBJECT_INTERACTION,
    /** Anchor at a region/scene boundary. */
    REGION_BRIDGE,
    /** User/trail-injected required-touch tile (sparse only). */
    SAFETY_ANCHOR
}
