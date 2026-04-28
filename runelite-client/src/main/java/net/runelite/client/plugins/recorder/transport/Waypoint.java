/*
 * Copyright (c) 2026, RuneLite
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.transport;

import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * A single step in a route. Either a plain walk target ({@link Kind#WALK}),
 * a walk into an arbitrary tile set ({@link Kind#WALK_AREA} — the bot picks
 * one valid tile per visit so it never hits the same pixel twice), or a
 * transport interaction ({@link Kind#TRANSPORT}) that requires the walker
 * to find an on-tile object exposing a verb.
 *
 * <p>For {@link Kind#WALK_AREA}, the source of truth is the immutable
 * {@code Set<WorldPoint>} stored in {@link #tiles()}. The legacy
 * {@link #area()} accessor returns the set's bounding-box {@link WorldArea}
 * — supports rectangular and irregular shapes. The route file format
 * serialises rectangular sets as {@code walkbox:} and irregular sets as
 * {@code walktiles:}; see {@link RouteParser}.
 */
public final class Waypoint
{
    public enum Kind { WALK, WALK_AREA, TRANSPORT }

    public enum TransportKind
    {
        OPEN("Open"),
        CLIMB_UP("Climb-up"),
        CLIMB_DOWN("Climb-down"),
        INTERACT(null);

        private final String defaultVerb;
        TransportKind(String v) { this.defaultVerb = v; }
        public String defaultVerb() { return defaultVerb; }
    }

    private final Kind kind;
    private final WorldPoint tile;       // non-null for WALK and TRANSPORT
    private final Set<WorldPoint> tiles; // non-null for WALK_AREA, immutable
    private final TransportKind transportKind;
    private final String verb;
    private final String name;
    // Cached bounding box for WALK_AREA — computed lazily.
    private volatile WorldArea cachedBbox;

    private Waypoint(Kind kind, WorldPoint tile, Set<WorldPoint> tiles,
                     TransportKind tk, String verb, String name)
    {
        this.kind = kind;
        this.tile = tile;
        this.tiles = tiles;
        this.transportKind = tk;
        this.verb = verb;
        this.name = name;
    }

    public static Waypoint walk(WorldPoint tile)
    {
        return new Waypoint(Kind.WALK, tile, null, null, null, null);
    }

    public static Waypoint walkNamed(String name, WorldPoint tile)
    {
        return new Waypoint(Kind.WALK, tile, null, null, null, name);
    }

    /** Construct a {@code WALK_AREA} waypoint from an arbitrary tile set. */
    public static Waypoint walkArea(@Nullable String name, Set<WorldPoint> tiles)
    {
        if (tiles == null || tiles.isEmpty())
            throw new IllegalArgumentException("walkArea tiles empty");
        int plane = tiles.iterator().next().getPlane();
        for (WorldPoint p : tiles)
        {
            if (p.getPlane() != plane)
                throw new IllegalArgumentException(
                    "walkArea tiles span multiple planes: " + plane + " vs " + p.getPlane());
        }
        return new Waypoint(Kind.WALK_AREA, null, Set.copyOf(tiles), null, null, name);
    }

    /** Construct a rectangular {@code WALK_AREA} waypoint. Convenience that
     *  fills every tile inside {@code area}. Existing callers (legacy parser,
     *  test fixtures) keep working without code changes. */
    public static Waypoint walkArea(@Nullable String name, WorldArea area)
    {
        if (area == null) throw new IllegalArgumentException("walkArea rect null");
        int w = area.getWidth();
        int h = area.getHeight();
        if (w <= 0 || h <= 0)
            throw new IllegalArgumentException(
                "walkArea rect has non-positive dimension: " + w + "x" + h);
        java.util.Set<WorldPoint> filled = new java.util.HashSet<>(w * h);
        for (int dx = 0; dx < w; dx++)
        {
            for (int dy = 0; dy < h; dy++)
            {
                filled.add(new WorldPoint(area.getX() + dx, area.getY() + dy, area.getPlane()));
            }
        }
        return walkArea(name, filled);
    }

    public static Waypoint transport(WorldPoint tile, TransportKind tk, String verb)
    {
        return new Waypoint(Kind.TRANSPORT, tile, null, tk, verb, null);
    }

    public static Waypoint transportNamed(String name, WorldPoint tile,
                                          TransportKind tk, String verb)
    {
        return new Waypoint(Kind.TRANSPORT, tile, null, tk, verb, name);
    }

    public Kind kind() { return kind; }

    @Nullable public WorldPoint tile() { return tile; }

    /** Immutable tile set. Source of truth for {@link Kind#WALK_AREA}.
     *  For {@link Kind#WALK} returns a one-element set. Empty set for
     *  {@link Kind#TRANSPORT}. */
    public Set<WorldPoint> tiles()
    {
        if (kind == Kind.WALK_AREA) return tiles;
        if (kind == Kind.WALK) return Set.of(tile);
        return Set.of();
    }

    /** Bounding box of the tile set. For {@link Kind#WALK} synthesises a
     *  1×1 area at the tile. Null for {@link Kind#TRANSPORT}. */
    @Nullable
    public WorldArea area()
    {
        if (kind == Kind.WALK_AREA)
        {
            if (cachedBbox == null) cachedBbox = computeBbox(tiles);
            return cachedBbox;
        }
        if (kind == Kind.WALK)
        {
            return new WorldArea(tile.getX(), tile.getY(), 1, 1, tile.getPlane());
        }
        return null;
    }

    /** True when {@link #tiles()} forms a perfect rectangle (every tile in
     *  the bounding box is present). Used by serialisation to choose between
     *  {@code walkbox:} and {@code walktiles:}. */
    public boolean isRectangular()
    {
        if (kind != Kind.WALK_AREA) return false;
        WorldArea a = area();
        return tiles.size() == a.getWidth() * a.getHeight();
    }

    @Nullable public TransportKind transportKind() { return transportKind; }
    @Nullable public String verb() { return verb; }
    @Nullable public String name() { return name; }

    private static WorldArea computeBbox(Set<WorldPoint> tiles)
    {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        int plane = 0;
        for (WorldPoint p : tiles)
        {
            plane = p.getPlane();
            if (p.getX() < minX) minX = p.getX();
            if (p.getY() < minY) minY = p.getY();
            if (p.getX() > maxX) maxX = p.getX();
            if (p.getY() > maxY) maxY = p.getY();
        }
        return new WorldArea(minX, minY, maxX - minX + 1, maxY - minY + 1, plane);
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        if (name != null) sb.append(name).append(": ");
        switch (kind)
        {
            case WALK:
                sb.append("walk:").append(tile.getX()).append(',').append(tile.getY())
                    .append(",p=").append(tile.getPlane());
                break;
            case WALK_AREA:
            {
                WorldArea a = area();
                if (isRectangular())
                {
                    sb.append("walkbox:").append(a.getX()).append(',').append(a.getY())
                        .append(" - ").append(a.getX() + a.getWidth() - 1).append(',')
                        .append(a.getY() + a.getHeight() - 1)
                        .append(",p=").append(a.getPlane());
                }
                else
                {
                    sb.append("walktiles:");
                    Iterator<WorldPoint> it = tiles.stream()
                        .sorted((p, q) -> p.getX() != q.getX()
                            ? Integer.compare(p.getX(), q.getX())
                            : Integer.compare(p.getY(), q.getY()))
                        .iterator();
                    boolean first = true;
                    while (it.hasNext())
                    {
                        WorldPoint p = it.next();
                        if (!first) sb.append(';');
                        sb.append(p.getX()).append(',').append(p.getY());
                        first = false;
                    }
                    sb.append(",p=").append(a.getPlane());
                }
                break;
            }
            case TRANSPORT:
                sb.append(transportKind.name().toLowerCase().replace('_', '-'))
                    .append(':').append(tile.getX()).append(',').append(tile.getY())
                    .append(",p=").append(tile.getPlane())
                    .append(" verb='").append(verb).append("'");
                break;
        }
        return sb.toString();
    }
}
