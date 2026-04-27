/*
 * Copyright (c) 2025, https://github.com/runelite/runelite
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
package net.runelite.client.plugins.recorder.analyse;

import net.runelite.client.plugins.recorder.events.Events;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;

public class PhaseSegmenterTest
{
    @Test
    public void singlePhaseWhenContinuousActivity()
    {
        List<RecordedEvent> es = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            es.add(new Events.Tick(i, i * 600L, i, 0, 0, 0, 0, false, 100, false, 50, 99));
            es.add(new Events.MouseMove(i + 100, i * 600L + 50, i, i, i));
        }
        var phases = new PhaseSegmenter().segment(es);
        assertEquals(1, phases.size());
    }

    @Test
    public void markerCreatesPhaseBoundary()
    {
        List<RecordedEvent> es = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            es.add(new Events.Tick(i, i * 600L, i, 0, 0, 0, 0, false, 100, false, 50, 99));
        }
        es.add(new Events.Marker(50, 3000, 5, "deposit phase"));
        for (int i = 5; i < 10; i++) {
            es.add(new Events.Tick(i + 100, i * 600L, i, 0, 0, 0, 0, false, 100, false, 50, 99));
        }
        var phases = new PhaseSegmenter().segment(es);
        assertEquals(2, phases.size());
        assertEquals("deposit phase", phases.get(1).getMarkerLabel());
    }

    @Test
    public void softBoundaryFromIdleStretch()
    {
        // Long idle stretch (>= IDLE_TICKS=25) must split into two phases.
        // Each side of the idle window does substantial work so neither side
        // is dropped by the short-unlabeled-phase merge.
        List<RecordedEvent> es = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            es.add(new Events.MouseMove(i, i * 600L, i, i, i));
        }
        // Pad first phase past the merge floor (>= 2s of activity).
        for (int i = 0; i < 5; i++) {
            es.add(new Events.MouseMove(100 + i, 3500L + i * 100, 5, i, i));
        }
        for (int i = 6; i < 40; i++) {
            es.add(new Events.Tick(i + 200, i * 600L, i, 0, 0, 0, 0, true, 100, false, 50, 99));
        }
        // Second phase: > 2s of activity so the merge step does not absorb it.
        for (int i = 40; i < 50; i++) {
            es.add(new Events.MouseMove(i + 400, i * 600L, i, i, i));
        }
        var phases = new PhaseSegmenter().segment(es);
        assertTrue("expected idle gap to split phases, got " + phases.size(),
            phases.size() >= 2);
    }

    @Test
    public void shortUnlabeledPhaseIsMergedIntoPrevious()
    {
        // Set up: a labeled phase, then exactly enough idle to trigger the
        // soft-boundary split, then 1 trailing tick that lands within 2s of
        // the split. The trailing unlabeled phase is short enough to merge.
        List<RecordedEvent> es = new ArrayList<>();
        es.add(new Events.Marker(0, 0L, 0, "labeled phase"));
        es.add(new Events.MouseMove(1, 100L, 0, 0, 0));
        // 25 idle ticks (1..25). At tick=25, 25 - lastActivity(0) = 25 -> split.
        for (int t = 1; t <= 25; t++)
        {
            es.add(new Events.Tick(t + 10, t * 600L, t, 0, 0, 0, 0, true, 100, false, 50, 99));
        }
        // 1 more tick after the split — phase 2 spans tick25..tick26, ~600ms.
        es.add(new Events.Tick(100, 26 * 600L, 26, 0, 0, 0, 0, true, 100, false, 50, 99));

        var phases = new PhaseSegmenter().segment(es);
        // Without merge: 2 phases. With merge: the short unlabeled tail
        // (~600ms < 2s) folds into the marker-labeled phase, leaving 1.
        assertEquals(1, phases.size());
        assertEquals("labeled phase", phases.get(0).getMarkerLabel());
    }
}
