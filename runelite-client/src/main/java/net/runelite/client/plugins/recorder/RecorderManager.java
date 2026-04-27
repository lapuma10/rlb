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

import com.google.gson.GsonBuilder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.recorder.analyse.HtmlViewerGenerator;
import net.runelite.client.plugins.recorder.analyse.PhaseSegmenter;
import net.runelite.client.plugins.recorder.analyse.SummaryGenerator;
import net.runelite.client.plugins.recorder.buffer.JsonlGzipWriter;
import net.runelite.client.plugins.recorder.buffer.RecordingBuffer;
import net.runelite.client.plugins.recorder.capture.EventBusCapture;
import net.runelite.client.plugins.recorder.capture.FocusCapture;
import net.runelite.client.plugins.recorder.capture.KeyCapture;
import net.runelite.client.plugins.recorder.capture.MouseCapture;
import net.runelite.client.plugins.recorder.events.EventCodec;
import net.runelite.client.plugins.recorder.events.Events;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import net.runelite.client.plugins.recorder.flush.FlushDaemon;
import net.runelite.client.plugins.recorder.session.MetaJson;
import net.runelite.client.plugins.recorder.session.RecordingSession;
import net.runelite.client.plugins.recorder.session.SessionDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Owns the recorder's runtime state. start() creates a session, hooks the
 *  buffer onto every capture, starts the flusher. stop() drains, runs analysis,
 *  writes summary + html, renames the session dir. */
@Slf4j
public final class RecorderManager
{
    private final Client client;
    private final RecorderConfig config;
    private final EventBusCapture eventBus;
    private final MouseCapture mouse;
    private final KeyCapture key;
    private final FocusCapture focus;
    private final SessionDirectory sessions;
    private final ItemManager itemManager;
    private final ClientThread clientThread;

    @Getter private RecorderState state = RecorderState.IDLE;
    private RecordingBuffer buffer;
    private FlushDaemon flusher;
    private RecordingSession session;
    private final List<Consumer<RecorderState>> stateListeners = new ArrayList<>();

    public RecorderManager(Client client, RecorderConfig config,
                           EventBusCapture eventBus, MouseCapture mouse,
                           KeyCapture key, FocusCapture focus,
                           SessionDirectory sessions)
    {
        this(client, config, eventBus, mouse, key, focus, sessions, null, null);
    }

    public RecorderManager(Client client, RecorderConfig config,
                           EventBusCapture eventBus, MouseCapture mouse,
                           KeyCapture key, FocusCapture focus,
                           SessionDirectory sessions, ItemManager itemManager)
    {
        this(client, config, eventBus, mouse, key, focus, sessions, itemManager, null);
    }

    public RecorderManager(Client client, RecorderConfig config,
                           EventBusCapture eventBus, MouseCapture mouse,
                           KeyCapture key, FocusCapture focus,
                           SessionDirectory sessions, ItemManager itemManager,
                           ClientThread clientThread)
    {
        this.client = client; this.config = config;
        this.eventBus = eventBus; this.mouse = mouse; this.key = key; this.focus = focus;
        this.sessions = sessions; this.itemManager = itemManager;
        this.clientThread = clientThread;
    }

    /** Live recording stats for the side panel. Returns 0/empty when idle. */
    public synchronized long elapsedMs()
    {
        if (state != RecorderState.RECORDING || buffer == null) return 0L;
        return System.currentTimeMillis() - buffer.startMs();
    }

    public synchronized long totalEvents()
    {
        if (buffer == null) return 0L;
        return buffer.totalEvents();
    }

    public synchronized Map<String, Long> eventCountsSnapshot()
    {
        if (buffer == null) return Map.of();
        return buffer.typeCountsSnapshot();
    }

    /** Most recent meaningful events (clicks, world interactions, inv changes,
     *  chat, markers). Used by the side panel for the live scrolling feed. */
    public synchronized List<RecordedEvent> recentEventsSnapshot()
    {
        if (buffer == null) return List.of();
        return buffer.recentInterestingSnapshot();
    }

    public synchronized void onStateChanged(Consumer<RecorderState> l) { stateListeners.add(l); }

    public synchronized RecordingSession start() throws IOException
    {
        if (state != RecorderState.IDLE) throw new IllegalStateException("recorder not idle");
        Instant now = Instant.now();
        session = sessions.create(now);
        buffer = new RecordingBuffer();
        Path eventsFile = session.getDirectory().resolve("events.jsonl.gz");
        JsonlGzipWriter writer = new JsonlGzipWriter(eventsFile);
        flusher = new FlushDaemon(buffer, writer, new EventCodec(), config.flushIntervalMs());
        eventBus.setBuffer(buffer);
        mouse.setBuffer(buffer);
        key.setBuffer(buffer);
        focus.setBuffer(buffer);
        flusher.start();
        setState(RecorderState.RECORDING);
        return session;
    }

    public synchronized void recordMarker(String label)
    {
        if (state != RecorderState.RECORDING || buffer == null) return;
        int tick = client.getTickCount();
        buffer.enqueue((seq, tMs) -> new Events.Marker(seq, tMs, tick, label));
    }

    public synchronized void recordMarkerDialogOpen() { recordDialogState(true); }
    public synchronized void recordMarkerDialogClose() { recordDialogState(false); }
    private void recordDialogState(boolean opened)
    {
        if (state != RecorderState.RECORDING || buffer == null) return;
        int tick = client.getTickCount();
        buffer.enqueue((seq, tMs) -> new Events.MarkerDialog(seq, tMs, tick, opened));
    }

    public synchronized void stop(String intentLabel) throws IOException
    {
        if (state != RecorderState.RECORDING) return;
        setState(RecorderState.FINALISING);
        try
        {
            flusher.stop();
            eventBus.setBuffer(null);
            mouse.setBuffer(null);
            key.setBuffer(null);
            focus.setBuffer(null);
            session.setIntentLabel(intentLabel);
            session.setEndedAt(Instant.now());
            finaliseBundle(flusher.capturedInMemory());
            Path renamed = sessions.renameWithIntent(session, intentLabel);
            sessions.openInFileBrowser(renamed);
        }
        finally
        {
            flusher = null; buffer = null; session = null;
            setState(RecorderState.IDLE);
        }
    }

    /** Best-effort drain + close without producing summary/html — used during
     *  plugin shutdown so we don't hang the EDT or pop up a file browser. */
    public synchronized void abort()
    {
        if (state != RecorderState.RECORDING) return;
        setState(RecorderState.FINALISING);
        try
        {
            flusher.stop();
            eventBus.setBuffer(null);
            mouse.setBuffer(null);
            key.setBuffer(null);
            focus.setBuffer(null);
        }
        catch (IOException io)
        {
            log.warn("abort: flusher stop failed", io);
        }
        finally
        {
            flusher = null; buffer = null; session = null;
            setState(RecorderState.IDLE);
        }
    }

    private void finaliseBundle(List<RecordedEvent> events) throws IOException
    {
        var phases = new PhaseSegmenter().segment(events);
        Path dir = session.getDirectory();
        Path phasesFile = dir.resolve("phases.json");
        Files.writeString(phasesFile, new GsonBuilder().setPrettyPrinting().create()
            .toJson(Map.of("phases", phases)));
        Map<String, Long> eventCounts = new LinkedHashMap<>();
        for (RecordedEvent e : events)
        {
            eventCounts.merge(e.type(), 1L, Long::sum);
        }
        MetaJson meta = MetaJson.builder()
            .schemaVersion(1)
            .sessionId(session.getSessionId())
            .intentLabel(session.getIntentLabel() == null ? "" : session.getIntentLabel())
            .startedAtUtc(DateTimeFormatter.ISO_INSTANT.format(session.getStartedAt()))
            .endedAtUtc(DateTimeFormatter.ISO_INSTANT.format(session.getEndedAt()))
            .durationMs(session.durationMs())
            .runeliteVersion(net.runelite.client.RuneLiteProperties.getVersion())
            .characterName(localPlayerName())
            .world(client.getWorld())
            .clientDimensions(new int[]{client.getCanvas().getWidth(), client.getCanvas().getHeight()})
            .fixedMode(client.isResized() == false)
            .eventCounts(eventCounts)
            .markerCount((int) events.stream().filter(e -> e instanceof Events.Marker).count())
            .build();
        sessions.writeMeta(session, meta);
        Files.writeString(dir.resolve("summary.md"),
            new SummaryGenerator(this::resolveItemName).generate(events, phases, meta));
        Files.writeString(dir.resolve("recording.html"),
            new HtmlViewerGenerator().generate(events, phases));
    }

    /** Best-effort item-name lookup. Returns "id=N" if ItemManager is not
     *  available (tests) or the lookup fails. {@code itemManager.getItemComposition}
     *  asserts on the client thread, so when {@link #clientThread} is
     *  injected we hop there before reading; the catch is widened to
     *  {@link Throwable} so a stray assertion can never crash the stop()
     *  flow that owns this lookup. */
    private String resolveItemName(int itemId)
    {
        if (itemManager == null || itemId <= 0) return "id=" + itemId;
        try
        {
            String name;
            if (clientThread != null && !client.isClientThread())
            {
                CompletableFuture<String> fut = new CompletableFuture<>();
                clientThread.invoke(() -> {
                    try
                    {
                        var comp = itemManager.getItemComposition(itemId);
                        fut.complete(comp == null ? null : comp.getName());
                    }
                    catch (Throwable th) { fut.completeExceptionally(th); }
                });
                name = fut.get(2000, TimeUnit.MILLISECONDS);
            }
            else
            {
                var comp = itemManager.getItemComposition(itemId);
                name = comp == null ? null : comp.getName();
            }
            return name == null || name.isBlank() ? "id=" + itemId : name;
        }
        catch (Throwable ignored)
        {
            return "id=" + itemId;
        }
    }

    private String localPlayerName()
    {
        var p = client.getLocalPlayer();
        return p == null || p.getName() == null ? "?" : p.getName();
    }

    private void setState(RecorderState s)
    {
        this.state = s;
        for (var l : stateListeners)
        {
            try { l.accept(s); } catch (Throwable t) { log.warn("listener threw: {}", t.toString()); }
        }
    }
}
