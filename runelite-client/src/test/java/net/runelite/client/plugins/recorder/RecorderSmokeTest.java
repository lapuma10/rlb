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
package net.runelite.client.plugins.recorder;

import java.awt.Canvas;
import net.runelite.api.Client;
import net.runelite.client.plugins.recorder.capture.CameraSampler;
import net.runelite.client.plugins.recorder.capture.ChatFilter;
import net.runelite.client.plugins.recorder.capture.ClickResolver;
import net.runelite.client.plugins.recorder.capture.EventBusCapture;
import net.runelite.client.plugins.recorder.capture.FocusCapture;
import net.runelite.client.plugins.recorder.capture.KeyCapture;
import net.runelite.client.plugins.recorder.capture.MouseCapture;
import net.runelite.client.plugins.recorder.capture.NearbyResolver;
import net.runelite.client.plugins.recorder.session.SessionDirectory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;

/** Drives a tiny synthetic session through the full pipeline and asserts the
 *  bundle exists. UI / mouse-listener wiring is not exercised — that belongs
 *  to manual in-client verification. */
public class RecorderSmokeTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void startMarkerStop_producesAllFiveBundleFiles() throws Exception
    {
        Client client = mock(Client.class);
        Canvas canvas = mock(Canvas.class);
        when(canvas.getWidth()).thenReturn(800); when(canvas.getHeight()).thenReturn(600);
        when(client.getCanvas()).thenReturn(canvas);
        when(client.isResized()).thenReturn(true);
        when(client.getWorld()).thenReturn(308);
        when(client.getTickCount()).thenReturn(0);

        RecorderConfig config = mock(RecorderConfig.class);
        when(config.flushIntervalMs()).thenReturn(50);
        when(config.cameraSampleThresholdYaw()).thenReturn(16);
        when(config.cameraSampleThresholdPitch()).thenReturn(16);
        when(config.mouseMoveDownsampleHz()).thenReturn(0);
        when(config.capturePlayerChat()).thenReturn(false);

        SessionDirectory sessions = new SessionDirectory(tmp.getRoot().toPath());
        EventBusCapture ebc = new EventBusCapture(client, new ChatFilter(false),
            new ClickResolver(), new NearbyResolver(),
            new CameraSampler(16, 16));
        MouseCapture mc = new MouseCapture(0);
        KeyCapture kc = new KeyCapture();
        FocusCapture fc = new FocusCapture();

        RecorderManager mgr = new RecorderManager(client, config, ebc, mc, kc, fc, sessions);
        mgr.start();
        // Inject one marker through the public API
        mgr.recordMarker("hello");
        // Give the flusher one cycle to drain
        Thread.sleep(150);
        mgr.stop("smoke");

        // The dir was renamed to embed "smoke"; locate it.
        var any = Files.list(tmp.getRoot().toPath()).findFirst().orElseThrow();
        assertTrue(Files.exists(any.resolve("meta.json")));
        assertTrue(Files.exists(any.resolve("events.jsonl.gz")));
        assertTrue(Files.exists(any.resolve("phases.json")));
        assertTrue(Files.exists(any.resolve("summary.md")));
        assertTrue(Files.exists(any.resolve("recording.html")));
        String summary = Files.readString(any.resolve("summary.md"));
        assertTrue(summary.contains("# Recording summary"));
        assertTrue("summary should reference the marker label", summary.contains("hello"));
    }
}
