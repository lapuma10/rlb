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

import lombok.Value;
import net.runelite.client.plugins.recorder.events.Events;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import java.util.ArrayList;
import java.util.List;

/** Walks the event stream and produces phase boundaries:
 *  hard boundaries on Marker events, soft boundaries on idle stretches
 *  (>= IDLE_TICKS ticks with no clicks AND no mousemoves). After
 *  segmentation, short unlabeled phases are merged into the previous phase
 *  to reduce noise from brief standing-still gaps (e.g., at the GE). */
public final class PhaseSegmenter
{
    /** Soft boundary threshold. 25 ticks = 15s — long enough to not split
     *  while waiting for a GE offer or chatting, short enough to mark
     *  travel→activity transitions. */
    private static final int IDLE_TICKS = 25;
    /** Phases shorter than this with no marker label are merged into the
     *  previous phase. */
    private static final long MERGE_UNLABELED_BELOW_MS = 2_000L;

    @Value
    public static class Phase
    {
        int id;
        long startedTMs;
        long endedTMs;
        String label;
        String markerLabel;
        int clickCount;
        int walkedTiles;
    }

    public List<Phase> segment(List<RecordedEvent> events)
    {
        if (events.isEmpty()) return List.of();
        List<int[]> boundaries = new ArrayList<>();   // [startIdx, endIdxExclusive]

        int phaseStart = 0;
        int lastActivityTick = events.get(0).tick();

        for (int i = 0; i < events.size(); i++)
        {
            RecordedEvent e = events.get(i);
            if (e instanceof Events.Marker mk)
            {
                if (i > phaseStart)
                {
                    boundaries.add(new int[]{phaseStart, i});
                }
                phaseStart = i;
                lastActivityTick = mk.tick();
            }
            if (isActivity(e))
            {
                lastActivityTick = e.tick();
            }
            else if (e instanceof Events.Tick tk)
            {
                if (tk.tick() - lastActivityTick >= IDLE_TICKS)
                {
                    if (i > phaseStart)
                    {
                        boundaries.add(new int[]{phaseStart, i});
                    }
                    phaseStart = i;
                    lastActivityTick = tk.tick();
                }
            }
        }
        if (phaseStart < events.size())
        {
            boundaries.add(new int[]{phaseStart, events.size()});
        }

        // Build phases; marker label is the first Marker event inside each boundary, if any.
        List<Phase> out = new ArrayList<>();
        for (int idx = 0; idx < boundaries.size(); idx++)
        {
            int from = boundaries.get(idx)[0];
            int to = boundaries.get(idx)[1];
            String marker = null;
            for (int j = from; j < to; j++)
            {
                if (events.get(j) instanceof Events.Marker m) { marker = m.label(); break; }
            }
            int clicks = 0;
            int walked = 0;
            int lastWx = Integer.MIN_VALUE, lastWy = Integer.MIN_VALUE;
            for (int j = from; j < to; j++)
            {
                RecordedEvent e = events.get(j);
                if (e instanceof Events.MenuClick) clicks++;
                if (e instanceof Events.Tick tk)
                {
                    if (lastWx != Integer.MIN_VALUE && (tk.worldX() != lastWx || tk.worldY() != lastWy)) walked++;
                    lastWx = tk.worldX(); lastWy = tk.worldY();
                }
            }
            out.add(new Phase(idx,
                events.get(from).tMs(),
                events.get(to - 1).tMs(),
                marker, marker, clicks, walked));
        }
        return mergeShortUnlabeled(out);
    }

    /** Roll up unlabeled phases shorter than MERGE_UNLABELED_BELOW_MS into the
     *  previous phase. Marker-bearing phases are never merged away. The first
     *  phase, if short and unlabeled, is left alone (no previous to merge to). */
    private static List<Phase> mergeShortUnlabeled(List<Phase> raw)
    {
        if (raw.size() < 2) return raw;
        List<Phase> merged = new ArrayList<>();
        merged.add(raw.get(0));
        for (int i = 1; i < raw.size(); i++)
        {
            Phase cur = raw.get(i);
            long dur = cur.getEndedTMs() - cur.getStartedTMs();
            boolean unlabeled = cur.getMarkerLabel() == null;
            if (unlabeled && dur < MERGE_UNLABELED_BELOW_MS)
            {
                Phase prev = merged.remove(merged.size() - 1);
                merged.add(new Phase(prev.getId(),
                    prev.getStartedTMs(), cur.getEndedTMs(),
                    prev.getLabel(), prev.getMarkerLabel(),
                    prev.getClickCount() + cur.getClickCount(),
                    prev.getWalkedTiles() + cur.getWalkedTiles()));
            }
            else
            {
                merged.add(new Phase(merged.size(),
                    cur.getStartedTMs(), cur.getEndedTMs(),
                    cur.getLabel(), cur.getMarkerLabel(),
                    cur.getClickCount(), cur.getWalkedTiles()));
            }
        }
        return merged;
    }

    private static boolean isActivity(RecordedEvent e)
    {
        return e instanceof Events.MouseMove
            || e instanceof Events.MouseDown
            || e instanceof Events.MouseUp
            || e instanceof Events.MenuClick
            || e instanceof Events.WidgetClick
            || e instanceof Events.WorldClick
            || e instanceof Events.Key
            || e instanceof Events.Wheel;
    }
}
