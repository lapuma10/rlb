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
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.recorder.analyse;

import net.runelite.client.plugins.recorder.events.Events;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;

public class HtmlViewerGeneratorTest
{
    @Test
    public void producesValidStandaloneHtml()
    {
        List<RecordedEvent> es = new ArrayList<>();
        es.add(new Events.MouseMove(1, 10, 0, 100, 100));
        es.add(new Events.MouseMove(2, 20, 0, 110, 110));
        es.add(new Events.MenuClick(3, 30, 0, 0, 110, 110, 0,
            "Walk here", "", 0, "WALK", 0, 0, 8));
        String html = new HtmlViewerGenerator().generate(es, List.of());
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<canvas"));
        assertTrue(html.contains("<script>"));
        assertFalse(html.contains("__EVENTS__"));   // placeholder must be substituted
    }
}
