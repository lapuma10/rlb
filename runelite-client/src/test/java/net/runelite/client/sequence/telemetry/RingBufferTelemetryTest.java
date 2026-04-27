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
package net.runelite.client.sequence.telemetry;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RingBufferTelemetryTest {
    @Test
    public void record_storesAndTails() {
        RingBufferTelemetry t = new RingBufferTelemetry(4);
        for (int i = 0; i < 3; i++) {
            t.record(new TelemetryRecord(i, 0, "Step" + i, TelemetryRecord.Event.STARTED, ""));
        }
        List<TelemetryRecord> tail = t.tail(10);
        assertEquals(3, tail.size());
        assertEquals(0, tail.get(0).tick());
    }

    @Test
    public void overflow_dropsOldest() {
        RingBufferTelemetry t = new RingBufferTelemetry(2);
        for (int i = 0; i < 5; i++) {
            t.record(new TelemetryRecord(i, 0, "Step" + i, TelemetryRecord.Event.STARTED, ""));
        }
        List<TelemetryRecord> tail = t.tail(10);
        assertEquals(2, tail.size());
        assertEquals(3, tail.get(0).tick());
        assertEquals(4, tail.get(1).tick());
    }

    @Test
    public void subscriber_receivesRecords() {
        RingBufferTelemetry t = new RingBufferTelemetry(4);
        List<TelemetryRecord> seen = new ArrayList<>();
        t.subscribe(seen::add);
        t.record(new TelemetryRecord(1, 0, "X", TelemetryRecord.Event.STARTED, ""));
        assertEquals(1, seen.size());
    }
}
