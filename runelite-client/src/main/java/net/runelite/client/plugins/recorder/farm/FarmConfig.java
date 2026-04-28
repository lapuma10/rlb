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
package net.runelite.client.plugins.recorder.farm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldArea;
import net.runelite.client.plugins.recorder.transport.RouteParser;
import net.runelite.client.plugins.recorder.transport.Waypoint;

/**
 * Loads the route file and indexes the bank / pen walk-areas by name. The
 * canonical names are {@code lumbridge_bank} and {@code chicken_pen}.
 * Everything between (in order) is the bank-to-pen route; reverse for
 * the return trip.
 */
public final class FarmConfig
{
    public static final String BANK_NAME = "lumbridge_bank";
    public static final String PEN_NAME = "chicken_pen";
    /** Tolerance in tiles for "I'm near the next waypoint" arrival checks. */
    public static final int ARRIVAL_TILE_TOLERANCE = 2;
    /** Tolerance in tiles for "I'm on the path" resume detection. */
    public static final int RESUME_TILE_TOLERANCE = 8;
    public static final Path DEFAULT_ROUTE_FILE = Paths.get(
        System.getProperty("user.home"),
        ".runelite", "sequencer", "routes", "lumbridge_bank_to_pen.txt");

    private final WorldArea bankArea;
    private final WorldArea penArea;
    private final List<Waypoint> routeBankToPen;

    private FarmConfig(WorldArea bank, WorldArea pen, List<Waypoint> route)
    {
        this.bankArea = bank;
        this.penArea = pen;
        this.routeBankToPen = List.copyOf(route);
    }

    public WorldArea bankArea() { return bankArea; }
    public WorldArea penArea() { return penArea; }
    public List<Waypoint> routeBankToPen() { return routeBankToPen; }

    /** Reverse of {@link #routeBankToPen()}; same waypoints, opposite order.
     *  Transports auto-skip if already-open so reusing the same waypoints is safe. */
    public List<Waypoint> routePenToBank()
    {
        return List.copyOf(reversed(routeBankToPen));
    }

    private static <T> List<T> reversed(List<T> in)
    {
        List<T> out = new ArrayList<>(in);
        Collections.reverse(out);
        return out;
    }

    public static FarmConfig load(Path routeFile) throws IOException
    {
        String text = Files.readString(routeFile);
        RouteParser.Result r = RouteParser.parse(text);
        if (r.hasErrors())
            throw new IllegalStateException("route file errors: " + r.errors());
        WorldArea bank = null;
        WorldArea pen = null;
        List<Waypoint> mid = new ArrayList<>();
        boolean afterBank = false;
        for (Waypoint w : r.waypoints())
        {
            if (BANK_NAME.equals(w.name()) && w.kind() == Waypoint.Kind.WALK_AREA)
            {
                bank = w.area();
                afterBank = true;
                continue;
            }
            if (PEN_NAME.equals(w.name()) && w.kind() == Waypoint.Kind.WALK_AREA)
            {
                pen = w.area();
                break;
            }
            if (afterBank) mid.add(w);
        }
        if (bank == null) throw new IllegalStateException(
            "route file missing '" + BANK_NAME + ":' walkbox");
        if (pen == null) throw new IllegalStateException(
            "route file missing '" + PEN_NAME + ":' walkbox");
        return new FarmConfig(bank, pen, mid);
    }
}
