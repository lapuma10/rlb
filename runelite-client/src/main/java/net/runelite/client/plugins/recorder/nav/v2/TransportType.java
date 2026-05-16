package net.runelite.client.plugins.recorder.nav.v2;

/** Spec §3 (Lane 1 contract): typed transport categories. The executor
 *  drives different click pipelines per type (e.g. doors and gates use
 *  a verb-on-adjacent-tile, stairs use a verb-on-tile). */
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
    REGION_LINK,
    /** Legacy fallback for transports recorded before the spec landed —
     *  any verb-on-object transport whose subtype isn't classified.
     *  The executor handles it identically to a single verb-on-object
     *  click. */
    OBJECT_VERB
}
