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

import net.runelite.api.coords.WorldPoint;

/**
 * A single step in a route. Either a plain walk target ({@link Kind#WALK})
 * or a transport interaction ({@link Kind#TRANSPORT}) that requires the walker
 * to find a tile object at the tile and dispatch a verb against it
 * (e.g. "Open" a door, "Climb-up" a ladder). Modeled as a discriminated record
 * so callers can {@code switch} on the kind without holding stringly-typed
 * shape data.
 */
public final class Waypoint
{
    public enum Kind { WALK, TRANSPORT }

    public enum TransportKind
    {
        /** Door / gate. Looks at WallObjects first; falls back to GameObjects. */
        OPEN("Open"),
        /** Stair / ladder / trapdoor going up. */
        CLIMB_UP("Climb-up"),
        /** Stair / ladder / trapdoor going down. */
        CLIMB_DOWN("Climb-down"),
        /** Generic verb (Use / Push / Pull / Search-for-traps / etc.) — the
         *  walker matches whatever string the user gave us. */
        INTERACT(null);

        private final String defaultVerb;
        TransportKind(String v) { this.defaultVerb = v; }
        public String defaultVerb() { return defaultVerb; }
    }

    private final Kind kind;
    private final WorldPoint tile;
    private final TransportKind transportKind;
    private final String verb;

    private Waypoint(Kind kind, WorldPoint tile, TransportKind tk, String verb)
    {
        this.kind = kind;
        this.tile = tile;
        this.transportKind = tk;
        this.verb = verb;
    }

    public static Waypoint walk(WorldPoint tile)
    {
        return new Waypoint(Kind.WALK, tile, null, null);
    }

    public static Waypoint transport(WorldPoint tile, TransportKind tk, String verb)
    {
        return new Waypoint(Kind.TRANSPORT, tile, tk, verb);
    }

    public Kind kind() { return kind; }
    public WorldPoint tile() { return tile; }
    public TransportKind transportKind() { return transportKind; }
    /** The exact menu verb to match (case-insensitive). For OPEN / CLIMB_UP
     *  / CLIMB_DOWN this is the default verb of the transport kind; for
     *  INTERACT it is whatever the user supplied after the colon. */
    public String verb() { return verb; }

    @Override
    public String toString()
    {
        if (kind == Kind.WALK)
        {
            return "walk:" + tile.getX() + "," + tile.getY() + ",p=" + tile.getPlane();
        }
        return transportKind.name().toLowerCase().replace('_', '-')
            + ":" + tile.getX() + "," + tile.getY() + ",p=" + tile.getPlane()
            + " verb='" + verb + "'";
    }
}
