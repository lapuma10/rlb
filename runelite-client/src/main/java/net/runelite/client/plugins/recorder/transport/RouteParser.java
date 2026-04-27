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
import java.util.ArrayList;
import java.util.List;

/**
 * Parses route text-area lines into {@link Waypoint}s. Plain {@code x,y[,p]}
 * lines become walk waypoints; {@code open:}, {@code climb-up:},
 * {@code climb-down:}, and {@code interact:...:Verb} prefixes become
 * transport waypoints. Centralises the line grammar so the panel and tests
 * exercise the same parser.
 */
public final class RouteParser
{
    private RouteParser() {}

    /** Parse one waypoint per line, ignoring blank lines and {@code #} comments.
     *  Throws no exceptions — malformed lines are accumulated into
     *  {@link Result#errors()} so the caller can show a useful summary. */
    public static Result parse(String text)
    {
        List<Waypoint> ok = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (text == null) return new Result(ok, errors);
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++)
        {
            String raw = lines[i];
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            try
            {
                ok.add(parseLine(line));
            }
            catch (IllegalArgumentException ex)
            {
                errors.add("line " + (i + 1) + ": " + ex.getMessage());
            }
        }
        return new Result(ok, errors);
    }

    /** Parse a single non-empty, non-comment line. */
    public static Waypoint parseLine(String line)
    {
        if (line == null || line.isBlank())
            throw new IllegalArgumentException("empty line");
        // Detect a leading verb prefix by looking for ':' before any digit.
        // (We use ':' as the separator so a coordinate doesn't get confused
        // with the prefix parser.)
        int colon = line.indexOf(':');
        if (colon < 0 || hasDigitBefore(line, colon))
        {
            // Plain "x,y[,p]" walk waypoint.
            return Waypoint.walk(parseTile(line));
        }
        String prefix = line.substring(0, colon).trim().toLowerCase();
        String rest = line.substring(colon + 1).trim();
        switch (prefix)
        {
            case "walk":
                return Waypoint.walk(parseTile(rest));
            case "open":
                return Waypoint.transport(parseTile(rest), Waypoint.TransportKind.OPEN, "Open");
            case "climb-up":
            case "climbup":
                return Waypoint.transport(parseTile(rest), Waypoint.TransportKind.CLIMB_UP, "Climb-up");
            case "climb-down":
            case "climbdown":
                return Waypoint.transport(parseTile(rest), Waypoint.TransportKind.CLIMB_DOWN, "Climb-down");
            case "interact":
            {
                // interact:x,y[,p]:Verb — split the rest on the next ':'.
                int sep = rest.indexOf(':');
                if (sep < 0)
                    throw new IllegalArgumentException(
                        "interact requires a verb after the tile (interact:x,y[,p]:Verb)");
                WorldPoint tile = parseTile(rest.substring(0, sep).trim());
                String verb = rest.substring(sep + 1).trim();
                if (verb.isEmpty())
                    throw new IllegalArgumentException("interact verb cannot be empty");
                return Waypoint.transport(tile, Waypoint.TransportKind.INTERACT, verb);
            }
            default:
                throw new IllegalArgumentException("unknown prefix '" + prefix
                    + "' (expected open / climb-up / climb-down / interact / walk)");
        }
    }

    /** Parse {@code "x,y"} or {@code "x, y, plane"} into a {@link WorldPoint}.
     *  Plane defaults to 0 if omitted. */
    private static WorldPoint parseTile(String s)
    {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("missing tile");
        String[] parts = s.split("\\s*,\\s*");
        if (parts.length < 2) throw new IllegalArgumentException("tile needs at least x,y");
        try
        {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int plane = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            return new WorldPoint(x, y, plane);
        }
        catch (NumberFormatException nfe)
        {
            throw new IllegalArgumentException("non-numeric tile coordinate in '" + s + "'");
        }
    }

    private static boolean hasDigitBefore(String s, int idx)
    {
        for (int i = 0; i < idx; i++)
        {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    /** Outcome of a multi-line parse: the well-formed waypoints in order
     *  plus any per-line error messages so the panel can surface a "skipped
     *  N lines" summary without dropping legible waypoints. */
    public static final class Result
    {
        private final List<Waypoint> waypoints;
        private final List<String> errors;
        public Result(List<Waypoint> waypoints, List<String> errors)
        {
            this.waypoints = waypoints;
            this.errors = errors;
        }
        public List<Waypoint> waypoints() { return waypoints; }
        public List<String> errors() { return errors; }
        public boolean hasErrors() { return !errors.isEmpty(); }
    }
}
