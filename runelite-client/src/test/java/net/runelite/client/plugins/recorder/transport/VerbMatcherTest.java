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
 * LOSS OF USE, DATA, OR PROFITS; HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.transport;

import org.junit.Test;
import static org.junit.Assert.*;

public class VerbMatcherTest
{
    @Test
    public void exactMatch()
    {
        assertTrue(VerbMatcher.matches("Open", "Open"));
    }

    @Test
    public void caseInsensitive()
    {
        assertTrue(VerbMatcher.matches("open", "OPEN"));
        assertTrue(VerbMatcher.matches("Climb-up", "CLIMB-UP"));
    }

    @Test
    public void whitespaceTrimmed()
    {
        assertTrue(VerbMatcher.matches("  Open  ", "Open"));
        assertTrue(VerbMatcher.matches("Open", "  Open  "));
    }

    @Test
    public void hyphenSpaceEquivalent()
    {
        assertTrue(VerbMatcher.matches("Climb up", "Climb-up"));
        assertTrue(VerbMatcher.matches("Climb-up", "Climb up"));
        assertTrue(VerbMatcher.matches("climb up", "Climb-up"));
    }

    @Test
    public void colourTagsStripped()
    {
        // RuneLite menu options sometimes carry <col=...> tags.
        assertTrue(VerbMatcher.matches("Open", "<col=ff9040>Open"));
    }

    @Test
    public void mismatchReturnsFalse()
    {
        assertFalse(VerbMatcher.matches("Open", "Close"));
        assertFalse(VerbMatcher.matches("Climb-up", "Climb-down"));
    }

    @Test
    public void nullHandledGracefully()
    {
        assertFalse(VerbMatcher.matches(null, "Open"));
        assertFalse(VerbMatcher.matches("Open", null));
        assertFalse(VerbMatcher.matches(null, null));
    }
}
