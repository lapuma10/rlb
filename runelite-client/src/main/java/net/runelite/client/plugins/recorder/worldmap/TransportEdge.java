package net.runelite.client.plugins.recorder.worldmap;

import net.runelite.api.coords.WorldPoint;

/** A fully-resolved transport edge: stairs, ladders, gates, doors, climb
 *  actions, pay-toll, etc. Captured by {@link TransportObserver} via the
 *  two-phase pending → resolution lifecycle and stored in {@link
 *  TransportIndex}.
 *
 *  <p>Half-formed edges (click captured, but no movement / plane change
 *  observed within the bounded resolution window) are discarded by the
 *  observer rather than persisted — see the spec's "do NOT persist
 *  half-formed transport edges" rule.
 *
 *  <p>{@code seenCount} and {@code lastSeenAtMs} are incremental usage
 *  stats — call {@link #bumpSeen} to update them after observing the
 *  same edge again. {@code observedDurationMs} is from the first
 *  observation and is not refreshed.
 *
 *  <p>{@code approachTile} defaults to {@link #fromTile} when null,
 *  since for most transports the player stands on the approach tile
 *  when clicking the transport object. Cases where they differ
 *  (clicking a door from a tile away) get a real value from the
 *  observer. */
public record TransportEdge(
    WorldPoint fromTile,
    WorldPoint toTile,
    int objectId,
    String objectName,
    String verb,
    int param0,
    int param1,
    String targetKind,
    WorldPoint approachTile,
    int regionId,
    int seenCount,
    long lastSeenAtMs,
    long observedDurationMs)
{
    public TransportEdge
    {
        if (fromTile == null) throw new IllegalArgumentException("fromTile null");
        if (toTile == null) throw new IllegalArgumentException("toTile null");
        if (verb == null || verb.isBlank()) throw new IllegalArgumentException("verb blank");
        if (objectName == null) objectName = "";
        if (targetKind == null) targetKind = "";
        if (approachTile == null) approachTile = fromTile;
    }

    /** Returns a copy with seenCount + 1 and lastSeenAtMs updated.
     *  Other fields are preserved — observedDurationMs is from the
     *  first observation by convention. */
    public TransportEdge bumpSeen(long timestampMs)
    {
        return new TransportEdge(fromTile, toTile, objectId, objectName, verb,
            param0, param1, targetKind, approachTile, regionId,
            seenCount + 1, timestampMs, observedDurationMs);
    }

    /** Composite key TransportIndex uses to dedupe captures of the same
     *  in-world transport across runs. fromTile + verb + objectId is
     *  enough to distinguish the engine's transport actions in
     *  practice; toTile is intentionally excluded so a door observed
     *  with a slightly different to-tile (player stepped through onto
     *  an adjacent tile) merges with the existing edge. */
    public String key()
    {
        return keyOf(fromTile, verb, objectId);
    }

    public static String keyOf(WorldPoint fromTile, String verb, int objectId)
    {
        return fromTile.getX() + "," + fromTile.getY() + "," + fromTile.getPlane()
            + "|" + verb + "|" + objectId;
    }
}
