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
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
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
import net.runelite.client.plugins.recorder.worldmap.TransportIO;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.plugins.recorder.worldmap.TransportObserver;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;
import net.runelite.client.plugins.recorder.annotator.AnnotatorHudOverlay;
import net.runelite.client.plugins.recorder.annotator.AreaSelector;
import net.runelite.client.plugins.recorder.scripts.ChickenFarmV2Script;
import net.runelite.client.plugins.recorder.scripts.CooksAssistantScript;
import net.runelite.client.plugins.recorder.scripts.GrandExchangeScript;
import net.runelite.client.plugins.recorder.scripts.PieDishScript;
import net.runelite.client.plugins.recorder.scripts.PizzaScript;
import net.runelite.client.plugins.recorder.scripts.UltraCompostScript;
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
import net.runelite.client.plugins.recorder.nav.v2.CollisionDebugOverlay;
import net.runelite.client.plugins.recorder.nav.v2.ObjectDebugOverlay;
import net.runelite.client.plugins.recorder.nav.v2.V2PathOverlay;
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
    private V2PathOverlay v2PathOverlay;
    private CollisionDebugOverlay collisionDebugOverlay;
    private ObjectDebugOverlay objectDebugOverlay;
    private net.runelite.client.plugins.recorder.worldmap.WorldMapMinimapOverlay worldMapMinimapOverlay;
    private TileMarker tileMarker;
    private ChickenCombatLoop chickenLoop;
    private MiningLoop miningLoop;
    private GrandExchangeScript grandExchangeScript;
    private TrailRecorder trailRecorder;
    private TrailRegistry trailRegistry;
    private AnnotatorHudOverlay hudOverlay;
    private CooksAssistantScript cooksAssistantScript;
    private PieDishScript pieDishScript;
    private UltraCompostScript ultraCompostScript;
    private PizzaScript pizzaScript;
    private AreaSelector areaSelector;
    private net.runelite.client.plugins.recorder.inspector.ClickInspector clickInspector;

    // WorldMemory subsystem — passive tile/entity scrapers + planner.
    private MapStore worldMapStore;
    private EntityIndex worldEntityIndex;
    private TransportIndex transportIndex;
    /** v2.1 nav sidecars — populated by Tasks 7/8. Constructed at
     *  startUp alongside {@link #transportIndex} so V21Env always has
     *  non-null stores; behavioural use is deferred. */
    private net.runelite.client.plugins.recorder.nav.v21.GoalDeadEndMemory v21GoalDeadEnds;
    private net.runelite.client.plugins.recorder.nav.v21.RouteSkeletonStore v21RouteSkeletons;
    private TransportObserver transportObserver;
    private SceneScraper sceneScraper;
    private EntityScraper entityScraper;
    private MapPlanner mapPlanner;
    private WorldMemoryConfig wmConfig;
    private FlushDaemon flushDaemon;
    private int tickCounter;
    /** Root for persisted region/entity/transport JSON. Set in startUp;
     *  read by both the FlushDaemon and the bootstrap path. */
    private File worldmapRoot;

    /** Precomputed connectivity components for static collision. Built
     *  on a background daemon thread during startUp; null until that
     *  finishes (~1-2s). Read by {@link WaypointPlannerShim} via a
     *  Supplier — see the architect-pinned mint-time resolution rule
     *  in {@code WorldSnapshotBuilder.captureOnClientThread}.
     *  Single-writer (the daemon thread), many-reader (snapshot mint
     *  on the client thread); {@code volatile} is the right primitive. */
    private volatile net.runelite.client.plugins.recorder.nav.v2.collision.ConnectivityComponents
        v2Components;
    /** Once-per-plugin-startup guard: the disk bootstrap should run
     *  exactly once after the first LOGGED_IN event so subsequent
     *  log-out / log-in cycles don't overwrite warm in-memory state
     *  with potentially older disk snapshots. */
    private final java.util.concurrent.atomic.AtomicBoolean worldMapBootstrapped
        = new java.util.concurrent.atomic.AtomicBoolean(false);

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
        v2PathOverlay = new V2PathOverlay(client, config);
        collisionDebugOverlay = new CollisionDebugOverlay(client, config);
        objectDebugOverlay = new ObjectDebugOverlay(client, config);
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
        overlayManager.add(v2PathOverlay);
        overlayManager.add(collisionDebugOverlay);
        overlayManager.add(objectDebugOverlay);
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

        // Chicken farm V3 wiring is deferred to after the worldmap subsystem
        // is constructed (below) — the Navigator factory now also accepts
        // a V2Navigator built on shared MapStore + TransportIndex.

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

        // Ernest the Chicken — engine-driven LinearSequence. Construction is
        // deferred until after V2Navigator is built (see below) because the
        // cross-scene walks need V2Nav, which doesn't exist yet at this point.

        // Pie dish script — buy pie dishes + flour + water, craft pastry dough
        // then pie shells at GE, and sell shells. Uses the same grandExchangeScript
        // for GE buy/sell. Independent dispatcher so it never collides with
        // Cook's Assistant on the dispatcher busy flag.
        HumanizedInputDispatcher pieDispatcher = new HumanizedInputDispatcher(client, clientThread);
        pieDishScript = new PieDishScript(client, clientThread, pieDispatcher, grandExchangeScript);
        panel.setPieDishScript(pieDishScript);

        // Ultra compost script — buy supercompost + volcanic ash, combine into
        // ultracompost at GE, sell. Independent dispatcher mirrors pieDishScript.
        HumanizedInputDispatcher ultraDispatcher = new HumanizedInputDispatcher(client, clientThread);
        ultraCompostScript = new UltraCompostScript(client, clientThread, ultraDispatcher, grandExchangeScript);
        panel.setUltraCompostScript(ultraCompostScript);

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
        panel.setNavigatorModeConfig(config, configManager);

        // WorldMemory subsystem — passive scraper + planner. Scrapers run on
        // the client thread (via @Subscribe onGameTick); the planner is called
        // from worker threads (cooking scripts etc.).
        wmConfig = new WorldMemoryConfig();
        worldMapStore = new MapStore(wmConfig);
        worldEntityIndex = new EntityIndex();
        transportIndex = new TransportIndex();
        v21GoalDeadEnds = new net.runelite.client.plugins.recorder.nav.v21.GoalDeadEndMemory();
        v21RouteSkeletons = new net.runelite.client.plugins.recorder.nav.v21.RouteSkeletonStore();
        transportObserver = new TransportObserver(client, transportIndex);
        eventBus.register(transportObserver);
        sceneScraper = new SceneScraper(client, worldMapStore, worldEntityIndex, wmConfig);
        entityScraper = new EntityScraper();
        mapPlanner = new MapPlanner(worldMapStore, wmConfig);

        // Bootstrap: load any persisted chunks for the player's current region
        // + 8 neighbours so the planner has data immediately on a warm run
        // (otherwise the planner returns empty for the first scrape cycle
        // even when JSON exists from a prior session). Done off the client
        // thread to avoid blocking startup.
        //
        // Trigger model — fire ONCE per plugin lifetime, on first LOGGED_IN:
        //  * If the plugin loads while the player is already in-game (rare
        //    at cold launch but happens on /reload-plugins or hot-swap),
        //    the initial check below fires the bootstrap immediately.
        //  * Otherwise, onGameStateChanged catches the LOGGED_IN transition
        //    that follows a normal title-screen → world login flow.
        //
        // The previous one-shot startup thread silently no-op'd whenever
        // the player wasn't logged in at startUp (the common case),
        // leaving MapStore empty until SceneScraper rebuilt — which left
        // gaps wherever the corridor wasn't walked in the live scrape
        // window. Symptom: V2_STRICT returning "no route" with persisted
        // region JSON sitting unread on disk.
        worldmapRoot = new File(RuneLite.RUNELITE_DIR, "recorder/worldmap");
        // Wire on-disk lazy-load so MapStore.snapshotFor() can rehydrate a
        // region after the LRU evicts it (default cap = 24). Without this
        // the V2 planner returns NO_ROUTE for any destination region the
        // player has visited but has since drifted far from — even though
        // the snapshot is still on disk.
        worldMapStore.setRootDir(worldmapRoot);
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            triggerWorldMapBootstrap();
        }

        flushDaemon = new FlushDaemon(worldMapStore, worldEntityIndex,
            transportIndex, worldmapRoot, wmConfig.flushEverySeconds * 1000L);
        // v2.1 dead-end sidecar — load on startup, flush on the same
        // cadence as the worldmap/transport stores via the registered
        // callback. Path mirrors transports.json's location.
        java.nio.file.Path v21DeadEndsPath = worldmapRoot.toPath().resolve("v21-deadends.json");
        v21GoalDeadEnds.loadFrom(v21DeadEndsPath);
        flushDaemon.addFlushCallback(() -> v21GoalDeadEnds.flushTo(v21DeadEndsPath));
        // v2.1 route-skeleton sidecar — record-only this round, mirrors the
        // dead-end sidecar's lifecycle (load at startup, flush via daemon
        // cadence + on shutdown via flushOnce).
        java.nio.file.Path v21SkeletonsPath = worldmapRoot.toPath().resolve("v21-skeletons.json");
        v21RouteSkeletons.loadFrom(v21SkeletonsPath);
        flushDaemon.addFlushCallback(() -> v21RouteSkeletons.flushTo(v21SkeletonsPath));
        flushDaemon.start();

        // V2 Navigator stack — shared planner + per-script executor.
        // V2Planner + RouteHistory are stateless w.r.t. who is calling
        // (planning reads world memory, doesn't mutate per-script state),
        // so a single instance serves every script that opts in. The
        // executor + invalidation classifier are per-script because they
        // own attempt-local state (blacklist, transient penalties, recent-
        // reject window) that shouldn't bleed across independent walks.
        net.runelite.client.plugins.recorder.nav.v2.RouteHistory v2RouteHistory
            = new net.runelite.client.plugins.recorder.nav.v2.RouteHistory();
        // Trail-bias adapter: V2Planner asks for a trail's tile sequence
        // by name, gets back the recorded WorldPoints (or null when the
        // trail isn't on disk). Steers A* toward the V1-recorded route
        // for known destinations while keeping V2's adaptability when
        // those tiles are blocked at runtime.
        java.util.function.Function<String, java.util.List<net.runelite.api.coords.WorldPoint>>
            v2TrailTilesByName = name -> {
                if (name == null || name.isBlank()) return null;
                net.runelite.client.plugins.recorder.trail.Trail t = trailRegistry.byName(name);
                if (t == null) return null;
                java.util.List<net.runelite.api.coords.WorldPoint> tiles = new java.util.ArrayList<>();
                for (var ev : t.events())
                {
                    if (ev instanceof net.runelite.client.plugins.recorder.trail.TrailEvent.Tile tile)
                    {
                        tiles.add(tile.tile());
                    }
                }
                return tiles;
            };
        net.runelite.client.plugins.recorder.nav.v2.V2Planner sharedV2Planner
            = new net.runelite.client.plugins.recorder.nav.v2.V2Planner(
                worldMapStore, transportIndex, wmConfig, v2RouteHistory,
                config::enableV2RouteVariation, v2TrailTilesByName);

        // New observation-aware engine (spec 2026-05-16). The legacy
        // V2Planner above is constructed but the V2Navigator below is
        // wired to WaypointPlannerShim, which uses Skretzo's bundled
        // collision data + transport TSVs through Lane 4's
        // WaypointPlanner. The sharedV2Planner instance is preserved
        // for InspectionDumper (line 491) until the legacy planner is
        // fully retired per spec §8 THROW.
        net.runelite.client.plugins.recorder.nav.v2.collision.GlobalCollisionSnapshot
            sharedGlobalSnapshot;
        try
        {
            sharedGlobalSnapshot = net.runelite.client.plugins.recorder.nav.v2.collision
                .GlobalCollisionSnapshot.fromBundledResource();
            log.info("nav-engine: loaded global collision snapshot version {}",
                sharedGlobalSnapshot.mapVersion());
        }
        catch (Throwable th)
        {
            log.error("nav-engine: GlobalCollisionSnapshot.fromBundledResource() FAILED: {}",
                th.toString(), th);
            sharedGlobalSnapshot = null;
        }

        // Connectivity-components precompute: ~1-2s flood-fill over
        // every loaded region in the global snapshot. Runs on a
        // dedicated daemon thread so plugin startUp doesn't block.
        // Result lands in the volatile v2Components field; the planner
        // shim reads it via Supplier and stores the captured reference
        // on each WorldSnapshot at mint time. Dijkstra is collision-
        // blind during the precompute window and switches over once
        // the field is non-null. See spec
        // docs/superpowers/specs/2026-05-17-collision-aware-dijkstra-design.md
        if (sharedGlobalSnapshot != null)
        {
            final net.runelite.client.plugins.recorder.nav.v2.collision.GlobalCollisionSnapshot
                snapForPrecompute = sharedGlobalSnapshot;
            Thread componentsThread = new Thread(() ->
            {
                long t0 = System.nanoTime();
                try
                {
                    net.runelite.client.plugins.recorder.nav.v2.collision.ConnectivityComponents c
                        = net.runelite.client.plugins.recorder.nav.v2.collision
                            .ConnectivityComponents.fromSnapshot(snapForPrecompute);
                    v2Components = c;
                    log.info("[nav-v2.components] ready — {} components in {} ms",
                        c.componentCount(), (System.nanoTime() - t0) / 1_000_000);
                }
                catch (Throwable t)
                {
                    log.error("[nav-v2.components] precompute FAILED: {}",
                        t.toString(), t);
                }
            }, "nav-v2-components-precompute");
            componentsThread.setDaemon(true);
            componentsThread.start();
        }
        net.runelite.client.plugins.recorder.nav.v2.transport.TransportTable sharedTransportTable;
        try
        {
            sharedTransportTable = net.runelite.client.plugins.recorder.nav.v2.transport
                .TransportTable.loadDefaults();
        }
        catch (Throwable th)
        {
            log.error("nav-engine: TransportTable.loadDefaults() FAILED: {}", th.toString(), th);
            sharedTransportTable = null;
        }
        net.runelite.client.plugins.recorder.nav.v2.predicate.PredicateRegistry sharedPredicates
            = new net.runelite.client.plugins.recorder.nav.v2.predicate.PredicateRegistry();

        // Chicken farm V3 — Navigator-driven outer FSM. Each script that
        // opts into V2 carries its own dispatcher (no busy-flag contention)
        // and its own executor (independent recovery state).
        HumanizedInputDispatcher v3Dispatcher = new HumanizedInputDispatcher(client, clientThread);
        TrailWalker v3Walker = new TrailWalker(client, clientThread, v3Dispatcher);
        net.runelite.client.plugins.recorder.nav.v2.V2Navigator v3V2Nav;
        if (sharedGlobalSnapshot != null && sharedTransportTable != null)
        {
            net.runelite.client.plugins.recorder.nav.v2.planner.WaypointPlannerShim shim
                = new net.runelite.client.plugins.recorder.nav.v2.planner.WaypointPlannerShim(
                    client, clientThread, sharedGlobalSnapshot, sharedTransportTable,
                    sharedPredicates,
                    () -> this.v2Components);
            v3V2Nav = buildV2NavigatorWithHook(v3Dispatcher, shim);
            log.info("nav-engine: V2Navigator wired to WaypointPlannerShim (new observation-aware engine)");
        }
        else
        {
            // Fallback to legacy V2Planner if Skretzo data couldn't load.
            v3V2Nav = buildV2Navigator(v3Dispatcher, sharedV2Planner);
            log.warn("nav-engine: falling back to legacy V2Planner — Skretzo data missing");
        }
        net.runelite.client.plugins.recorder.nav.v21.V21Navigator v3V21Nav = buildV21Navigator(v3Dispatcher);
        NavigatorFactory v3NavFactory = new NavigatorFactory(
            config, v3Walker, trailRegistry, v3V2Nav, v3V21Nav);
        net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script chickenFarmV3 =
            new net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script(
                client, clientThread, v3Dispatcher, trailRegistry, eventBus,
                v3NavFactory);
        panel.setChickenFarmV3(chickenFarmV3);

        // Ernest the Chicken — own dispatcher + V2Navigator (independent busy
        // flag + executor state). NavWalkStep drives this nav for cross-scene
        // legs (bank→Veronica, manor approach, fountain, etc.); the basement
        // lever-puzzle keeps the engine's in-scene WalkStep since those are
        // all within the loaded scene.
        HumanizedInputDispatcher ernestDispatcher = new HumanizedInputDispatcher(client, clientThread);
        net.runelite.client.plugins.recorder.nav.v2.V2Navigator ernestV2Nav;
        if (sharedGlobalSnapshot != null && sharedTransportTable != null)
        {
            net.runelite.client.plugins.recorder.nav.v2.planner.WaypointPlannerShim ernestShim
                = new net.runelite.client.plugins.recorder.nav.v2.planner.WaypointPlannerShim(
                    client, clientThread, sharedGlobalSnapshot, sharedTransportTable,
                    sharedPredicates,
                    () -> this.v2Components);
            ernestV2Nav = buildV2NavigatorWithHook(ernestDispatcher, ernestShim);
        }
        else
        {
            ernestV2Nav = buildV2Navigator(ernestDispatcher, sharedV2Planner);
            log.warn("nav-engine: Ernest falling back to legacy V2Planner — Skretzo data missing");
        }
        net.runelite.client.plugins.recorder.quest.ErnestQuestScript ernestQuestScript =
            new net.runelite.client.plugins.recorder.quest.ErnestQuestScript(
                client, clientThread, ernestDispatcher, ernestV2Nav);
        panel.setErnestQuestScript(ernestQuestScript, trailRegistry);
        eventBus.register(ernestQuestScript);

        File inspectDir = new File(RuneLite.RUNELITE_DIR, "recorder/inspect");
        net.runelite.client.plugins.recorder.nav.v2.MultiRegionAStar v2Planner
            = new net.runelite.client.plugins.recorder.nav.v2.MultiRegionAStar(
                worldMapStore, transportIndex, wmConfig);
        net.runelite.client.plugins.recorder.worldmap.InspectionDumper inspectionDumper
            = new net.runelite.client.plugins.recorder.worldmap.InspectionDumper(
                worldMapStore, worldEntityIndex, transportIndex, wmConfig, inspectDir,
                v2Planner);
        worldMapMinimapOverlay = new net.runelite.client.plugins.recorder.worldmap
            .WorldMapMinimapOverlay(client, config, worldMapStore, worldEntityIndex, transportIndex);
        overlayManager.add(worldMapMinimapOverlay);
        eventBus.register(worldMapMinimapOverlay);
        panel.setInspectionDumper(inspectionDumper,
            net.runelite.client.plugins.recorder.worldmap.WorldMapMinimapOverlay::clearActiveRoute);

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

    /** Build a per-script v2.1 reactive Navigator. Uses the script's own
     *  dispatcher so its busy flag isn't contended. v2.1 has no shared
     *  planner — each tick reads the live scene/collision via its own
     *  env. Cheap to construct; no precomputed graphs.
     *
     *  <p>The {@link TransportIndex}, {@link TrailRegistry},
     *  {@code GoalDeadEndMemory}, and {@code RouteSkeletonStore} are
     *  shared across all v2.1 navigators — planner reads are read-only
     *  w.r.t. the index, and dead-end / skeleton sidecars are global
     *  by design (learning that "this approach to the chicken pen
     *  failed" should benefit any script that targets the pen). */
    private net.runelite.client.plugins.recorder.nav.v21.V21Navigator buildV21Navigator(
        HumanizedInputDispatcher dispatcher)
    {
        net.runelite.client.plugins.recorder.nav.v21.V21Env env
            = new net.runelite.client.plugins.recorder.nav.v21.V21Env(
                client, clientThread, dispatcher,
                transportIndex, trailRegistry,
                v21GoalDeadEnds, v21RouteSkeletons);
        return new net.runelite.client.plugins.recorder.nav.v21.V21Navigator(env);
    }

    /** Build a per-script V2Navigator on top of the shared planner. The
     *  executor + invalidation classifier + filter/clicker collaborators
     *  are private to the script so its recovery state doesn't bleed
     *  into other scripts that might be running V2 concurrently. */
    private net.runelite.client.plugins.recorder.nav.v2.V2Navigator buildV2Navigator(
        HumanizedInputDispatcher dispatcher,
        net.runelite.client.plugins.recorder.nav.v2.V2Planner planner)
    {
        net.runelite.client.plugins.recorder.nav.v2.EmptyTileFilter filter
            = new net.runelite.client.plugins.recorder.nav.v2.EmptyTileFilter(client);
        net.runelite.client.plugins.recorder.nav.v2.MinimapClicker minimap
            = new net.runelite.client.plugins.recorder.nav.v2.MinimapClicker(
                client, dispatcher.pixelResolver(), dispatcher);
        net.runelite.client.plugins.recorder.nav.v2.CanvasTilePicker picker
            = new net.runelite.client.plugins.recorder.nav.v2.CanvasTilePicker();
        net.runelite.client.plugins.recorder.nav.v2.InvalidationClassifier classifier
            = new net.runelite.client.plugins.recorder.nav.v2.InvalidationClassifier();
        net.runelite.client.plugins.recorder.nav.v2.V2Executor.Env env
            = new net.runelite.client.plugins.recorder.nav.v2.V2ExecutorEnv(
                client, clientThread, dispatcher, filter, minimap, worldMapStore, transportIndex);
        net.runelite.client.plugins.recorder.nav.v2.V2Executor.Toggles toggles
            = new net.runelite.client.plugins.recorder.nav.v2.V2Executor.Toggles()
        {
            @Override public boolean variableDistance() { return config.enableV2VariableDistance(); }
            @Override public boolean minimapModality() { return config.enableV2MinimapModality(); }
            @Override public boolean catchupClicks() { return config.enableV2CatchupClicks(); }
        };
        net.runelite.client.plugins.recorder.nav.v2.V2Executor executor
            = new net.runelite.client.plugins.recorder.nav.v2.V2Executor(
                env, picker, classifier, new java.util.Random(), toggles);
        return new net.runelite.client.plugins.recorder.nav.v2.V2Navigator(
            planner, executor, () -> playerLocOnClientThread(), worldEntityIndex);
    }

    /** Same shape as {@link #buildV2Navigator(HumanizedInputDispatcher,
     *  net.runelite.client.plugins.recorder.nav.v2.V2Planner)} but accepts
     *  a {@link net.runelite.client.plugins.recorder.nav.v2.V2Navigator.PlannerHook}
     *  directly so the new {@code WaypointPlannerShim} can be wired
     *  without going through {@code V2Planner}. */
    private net.runelite.client.plugins.recorder.nav.v2.V2Navigator buildV2NavigatorWithHook(
        HumanizedInputDispatcher dispatcher,
        net.runelite.client.plugins.recorder.nav.v2.V2Navigator.PlannerHook plannerHook)
    {
        net.runelite.client.plugins.recorder.nav.v2.EmptyTileFilter filter
            = new net.runelite.client.plugins.recorder.nav.v2.EmptyTileFilter(client);
        net.runelite.client.plugins.recorder.nav.v2.MinimapClicker minimap
            = new net.runelite.client.plugins.recorder.nav.v2.MinimapClicker(
                client, dispatcher.pixelResolver(), dispatcher);
        net.runelite.client.plugins.recorder.nav.v2.CanvasTilePicker picker
            = new net.runelite.client.plugins.recorder.nav.v2.CanvasTilePicker();
        net.runelite.client.plugins.recorder.nav.v2.InvalidationClassifier classifier
            = new net.runelite.client.plugins.recorder.nav.v2.InvalidationClassifier();
        net.runelite.client.plugins.recorder.nav.v2.V2Executor.Env env
            = new net.runelite.client.plugins.recorder.nav.v2.V2ExecutorEnv(
                client, clientThread, dispatcher, filter, minimap, worldMapStore, transportIndex);
        net.runelite.client.plugins.recorder.nav.v2.V2Executor.Toggles toggles
            = new net.runelite.client.plugins.recorder.nav.v2.V2Executor.Toggles()
        {
            @Override public boolean variableDistance() { return config.enableV2VariableDistance(); }
            @Override public boolean minimapModality() { return config.enableV2MinimapModality(); }
            @Override public boolean catchupClicks() { return config.enableV2CatchupClicks(); }
        };
        net.runelite.client.plugins.recorder.nav.v2.V2Executor executor
            = new net.runelite.client.plugins.recorder.nav.v2.V2Executor(
                env, picker, classifier, new java.util.Random(), toggles);
        net.runelite.client.plugins.recorder.nav.v2.V2Navigator nav =
            net.runelite.client.plugins.recorder.nav.v2.V2Navigator.withPlannerHook(
                plannerHook, executor, () -> playerLocOnClientThread(), worldEntityIndex);
        // Wire TransportCorrectionSink so transport-mismatch corrections
        // flow through the navigator (spec §4 Lane 5 + §7 rule 5 — navigator
        // owns TransportTable mutation, not the executor). The sink applies
        // a TransportCorrectionRequest as a TransportTable.replace(...).
        nav.setTransportCorrectionSink(req -> {
            // Build a corrected TransportLink to replace the stale one.
            // Look up the planner's TransportTable singleton via the shim's
            // reference if the hook is a WaypointPlannerShim; otherwise no-op.
            if (plannerHook instanceof net.runelite.client.plugins.recorder.nav.v2.planner.WaypointPlannerShim shim)
            {
                shim.applyTransportCorrection(req);
            }
            else
            {
                log.debug("nav-engine: TransportCorrectionRequest dropped (non-shim planner): {}", req);
            }
        });
        return nav;
    }

    /** Synchronous client-thread read of the local player's world
     *  location. Reused by V2Navigator's planner-input supplier — it
     *  runs on the script's worker thread, so getLocalPlayer (which
     *  asserts on the client thread) needs marshalling.
     *
     *  <p>Fast path: if the caller is already on the client thread,
     *  read inline. Mirrors the {@code V2ExecutorEnv.onClient} guard:
     *  prevents a 1500 ms self-deadlock if a future caller invokes
     *  this from the client thread. */
    @javax.annotation.Nullable
    private net.runelite.api.coords.WorldPoint playerLocOnClientThread()
    {
        if (client.isClientThread())
        {
            net.runelite.api.Player self = client.getLocalPlayer();
            return self == null ? null : self.getWorldLocation();
        }
        java.util.concurrent.atomic.AtomicReference<net.runelite.api.coords.WorldPoint> ref
            = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try
            {
                net.runelite.api.Player self = client.getLocalPlayer();
                ref.set(self == null ? null : self.getWorldLocation());
            }
            finally { latch.countDown(); }
        });
        try
        {
            if (!latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS))
            {
                log.warn("v2: playerLocOnClientThread timed out — returning null");
                return null;
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
        return ref.get();
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
        if (ultraCompostScript != null) { ultraCompostScript.stop(); ultraCompostScript = null; }
        if (pizzaScript != null) { pizzaScript.stop(); pizzaScript = null; }
        if (hudOverlay != null) overlayManager.remove(hudOverlay);
        if (areaSelector != null && areaSelector.isActive()) areaSelector.cancel();
        if (debugOverlay != null) overlayManager.remove(debugOverlay);
        if (loginDebugOverlay != null) overlayManager.remove(loginDebugOverlay);
        if (chickenOverlay != null) overlayManager.remove(chickenOverlay);
        if (routeOverlay != null) overlayManager.remove(routeOverlay);
        if (trailOverlay != null) { overlayManager.remove(trailOverlay); trailOverlay.detach(); }
        if (v2PathOverlay != null) { overlayManager.remove(v2PathOverlay); v2PathOverlay.detach(); }
        if (collisionDebugOverlay != null) overlayManager.remove(collisionDebugOverlay);
        if (objectDebugOverlay != null) overlayManager.remove(objectDebugOverlay);
        if (worldMapMinimapOverlay != null)
        {
            overlayManager.remove(worldMapMinimapOverlay);
            eventBus.unregister(worldMapMinimapOverlay);
            worldMapMinimapOverlay.detach();
            worldMapMinimapOverlay = null;
        }
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
        if (transportObserver != null)
        {
            eventBus.unregister(transportObserver);
            transportObserver = null;
        }
        if (flushDaemon != null)
        {
            flushDaemon.stop();
            flushDaemon.flushOnce();   // persist in-memory data before exit
            flushDaemon = null;
        }
        worldMapStore = null;
        worldEntityIndex = null;
        transportIndex = null;
        v21GoalDeadEnds = null;
        v21RouteSkeletons = null;
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

    /** Listen for LOGGED_IN so the disk bootstrap fires the moment the
     *  player has a real {@link Player#getWorldLocation()}. Idempotent
     *  via {@link #worldMapBootstrapped} — the first successful fire
     *  marks the plugin lifetime as bootstrapped; subsequent log-out /
     *  log-in cycles leave the warm in-memory MapStore alone. */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN)
        {
            triggerWorldMapBootstrap();
        }
    }

    /** Idempotent kick. Safe to call from any thread; the actual disk
     *  reads run on a daemon worker. */
    private void triggerWorldMapBootstrap()
    {
        if (worldmapRoot == null || worldMapStore == null || transportIndex == null
            || worldEntityIndex == null)
        {
            // Plugin still mid-startUp; the explicit check at the end of
            // startUp will run the bootstrap once everything's wired.
            return;
        }
        if (!worldMapBootstrapped.compareAndSet(false, true)) return;
        Thread t = new Thread(this::runWorldMapBootstrap, "worldmap-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    /** Worker-thread bootstrap: cold-loads region snapshots, entity
     *  sightings, and the transport graph for the player's current
     *  region + 8 neighbours.
     *
     *  <p>LOGGED_IN fires before the {@link Player} object is fully
     *  populated. Poll for the player location every 500ms, up to 20
     *  attempts (10s budget). Reads MUST go through the client thread
     *  — {@code Player.getWorldLocation()} asserts under {@code -ea}.
     *
     *  <p>If the player still isn't ready after the budget, give up
     *  cleanly and reset the guard so a future LOGGED_IN cycle can
     *  retry (e.g. on a logout/login). */
    private void runWorldMapBootstrap()
    {
        net.runelite.api.coords.WorldPoint loc = playerLocOnClientThread();
        int attempts = 0;
        while (loc == null)
        {
            if (attempts >= 20)
            {
                log.warn("worldmap-bootstrap: player not ready after {} attempts ({}s) — giving up; "
                    + "next LOGGED_IN cycle will retry", attempts, attempts / 2);
                worldMapBootstrapped.set(false);
                return;
            }
            try { Thread.sleep(500); }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                worldMapBootstrapped.set(false);
                return;
            }
            loc = playerLocOnClientThread();
            attempts++;
        }
        if (attempts > 0)
        {
            log.info("worldmap-bootstrap: player ready after {} retries", attempts);
        }
        int rx = loc.getX() >> 6;
        int ry = loc.getY() >> 6;
        log.info("worldmap-bootstrap: loading regions around ({}, {})", rx, ry);
        int regionsLoaded = 0;
        int sightingsLoaded = 0;
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                int regionId = ((rx + dx) << 8) | ((ry + dy) & 0xff);
                if (worldMapStore.loadFromDisk(worldmapRoot, regionId)) regionsLoaded++;
            }
        }
        for (int dx = -1; dx <= 1; dx++)
        {
            for (int dy = -1; dy <= 1; dy++)
            {
                int regionId = ((rx + dx) << 8) | ((ry + dy) & 0xff);
                for (EntitySighting s : MapStoreIO.readEntities(worldmapRoot, regionId))
                {
                    worldEntityIndex.recordNpcSighting(s.id, s.name, s.lastTile, s.lastSeenAt);
                    sightingsLoaded++;
                }
            }
        }
        java.util.Collection<net.runelite.client.plugins.recorder.worldmap.TransportEdge> transports
            = TransportIO.readAll(worldmapRoot);
        transportIndex.replaceAll(transports);
        log.info("worldmap-bootstrap: complete — {} regions, {} entity sightings, {} transports",
            regionsLoaded, sightingsLoaded, transports.size());
    }
}
