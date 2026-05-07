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
import java.io.File;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameTick;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.recorder.worldmap.EntityIndex;
import net.runelite.client.plugins.recorder.worldmap.EntityScraper;
import net.runelite.client.plugins.recorder.worldmap.EntitySighting;
import net.runelite.client.plugins.recorder.worldmap.FlushDaemon;
import net.runelite.client.plugins.recorder.worldmap.MapPlanner;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.MapStoreIO;
import net.runelite.client.plugins.recorder.worldmap.SceneScraper;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;
import net.runelite.client.plugins.recorder.annotator.AnnotatorHudOverlay;
import net.runelite.client.plugins.recorder.annotator.AreaSelector;
import net.runelite.client.plugins.recorder.scripts.ChickenFarmV2Script;
import net.runelite.client.plugins.recorder.scripts.CooksAssistantScript;
import net.runelite.client.plugins.recorder.scripts.GrandExchangeScript;
import net.runelite.client.plugins.recorder.scripts.PieDishScript;
import net.runelite.client.plugins.recorder.scripts.PizzaScript;
import net.runelite.api.coords.WorldArea;
import net.runelite.client.plugins.recorder.scripts.LumbridgeBankPenScript;
import net.runelite.client.plugins.recorder.nav.NavigatorFactory;
import net.runelite.client.plugins.recorder.trail.TrailRecorder;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
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
import net.runelite.client.plugins.recorder.trail.TrailOverlay;
import net.runelite.client.plugins.recorder.transport.RouteOverlay;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.InputOwnership;
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
    @Inject private ConfigManager configManager;

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
    private TrailOverlay trailOverlay;
    private TileMarker tileMarker;
    private ChickenCombatLoop chickenLoop;
    private MiningLoop miningLoop;
    private GrandExchangeScript grandExchangeScript;
    private TrailRecorder trailRecorder;
    private TrailRegistry trailRegistry;
    private AnnotatorHudOverlay hudOverlay;
    private CooksAssistantScript cooksAssistantScript;
    private PieDishScript pieDishScript;
    private PizzaScript pizzaScript;
    private AreaSelector areaSelector;
    private net.runelite.client.plugins.recorder.inspector.ClickInspector clickInspector;

    // WorldMemory subsystem — passive tile/entity scrapers + planner.
    private MapStore worldMapStore;
    private EntityIndex worldEntityIndex;
    private SceneScraper sceneScraper;
    private EntityScraper entityScraper;
    private MapPlanner mapPlanner;
    private WorldMemoryConfig wmConfig;
    private FlushDaemon flushDaemon;
    private int tickCounter;

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
        // TrailWalker pushes its active path + current click pick to the
        // overlay via static publish helpers, so this single instance
        // visualises whichever script is currently driving the walker.
        trailOverlay = new TrailOverlay(client, config);
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
        overlayManager.add(trailOverlay);
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

        // Login V2 — parallel path; new sprite-frame click coords + per-account
        // last-world preference. Uses an independent dispatcher so V1/V2 can't
        // collide on the dispatcher's busy flag.
        HumanizedInputDispatcher loginV2Dispatcher = new HumanizedInputDispatcher(client, clientThread);
        net.runelite.client.sequence.login.LoginAssistantV2 loginAssistantV2 =
            new net.runelite.client.sequence.login.LoginAssistantV2(loginV2Dispatcher, client, clientThread);
        net.runelite.client.sequence.login.AccountPrefs accountPrefs =
            new net.runelite.client.sequence.login.AccountPrefs(configManager);
        panel.setLoginAssistantV2(loginAssistantV2);
        panel.setAccountPrefs(accountPrefs);

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

        // Chicken farm V3 — same outer FSM, walking phases driven via
        // the Navigator interface. Independent dispatcher + walker so
        // V1/V2/V3 coexist without dispatcher-busy contention. Wired
        // after trailRegistry is created.
        HumanizedInputDispatcher v3Dispatcher = new HumanizedInputDispatcher(client, clientThread);
        TrailWalker v3Walker = new TrailWalker(client, clientThread, v3Dispatcher);
        NavigatorFactory v3NavFactory = new NavigatorFactory(config, v3Walker, trailRegistry);
        net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script chickenFarmV3 =
            new net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script(
                client, clientThread, v3Dispatcher, trailRegistry, eventBus,
                v3NavFactory.getNavigator());
        panel.setChickenFarmV3(chickenFarmV3);

        // Mining loop: separate dispatcher, independent busy flag from
        // combat / login / test-walk. The user adds candidate rocks via
        // the side-panel "Mining" section, then starts/stops the loop.
        HumanizedInputDispatcher miningDispatcher = new HumanizedInputDispatcher(client, clientThread);
        miningLoop = new MiningLoop(miningDispatcher, client, clientThread, null,
            msg -> panel.onMiningStatus(msg));
        panel.setMiningLoop(miningLoop);

        // Cooking script — independent dispatcher + transport resolver.
        // Cooking V2 — independent dispatcher + resolver. Panel
        // enforces mutual exclusion at the Start-button level (only one
        // cooking script runs at a time). V2 fixes the four bot-tells
        // documented in CookingScriptV2 javadoc.
        HumanizedInputDispatcher cookingV2Dispatcher = new HumanizedInputDispatcher(client, clientThread);
        TransportResolver cookingV2Resolver = new TransportResolver(client);
        net.runelite.client.plugins.recorder.scripts.CookingScriptV2 cookingScriptV2 =
            new net.runelite.client.plugins.recorder.scripts.CookingScriptV2(
                client, clientThread, cookingV2Dispatcher, cookingV2Resolver);
        panel.setCookingScriptV2(cookingScriptV2);

        // Cooking V3 — independent dispatcher + resolver (same shape as
        // V2). V3 dispatches the bank-booth click as live-tracked
        // (ActionRequest.liveTracked=true) so the dispatcher re-projects
        // the booth's hull during cursor motion instead of binding to
        // a stale T0 pixel — see CookingScriptV3 javadoc.
        HumanizedInputDispatcher cookingV3Dispatcher = new HumanizedInputDispatcher(client, clientThread);
        TransportResolver cookingV3Resolver = new TransportResolver(client);
        net.runelite.client.plugins.recorder.scripts.CookingScriptV3 cookingScriptV3 =
            new net.runelite.client.plugins.recorder.scripts.CookingScriptV3(
                client, clientThread, cookingV3Dispatcher, cookingV3Resolver,
                this, config);
        panel.setCookingScriptV3(cookingScriptV3);

        // GE Core + Phase B bank-prep: independent dispatcher + InputOwnership
        // lease. The bank-prep variants withdraw coins / sell items from a GE
        // bank booth before placing the offer; we wire a BankInteraction
        // backed by the GE script's own dispatcher so bank clicks coordinate
        // with the rest of the GE flow.
        HumanizedInputDispatcher geDispatcher = new HumanizedInputDispatcher(client, clientThread);
        InputOwnership geInputOwnership = new InputOwnership();
        WorldArea geArea = new WorldArea(3140, 3470, 30, 30, 0);
        net.runelite.client.plugins.recorder.farm.BankInteraction geBank =
            new net.runelite.client.plugins.recorder.farm.BankInteraction(
                client, clientThread, geDispatcher);
        grandExchangeScript = new GrandExchangeScript(
            client, clientThread, geDispatcher, geInputOwnership, geArea, geBank);
        eventBus.register(grandExchangeScript);
        panel.setGrandExchangeScript(grandExchangeScript, itemManager);

        // Cook's Assistant quest script — constructed after GE Core so we can
        // pass grandExchangeScript + trailRegistry as dependencies.
        HumanizedInputDispatcher questDispatcher = new HumanizedInputDispatcher(client, clientThread);
        TransportResolver questResolver = new TransportResolver(client);
        cooksAssistantScript = new CooksAssistantScript(
            client, clientThread, questDispatcher, questResolver, trailRegistry, grandExchangeScript);
        panel.setCooksAssistantScript(cooksAssistantScript);

        // Pie dish script — buy pie dishes + flour + water, craft pastry dough
        // then pie shells at GE, and sell shells. Uses the same grandExchangeScript
        // for GE buy/sell. Independent dispatcher so it never collides with
        // Cook's Assistant on the dispatcher busy flag.
        HumanizedInputDispatcher pieDispatcher = new HumanizedInputDispatcher(client, clientThread);
        pieDishScript = new PieDishScript(client, clientThread, pieDispatcher, grandExchangeScript);
        panel.setPieDishScript(pieDishScript);

        // Pizza script — three batched bank↔bank loops (combine inputs)
        // plus one bank → kitchen-range round trip to cook the uncooked
        // pizzas. Independent dispatcher so it never collides with the
        // other scripts on the dispatcher busy flag.
        HumanizedInputDispatcher pizzaDispatcher = new HumanizedInputDispatcher(client, clientThread);
        pizzaScript = new PizzaScript(client, clientThread, pizzaDispatcher, trailRegistry);
        panel.setPizzaScript(pizzaScript);

        // Click inspector — toggleable diagnostic. Subscribes itself to the
        // EventBus only when enabled; safe to leave off by default.
        clickInspector = new net.runelite.client.plugins.recorder.inspector.ClickInspector(
            client, eventBus);
        panel.setClickInspector(clickInspector);
        panel.setWorldMemoryPlannerConfig(config, configManager);
        panel.setNavigatorImplConfig(config, configManager);

        // WorldMemory subsystem — passive scraper + planner. Scrapers run on
        // the client thread (via @Subscribe onGameTick); the planner is called
        // from worker threads (cooking scripts etc.).
        wmConfig = new WorldMemoryConfig();
        worldMapStore = new MapStore(wmConfig);
        worldEntityIndex = new EntityIndex();
        sceneScraper = new SceneScraper(client, worldMapStore, worldEntityIndex, wmConfig);
        entityScraper = new EntityScraper();
        mapPlanner = new MapPlanner(worldMapStore, wmConfig);

        // Bootstrap: load any persisted chunks for the player's current region
        // + 8 neighbours so the planner has data immediately on a warm run
        // (otherwise the planner returns empty for the first scrape cycle
        // even when JSON exists from a prior session). Done off the client
        // thread to avoid blocking startup.
        File worldmapRoot = new File(RuneLite.RUNELITE_DIR, "recorder/worldmap");
        Thread bootstrapThread = new Thread(() -> {
            Player self = client.getLocalPlayer();
            if (self == null || self.getWorldLocation() == null) return;
            int rx = self.getWorldLocation().getX() >> 6;
            int ry = self.getWorldLocation().getY() >> 6;
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    int regionId = ((rx + dx) << 8) | ((ry + dy) & 0xff);
                    worldMapStore.loadFromDisk(worldmapRoot, regionId);
                }
            }
            // Entity sightings — for nearestNpc/Object queries.
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    int regionId = ((rx + dx) << 8) | ((ry + dy) & 0xff);
                    for (EntitySighting s : MapStoreIO.readEntities(worldmapRoot, regionId))
                    {
                        worldEntityIndex.recordNpcSighting(s.id, s.name, s.lastTile, s.lastSeenAt);
                    }
                }
            }
        }, "worldmap-bootstrap");
        bootstrapThread.setDaemon(true);
        bootstrapThread.start();

        flushDaemon = new FlushDaemon(worldMapStore, worldEntityIndex,
            worldmapRoot, wmConfig.flushEverySeconds * 1000L);
        flushDaemon.start();

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
        if (cooksAssistantScript != null) { cooksAssistantScript.stop(); cooksAssistantScript = null; }
        if (pieDishScript != null) { pieDishScript.stop(); pieDishScript = null; }
        if (pizzaScript != null) { pizzaScript.stop(); pizzaScript = null; }
        if (hudOverlay != null) overlayManager.remove(hudOverlay);
        if (areaSelector != null && areaSelector.isActive()) areaSelector.cancel();
        if (debugOverlay != null) overlayManager.remove(debugOverlay);
        if (loginDebugOverlay != null) overlayManager.remove(loginDebugOverlay);
        if (chickenOverlay != null) overlayManager.remove(chickenOverlay);
        if (routeOverlay != null) overlayManager.remove(routeOverlay);
        if (trailOverlay != null) { overlayManager.remove(trailOverlay); trailOverlay.detach(); }
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
        if (grandExchangeScript != null) {
            try { grandExchangeScript.stop(); } catch (Exception ignore) {}
            eventBus.unregister(grandExchangeScript);
            grandExchangeScript = null;
        }
        if (eventCapture != null) eventBus.unregister(eventCapture);
        if (clickInspector != null) {
            clickInspector.setEnabled(false);
            clickInspector = null;
        }
        panel = null; navButton = null; debugOverlay = null; chickenOverlay = null; routeOverlay = null; trailOverlay = null; tileMarker = null;
        hudOverlay = null; areaSelector = null;
        markerListener = null; toggleListener = null;
        mouseCapture = null; keyCapture = null; focusCapture = null;
        focusBridge = null;
        eventCapture = null; manager = null; hotkeys = null;
        chickenLoop = null;
        miningLoop = null;
        if (flushDaemon != null)
        {
            flushDaemon.stop();
            flushDaemon.flushOnce();   // persist in-memory data before exit
            flushDaemon = null;
        }
        worldMapStore = null;
        worldEntityIndex = null;
        sceneScraper = null;
        entityScraper = null;
        mapPlanner = null;
        wmConfig = null;
        tickCounter = 0;
    }

    @Subscribe
    public void onGameTick(GameTick e)
    {
        if (wmConfig == null) return;
        if (++tickCounter % wmConfig.scrapeEveryNTicks != 0) return;
        long now = System.currentTimeMillis();
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return;
        if (wv.getScene() != null && wv.getScene().isInstance()) return;
        long start = System.nanoTime();
        sceneScraper.scan(wv, now);
        if (System.nanoTime() - start > wmConfig.scrapeBudgetNanos)
        {
            log.debug("worldmap: scene scrape exceeded budget; skipping entities this tick");
            return;
        }
        entityScraper.scanNpcs(wv, now, worldEntityIndex);
    }

    /** Public accessor for scripts (e.g. CookingScriptV3). */
    public MapPlanner mapPlanner() { return mapPlanner; }
}
