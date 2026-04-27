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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class RingBufferTelemetry implements Telemetry {
    private final int capacity;
    private final Deque<TelemetryRecord> buffer = new ArrayDeque<>();
    private final List<Consumer<TelemetryRecord>> listeners = new CopyOnWriteArrayList<>();

    public RingBufferTelemetry(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
    }

    @Override
    public synchronized void record(TelemetryRecord r) {
        if (buffer.size() == capacity) buffer.removeFirst();
        buffer.addLast(r);
        for (Consumer<TelemetryRecord> l : listeners) l.accept(r);
    }

    @Override
    public synchronized List<TelemetryRecord> tail(int n) {
        int take = Math.min(n, buffer.size());
        List<TelemetryRecord> out = new ArrayList<>(take);
        int skip = buffer.size() - take;
        int i = 0;
        for (TelemetryRecord r : buffer) {
            if (i++ >= skip) out.add(r);
        }
        return out;
    }

    @Override public void subscribe(Consumer<TelemetryRecord> l) { listeners.add(l); }
    @Override public void unsubscribe(Consumer<TelemetryRecord> l) { listeners.remove(l); }
}
