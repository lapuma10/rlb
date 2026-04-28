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

import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        // Strip inline annotator comments (separator is "  #" — two spaces + hash)
        // so saved routes like "open:3222,3222,0  # objId=1551" round-trip safely.
        int hashIdx = line.indexOf("  #");
        if (hashIdx >= 0) line = line.substring(0, hashIdx).trim();
        String name = null;
        String body = line;
        // Optional "name: <body>" prefix.  A name is a letter-starting token
        // before ':' that is immediately followed by a space (": "), so that
        // bare verb prefixes like "open:x,y" or "teleport:x,y" are NOT
        // mistakenly treated as named routes.  The body after stripping the
        // name must also start with a known verb prefix or a digit.
        int firstColon = line.indexOf(':');
        if (firstColon > 0 && firstColon + 1 < line.length()
            && line.charAt(firstColon + 1) == ' ')
        {
            String head = line.substring(0, firstColon);
            String candidateBody = line.substring(firstColon + 1).trim();
            if (looksLikeName(head) && bodyHasKnownStart(candidateBody))
            {
                name = head.trim();
                body = candidateBody;
            }
        }
        return parseBody(body, name);
    }

    /** Reserved verb prefixes that must never be treated as name tokens. */
    private static final Set<String> RESERVED_PREFIXES = new HashSet<>(Arrays.asList(
        "walk", "walkbox", "walktiles", "open", "interact",
        "climb-up", "climbup", "climb-down", "climbdown"
    ));

    /**
     * A name token must start with a letter and must not be a reserved verb
     * prefix and must not contain a comma (which would indicate a coordinate).
     */
    private static boolean looksLikeName(String head)
    {
        if (head.isBlank()) return false;
        String trimmed = head.trim().toLowerCase();
        if (RESERVED_PREFIXES.contains(trimmed)) return false;
        char first = trimmed.charAt(0);
        if (!Character.isLetter(first)) return false;
        for (int i = 0; i < head.length(); i++)
        {
            if (head.charAt(i) == ',') return false;
        }
        return true;
    }

    /**
     * Returns true if the candidate body starts with a digit (plain tile) or
     * a known verb prefix followed by ':'. This guards against treating
     * arbitrary unknown-prefix lines like "teleport:..." as named routes.
     */
    private static boolean bodyHasKnownStart(String body)
    {
        if (body.isEmpty()) return false;
        // Plain tile: starts with a digit
        if (Character.isDigit(body.charAt(0))) return true;
        // Known verb prefix
        int colon = body.indexOf(':');
        if (colon > 0)
        {
            String prefix = body.substring(0, colon).trim().toLowerCase();
            if (RESERVED_PREFIXES.contains(prefix)) return true;
        }
        return false;
    }

    private static Waypoint parseBody(String body, String name)
    {
        int colon = body.indexOf(':');
        if (colon < 0 || hasDigitBefore(body, colon))
        {
            // Plain "x,y[,p]"
            WorldPoint tile = parseTile(body);
            return name == null ? Waypoint.walk(tile) : Waypoint.walkNamed(name, tile);
        }
        String prefix = body.substring(0, colon).trim().toLowerCase();
        String rest = body.substring(colon + 1).trim();
        switch (prefix)
        {
            case "walk":
            {
                WorldPoint tile = parseTile(rest);
                return name == null ? Waypoint.walk(tile) : Waypoint.walkNamed(name, tile);
            }
            case "walkbox":
            {
                return Waypoint.walkArea(name, parseWalkbox(rest));
            }
            case "walktiles":
            {
                return Waypoint.walkArea(name, parseWalktiles(rest));
            }
            case "open":
            {
                WorldPoint t = parseTile(rest);
                return name == null
                    ? Waypoint.transport(t, Waypoint.TransportKind.OPEN, "Open")
                    : Waypoint.transportNamed(name, t, Waypoint.TransportKind.OPEN, "Open");
            }
            case "climb-up":
            case "climbup":
            {
                WorldPoint t = parseTile(rest);
                return name == null
                    ? Waypoint.transport(t, Waypoint.TransportKind.CLIMB_UP, "Climb-up")
                    : Waypoint.transportNamed(name, t, Waypoint.TransportKind.CLIMB_UP, "Climb-up");
            }
            case "climb-down":
            case "climbdown":
            {
                WorldPoint t = parseTile(rest);
                return name == null
                    ? Waypoint.transport(t, Waypoint.TransportKind.CLIMB_DOWN, "Climb-down")
                    : Waypoint.transportNamed(name, t, Waypoint.TransportKind.CLIMB_DOWN, "Climb-down");
            }
            case "interact":
            {
                int sep = rest.indexOf(':');
                if (sep < 0)
                    throw new IllegalArgumentException(
                        "interact requires a verb after the tile (interact:x,y[,p]:Verb)");
                WorldPoint t = parseTile(rest.substring(0, sep).trim());
                String verb = rest.substring(sep + 1).trim();
                if (verb.isEmpty())
                    throw new IllegalArgumentException("interact verb cannot be empty");
                return name == null
                    ? Waypoint.transport(t, Waypoint.TransportKind.INTERACT, verb)
                    : Waypoint.transportNamed(name, t, Waypoint.TransportKind.INTERACT, verb);
            }
            default:
                throw new IllegalArgumentException("unknown prefix '" + prefix
                    + "' (expected one of: " + String.join(", ", new java.util.TreeSet<>(RESERVED_PREFIXES)) + ")");
        }
    }

    /** Parse {@code "sw_x,sw_y - ne_x,ne_y[,plane]"} into a {@link WorldArea}. */
    private static WorldArea parseWalkbox(String s)
    {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("missing area");
        int sep = s.indexOf(" - ");
        if (sep < 0) throw new IllegalArgumentException(
            "walkbox needs sw and ne corners separated by ' - '");
        String swPart = s.substring(0, sep).trim();
        String nePart = s.substring(sep + 3).trim();
        String[] sw = swPart.split("\\s*,\\s*");
        String[] ne = nePart.split("\\s*,\\s*");
        if (sw.length < 2) throw new IllegalArgumentException("sw corner needs x,y");
        if (ne.length < 2) throw new IllegalArgumentException("ne corner needs x,y");
        try
        {
            int sx = Integer.parseInt(sw[0]);
            int sy = Integer.parseInt(sw[1]);
            int nx = Integer.parseInt(ne[0]);
            int ny = Integer.parseInt(ne[1]);
            if (nx < sx || ny < sy)
                throw new IllegalArgumentException(
                    "walkbox ne corner must be >= sw corner (got sw=" + sx + "," + sy
                        + " ne=" + nx + "," + ny + ")");
            // plane lives on the ne side: "sw - ne,plane" or "sw - ne,p=N"
            int plane = 0;
            if (ne.length >= 3)
            {
                String p = ne[2].trim();
                if (p.startsWith("p=")) p = p.substring(2);
                plane = Integer.parseInt(p);
            }
            else if (sw.length >= 3)
            {
                String p = sw[2].trim();
                if (p.startsWith("p=")) p = p.substring(2);
                plane = Integer.parseInt(p);
            }
            return new WorldArea(sx, sy, nx - sx + 1, ny - sy + 1, plane);
        }
        catch (NumberFormatException nfe)
        {
            throw new IllegalArgumentException("non-numeric coordinate in walkbox '" + s + "'");
        }
    }

    /** Parse {@code "x1,y1;x2,y2;...[,plane]"} into a tile set. The trailing
     *  ",plane" applies to every tile; per-tile planes are not allowed. */
    private static java.util.Set<WorldPoint> parseWalktiles(String s)
    {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("missing tiles");
        // The plane is everything after the LAST comma if it has the form
        // "p=N" or a bare integer. Split off the plane first so we don't confuse
        // it with a tile coord.
        int plane = 0;
        int lastComma = s.lastIndexOf(',');
        String pairs = s;
        if (lastComma >= 0)
        {
            String tail = s.substring(lastComma + 1).trim();
            if (tail.startsWith("p=")) tail = tail.substring(2);
            try
            {
                plane = Integer.parseInt(tail);
                pairs = s.substring(0, lastComma).trim();
            }
            catch (NumberFormatException ignored)
            {
                // Last segment isn't a plane — treat the whole string as pairs.
            }
        }
        String[] tokens = pairs.split("\\s*;\\s*");
        if (tokens.length == 0) throw new IllegalArgumentException("walktiles needs at least one tile");
        java.util.Set<WorldPoint> out = new java.util.HashSet<>(tokens.length);
        for (String tok : tokens)
        {
            if (tok.isBlank()) continue;
            String[] xy = tok.split("\\s*,\\s*");
            if (xy.length != 2)
                throw new IllegalArgumentException(
                    "walktiles tile must be 'x,y' (got '" + tok + "')");
            int x, y;
            try
            {
                x = Integer.parseInt(xy[0]);
                y = Integer.parseInt(xy[1]);
            }
            catch (NumberFormatException nfe)
            {
                throw new IllegalArgumentException("non-numeric coordinate in walktiles tile '" + tok + "'");
            }
            out.add(new WorldPoint(x, y, plane));
        }
        if (out.isEmpty())
            throw new IllegalArgumentException("walktiles produced no tiles");
        return out;
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
