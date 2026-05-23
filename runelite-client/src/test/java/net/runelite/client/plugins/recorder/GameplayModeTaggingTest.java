package net.runelite.client.plugins.recorder;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Canvas;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.capture.CameraSampler;
import net.runelite.client.plugins.recorder.capture.ChatFilter;
import net.runelite.client.plugins.recorder.capture.ClickResolver;
import net.runelite.client.plugins.recorder.capture.EventBusCapture;
import net.runelite.client.plugins.recorder.capture.FocusCapture;
import net.runelite.client.plugins.recorder.capture.KeyCapture;
import net.runelite.client.plugins.recorder.capture.MouseCapture;
import net.runelite.client.plugins.recorder.capture.NearbyResolver;
import net.runelite.client.plugins.recorder.session.LoginSession;
import net.runelite.client.plugins.recorder.session.SessionDirectory;
import net.runelite.client.plugins.recorder.session.SessionStore;
import net.runelite.client.plugins.recorder.session.SessionTracker;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Drives a synthetic mode-tagging flow through SessionTracker → RecorderManager
 *  and asserts the recorder writes {@code script_mode} events at the right
 *  transitions and populates the v2 manifest fields. No live game required. */
public class GameplayModeTaggingTest
{
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void recordsScriptModeTransitions_andManifestReflectsLastMode() throws Exception
    {
        // --- mocked Client + Canvas + Player ---
        Client client = mock(Client.class);
        Canvas canvas = mock(Canvas.class);
        when(canvas.getWidth()).thenReturn(800);
        when(canvas.getHeight()).thenReturn(600);
        when(client.getCanvas()).thenReturn(canvas);
        when(client.isResized()).thenReturn(true);
        when(client.getWorld()).thenReturn(308);
        when(client.getTickCount()).thenReturn(0);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("tester");
        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getUsername()).thenReturn("tester");

        RecorderConfig config = mock(RecorderConfig.class);
        when(config.flushIntervalMs()).thenReturn(50);
        when(config.cameraSampleThresholdYaw()).thenReturn(16);
        when(config.cameraSampleThresholdPitch()).thenReturn(16);
        when(config.mouseMoveDownsampleHz()).thenReturn(0);
        when(config.capturePlayerChat()).thenReturn(false);

        ClientThread clientThread = mock(ClientThread.class);

        SessionDirectory sessions = new SessionDirectory(tmp.getRoot().toPath());
        EventBusCapture ebc = new EventBusCapture(client, new ChatFilter(false),
            new ClickResolver(), new NearbyResolver(),
            new CameraSampler(16, 16));
        MouseCapture mc = new MouseCapture(0);
        KeyCapture kc = new KeyCapture();
        FocusCapture fc = new FocusCapture();

        // SessionStore that swallows all writes — keep the test off the
        // user's real ~/.runelite/recorder/sessions directory.
        SessionStore store = new SessionStore() {
            @Override public void upsertSession(String account, LocalDate date, LoginSession s) { /* no-op */ }
        };
        SessionTracker tracker = new SessionTracker(client, clientThread, store);

        RecorderManager mgr = new RecorderManager(client, config, ebc, mc, kc, fc, sessions);
        mgr.setActiveScriptIdSupplier(tracker::activeScriptId);
        tracker.setScriptModeListener(mgr);

        // Drive a login so the tracker accepts script-lifecycle calls.
        GameStateChanged login = new GameStateChanged();
        login.setGameState(GameState.LOGGED_IN);
        tracker.onGameStateChanged(login);

        // Flow:  start recording (live)  →  script starts (bot_watch)  →  script stops (live)  →  stop recording
        mgr.start();
        // Give the flusher a moment to drain the initial script_mode event.
        Thread.sleep(80);
        tracker.onScriptStarted("ultra_compost", "Ultra Compost");
        Thread.sleep(80);
        tracker.onScriptStopped("ultra_compost", null, null);
        Thread.sleep(80);
        mgr.stop("modetest");

        // Locate the (only) session dir under the temp folder.
        Path sessionDir = Files.list(tmp.getRoot().toPath()).findFirst().orElseThrow();
        assertTrue("events.jsonl.gz should exist", Files.exists(sessionDir.resolve("events.jsonl.gz")));
        assertTrue("meta.json should exist", Files.exists(sessionDir.resolve("meta.json")));

        // --- assert ScriptMode events in events.jsonl.gz ---
        List<JsonObject> scriptModeEvents = new ArrayList<>();
        Gson gson = new Gson();
        try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(sessionDir.resolve("events.jsonl.gz")));
             BufferedReader br = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = br.readLine()) != null)
            {
                JsonObject o = gson.fromJson(line, JsonObject.class);
                if (o != null && o.has("type") && "script_mode".equals(o.get("type").getAsString()))
                {
                    scriptModeEvents.add(o);
                }
            }
        }

        assertEquals("expected three script_mode events (live initial, bot_watch start, live stop)",
            3, scriptModeEvents.size());

        // 1st: initial emit from start() — no script running yet, so "live"
        assertEquals("live", scriptModeEvents.get(0).get("mode").getAsString());
        assertTrue("first event scriptId should be null/absent",
            scriptModeEvents.get(0).get("scriptId") == null
                || scriptModeEvents.get(0).get("scriptId").isJsonNull());

        // 2nd: bot_watch on script start
        assertEquals("bot_watch", scriptModeEvents.get(1).get("mode").getAsString());
        assertEquals("ultra_compost", scriptModeEvents.get(1).get("scriptId").getAsString());

        // 3rd: back to live on script stop
        assertEquals("live", scriptModeEvents.get(2).get("mode").getAsString());
        assertTrue("third event scriptId should be null/absent",
            scriptModeEvents.get(2).get("scriptId") == null
                || scriptModeEvents.get(2).get("scriptId").isJsonNull());

        // --- assert v2 manifest fields ---
        String metaJson = Files.readString(sessionDir.resolve("meta.json"));
        JsonObject meta = gson.fromJson(metaJson, JsonObject.class);
        assertEquals(2, meta.get("schemaVersion").getAsInt());
        assertEquals("live", meta.get("mode").getAsString());
        // script should be null because we stopped the script before stopping the recording
        assertTrue("script should be null in final manifest",
            meta.get("script") == null || meta.get("script").isJsonNull());
        assertEquals("stop", meta.get("endedReason").getAsString());
    }

    @Test
    public void initialModeIsBotWatch_whenScriptRunningAtStart() throws Exception
    {
        Client client = mock(Client.class);
        Canvas canvas = mock(Canvas.class);
        when(canvas.getWidth()).thenReturn(800);
        when(canvas.getHeight()).thenReturn(600);
        when(client.getCanvas()).thenReturn(canvas);
        when(client.isResized()).thenReturn(true);
        when(client.getWorld()).thenReturn(308);
        when(client.getTickCount()).thenReturn(0);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("tester");
        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getUsername()).thenReturn("tester");

        RecorderConfig config = mock(RecorderConfig.class);
        when(config.flushIntervalMs()).thenReturn(50);
        when(config.cameraSampleThresholdYaw()).thenReturn(16);
        when(config.cameraSampleThresholdPitch()).thenReturn(16);
        when(config.mouseMoveDownsampleHz()).thenReturn(0);
        when(config.capturePlayerChat()).thenReturn(false);
        ClientThread clientThread = mock(ClientThread.class);

        SessionDirectory sessions = new SessionDirectory(tmp.getRoot().toPath());
        EventBusCapture ebc = new EventBusCapture(client, new ChatFilter(false),
            new ClickResolver(), new NearbyResolver(),
            new CameraSampler(16, 16));
        MouseCapture mc = new MouseCapture(0);
        KeyCapture kc = new KeyCapture();
        FocusCapture fc = new FocusCapture();
        SessionStore store = new SessionStore() {
            @Override public void upsertSession(String a, LocalDate d, LoginSession s) {}
        };
        SessionTracker tracker = new SessionTracker(client, clientThread, store);

        RecorderManager mgr = new RecorderManager(client, config, ebc, mc, kc, fc, sessions);
        mgr.setActiveScriptIdSupplier(tracker::activeScriptId);
        tracker.setScriptModeListener(mgr);

        // Log in + start script BEFORE the recorder.
        GameStateChanged login = new GameStateChanged();
        login.setGameState(GameState.LOGGED_IN);
        tracker.onGameStateChanged(login);
        tracker.onScriptStarted("rooftop_agility", "Rooftop Agility");
        Thread.sleep(20);

        mgr.start();
        Thread.sleep(80);
        mgr.stop("modetest");

        Path sessionDir = Files.list(tmp.getRoot().toPath()).findFirst().orElseThrow();
        Gson gson = new Gson();
        List<JsonObject> modes = new ArrayList<>();
        try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(sessionDir.resolve("events.jsonl.gz")));
             BufferedReader br = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = br.readLine()) != null)
            {
                JsonObject o = gson.fromJson(line, JsonObject.class);
                if (o != null && "script_mode".equals(o.get("type").getAsString())) modes.add(o);
            }
        }
        assertFalse("at least one script_mode event expected", modes.isEmpty());
        assertEquals("bot_watch", modes.get(0).get("mode").getAsString());
        assertEquals("rooftop_agility", modes.get(0).get("scriptId").getAsString());

        JsonObject meta = gson.fromJson(Files.readString(sessionDir.resolve("meta.json")), JsonObject.class);
        assertEquals(2, meta.get("schemaVersion").getAsInt());
        assertEquals("bot_watch", meta.get("mode").getAsString());
        assertEquals("rooftop_agility", meta.get("script").getAsString());
        assertEquals("stop", meta.get("endedReason").getAsString());
    }
}
