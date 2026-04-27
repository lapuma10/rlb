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
package net.runelite.client.sequence.login;

import org.junit.Test;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

public class HumanizedTypingTest
{
    /** A 100-char typing run should produce delays grouped into bursts —
     *  we expect (a) several long inter-burst pauses and (b) many more
     *  fast intra-burst delays than gaps. Uniform distribution would
     *  fail this. */
    @Test
    public void produces_letterGroupings()
    {
        // Fixed seed so the test is deterministic.
        HumanizedTyping ht = new HumanizedTyping(new Random(42L));
        List<Integer> delays = ht.sample(100);
        assertEquals(100, delays.size());

        long longGaps = delays.stream().filter(d -> d > 200).count();
        long fastBand = delays.stream().filter(d -> d >= 80 && d <= 250).count();

        // With pace factor in [0.8, 1.4] the fast band shifts up to
        // ~64..224ms; the gap band shifts to ~160..840ms. So "long" is
        // anything > 200ms (always above the fast band's hardest cap of
        // 160ms scaled by 1.4 = 224ms? Actually 224 > 200). To keep the
        // assertions robust to the pace factor we use 250ms as the gap
        // threshold and accept the fast band as being "small relative to
        // gaps".

        long pacedLongGaps = delays.stream().filter(d -> d > 250).count();
        long pacedFastBand = delays.stream().filter(d -> d <= 250).count();

        assertTrue("expected several gap delays > 250ms; got " + pacedLongGaps
            + " (pace=" + ht.getPaceFactor() + ", first 12=" + delays.subList(0, 12) + ")",
            pacedLongGaps >= 5);
        assertTrue("fast-band delays should outnumber gaps at least 2x; got fast="
            + pacedFastBand + " gap=" + pacedLongGaps,
            pacedFastBand >= 2 * pacedLongGaps);

        // Delays should never be zero or absurdly large.
        for (Integer d : delays)
        {
            assertTrue("delay must be positive: " + d, d > 0);
            assertTrue("delay must be plausible: " + d, d < 2000);
        }

        // pace factor should be in the documented range.
        assertTrue(ht.getPaceFactor() >= HumanizedTyping.PACE_MIN);
        assertTrue(ht.getPaceFactor() <= HumanizedTyping.PACE_MAX);
    }

    @Test
    public void delays_areNotUniform()
    {
        // Sanity: the variance should be high enough to flunk a uniform-
        // distribution null hypothesis at a hand-eye level.
        HumanizedTyping ht = new HumanizedTyping(new Random(1L));
        List<Integer> delays = ht.sample(200);
        // Basic stats
        double mean = delays.stream().mapToInt(Integer::intValue).average().orElse(0);
        double sumSq = 0;
        for (int d : delays) sumSq += (d - mean) * (d - mean);
        double std = Math.sqrt(sumSq / delays.size());
        // Stdev must be > ~50 — a uniform distribution of 80..160 only has
        // stdev ~23; with bursts and gaps mixed in we expect well over 50.
        assertTrue("stdev should be high — got " + std, std > 50);
    }

    @Test
    public void sample_repeatableWithFixedSeed()
    {
        HumanizedTyping a = new HumanizedTyping(new Random(7L));
        HumanizedTyping b = new HumanizedTyping(new Random(7L));
        assertEquals(a.sample(50), b.sample(50));
    }
}
