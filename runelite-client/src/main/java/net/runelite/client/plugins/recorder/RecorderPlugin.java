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

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.recorder.annotator.AnnotatorHudOverlay;
import net.runelite.client.plugins.recorder.annotator.AreaSelector;
import net.runelite.client.plugins.recorder.scripts.ChickenFarmV2Script;
import net.runelite.client.plugins.recorder.scripts.CookingScript;
import net.runelite.client.plugins.recorder.scripts.LumbridgeBankPenScript;
import net.runelite.client.plugins.recorder.trail.TrailRecorder;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.capture.CameraSampler;
import net.runelite.client.plugins.recorder.capture.ChatFilter;
import net.runelite.client.plugins.recorder.capture.ClickResolver;
import net.runelite.client.plugins.recorder.capture.EventBusCapture;
import net.runelite.client.plugins.recorder.capture.FocusCapture;
import net.runelite.client.plugins.recorder.capture.KeyCapture;
import net.runelite.client.plugins.recorder.capture.MouseCapture;
import net.runelite.client.plugins.recorder.capture.NearbyResolver;
import net.runelite.client.plugins.recorder.combat.ChickenCombatLoop;
import net.runelite.client.plugins.recorder.combat.ChickenOverlay;
import net.runelite.client.plugins.recorder.debug.DebugOverlay;
import net.runelite.client.plugins.recorder.debug.LoginDebugOverlay;
import net.runelite.client.plugins.recorder.debug.TileMarker;
import net.runelite.client.plugins.recorder.hotkey.HotkeyHandler;
import net.runelite.client.plugins.recorder.mining.MiningLoop;
import net.runelite.client.plugins.recorder.session.SessionDirectory;
import net.runelite.client.plugins.recorder.transport.RouteOverlay;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.login.CredentialStore;
import net.runelite.client.sequence.login.EncryptedFileCredentialStore;
import net.runelite.client.sequence.login.KeychainCredentialStore;
import net.runelite.client.sequence.login.LoginAssistant;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@PluginDescriptor(
    name = "Recorder",
    description = "Captures human gameplay sessions for offline analysis",
    tags = {"recorder", "capture", "sequencer", "automation"}
)
public class RecorderPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private RecorderConfig config;
    @Inject private MouseManager mouseManager;
    @Inject private KeyManager keyManager;
    @Inject private EventBus eventBus;
    @Inject private ClientToolbar clientToolbar;
    @Inject private ItemManager itemManager;
    @Inject private OverlayManager overlayManager;

    private RecorderManager manager;
    private RecorderPanel panel;
    private NavigationButton navButton;
    private MouseCapture mouseCapture;
    private KeyCapture keyCapture;
    private FocusCapture focusCapture;
    private EventBusCapture eventCapture;
    private HotkeyHandler hotkeys;
    private HotkeyListener markerListener;
    private HotkeyListener toggleListener;
    private AWTEventListener focusBridge;
    private DebugOverlay debugOverlay;
    private LoginDebugOverlay loginDebugOverlay;
    private ChickenOverlay chickenOverlay;
    private RouteOverlay routeOverlay;
    private TileMarker tileMarker;
    private ChickenCombatLoop chickenLoop;
    private MiningLoop miningLoop;
    private TrailRecorder trailRecorder;
    private TrailRegistry trailRegistry;
    private AnnotatorHudOverlay hudOverlay;
    private AreaSelector areaSelector;

    @Provides
    RecorderConfig provideConfig(ConfigManager cm)
    {
        return cm.getConfig(RecorderConfig.class);
    }

    @Override
    protected void startUp()
    {
        Path root = config.recordingsDir().isBlank()
            ? Paths.get(System.getProperty("user.home"), ".runelite", "sequencer", "recordings")
            : Paths.get(config.recordingsDir());
        SessionDirectory sessions = new SessionDirectory(root);

        ChatFilter chatFilter = new ChatFilter(config.capturePlayerChat());
        ClickResolver click = new ClickResolver();
        NearbyResolver nearby = new NearbyResolver();
        CameraSampler camera = new CameraSampler(
            config.cameraSampleThresholdYaw(), config.cameraSampleThresholdPitch());
        eventCapture = new EventBusCapture(client, chatFilter, click, nearby, camera);
        mouseCapture = new MouseCapture(config.mouseMoveDownsampleHz());
        keyCapture = new KeyCapture();
        focusCapture = new FocusCapture();

        manager = new RecorderManager(client, config, eventCapture,
            mouseCapture, keyCapture, focusCapture, sessions, itemManager, clientThread);
        panel = new RecorderPanel(manager, client, clientThread);
        debugOverlay = new DebugOverlay(client);
        loginDebugOverlay = new LoginDebugOverlay(client);
        chickenOverlay = new ChickenOverlay(client, config);
        routeOverlay = new RouteOverlay(client);
        panel.setRouteOverlay(routeOverlay);
        panel.setTransportResolver(new TransportResolver(client));
        tileMarker = new TileMarker(client);
        panel.setDebugOverlay(debugOverlay);
        panel.setTileMarker(tileMarker);
        hudOverlay = new AnnotatorHudOverlay();
        areaSelector = new AreaSelector(client, clientThread, mouseManager);
        panel.setHudOverlay(hudOverlay);
        panel.setAreaSelector(areaSelector);
        overlayManager.add(debugOverlay);
        overlayManager.add(loginDebugOverlay);
        overlayManager.add(chickenOverlay);
        overlayManager.add(routeOverlay);
        overlayManager.add(hudOverlay);

        // Wire login assistant. We construct a fresh dispatcher here for
        // the assistant so its single-flight busy flag is independent from
        // the panel's walk-test dispatcher (the panel's dispatcher is the
        // one in RecorderPanel; both are humanized, both use the same
        // CanvasInput plumbing — concurrent use across them would still
        // double-press, so the panel serialises by convention via daemon
        // threads it owns). The login assistant only runs on user click.
        HumanizedInputDispatcher loginDispatcher = new HumanizedInputDispatcher(client, clientThread);
        LoginAssistant loginAssistant = new LoginAssistant(loginDispatcher, client, clientThread);
        CredentialStore credentialStore = KeychainCredentialStore.isAvailable()
            ? new KeychainCredentialStore()
            : new EncryptedFileCredentialStore(
                java.nio.file.Paths.get(System.getProperty("user.home"),
                    ".runelite", "sequencer", "credentials.enc"),
                () -> {
                    // Fall back to a fixed-name env var the user can set per
                    // session. Encrypted file is the fallback path; on
                    // macOS we never reach here.
                    String env = System.getenv("RUNELITE_SEQUENCER_PASSPHRASE");
                    return env == null ? new char[0] : env.toCharArray();
                });
        panel.setLoginAssistant(loginAssistant);
        panel.setCredentialStore(credentialStore);

        // Chicken combat loop: uses a fresh dispatcher (independent busy flag
        // from the panel's walk-test dispatcher and the login dispatcher).
        // The user starts/stops via the side-panel "Combat" section.
        HumanizedInputDispatcher combatDispatcher = new HumanizedInputDispatcher(client, clientThread);
        chickenLoop = new ChickenCombatLoop(combatDispatcher, client, clientThread);
        panel.setChickenLoop(chickenLoop);

        // Lumbridge bank ↔ pen script — the hand-coded loop the user
        // wrote (V1). Independent dispatcher; uses the TransportResolver
        // already constructed for the panel's Mark object button.
        HumanizedInputDispatcher lumbyDispatcher = new HumanizedInputDispatcher(client, clientThread);
        TransportResolver lumbyResolver = new TransportResolver(client);
        LumbridgeBankPenScript lumbyScript = new LumbridgeBankPenScript(
            client, clientThread, lumbyDispatcher, lumbyResolver);
        panel.setLumbyScript(lumbyScript);

        // Chicken farm V2 — same route, but the walking phases are driven
        // by the walker framework (UniversalWalker + PathSpec). Banking
        // and combat stay in the script. Lives alongside V1 with its own
        // dispatcher so the two can be compared side-by-side without
        // dispatcher-busy contention.
        HumanizedInputDispatcher v2Dispatcher = new HumanizedInputDispatcher(client, clientThread);
        TransportResolver v2Resolver = new TransportResolver(client);
        ChickenFarmV2Script chickenFarmV2 = new ChickenFarmV2Script(
            client, clientThread, v2Dispatcher, v2Resolver);
        panel.setChickenFarmV2(chickenFarmV2);

        // Trail recorder + registry. The registry directory lives under
        // ~/.runelite/recorder/trails/ — separate from the session
        // recordings root because trails are a different artefact (route
        // capture, not full session capture).
        java.nio.file.Path trailDir = java.nio.file.Paths.get(
            System.getProperty("user.home"), ".runelite", "recorder", "trails");
        trailRegistry = new TrailRegistry(trailDir);
        trailRegistry.load();
        trailRecorder = new TrailRecorder(client);
        eventBus.register(trailRecorder);
        panel.setTrailRecorder(trailRecorder);
        panel.setTrailRegistry(trailRegistry);

        // Chicken farm V3 — same outer FSM as V2 but uses the recorded
        // trail framework for the walking phases. Independent dispatcher
        // so V1/V2/V3 can coexist. Wired after trailRegistry is created.
        HumanizedInputDispatcher v3Dispatcher = new HumanizedInputDispatcher(client, clientThread);
        net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script chickenFarmV3 =
            new net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script(
                client, clientThread, v3Dispatcher, trailRegistry);
        panel.setChickenFarmV3(chickenFarmV3);

        // Mining loop: separate dispatcher, independent busy flag from
        // combat / login / test-walk. The user adds candidate rocks via
        // the side-panel "Mining" section, then starts/stops the loop.
        HumanizedInputDispatcher miningDispatcher = new HumanizedInputDispatcher(client, clientThread);
        miningLoop = new MiningLoop(miningDispatcher, client, clientThread, null,
            msg -> panel.onMiningStatus(msg));
        panel.setMiningLoop(miningLoop);

        // Cooking script — independent dispatcher + transport resolver.
        // The script is location- and food-agnostic; the panel feeds it
        // a CookingLocation + raw food item id and starts.
        HumanizedInputDispatcher cookingDispatcher = new HumanizedInputDispatcher(client, clientThread);
        TransportResolver cookingResolver = new TransportResolver(client);
        CookingScript cookingScript = new CookingScript(
            client, clientThread, cookingDispatcher, cookingResolver);
        panel.setCookingScript(cookingScript);

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/util/reset.png");
        navButton = NavigationButton.builder()
            .tooltip("Recorder")
            .icon(icon)
            .priority(8)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        eventBus.register(eventCapture);
        mouseManager.registerMouseListener(mouseCapture);
        mouseManager.registerMouseListener(tileMarker);
        mouseManager.registerMouseWheelListener(mouseCapture);
        keyManager.registerKeyListener(keyCapture);
        // AWT-level focus bridge so we don't depend on the RuneLite frame existing
        // at startUp time and so we cover any window the client creates later.
        focusBridge = event -> {
            if (event.getID() == WindowEvent.WINDOW_GAINED_FOCUS)
            {
                focusCapture.windowGainedFocus((WindowEvent) event);
            }
            else if (event.getID() == WindowEvent.WINDOW_LOST_FOCUS)
            {
                focusCapture.windowLostFocus((WindowEvent) event);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(focusBridge, AWTEvent.WINDOW_FOCUS_EVENT_MASK);

        hotkeys = new HotkeyHandler(manager, clientThread);
        markerListener = new HotkeyListener(() -> config.markerHotkey())
        {
            @Override public void hotkeyPressed() { hotkeys.onMarkerHotkey(); }
        };
        toggleListener = new HotkeyListener(() -> config.toggleHotkey())
        {
            @Override public void hotkeyPressed() { hotkeys.onToggleHotkey(); }
        };
        keyManager.registerKeyListener(markerListener);
        keyManager.registerKeyListener(toggleListener);

        log.info("Recorder plugin started");
    }

    @Override
    protected void shutDown()
    {
        // Stop in-flight recording first so producers don't enqueue while we
        // tear listeners down. abort() drains and closes the writer without
        // running analysis or popping a file browser.
        if (manager != null && manager.getState() != RecorderState.IDLE)
        {
            manager.abort();
        }
        if (chickenLoop != null) chickenLoop.stop();
        if (miningLoop != null) miningLoop.stop();
        if (hudOverlay != null) overlayManager.remove(hudOverlay);
        if (areaSelector != null && areaSelector.isActive()) areaSelector.cancel();
        if (debugOverlay != null) overlayManager.remove(debugOverlay);
        if (loginDebugOverlay != null) overlayManager.remove(loginDebugOverlay);
        if (chickenOverlay != null) overlayManager.remove(chickenOverlay);
        if (routeOverlay != null) overlayManager.remove(routeOverlay);
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        if (panel != null) panel.dispose();
        if (markerListener != null) keyManager.unregisterKeyListener(markerListener);
        if (toggleListener != null) keyManager.unregisterKeyListener(toggleListener);
        if (mouseCapture != null)
        {
            mouseManager.unregisterMouseListener(mouseCapture);
            if (tileMarker != null) mouseManager.unregisterMouseListener(tileMarker);
            mouseManager.unregisterMouseWheelListener(mouseCapture);
        }
        if (keyCapture != null) keyManager.unregisterKeyListener(keyCapture);
        if (focusBridge != null) Toolkit.getDefaultToolkit().removeAWTEventListener(focusBridge);
        if (trailRecorder != null) eventBus.unregister(trailRecorder);
        trailRecorder = null;
        trailRegistry = null;
        if (eventCapture != null) eventBus.unregister(eventCapture);
        panel = null; navButton = null; debugOverlay = null; chickenOverlay = null; routeOverlay = null; tileMarker = null;
        hudOverlay = null; areaSelector = null;
        markerListener = null; toggleListener = null;
        mouseCapture = null; keyCapture = null; focusCapture = null;
        focusBridge = null;
        eventCapture = null; manager = null; hotkeys = null;
        chickenLoop = null;
        miningLoop = null;
    }
}
