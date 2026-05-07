package net.runelite.client.plugins.recorder.nav.v2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;

/** A complete plan from a start tile to a destination tile, decomposed
 *  into walk legs and transport legs.
 *
 *  <p>{@link #routeId} is a stable hash over the leg sequence
 *  (transport-edge keys + walk start/end tiles). Two paths with the
 *  same routeId are interchangeable from {@link
 *  net.runelite.client.plugins.recorder.nav.v2.RouteHistory}'s point
 *  of view. The hash deliberately ignores intermediate walk tiles —
 *  two A* runs with different tile-level noise on the same macro
 *  route share a routeId. */
public final class V2Path
{
    private final List<V2Leg> legs;
    private final int totalCost;
    private final String routeId;

    public V2Path(List<V2Leg> legs, int totalCost)
    {
        if (legs == null) throw new IllegalArgumentException("legs null");
        this.legs = List.copyOf(legs);
        this.totalCost = totalCost;
        this.routeId = computeRouteId(this.legs);
    }

    public List<V2Leg> legs() { return legs; }
    public int totalCost() { return totalCost; }
    public String routeId() { return routeId; }
    public boolean isEmpty() { return legs.isEmpty(); }

    /** All tiles in the path, in execution order. Walk-leg tiles are
     *  inlined; transport legs contribute their fromTile and toTile.
     *  Useful for the minimap overlay's blue-route paint. */
    public List<WorldPoint> allTiles()
    {
        return legs.stream()
            .flatMap(leg -> {
                if (leg instanceof V2Leg.Walk w) return w.tiles().stream();
                if (leg instanceof V2Leg.Transport t)
                    return java.util.stream.Stream.of(t.edge().fromTile(), t.edge().toTile());
                return java.util.stream.Stream.empty();
            })
            .collect(Collectors.toUnmodifiableList());
    }

    /** Stable hash over the leg sequence. Walk legs contribute their
     *  start + end (not intermediate tiles); transport legs contribute
     *  the {@link TransportEdge#key()}. SHA-1 hex truncated to 16 chars
     *  — collision probability is negligible for the small route sets
     *  V2 produces. */
    private static String computeRouteId(List<V2Leg> legs)
    {
        StringBuilder sb = new StringBuilder();
        for (V2Leg leg : legs)
        {
            if (leg instanceof V2Leg.Walk w)
            {
                sb.append("W|").append(w.regionId()).append('|');
                sb.append(fingerprint(w.start())).append('-').append(fingerprint(w.end()));
            }
            else if (leg instanceof V2Leg.Transport t)
            {
                sb.append("T|").append(t.edge().key());
            }
            sb.append(';');
        }
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8 && i < digest.length; i++)
                hex.append(String.format("%02x", digest[i] & 0xff));
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            // SHA-1 is in every JRE; fall back to the raw string anyway.
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private static String fingerprint(WorldPoint p)
    {
        return p.getX() + "," + p.getY() + "," + p.getPlane();
    }

    public static final V2Path EMPTY = new V2Path(List.of(), 0);
}
