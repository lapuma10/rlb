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
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;

public class SummaryGeneratorTest
{
    @Test
    public void summaryContainsAllRequiredSections()
    {
        List<RecordedEvent> es = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            es.add(new Events.Tick(i, i * 600L, i, 3200 + i, 3200, 0, 0, false, 100, true, 50, 99));
        }
        es.add(new Events.MenuClick(10, 100, 1, 0, 50, 60, 200,
            "Drop", "Coins", 1004, "CC_OP", 0, 149 << 16, 57));
        es.add(new Events.Marker(11, 110, 1, "test phase"));
        MetaJson meta = MetaJson.builder()
            .schemaVersion(1)
            .sessionId("2026-04-26-1442-test")
            .intentLabel("test")
            .startedAtUtc("2026-04-26T14:42:00Z")
            .endedAtUtc("2026-04-26T14:43:00Z")
            .durationMs(60000)
            .runeliteVersion("1.12.25")
            .characterName("Mantas")
            .world(308)
            .clientDimensions(new int[]{1280, 720})
            .fixedMode(false)
            .markerCount(1)
            .build();
        String md = new SummaryGenerator().generate(es, List.of(), meta);
        assertTrue(md.contains("# Recording summary"));
        assertTrue(md.contains("## Overview"));
        assertTrue(md.contains("## Movement"));
        assertTrue(md.contains("## Phases"));
        assertTrue(md.contains("## Interaction inventory"));
        assertTrue(md.contains("## Inventory deltas"));
        assertTrue(md.contains("## Bank deltas"));
        assertTrue(md.contains("## Chat"));
        assertTrue(md.contains("## Click distributions"));
        assertTrue(md.contains("## Cursor approach"));
        assertTrue(md.contains("## Hotkey use"));
        assertTrue(md.contains("## Run-energy / camera"));
        assertTrue(md.contains("## Markers"));
        assertTrue(md.contains("test phase"));
        assertTrue(md.contains("Mantas"));
    }

    @Test
    public void invChangeSurfacesAsItemDeltaWithName()
    {
        // +1,000,000 coins (received in trade) — must appear in the
        // Inventory deltas section with the resolved name.
        List<RecordedEvent> es = new ArrayList<>();
        es.add(new Events.Tick(0, 0, 0, 3164, 3491, 0, 0, true, 100, false, 50, 99));
        es.add(new Events.InvChange(1, 100, 0, List.of(
            new Events.InvChange.SlotDelta(0, -1, 0, 995, 1_000_000)
        )));
        MetaJson meta = MetaJson.builder()
            .schemaVersion(1).sessionId("x").intentLabel("trade")
            .startedAtUtc("a").endedAtUtc("b").durationMs(0)
            .runeliteVersion("1.0").characterName("?").world(0)
            .clientDimensions(new int[]{0,0}).fixedMode(false).markerCount(0)
            .build();
        String md = new SummaryGenerator(id -> id == 995 ? "Coins" : "id=" + id)
            .generate(es, List.of(), meta);
        assertTrue("expected Coins to appear in summary", md.contains("Coins"));
        assertTrue(md.contains("+1000000"));
    }

    @Test
    public void invChangesNettingToZeroCollapseToNoChange()
    {
        // +1M then -1M — net 0, so the inventory section says "no net change".
        List<RecordedEvent> es = new ArrayList<>();
        es.add(new Events.InvChange(1, 100, 0, List.of(
            new Events.InvChange.SlotDelta(0, -1, 0, 995, 1_000_000)
        )));
        es.add(new Events.InvChange(2, 200, 0, List.of(
            new Events.InvChange.SlotDelta(0, 995, 1_000_000, -1, 0)
        )));
        MetaJson meta = MetaJson.builder().schemaVersion(1).sessionId("x").intentLabel("trade")
            .startedAtUtc("a").endedAtUtc("b").durationMs(0).runeliteVersion("1.0")
            .characterName("?").world(0).clientDimensions(new int[]{0,0}).fixedMode(false)
            .markerCount(0).build();
        String md = new SummaryGenerator(id -> id == 995 ? "Coins" : "id=" + id)
            .generate(es, List.of(), meta);
        assertTrue(md.contains("_(no net change)_"));
    }

    @Test
    public void bankChangeProducesBankSection()
    {
        List<RecordedEvent> es = new ArrayList<>();
        es.add(new Events.BankChange(0, 0, 0, List.of(
            new Events.InvChange.SlotDelta(0, 995, 5_000_000, 995, 4_000_000)
        )));
        MetaJson meta = MetaJson.builder().schemaVersion(1).sessionId("x").intentLabel("bank")
            .startedAtUtc("a").endedAtUtc("b").durationMs(0).runeliteVersion("1.0")
            .characterName("?").world(0).clientDimensions(new int[]{0,0}).fixedMode(false)
            .markerCount(0).build();
        String md = new SummaryGenerator(id -> id == 995 ? "Coins" : "id=" + id)
            .generate(es, List.of(), meta);
        assertTrue(md.contains("## Bank deltas"));
        assertTrue(md.contains("Coins"));
        assertTrue(md.contains("-1000000"));
    }

    @Test
    public void worldClickGroundShowsAsWalkTarget()
    {
        List<RecordedEvent> es = new ArrayList<>();
        es.add(new Events.Tick(0, 0, 0, 3200, 3200, 0, 0, false, 100, true, 50, 99));
        es.add(new Events.WorldClick(1, 100, 0, "ground", 0, "", 3210, 3210, 0, 400, 300));
        MetaJson meta = MetaJson.builder().schemaVersion(1).sessionId("x").intentLabel("walk")
            .startedAtUtc("a").endedAtUtc("b").durationMs(0).runeliteVersion("1.0")
            .characterName("?").world(0).clientDimensions(new int[]{0,0}).fixedMode(false)
            .markerCount(0).build();
        String md = new SummaryGenerator().generate(es, List.of(), meta);
        assertTrue(md.contains("Walk targets"));
        assertTrue(md.contains("(3210, 3210)"));
        assertTrue(md.contains("Bounding box"));
    }

    @Test
    public void playerWorldClickSurfacesAsPlayerInteraction()
    {
        List<RecordedEvent> es = new ArrayList<>();
        es.add(new Events.WorldClick(0, 100, 0, "player", 7, "Hatcholio", 3164, 3491, 0, 400, 300));
        MetaJson meta = MetaJson.builder().schemaVersion(1).sessionId("x").intentLabel("trade")
            .startedAtUtc("a").endedAtUtc("b").durationMs(0).runeliteVersion("1.0")
            .characterName("?").world(0).clientDimensions(new int[]{0,0}).fixedMode(false)
            .markerCount(0).build();
        String md = new SummaryGenerator().generate(es, List.of(), meta);
        assertTrue(md.contains("Players interacted with"));
        assertTrue(md.contains("Hatcholio"));
    }

    @Test
    public void chatLinesAppearInSummary()
    {
        List<RecordedEvent> es = new ArrayList<>();
        es.add(new Events.Chat(0, 100, 0, "TRADE", "system", "Hatcholio wishes to trade with you"));
        es.add(new Events.Chat(1, 200, 0, "GAMEMESSAGE", "system", "Accepted trade"));
        MetaJson meta = MetaJson.builder().schemaVersion(1).sessionId("x").intentLabel("chat")
            .startedAtUtc("a").endedAtUtc("b").durationMs(0).runeliteVersion("1.0")
            .characterName("?").world(0).clientDimensions(new int[]{0,0}).fixedMode(false)
            .markerCount(0).build();
        String md = new SummaryGenerator().generate(es, List.of(), meta);
        assertTrue(md.contains("Hatcholio wishes to trade with you"));
        assertTrue(md.contains("Accepted trade"));
    }

    @Test
    public void emptyRecording_producesValidSummary()
    {
        MetaJson meta = MetaJson.builder()
            .schemaVersion(1).sessionId("empty").intentLabel("empty")
            .startedAtUtc("x").endedAtUtc("y").durationMs(0)
            .runeliteVersion("1.12.25").characterName("?").world(0)
            .clientDimensions(new int[]{0, 0}).fixedMode(false).markerCount(0)
            .build();
        String md = new SummaryGenerator().generate(List.of(), List.of(), meta);
        assertTrue(md.contains("# Recording summary"));
    }
}
