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

import javax.annotation.Nullable;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * A single step in a route. Either a plain walk target ({@link Kind#WALK}),
 * a walk into an arbitrary tile area ({@link Kind#WALK_AREA}) — runtime
 * samples one valid tile per visit so the bot never hits the same pixel
 * twice — or a transport interaction ({@link Kind#TRANSPORT}) that requires
 * the walker to find an on-tile object exposing a verb. Modeled as a
 * discriminated union with optional human-readable {@code name} for
 * status messages and route-overlay labels.
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
    private final WorldArea area;        // non-null for WALK_AREA
    private final TransportKind transportKind;
    private final String verb;
    private final String name;

    private Waypoint(Kind kind, WorldPoint tile, WorldArea area,
                     TransportKind tk, String verb, String name)
    {
        this.kind = kind;
        this.tile = tile;
        this.area = area;
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

    public static Waypoint walkArea(@Nullable String name, WorldArea area)
    {
        if (area == null) throw new IllegalArgumentException("area null");
        return new Waypoint(Kind.WALK_AREA, null, area, null, null, name);
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

    /** Single-tile target. Null for {@link Kind#WALK_AREA}. */
    @Nullable public WorldPoint tile() { return tile; }

    /** Tile area. For {@link Kind#WALK} this returns a 1x1 area at
     *  {@link #tile()} so consumers can treat WALK and WALK_AREA uniformly.
     *  Null for {@link Kind#TRANSPORT}. */
    @Nullable
    public WorldArea area()
    {
        if (kind == Kind.WALK_AREA) return area;
        if (kind == Kind.WALK) return new WorldArea(tile.getX(), tile.getY(), 1, 1, tile.getPlane());
        return null;
    }

    @Nullable public TransportKind transportKind() { return transportKind; }
    @Nullable public String verb() { return verb; }
    @Nullable public String name() { return name; }

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
                sb.append("walkbox:").append(area.getX()).append(',').append(area.getY())
                    .append(" - ").append(area.getX() + area.getWidth() - 1).append(',')
                    .append(area.getY() + area.getHeight() - 1)
                    .append(",p=").append(area.getPlane());
                break;
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
