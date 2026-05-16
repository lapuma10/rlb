package net.runelite.client.plugins.recorder.nav.v2.transport;

/** Transport categories per spec §3.
 *
 *  <p><b>Local mock</b>: this enum is the spec §3 contract type
 *  ({@code TransportType}). Lane 1 owns the interface shape; Lane 4
 *  ships this concrete enum because the planner constructs and
 *  routes over typed instances. Integration consolidates this with
 *  any canonical Lane 1 location.
 *
 *  <p>Categories map onto Skretzo's TSV files at load time
 *  ({@link TransportTableLoader} infers type from the menuOption
 *  verb + file context). The planner treats each category uniformly
 *  for routing purposes — type drives <i>executor</i> dispatch
 *  (Lane 5), not the Dijkstra cost. */
public enum TransportType
{
	DOOR,
	GATE,
	STAIRS_UP,
	STAIRS_DOWN,
	AGILITY_SHORTCUT,
	FAIRY_RING,
	SPIRIT_TREE,
	TELEPORT_ITEM,
	TELEPORT_SPELL,
	CHARTER,
	REGION_LINK
}
