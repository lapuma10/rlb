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

/**
 * Single point of truth for matching a user-supplied verb against an OSRS
 * menu option. The two strings come from different sources — RuneLite's deob
 * exposes "Climb-up" verbatim, the user types "climb up", "Climb-Up", etc.
 * — so we normalise both before comparing.
 */
public final class VerbMatcher
{
    private VerbMatcher() {}

    /** True if {@code candidate} matches {@code wanted}. Case-insensitive,
     *  whitespace-trimmed, and hyphen-equivalent — i.e. "Climb up", "climb-up",
     *  "  CLIMB-UP " all match "Climb-up". RuneLite colour tags (e.g.
     *  {@code <col=ff9040>}) in the candidate are stripped before comparison
     *  so menu strings rendered in the UI still match.
     *
     *  <p>Returns {@code false} if either argument is null or blank. */
    public static boolean matches(String wanted, String candidate)
    {
        if (wanted == null || candidate == null) return false;
        return normalise(wanted).equals(normalise(candidate));
    }

    /** Lower-case, strip RuneLite colour tags, replace runs of whitespace
     *  and hyphens with a single hyphen. Empty input yields the empty string. */
    public static String normalise(String s)
    {
        if (s == null) return "";
        String stripped = s.replaceAll("<[^>]+>", "").trim().toLowerCase();
        if (stripped.isEmpty()) return "";
        StringBuilder out = new StringBuilder(stripped.length());
        boolean lastSep = false;
        for (int i = 0; i < stripped.length(); i++)
        {
            char ch = stripped.charAt(i);
            boolean sep = ch == '-' || ch == '_' || Character.isWhitespace(ch);
            if (sep)
            {
                if (!lastSep && out.length() > 0) out.append('-');
                lastSep = true;
            }
            else
            {
                out.append(ch);
                lastSep = false;
            }
        }
        // Strip any trailing separator from the rebuild.
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-')
            out.deleteCharAt(out.length() - 1);
        return out.toString();
    }
}
