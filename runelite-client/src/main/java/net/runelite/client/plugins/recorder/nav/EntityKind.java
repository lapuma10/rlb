package net.runelite.client.plugins.recorder.nav;

/** Phase-16 entity-targeted navigation: scripts ask for "go to a chicken /
 *  bank booth / cooking range" instead of computing a tile by hand. The
 *  Navigator resolves to the nearest known sighting from the world model
 *  and walks there.
 *
 *  <p>{@link #NPC} — moveable entity (Cook, Banker, Chicken). Resolved
 *  via {@code EntityIndex.findNpcsByName}.
 *  <br>{@link #OBJECT} — static interactive scenery (Bank booth, Range,
 *  Door). Resolved via {@code EntityIndex.findObjectsByName}.
 *  <br>{@link #AREA} — landmark / sub-region keyed by name (e.g. "Lumby
 *  bank lobby"). Round-1: not yet wired, but reserved so future scripts
 *  can request areas without a new enum value. */
public enum EntityKind
{
    NPC,
    OBJECT,
    AREA
}
