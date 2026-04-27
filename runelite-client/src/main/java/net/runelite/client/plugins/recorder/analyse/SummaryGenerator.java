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
import net.runelite.client.plugins.recorder.session.MetaJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/** Pure analytical pass over an in-memory event list; produces summary.md. */
public final class SummaryGenerator
{
    /** Maps an item id to a human-readable name. Production wires this to
     *  ItemManager; tests can pass id->"id=N" or anything stable. */
    private final IntFunction<String> itemName;

    public SummaryGenerator()
    {
        this(id -> "id=" + id);
    }

    public SummaryGenerator(IntFunction<String> itemName)
    {
        this.itemName = itemName == null ? id -> "id=" + id : itemName;
    }

    public String generate(List<RecordedEvent> events, List<PhaseSegmenter.Phase> phases, MetaJson meta)
    {
        StringBuilder s = new StringBuilder();
        s.append("# Recording summary\n\n");
        appendOverview(s, events, meta);
        appendMovement(s, events);
        appendPhases(s, phases);
        appendInteractionInventory(s, events);
        appendItemDeltas(s, events);
        appendBankDeltas(s, events);
        appendChat(s, events);
        appendClickDistributions(s, events);
        appendCursorApproach(s, events);
        appendHotkeys(s, events);
        appendRunEnergyCamera(s, events);
        appendMarkers(s, events);
        return s.toString();
    }

    private void appendOverview(StringBuilder s, List<RecordedEvent> events, MetaJson meta)
    {
        long clicks = events.stream().filter(e -> e instanceof Events.MenuClick).count();
        long walked = events.stream().filter(e -> e instanceof Events.Tick).count();
        double meanGap = meanInterClickMs(events);
        s.append("## Overview\n\n");
        s.append("- **Intent:** ").append(meta.getIntentLabel()).append('\n');
        s.append("- **Duration:** ").append(meta.getDurationMs() / 1000).append("s\n");
        s.append("- **Character:** ").append(meta.getCharacterName()).append(" (world ").append(meta.getWorld()).append(")\n");
        s.append("- **Client mode:** ").append(meta.isFixedMode() ? "fixed" : "resizable").append('\n');
        s.append("- **Total clicks:** ").append(clicks).append('\n');
        s.append("- **Ticks observed:** ").append(walked).append('\n');
        s.append(String.format("- **Mean inter-click delay:** %.0f ms%n%n", meanGap));
    }

    private double meanInterClickMs(List<RecordedEvent> events)
    {
        long prev = -1;
        long sum = 0;
        int n = 0;
        for (RecordedEvent e : events)
        {
            if (e instanceof Events.MenuClick)
            {
                if (prev != -1) { sum += e.tMs() - prev; n++; }
                prev = e.tMs();
            }
        }
        return n == 0 ? 0 : (double) sum / n;
    }

    /** Path summary: start, end, bbox, distance, walk targets. Computed
     *  from the Tick stream (player position once per tick) and from
     *  WorldClick/entityKind=ground events (explicit walk-here clicks). */
    private void appendMovement(StringBuilder s, List<RecordedEvent> events)
    {
        s.append("## Movement\n\n");
        Events.Tick first = null, last = null;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        long distance = 0;
        int prevX = Integer.MIN_VALUE, prevY = Integer.MIN_VALUE;
        int tilesChanged = 0;
        for (RecordedEvent e : events)
        {
            if (!(e instanceof Events.Tick tk)) continue;
            if (first == null) first = tk;
            last = tk;
            int x = tk.worldX(), y = tk.worldY();
            if (x == 0 && y == 0) continue; // unloaded position
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
            if (prevX != Integer.MIN_VALUE && (x != prevX || y != prevY))
            {
                distance += Math.abs(x - prevX) + Math.abs(y - prevY);
                tilesChanged++;
            }
            prevX = x; prevY = y;
        }
        if (first == null)
        {
            s.append("_(no ticks recorded)_\n\n");
            return;
        }
        s.append("- **Start:** (").append(first.worldX()).append(", ").append(first.worldY())
            .append(", plane=").append(first.worldPlane()).append(")\n");
        s.append("- **End:** (").append(last.worldX()).append(", ").append(last.worldY())
            .append(", plane=").append(last.worldPlane()).append(")\n");
        if (minX != Integer.MAX_VALUE)
        {
            s.append("- **Bounding box:** x=").append(minX).append("..").append(maxX)
                .append(" y=").append(minY).append("..").append(maxY)
                .append(" (").append(maxX - minX).append("x").append(maxY - minY).append(")\n");
        }
        s.append("- **Tiles changed:** ").append(tilesChanged).append('\n');
        s.append("- **Manhattan distance:** ").append(distance).append(" tiles\n");

        // Three sources of "walk happened":
        //   ground       — explicit "Walk here" menu action on the main view
        //   minimap_walk — explicit SET_HEADING menu action (right-click minimap)
        //   walk_dest    — engine-observed destination change (catches silent
        //                  left-click minimap walks too); emitted per tick.
        List<Events.WorldClick> walks = events.stream()
            .filter(e -> e instanceof Events.WorldClick wc
                && ("ground".equals(wc.entityKind())
                    || "minimap_walk".equals(wc.entityKind())
                    || "walk_dest".equals(wc.entityKind())))
            .map(e -> (Events.WorldClick) e)
            .collect(Collectors.toList());
        long minimapHits = walks.stream().filter(w -> "minimap_walk".equals(w.entityKind())).count();
        long destObserved = walks.stream().filter(w -> "walk_dest".equals(w.entityKind())).count();
        long groundHits = walks.size() - minimapHits - destObserved;
        if (walks.isEmpty())
        {
            s.append("- **Walk targets:** _(none observed)_\n\n");
        }
        else
        {
            s.append("- **Walk targets:** ").append(walks.size())
                .append(" (").append(groundHits).append(" ground, ")
                .append(minimapHits).append(" minimap, ")
                .append(destObserved).append(" engine-observed)\n");
            int max = Math.min(walks.size(), 20);
            for (int i = 0; i < max; i++)
            {
                Events.WorldClick wc = walks.get(i);
                String src;
                switch (wc.entityKind())
                {
                    case "minimap_walk": src = " [minimap]"; break;
                    case "walk_dest":    src = " [engine]"; break;
                    default:             src = ""; break;
                }
                s.append("  - t=").append(wc.tMs()).append("ms (")
                    .append(wc.worldX()).append(", ").append(wc.worldY()).append(")")
                    .append(src).append('\n');
            }
            if (walks.size() > max)
            {
                s.append("  - … ").append(walks.size() - max).append(" more\n");
            }
            s.append('\n');
        }
    }

    private void appendPhases(StringBuilder s, List<PhaseSegmenter.Phase> phases)
    {
        s.append("## Phases\n\n");
        if (phases.isEmpty()) { s.append("_(none)_\n\n"); return; }
        s.append("| # | Label | Duration | Clicks | Walked tiles |\n");
        s.append("|---|-------|----------|--------|--------------|\n");
        for (var p : phases)
        {
            long dur = p.getEndedTMs() - p.getStartedTMs();
            s.append("| ").append(p.getId()).append(" | ")
                .append(p.getLabel() == null ? "_unlabeled_" : p.getLabel()).append(" | ")
                .append(dur / 1000).append("s | ")
                .append(p.getClickCount()).append(" | ")
                .append(p.getWalkedTiles()).append(" |\n");
        }
        s.append('\n');
    }

    private void appendInteractionInventory(StringBuilder s, List<RecordedEvent> events)
    {
        s.append("## Interaction inventory\n\n");
        Map<Integer, String> npcsHit = new TreeMap<>();
        Map<Integer, Long> objectsClicked = new TreeMap<>();
        Map<String, String> playersInteracted = new TreeMap<>();
        Map<String, Long> widgetsClicked = new TreeMap<>();
        long itemSlotClicks = 0;
        for (RecordedEvent e : events)
        {
            if (e instanceof Events.WidgetClick wc)
            {
                widgetsClicked.merge(wc.widgetKind(), 1L, Long::sum);
                if (wc.itemId() > 0) itemSlotClicks++;
            }
            if (e instanceof Events.WorldClick wc)
            {
                if ("npc".equals(wc.entityKind())) npcsHit.put(wc.entityId(), wc.entityName());
                else if ("object".equals(wc.entityKind())) objectsClicked.merge(wc.entityId(), 1L, Long::sum);
                else if ("player".equals(wc.entityKind()))
                {
                    String key = wc.entityName() == null || wc.entityName().isBlank()
                        ? "id=" + wc.entityId() : wc.entityName();
                    playersInteracted.put(key, wc.entityName());
                }
            }
        }
        s.append("- **Item-slot clicks:** ").append(itemSlotClicks).append('\n');
        s.append("- **NPCs interacted with:** ").append(npcsHit.size()).append('\n');
        for (var en : npcsHit.entrySet())
        {
            s.append("  - id=").append(en.getKey()).append(" \"").append(en.getValue()).append("\"\n");
        }
        s.append("- **Players interacted with:** ").append(playersInteracted.size()).append('\n');
        for (var en : playersInteracted.entrySet())
        {
            s.append("  - \"").append(en.getKey()).append("\"\n");
        }
        s.append("- **Objects clicked:** ").append(objectsClicked.size()).append('\n');
        for (var en : objectsClicked.entrySet())
        {
            s.append("  - id=").append(en.getKey()).append(" × ").append(en.getValue()).append('\n');
        }
        s.append("- **Widget kinds clicked:**\n");
        for (var en : widgetsClicked.entrySet())
        {
            s.append("  - ").append(en.getKey()).append(" × ").append(en.getValue()).append('\n');
        }
        s.append('\n');
    }

    /** Net inventory deltas: aggregate inv_change + equip_change SlotDeltas
     *  per item id. Positive = gained, negative = lost. Uses ItemManager for
     *  names where wired; otherwise prints "id=N". */
    private void appendItemDeltas(StringBuilder s, List<RecordedEvent> events)
    {
        s.append("## Inventory deltas\n\n");
        Map<Integer, Long> netInv = new LinkedHashMap<>();
        Map<Integer, Long> netEquip = new LinkedHashMap<>();
        for (RecordedEvent e : events)
        {
            if (e instanceof Events.InvChange ic) accumulate(netInv, ic.deltas());
            else if (e instanceof Events.EquipChange ec) accumulate(netEquip, ec.deltas());
        }
        renderDeltaMap(s, "Inventory (carry)", netInv);
        renderDeltaMap(s, "Equipment (worn)", netEquip);
        s.append('\n');
    }

    private void appendBankDeltas(StringBuilder s, List<RecordedEvent> events)
    {
        s.append("## Bank deltas\n\n");
        Map<Integer, Long> netBank = new LinkedHashMap<>();
        for (RecordedEvent e : events)
        {
            if (e instanceof Events.BankChange bc) accumulate(netBank, bc.deltas());
        }
        if (netBank.isEmpty()) { s.append("_(no bank changes)_\n\n"); return; }
        renderDeltaMap(s, "Bank", netBank);
        s.append('\n');
    }

    private static void accumulate(Map<Integer, Long> sink, List<Events.InvChange.SlotDelta> deltas)
    {
        for (var d : deltas)
        {
            // Same item in slot → straight quantity delta. Different item →
            // record the loss for the old id and the gain for the new id.
            if (d.beforeId() == d.afterId() && d.beforeId() > 0)
            {
                sink.merge(d.beforeId(), (long) (d.afterQty() - d.beforeQty()), Long::sum);
            }
            else
            {
                if (d.beforeId() > 0 && d.beforeQty() > 0)
                {
                    sink.merge(d.beforeId(), -(long) d.beforeQty(), Long::sum);
                }
                if (d.afterId() > 0 && d.afterQty() > 0)
                {
                    sink.merge(d.afterId(), (long) d.afterQty(), Long::sum);
                }
            }
        }
    }

    private void renderDeltaMap(StringBuilder s, String header, Map<Integer, Long> deltas)
    {
        s.append("- **").append(header).append(":** ");
        // Hide ids with net zero — they cancel out (e.g., items moved bank↔inv).
        long nonZero = deltas.entrySet().stream().filter(en -> en.getValue() != 0).count();
        if (nonZero == 0) { s.append("_(no net change)_\n"); return; }
        s.append(nonZero).append(" item(s)\n");
        deltas.entrySet().stream()
            .filter(en -> en.getValue() != 0)
            .sorted((a, b) -> Long.compare(Math.abs(b.getValue()), Math.abs(a.getValue())))
            .forEach(en -> {
                long q = en.getValue();
                String sign = q > 0 ? "+" : "";
                s.append("  - ").append(itemName.apply(en.getKey()))
                    .append(" ").append(sign).append(q).append('\n');
            });
    }

    private void appendChat(StringBuilder s, List<RecordedEvent> events)
    {
        s.append("## Chat\n\n");
        List<Events.Chat> chats = events.stream()
            .filter(e -> e instanceof Events.Chat).map(e -> (Events.Chat) e).collect(Collectors.toList());
        if (chats.isEmpty()) { s.append("_(none captured)_\n\n"); return; }
        Map<String, Long> byType = new TreeMap<>();
        for (var c : chats) byType.merge(c.chatType(), 1L, Long::sum);
        s.append("- **Lines captured:** ").append(chats.size()).append('\n');
        for (var en : byType.entrySet())
        {
            s.append("  - ").append(en.getKey()).append(" × ").append(en.getValue()).append('\n');
        }
        int max = Math.min(chats.size(), 30);
        s.append("- **First ").append(max).append(":**\n");
        for (int i = 0; i < max; i++)
        {
            Events.Chat c = chats.get(i);
            s.append("  - [").append(c.chatType()).append("] ");
            if (c.sender() != null && !"system".equals(c.sender())) s.append(c.sender()).append(": ");
            s.append(c.message()).append('\n');
        }
        s.append('\n');
    }

    private void appendClickDistributions(StringBuilder s, List<RecordedEvent> events)
    {
        s.append("## Click distributions\n\n");
        Map<String, List<int[]>> perKind = new TreeMap<>();
        for (RecordedEvent e : events)
        {
            if (e instanceof Events.WidgetClick wc)
            {
                perKind.computeIfAbsent(wc.widgetKind(), k -> new ArrayList<>())
                    .add(new int[]{wc.offsetX(), wc.offsetY(), wc.bboxW(), wc.bboxH()});
            }
        }
        if (perKind.isEmpty()) { s.append("_(no widget clicks)_\n\n"); return; }
        s.append("| Widget kind | n | mean offsetX | mean offsetY | mean bbox |\n");
        s.append("|-------------|---|--------------|--------------|-----------|\n");
        for (var en : perKind.entrySet())
        {
            var v = en.getValue();
            int n = v.size();
            double mx = v.stream().mapToInt(a -> a[0]).average().orElse(0);
            double my = v.stream().mapToInt(a -> a[1]).average().orElse(0);
            double mw = v.stream().mapToInt(a -> a[2]).average().orElse(0);
            double mh = v.stream().mapToInt(a -> a[3]).average().orElse(0);
            s.append(String.format("| %s | %d | %.1f | %.1f | %.0fx%.0f |%n",
                en.getKey(), n, mx, my, mw, mh));
        }
        s.append('\n');
    }

    private void appendCursorApproach(StringBuilder s, List<RecordedEvent> events)
    {
        s.append("## Cursor approach\n\n");
        List<Double> velocities = new ArrayList<>();
        Events.MouseMove prev = null;
        for (RecordedEvent e : events)
        {
            if (e instanceof Events.MouseMove mm)
            {
                if (prev != null)
                {
                    long dt = mm.tMs() - prev.tMs();
                    if (dt > 0)
                    {
                        double dx = mm.x() - prev.x(), dy = mm.y() - prev.y();
                        double v = Math.hypot(dx, dy) / dt;
                        velocities.add(v);
                    }
                }
                prev = mm;
            }
        }
        if (velocities.isEmpty()) { s.append("_(no mouse moves recorded)_\n\n"); return; }
        Collections.sort(velocities);
        s.append("- Cursor velocity quartiles (px/ms): ")
            .append(String.format("p25=%.2f p50=%.2f p75=%.2f%n%n",
                velocities.get(velocities.size() / 4),
                velocities.get(velocities.size() / 2),
                velocities.get((velocities.size() * 3) / 4)));
    }

    private void appendHotkeys(StringBuilder s, List<RecordedEvent> events)
    {
        s.append("## Hotkey use\n\n");
        Map<Integer, Long> downCounts = new TreeMap<>();
        for (RecordedEvent e : events)
        {
            if (e instanceof Events.Key k && k.down()) downCounts.merge(k.keyCode(), 1L, Long::sum);
        }
        if (downCounts.isEmpty()) { s.append("_(none)_\n\n"); return; }
        for (var en : downCounts.entrySet())
        {
            s.append("- keyCode=").append(en.getKey()).append(" × ").append(en.getValue()).append('\n');
        }
        s.append('\n');
    }

    private void appendRunEnergyCamera(StringBuilder s, List<RecordedEvent> events)
    {
        s.append("## Run-energy / camera\n\n");
        long cameraEvents = events.stream().filter(e -> e instanceof Events.Camera).count();
        long runToggleHits = 0;
        Boolean lastRun = null;
        for (RecordedEvent e : events)
        {
            if (e instanceof Events.Tick tk)
            {
                if (lastRun != null && lastRun != tk.runOn()) runToggleHits++;
                lastRun = tk.runOn();
            }
        }
        s.append("- Camera events: ").append(cameraEvents).append('\n');
        s.append("- Run toggles: ").append(runToggleHits).append('\n');
        s.append('\n');
    }

    private void appendMarkers(StringBuilder s, List<RecordedEvent> events)
    {
        s.append("## Markers\n\n");
        List<Events.Marker> markers = events.stream()
            .filter(e -> e instanceof Events.Marker).map(e -> (Events.Marker) e).collect(Collectors.toList());
        if (markers.isEmpty()) { s.append("_(none)_\n"); return; }
        for (var m : markers)
        {
            s.append("- t=").append(m.tMs()).append("ms tick=").append(m.tick()).append(" \"").append(m.label()).append("\"\n");
        }
    }
}
