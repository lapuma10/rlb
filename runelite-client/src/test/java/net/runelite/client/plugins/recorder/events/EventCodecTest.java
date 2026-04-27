/*
 * Copyright (c) 2024, RuneLite
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
package net.runelite.client.plugins.recorder.events;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class EventCodecTest
{
    @Test
    public void encodesTickWithTypeAndEnvelope()
    {
        EventCodec codec = new EventCodec();
        Events.Tick t = new Events.Tick(7, 1234, 5, 3200, 3200, 0, 0, true, 100, false, 50, 99);
        String json = codec.toJsonLine(t);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("tick", obj.get("type").getAsString());
        assertEquals(7L, obj.get("seq").getAsLong());
        assertEquals(1234L, obj.get("t_ms").getAsLong());
        assertEquals(5, obj.get("tick").getAsInt());
        assertEquals(3200, obj.get("worldX").getAsInt());
    }

    @Test
    public void encodesMenuOpenWithRowsArray()
    {
        EventCodec codec = new EventCodec();
        Events.MenuOpen mo = new Events.MenuOpen(1, 100, 0, 50, 60,
            List.of(new Events.MenuOpen.MenuRow("Walk here", "", 0, "ground"),
                    new Events.MenuOpen.MenuRow("Examine", "Tree", 1276, "object")));
        String json = codec.toJsonLine(mo);
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(2, obj.getAsJsonArray("rows").size());
        assertEquals("Tree", obj.getAsJsonArray("rows").get(1).getAsJsonObject().get("target").getAsString());
    }

    @Test
    public void everyConcreteEventTypeProducesValidJson()
    {
        EventCodec codec = new EventCodec();
        codec.toJsonLine(new Events.MouseMove(1, 1, 0, 100, 100));
        codec.toJsonLine(new Events.MouseDown(1, 1, 0, 1, 100, 100));
        codec.toJsonLine(new Events.MouseUp(1, 1, 0, 1, 100, 100, 42));
        codec.toJsonLine(new Events.Wheel(1, 1, 0, 100, 100, -1));
        codec.toJsonLine(new Events.Key(1, 1, 0, 37, 0, true));
        codec.toJsonLine(new Events.Focus(1, 1, 0, true));
        codec.toJsonLine(new Events.Marker(1, 1, 0, "deposit phase"));
        codec.toJsonLine(new Events.MarkerDialog(1, 1, 0, true));
        codec.toJsonLine(new Events.Camera(1, 1, 0, 200, 256, 600));
        codec.toJsonLine(new Events.Chat(1, 1, 0, "GAMEMESSAGE", "system", "You catch a raw shrimp."));
        codec.toJsonLine(new Events.XpChange(1, 1, 0, "FISHING", 100, 110));
    }
}
