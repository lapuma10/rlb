/*
 * Copyright (c) 2025, Mantas
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

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.recorder.annotator.AnnotatorHudOverlay;
import net.runelite.client.plugins.recorder.annotator.AreaSelector;
import net.runelite.client.plugins.recorder.annotator.RoutesTab;
import net.runelite.client.plugins.recorder.annotator.WaypointEditor;
import net.runelite.client.plugins.recorder.combat.ChickenCombatLoop;
import net.runelite.client.plugins.recorder.cook.CookingFood;
import net.runelite.client.plugins.recorder.cook.CookingLocation;
import net.runelite.client.plugins.recorder.cook.CookingLocations;
import net.runelite.client.plugins.recorder.debug.DebugOverlay;
import net.runelite.client.plugins.recorder.farm.RouteWalker;
import net.runelite.client.plugins.recorder.mining.MiningLoop;
import net.runelite.client.plugins.recorder.scripts.ChickenFarmV2Script;
import net.runelite.client.plugins.recorder.scripts.CooksAssistantScript;
import net.runelite.client.plugins.recorder.scripts.GrandExchangeScript;
import net.runelite.client.plugins.recorder.scripts.GrandExchangeTab;
import net.runelite.client.plugins.recorder.scripts.LumbridgeBankPenScript;
import net.runelite.client.plugins.recorder.trail.TrailGraph;
import net.runelite.client.plugins.recorder.trail.TrailPath;
import net.runelite.client.plugins.recorder.trail.TrailPlanner;
import net.runelite.client.plugins.recorder.trail.TrailRecorder;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.plugins.recorder.trail.TrailWalker;
import net.runelite.client.plugins.recorder.debug.TileMarker;
import net.runelite.client.plugins.recorder.events.Events;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import net.runelite.client.plugins.recorder.transport.RouteOverlay;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.login.CredentialStore;
import net.runelite.client.sequence.login.CredentialStoreException;
import net.runelite.client.sequence.login.LoginAssistant;
import net.runelite.client.sequence.login.LoginCredentials;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.KeyStroke;

@Slf4j
public final class RecorderPanel extends PluginPanel
{
    private final RecorderManager manager;
    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final JLabel stateLabel = new JLabel("State: IDLE");
    private final JLabel elapsedLabel = new JLabel("Elapsed: 0s");
    private final JLabel totalLabel = new JLabel("Events: 0");
    // HTML so multi-line wraps inside a single label; updated each tick.
    private final JLabel breakdownLabel = new JLabel("<html>&nbsp;</html>");
    private final JButton recordBtn = new JButton("Record");
    private final JTextField markerField = new JTextField(16);
    private final JButton markerBtn = new JButton("Add marker");
    private final DefaultListModel<String> recentModel = new DefaultListModel<>();
    private final JList<String> recentList = new JList<>(recentModel);
    private final JScrollPane recentScroll = new JScrollPane(recentList);
    private TransportResolver transportResolver;
    private final JButton markTileBtn = new JButton("Mark tile (next click)");
    private final JButton walkToMarkBtn = new JButton("Walk to mark");
    private final JButton clearMarkBtn = new JButton("Clear");
    private final JLabel markedLabel = new JLabel("Marked: (none)");
    private final JPanel credListPanel = new JPanel();
    private final java.util.List<String> currentUsernames = new java.util.ArrayList<>();
    private String selectedUsername = null;
    private final JButton addCornerBtn = new JButton("+");
    private final JButton loginBtn = new JButton("Log in");
    private final JButton loginV2Btn = new JButton("Log in V2");
    private final JTextField worldField = new JTextField(4);
    private final JLabel loginStatus = new JLabel(" ");
    private final java.util.concurrent.atomic.AtomicBoolean loginInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Thread loginThread = null;
    private final JButton stopBtn = new JButton("Stop");
    private LoginAssistant loginAssistant;
    private net.runelite.client.sequence.login.LoginAssistantV2 loginAssistantV2;
    private net.runelite.client.sequence.login.AccountPrefs accountPrefs;
    private CredentialStore credentialStore;
    private DebugOverlay debugOverlay;
    private TileMarker tileMarker;
    private RouteOverlay routeOverlay;
    private net.runelite.client.plugins.recorder.inspector.ClickInspector clickInspector;
    private net.runelite.client.plugins.recorder.worldmap.InspectionDumper inspectionDumper;
    private Runnable v2OverlayClearAction;
    private final JButton v2DumpRegionBtn = new JButton("Dump current region");
    private final JButton v2DumpNearbyBtn = new JButton("Dump nearby regions");
    private final JButton v2DumpTransportsBtn = new JButton("Dump transport graph");
    private final JButton v2DumpEntitiesBtn = new JButton("Dump entity sightings");
    private final JButton v2PlanAToBBtn = new JButton("Plan A→B");
    private final JButton v2ClearOverlayRouteBtn = new JButton("Clear debug overlay route");
    private final JTextField v2PlanFromField = new JTextField(10);
    private final JTextField v2PlanToField = new JTextField(10);
    private final JLabel v2InspectStatusLabel = new JLabel(" ");
    /** Named A↔B presets used by the V2 readiness/dump panel. Each entry
     *  defines a label + from/to coords for one route shoulder; selecting
     *  a preset fills the From/To text fields so the user can run
     *  readiness, corridor dump, or Plan A→B without typing coordinates.
     *  Anchors are plane-0 entry tiles (V2 round-1 cannot drive transport
     *  legs, so plane-0 endpoints surface real corridor data). */
    private static final java.util.List<RoutePreset> V2_PRESETS = java.util.List.of(
        new RoutePreset("Lumby bank → pen (north)",
            new WorldPoint(3208, 3220, 0), new WorldPoint(3236, 3294, 0)),
        new RoutePreset("Pen → Lumby bank (north)",
            new WorldPoint(3236, 3294, 0), new WorldPoint(3208, 3220, 0)),
        new RoutePreset("Lumby bank → pen (south)",
            new WorldPoint(3208, 3220, 0), new WorldPoint(3232, 3289, 0)),
        new RoutePreset("Pen → Lumby bank (south)",
            new WorldPoint(3232, 3289, 0), new WorldPoint(3208, 3220, 0)),
        new RoutePreset("Lumby → Draynor",
            new WorldPoint(3221, 3219, 0), new WorldPoint(3092, 3245, 0)),
        new RoutePreset("Draynor → Lumby",
            new WorldPoint(3092, 3245, 0), new WorldPoint(3221, 3219, 0)),
        new RoutePreset("Lumby → GE",
            new WorldPoint(3221, 3219, 0), new WorldPoint(3164, 3486, 0)),
        new RoutePreset("GE → Lumby",
            new WorldPoint(3164, 3486, 0), new WorldPoint(3221, 3219, 0)));
    private final JComboBox<RoutePreset> v2PresetCombo = new JComboBox<>(V2_PRESETS.toArray(new RoutePreset[0]));
    private final JButton v2PresetApplyBtn = new JButton("Apply preset");

    /** Named (label, from, to) triple for the V2 readiness preset combo box. */
    private record RoutePreset(String label, WorldPoint from, WorldPoint to)
    {
        @Override public String toString() { return label; }
    }
    private final javax.swing.JCheckBox clickInspectorCb =
        new javax.swing.JCheckBox("Click Inspector");
    private final javax.swing.JCheckBox worldMemoryPlannerCb =
        new javax.swing.JCheckBox("WorldMemory planner (experimental)");
    private final JComboBox<RecorderConfig.NavigatorMode> navigatorModeCombo =
        new JComboBox<>(RecorderConfig.NavigatorMode.values());
    private Timer refreshTimer;
    private final JButton chickenStartBtn = new JButton("Start chicken loop");
    private final JButton chickenStopBtn = new JButton("Stop");
    private final JLabel chickenStatusLabel = new JLabel("Chicken loop: idle");
    private final JLabel chickenKillsLabel = new JLabel("Kills: 0");
    private ChickenCombatLoop chickenLoop;
    // "Chicken farm" tab — hand-coded Lumbridge bank ↔ pen script.
    private LumbridgeBankPenScript lumbyScript;
    // Same tab — V2 (walker-framework version). Optional; both can coexist.
    private ChickenFarmV2Script chickenFarmV2;
    private final JButton lumbyStartBtn = new JButton("Start");
    private final JButton lumbyStopBtn = new JButton("Stop");
    private final JLabel lumbyStatusLabel = new JLabel("Chicken farm: idle");
    private final JLabel lumbyKillsLabel = new JLabel("Kills: 0");
    // V2 — same route via UniversalWalker. Lives next to V1 for side-by-side comparison.
    private final JButton v2StartBtn = new JButton("Start");
    private final JButton v2StopBtn = new JButton("Stop");
    private final JLabel v2StatusLabel = new JLabel("V2: idle");
    private final JLabel v2KillsLabel = new JLabel("Kills: 0");
    // V3 — trail-network walker version. Lives next to V2 for direct comparison.
    private final JButton v3StartBtn = new JButton("Start");
    private final JButton v3StopBtn = new JButton("Stop");
    // ──── V3 debug controls (walk-testing) ────
    private final javax.swing.JCheckBox v3DebugStopAfterArrivalCb =
        new javax.swing.JCheckBox("Stop after arrival");
    private final javax.swing.JCheckBox v3DebugSkipCombatCb =
        new javax.swing.JCheckBox("Skip combat at pen");
    private final javax.swing.JCheckBox v3DebugSkipBankingCb =
        new javax.swing.JCheckBox("Skip banking");
    private final JButton v3DebugWalkToPenBtn = new JButton("Walk → Pen");
    private final JButton v3DebugWalkToBankBtn = new JButton("Walk → Bank");
    private final JLabel v3StatusLabel = new JLabel("V3: idle");
    private final JLabel v3KillsLabel = new JLabel("Kills: 0");
    private net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script chickenFarmV3;
    private final net.runelite.client.plugins.recorder.combat.TrainingPlanStore trainingPlanStore =
        new net.runelite.client.plugins.recorder.combat.TrainingPlanStore();
    /** Last username we restored saved training settings for. Tracked so we
     *  re-load when the player logs into a different account (the panel's
     *  initial restoreTrainingPanelState typically runs at plugin startup
     *  when {@code client.getUsername()} is still empty / "default"). */
    private volatile String lastRestoredUsername;
    // V3 training-mode controls.
    private final javax.swing.JCheckBox trainAttackCb   = new javax.swing.JCheckBox("Attack");
    private final javax.swing.JCheckBox trainStrengthCb = new javax.swing.JCheckBox("Strength");
    private final javax.swing.JCheckBox trainDefenceCb  = new javax.swing.JCheckBox("Defence");
    private final JTextField trainAttackLvlField   = new JTextField("20", 3);
    private final JTextField trainStrengthLvlField = new JTextField("20", 3);
    private final JTextField trainDefenceLvlField  = new JTextField("20", 3);
    private final javax.swing.JComboBox<String> autoRetaliateCombo =
        new javax.swing.JComboBox<>(new String[]{"Leave alone", "ON", "OFF"});
    private final JButton v3StartTrainingBtn = new JButton("Start Training");
    // Mining section — unique field names so the parallel-agent's Combat
    // section doesn't collide. The loop is wired by the plugin via
    // setMiningLoop(); the panel only owns the UI surface.
    private final JButton miningStartBtn = new JButton("Start mining");
    private final JButton miningStopBtn = new JButton("Stop");
    private final JButton miningAddRockBtn = new JButton("Add rock here");
    private final JButton miningClearRocksBtn = new JButton("Clear rocks");
    private final JLabel miningStatusLabel = new JLabel("Mining: idle");
    private final JLabel miningOreCountLabel = new JLabel("Ores: 0");
    private final JLabel miningRockCountLabel = new JLabel("Rocks: 0");
    private MiningLoop miningLoop;
    // Cooking section.
    private net.runelite.client.plugins.recorder.scripts.CookingScriptV2 cookingScriptV2;
    private net.runelite.client.plugins.recorder.scripts.CookingScriptV3 cookingScriptV3;
    private final JButton cookStartV2Btn = new JButton("Start V2 (human)");
    private final JButton cookStartV3Btn = new JButton("Start V3 (tracked)");
    private final JButton cookStopBtn = new JButton("Stop");
    private final JComboBox<CookingLocation> cookLocationBox = new JComboBox<>();
    private final JComboBox<CookingFood.Entry> cookFoodBox = new JComboBox<>();
    // Per-version session caps. 0/0 = no cap. Read by the matching
    // onCook*Start; mid-run edits have no effect (script reads at start()).
    private final JTextField cookV2MaxHoursField = new JTextField("0", 3);
    private final JTextField cookV2MaxMinutesField = new JTextField("0", 3);
    private final JTextField cookV3MaxHoursField = new JTextField("0", 3);
    private final JTextField cookV3MaxMinutesField = new JTextField("0", 3);
    private final JLabel cookStatusLabel = new JLabel("Cooking: idle");
    private final JLabel cookCountsLabel = new JLabel("Cooked: 0  Burnt: 0");
    // Cook's Assistant quest script.
    private CooksAssistantScript cooksAssistantScript;
    private final JButton questStartBtn  = new JButton("Start quest");
    private final JButton questStopBtn   = new JButton("Stop");
    private final JLabel  questStatusLabel = new JLabel("Quest: idle");
    // Pie Dish script.
    private net.runelite.client.plugins.recorder.scripts.PieDishScript pieDishScript;
    private final JButton pieDishStartBtn  = new JButton("Start");
    private final JButton pieDishStopBtn   = new JButton("Stop");
    private final JLabel  pieDishStatusLabel = new JLabel("Pie Dish: idle");
    // Pizza script.
    private net.runelite.client.plugins.recorder.scripts.PizzaScript pizzaScript;
    private final JButton pizzaStartBtn  = new JButton("Start");
    private final JButton pizzaStopBtn   = new JButton("Stop");
    // Per-loop enable toggles. Default = all selected, matching the
    // script defaults; user unchecks to skip a loop.  Read live by
    // PizzaScript.tickDecide on each bank trip.
    private final javax.swing.JCheckBox pizzaTomatoBox =
        new javax.swing.JCheckBox("Add tomato (loop 1)", true);
    private final javax.swing.JCheckBox pizzaCheeseBox =
        new javax.swing.JCheckBox("Add cheese (loop 2)", true);
    private final javax.swing.JCheckBox pizzaCookBox =
        new javax.swing.JCheckBox("Cook pizza (loop 3)", true);
    private final javax.swing.JCheckBox pizzaAnchoviesBox =
        new javax.swing.JCheckBox("Add anchovies (loop 4)", true);
    private final javax.swing.JCheckBox pizzaBreaksBox =
        new javax.swing.JCheckBox("AFK breaks (humanizer)", true);
    private final JLabel  pizzaStatusLabel = new JLabel("Pizza: idle");
    private final JLabel  pizzaCountsLabel = new JLabel("Made: 0");
    private final JLabel  pizzaBreakLabel  = new JLabel("breaks: idle");
    // GE Core (Phase A): wired by RecorderPlugin via setGrandExchangeScript.
    private GrandExchangeTab grandExchangeTab;
    private final JTabbedPane tabs = new JTabbedPane();
    private AnnotatorHudOverlay hudOverlay;
    private AreaSelector areaSelector;
    private RoutesTab routesTab;
    private final JPanel routesContainer = new JPanel(new BorderLayout());
    private volatile Thread walkerThread;
    // Trails section — wired by RecorderPlugin via setTrailRecorder / setTrailRegistry.
    private TrailRecorder trailRecorder;
    private TrailRegistry trailRegistry;
    /** Editable combo: dropdown lists existing trails (for picking one to
     *  display or stop-and-save into), but you can also type a new name
     *  to start a fresh recording. {@link #refreshTrailNames()} repopulates
     *  the dropdown items after registry load/save. */
    private final JComboBox<String> trailNameCombo = new JComboBox<>();
    private final JButton trailRecordBtn = new JButton("Record trail");
    private final JButton trailStopSaveBtn = new JButton("Stop & save");
    /** Pulls the selected/typed trail name from the combo, looks it up in
     *  the registry, and publishes it to the overlay so you can eyeball
     *  the route on the world map / minimap. */
    private final JButton trailShowBtn = new JButton("Show on map");
    private final JTextField trailWalkToField = new JTextField(14);
    private final JButton trailWalkToBtn = new JButton("Walk to…");
    private final JLabel trailStatusLabel = new JLabel("Trails: idle");
    private volatile Thread trailWalkerThread;

    public RecorderPanel(RecorderManager manager, Client client)
    {
        this(manager, client, null);
    }

    public RecorderPanel(RecorderManager manager, Client client, ClientThread clientThread)
    {
        super(false);
        this.manager = manager;
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = client == null ? null
            : new HumanizedInputDispatcher(client, clientThread);
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        add(buildStatusHeader(), BorderLayout.NORTH);

        tabs.addTab("Routes", tabScroll(buildRoutesTab()));
        tabs.addTab("Combat", tabScroll(buildCombatTab()));
        tabs.addTab("Chicken farm", tabScroll(buildChickenFarmTab()));
        tabs.addTab("Record", tabScroll(buildRecordTab()));
        tabs.addTab("Mining", tabScroll(buildMiningTab()));
        tabs.addTab("Cooking", tabScroll(buildCookingTab()));
        tabs.addTab("Cook's Quest", tabScroll(buildCooksQuestTab()));
        tabs.addTab("Pie Dish", tabScroll(buildPieDishTab()));
        tabs.addTab("Pizza", tabScroll(buildPizzaTab()));
        tabs.addTab("Login",  tabScroll(buildLoginTab()));
        add(tabs, BorderLayout.CENTER);

        recordBtn.addActionListener(this::onRecordToggle);
        chickenStartBtn.addActionListener(e -> onChickenStart());
        chickenStopBtn.addActionListener(e -> onChickenStop());
        lumbyStartBtn.addActionListener(e -> onLumbyStart());
        lumbyStopBtn.addActionListener(e -> onLumbyStop());
        v2StartBtn.addActionListener(e -> onV2Start());
        v2StopBtn.addActionListener(e -> onV2Stop());
        miningStartBtn.addActionListener(e -> onMiningStart());
        miningStopBtn.addActionListener(e -> onMiningStop());
        miningAddRockBtn.addActionListener(e -> onMiningAddRock());
        miningClearRocksBtn.addActionListener(e -> onMiningClearRocks());
        cookStartV2Btn.addActionListener(e -> onCookV2Start());
        cookStartV3Btn.addActionListener(e -> onCookV3Start());
        cookStopBtn.addActionListener(e -> onCookStop());
        questStartBtn.addActionListener(e -> onQuestStart());
        questStopBtn.addActionListener(e -> onQuestStop());
        markerBtn.addActionListener(e -> onMarker());
        markTileBtn.addActionListener(e -> onMarkTile());
        walkToMarkBtn.addActionListener(e -> onWalkToMark());
        clearMarkBtn.addActionListener(e -> onClearMark());
        manager.onStateChanged(s -> SwingUtilities.invokeLater(this::refresh));
        refreshTimer = new Timer(500, e -> refresh());
        refreshTimer.start();
    }

    public void dispose()
    {
        if (refreshTimer != null) { refreshTimer.stop(); refreshTimer = null; }
    }

    private JComponent buildStatusHeader()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Status"));
        p.add(stateLabel);
        p.add(elapsedLabel);
        p.add(totalLabel);
        p.add(breakdownLabel);
        // Diagnostic toggle: routes every menu click + the varbit/varc
        // changes it triggers to client.log under the [click-inspector]
        // tag. Off by default; toggle when cataloguing a new UI flow.
        p.add(clickInspectorCb);
        clickInspectorCb.addActionListener(e -> {
            if (clickInspector != null) {
                clickInspector.setEnabled(clickInspectorCb.isSelected());
            }
        });
        p.add(worldMemoryPlannerCb);
        // Action listener wired by setWorldMemoryPlannerConfig() once the
        // plugin has injected config + configManager.

        // Navigator mode selector — Phase-7 switch. HybridNavigator reads
        // this every tick, so flipping the dropdown takes effect on the
        // next request without restarting the script.
        JPanel navRow = new JPanel(new BorderLayout(4, 0));
        navRow.setBackground(p.getBackground());
        navRow.add(new JLabel("Navigator mode:"), BorderLayout.WEST);
        navRow.add(navigatorModeCombo, BorderLayout.CENTER);
        navRow.setMaximumSize(new java.awt.Dimension(
            Integer.MAX_VALUE,
            navigatorModeCombo.getPreferredSize().height));
        p.add(navRow);
        return p;
    }

    /** Wired by RecorderPlugin at startUp. */
    public void setClickInspector(
        net.runelite.client.plugins.recorder.inspector.ClickInspector ci)
    {
        this.clickInspector = ci;
    }

    /**
     * Wire the WorldMemory planner checkbox to the RuneLite config system.
     * Called by RecorderPlugin after both config and configManager are available.
     * Reads the current config value so the checkbox reflects the persisted state,
     * then registers an action listener that writes back on toggle.
     */
    public void setWorldMemoryPlannerConfig(RecorderConfig cfg, ConfigManager cm)
    {
        worldMemoryPlannerCb.setSelected(cfg.useWorldMemoryPlanner());
        worldMemoryPlannerCb.addActionListener(e ->
            cm.setConfiguration("recorder", "useWorldMemoryPlanner",
                worldMemoryPlannerCb.isSelected()));
    }

    /** Wire the Navigator mode dropdown to the config. Called by the
     *  plugin once {@link RecorderConfig} and {@link ConfigManager}
     *  are available. The combo reflects the persisted value at
     *  startup and writes back on user selection. */
    public void setNavigatorModeConfig(RecorderConfig cfg, ConfigManager cm)
    {
        RecorderConfig.NavigatorMode current = cfg.navigatorMode();
        if (current == null) current = RecorderConfig.NavigatorMode.V1_ONLY;
        navigatorModeCombo.setSelectedItem(current);
        navigatorModeCombo.addActionListener(e ->
        {
            Object sel = navigatorModeCombo.getSelectedItem();
            if (sel instanceof RecorderConfig.NavigatorMode mode)
            {
                cm.setConfiguration("recorder", "navigatorMode", mode);
            }
        });
    }

    // ------------------------------------------------------------------
    // Routes tab — swaps between RoutesTab (list) and WaypointEditor
    // (drilldown) inside a single container. Debug tile-mark surface
    // sits underneath for parity with the legacy panel.
    // ------------------------------------------------------------------

    private JPanel buildRoutesTab()
    {
        routesTab = new RoutesTab(this::openRouteEditor);
        routesContainer.add(routesTab, BorderLayout.CENTER);

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(routesContainer, BorderLayout.CENTER);

        // Debug + tile mark: compact testing surface — mark a tile, walk to
        // it, or clear. Lives underneath the routes drilldown.
        JPanel debug = new JPanel();
        debug.setLayout(new BoxLayout(debug, BoxLayout.Y_AXIS));
        debug.setBorder(BorderFactory.createTitledBorder("Debug + tile mark"));
        debug.add(markTileBtn);
        JPanel row = new JPanel(new BorderLayout(4, 4));
        row.add(walkToMarkBtn, BorderLayout.CENTER);
        row.add(clearMarkBtn, BorderLayout.EAST);
        debug.add(row);
        debug.add(markedLabel);

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.setBackground(ColorScheme.DARK_GRAY_COLOR);
        south.add(debug);
        south.add(Box.createVerticalStrut(8));
        south.add(buildTrailsSection());
        south.add(Box.createVerticalStrut(8));
        p.add(south, BorderLayout.SOUTH);

        return p;
    }

    private void openRouteEditor(Path routeFile)
    {
        routesContainer.removeAll();
        WaypointEditor editor = new WaypointEditor(routeFile, routeOverlay,
            () -> {
                routesContainer.removeAll();
                routesTab.refresh();
                routesContainer.add(routesTab, BorderLayout.CENTER);
                if (routeOverlay != null) routeOverlay.setSelected(null);
                routesContainer.revalidate();
                routesContainer.repaint();
            },
            new WaypointEditor.Hooks()
            {
                @Override public void onMarkArea(@Nullable Waypoint editing,
                                                 Consumer<Set<WorldPoint>> onCommit)
                {
                    if (areaSelector == null || hudOverlay == null) return;
                    // Re-clicking Mark area mid-session restarts the session
                    // cleanly rather than throwing IllegalStateException.
                    if (areaSelector.isActive()) areaSelector.cancel();
                    Set<WorldPoint> initial = editing == null
                        ? Set.of()
                        : editing.tiles();
                    hudOverlay.show(editing == null ? "New area"
                        : "Editing: " + (editing.name() == null ? "(unnamed)" : editing.name()));
                    // Seed the preview with the initial set so editing an
                    // existing waypoint shows its current tiles immediately.
                    routeOverlay.setPreviewTiles(initial);
                    areaSelector.start(initial, new AreaSelector.Listener()
                    {
                        @Override public void onSetChanged(Set<WorldPoint> tiles)
                        {
                            // Push the working set to the overlay so the user
                            // sees what they've selected after each click /
                            // drag-release.
                            routeOverlay.setPreviewTiles(tiles);
                        }
                        @Override public void onCommit(Set<WorldPoint> tiles)
                        {
                            hudOverlay.show(null);
                            routeOverlay.setPreviewTiles(java.util.Set.of());
                            routeOverlay.setInflightRect(java.util.Set.of(), false);
                            clearAnnotatorKeybindings();
                            onCommit.accept(tiles);
                        }
                        @Override public void onCancel()
                        {
                            hudOverlay.show(null);
                            routeOverlay.setPreviewTiles(java.util.Set.of());
                            routeOverlay.setInflightRect(java.util.Set.of(), false);
                            clearAnnotatorKeybindings();
                        }
                        @Override public void onDragPreview(@Nullable WorldPoint pressTile,
                                                            @Nullable WorldPoint dragTile,
                                                            boolean subtract)
                        {
                            // Live rectangle preview during drag. Null tiles
                            // (off-canvas, or end-of-drag) clear the rect.
                            if (pressTile == null || dragTile == null)
                            {
                                routeOverlay.setInflightRect(java.util.Set.of(), false);
                                return;
                            }
                            int minX = Math.min(pressTile.getX(), dragTile.getX());
                            int maxX = Math.max(pressTile.getX(), dragTile.getX());
                            int minY = Math.min(pressTile.getY(), dragTile.getY());
                            int maxY = Math.max(pressTile.getY(), dragTile.getY());
                            int plane = dragTile.getPlane();
                            java.util.Set<WorldPoint> rect = new java.util.HashSet<>(
                                (maxX - minX + 1) * (maxY - minY + 1));
                            for (int x = minX; x <= maxX; x++)
                                for (int y = minY; y <= maxY; y++)
                                    rect.add(new WorldPoint(x, y, plane));
                            routeOverlay.setInflightRect(rect, subtract);
                        }
                    });
                    // Bind Enter / Esc on the panel so the user can commit/cancel.
                    getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "annotator-commit");
                    getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "annotator-cancel");
                    getActionMap().put("annotator-commit", new AbstractAction()
                    {
                        @Override public void actionPerformed(ActionEvent e)
                        { if (areaSelector.isActive()) areaSelector.commit(); }
                    });
                    getActionMap().put("annotator-cancel", new AbstractAction()
                    {
                        @Override public void actionPerformed(ActionEvent e)
                        { if (areaSelector.isActive()) areaSelector.cancel(); }
                    });
                }

                @Override public void onMarkObject(Consumer<Waypoint> onCommit)
                {
                    if (tileMarker == null || transportResolver == null) return;
                    tileMarker.arm(wp -> {
                        if (wp == null) return;
                        clientThread.invokeLater(() -> {
                            // Search the click tile + its 8 neighbors so a
                            // click on the visible door / gate resolves even
                            // when the engine snapped the cursor to the floor
                            // tile beside the WallObject.
                            TransportResolver.TileMatch tm =
                                transportResolver.findAnyTransportNear(wp);
                            SwingUtilities.invokeLater(() -> {
                                if (tm == null)
                                {
                                    log.warn("Mark object: no transport found near {}", wp);
                                    return;
                                }
                                TransportResolver.AnyMatch match = tm.match();
                                Waypoint t = Waypoint.transport(
                                    tm.tile(), match.kind(), match.verb());
                                onCommit.accept(t);
                            });
                        });
                    });
                }

                @Override public void onAddCurrent(Consumer<Waypoint> onCommit)
                {
                    if (clientThread == null || client == null) return;
                    clientThread.invokeLater(() -> {
                        net.runelite.api.Player self = client.getLocalPlayer();
                        if (self == null) return;
                        WorldPoint loc = self.getWorldLocation();
                        if (loc == null) return;
                        SwingUtilities.invokeLater(() -> onCommit.accept(Waypoint.walk(loc)));
                    });
                }

                @Override public void onAddMarked(Consumer<Waypoint> onCommit)
                {
                    if (tileMarker == null) return;
                    WorldPoint loc = tileMarker.lastMarked();
                    if (loc != null) onCommit.accept(Waypoint.walk(loc));
                }

                @Override public void onWalkPath(List<Waypoint> wps)
                {
                    runWalker(wps, wps.size());
                }

                @Override public void onWalkToSelected(List<Waypoint> wps, int selectedIdx)
                {
                    runWalker(wps, selectedIdx + 1);
                }
            });
        routesContainer.add(editor, BorderLayout.CENTER);
        routesContainer.revalidate();
        routesContainer.repaint();
    }

    /** Drive the walker over waypoints[0..endExclusive).
     *  <ul>
     *    <li>Cancels any in-flight walker so multiple Walk path / Walk to
     *        selected clicks don't stack up concurrent threads.</li>
     *    <li>Each arrived / tick / position read happens on the client
     *        thread (the engine asserts via -ea in developer mode).</li>
     *    <li>Click cadence is "click once, then wait for the player to
     *        move; only re-click if the player has been still for 3+
     *        seconds." So one waypoint = ~one click instead of ~one click
     *        per tick.</li>
     *    <li>Plane mismatch (player on plane A, waypoint on plane B) for
     *        non-TRANSPORT waypoints is detected up front and the waypoint
     *        is skipped with a clear warning — the route needs an explicit
     *        climb-up / climb-down transport waypoint.</li>
     *    <li>State-level log lines: starting / arrived / advancing /
     *        skipping / stuck. Per-tick noise is gone.</li>
     *  </ul>
     */
    private void runWalker(List<Waypoint> wps, int endExclusive)
    {
        if (client == null || dispatcher == null || transportResolver == null
            || clientThread == null)
        {
            log.warn("walker: aborting — missing dependency");
            return;
        }
        // Cancel any in-flight walker before starting fresh.
        Thread previous = walkerThread;
        if (previous != null && previous.isAlive())
        {
            log.info("walker: cancelling previous walker thread");
            previous.interrupt();
            try { previous.join(500); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }

        final List<Waypoint> slice = new ArrayList<>(
            wps.subList(0, Math.max(0, Math.min(endExclusive, wps.size()))));
        log.info("walker: starting slice of {} waypoints", slice.size());

        Thread t = new Thread(() -> {
            try
            {
                RouteWalker w = new RouteWalker(client, dispatcher, transportResolver);
                for (int i = 0; i < slice.size(); i++)
                {
                    if (Thread.currentThread().isInterrupted()) break;
                    Waypoint wp = slice.get(i);
                    int wpIdx = i;
                    String wpLabel = "#" + wpIdx
                        + (wp.name() == null ? "" : " " + wp.name());

                    // Plane check up front: if the waypoint is plane-locked
                    // and the player is on a different plane, no amount of
                    // clicking will help — the route needs a climb verb.
                    java.util.concurrent.atomic.AtomicReference<WorldPoint> initialPos =
                        new java.util.concurrent.atomic.AtomicReference<>();
                    java.util.concurrent.CountDownLatch posLatch =
                        new java.util.concurrent.CountDownLatch(1);
                    clientThread.invokeLater(() -> {
                        try
                        {
                            net.runelite.api.Player self = client.getLocalPlayer();
                            if (self != null) initialPos.set(self.getWorldLocation());
                        }
                        finally { posLatch.countDown(); }
                    });
                    posLatch.await();

                    int wpPlane = wp.area() != null ? wp.area().getPlane()
                        : wp.tile() != null ? wp.tile().getPlane() : -1;
                    WorldPoint here = initialPos.get();
                    if (wp.kind() != Waypoint.Kind.TRANSPORT
                        && here != null && wpPlane >= 0
                        && here.getPlane() != wpPlane)
                    {
                        log.warn("walker: skipping {} (plane={}, player on plane={})"
                                + " — route needs an explicit climb-up / climb-down waypoint",
                            wpLabel, wpPlane, here.getPlane());
                        continue;
                    }

                    log.info("walker: starting {}", wpLabel);

                    WorldPoint lastSeen = here;
                    long lastClickAt = 0L;
                    long lastMoveAt = System.currentTimeMillis();
                    boolean arrivedHere = false;

                    while (!Thread.currentThread().isInterrupted())
                    {
                        java.util.concurrent.atomic.AtomicBoolean arrived =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
                        java.util.concurrent.atomic.AtomicReference<WorldPoint> curPos =
                            new java.util.concurrent.atomic.AtomicReference<>();
                        java.util.concurrent.CountDownLatch latch =
                            new java.util.concurrent.CountDownLatch(1);
                        clientThread.invokeLater(() -> {
                            try
                            {
                                if (w.arrived(wp))
                                {
                                    arrived.set(true);
                                }
                                else
                                {
                                    net.runelite.api.Player self = client.getLocalPlayer();
                                    if (self != null) curPos.set(self.getWorldLocation());
                                }
                            }
                            catch (Throwable ex)
                            {
                                log.warn("walker arrived/pos failed", ex);
                            }
                            finally { latch.countDown(); }
                        });
                        latch.await();

                        if (arrived.get())
                        {
                            log.info("walker: arrived at {}", wpLabel);
                            arrivedHere = true;
                            break;
                        }

                        WorldPoint cur = curPos.get();
                        long now = System.currentTimeMillis();
                        boolean moved = cur != null && lastSeen != null
                            && !cur.equals(lastSeen);
                        if (moved)
                        {
                            lastSeen = cur;
                            lastMoveAt = now;
                        }

                        long sinceClick = now - lastClickAt;
                        long sinceMove = now - lastMoveAt;
                        // Click if: never clicked yet, OR player has been
                        // still for 3+ seconds since the last click took
                        // effect. The engine handles re-targets cleanly,
                        // but spamming clicks every tick floods the UI.
                        boolean shouldClick = lastClickAt == 0L
                            || (sinceClick > 3000 && sinceMove > 3000);

                        if (shouldClick)
                        {
                            log.info("walker: click toward {} (player at {})",
                                wpLabel, cur);
                            java.util.concurrent.CountDownLatch clickLatch =
                                new java.util.concurrent.CountDownLatch(1);
                            clientThread.invokeLater(() -> {
                                try { w.tick(wp); }
                                catch (Throwable ex)
                                {
                                    log.warn("walker tick failed", ex);
                                }
                                finally { clickLatch.countDown(); }
                            });
                            clickLatch.await();
                            lastClickAt = now;
                        }

                        // Stuck timeout: 15 seconds with no movement and
                        // no arrival → give up on this waypoint.
                        if (lastClickAt != 0L && sinceMove > 15000)
                        {
                            log.warn("walker: stuck on {} for 15s — advancing to next",
                                wpLabel);
                            break;
                        }

                        // Poll cadence (NOT click cadence). 800-1200ms.
                        SequenceSleep.sleep(client, 800 + (int)(Math.random() * 400));
                    }

                    if (!arrivedHere)
                    {
                        log.info("walker: leaving {} without arrival", wpLabel);
                    }
                }
                log.info("walker: finished slice");
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                log.info("walker: interrupted");
            }
        }, "annotator-test-walk");
        t.setDaemon(true);
        walkerThread = t;
        t.start();
    }

    private void clearAnnotatorKeybindings()
    {
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .remove(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .remove(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        getActionMap().remove("annotator-commit");
        getActionMap().remove("annotator-cancel");
    }

    // ------------------------------------------------------------------
    // Combat tab
    // ------------------------------------------------------------------

    private JPanel buildCombatTab()
    {
        // Manual start/stop for the chicken combat loop. The loop assumes the
        // user has already walked the player to the chicken pen — this UI
        // only triggers the combat orchestrator. Status string is updated by
        // the loop itself; the timer below polls state + kill count.
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Combat"));
        JPanel row = new JPanel(new BorderLayout(4, 4));
        row.add(chickenStartBtn, BorderLayout.CENTER);
        row.add(chickenStopBtn, BorderLayout.EAST);
        p.add(row);
        p.add(chickenStatusLabel);
        p.add(chickenKillsLabel);
        return p;
    }

    // ------------------------------------------------------------------
    // Record tab — Recording controls + Marker + Recent events list
    // ------------------------------------------------------------------

    private JPanel buildRecordTab()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        // Recording controls
        JPanel controls = new JPanel();
        controls.setBorder(BorderFactory.createTitledBorder("Recording"));
        controls.add(recordBtn);
        p.add(controls);
        p.add(Box.createVerticalStrut(6));

        // Marker
        JPanel marker = new JPanel(new BorderLayout(4, 4));
        marker.setBorder(BorderFactory.createTitledBorder("Marker"));
        marker.add(markerField, BorderLayout.CENTER);
        marker.add(markerBtn, BorderLayout.EAST);
        p.add(marker);
        p.add(Box.createVerticalStrut(6));

        // Recent events
        JPanel recent = new JPanel(new BorderLayout());
        recent.setBorder(BorderFactory.createTitledBorder("Recent (last 50)"));
        recentList.setVisibleRowCount(15);
        recentScroll.setPreferredSize(new Dimension(220, 320));
        recentScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        recent.add(recentScroll, BorderLayout.CENTER);
        p.add(recent);

        return p;
    }

    // ------------------------------------------------------------------
    // Mining tab
    // ------------------------------------------------------------------

    private JPanel buildMiningTab()
    {
        // Manual Start / Stop, plus an "Add rock here" that captures the
        // local player's current world tile as a rock candidate. Same idiom
        // as the route's "Add current" button. The user is expected to
        // stand next to the rock they want, click Add, repeat for 2-3
        // rocks, then start.
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Mining"));
        JPanel row1 = new JPanel(new BorderLayout(4, 4));
        row1.add(miningStartBtn, BorderLayout.CENTER);
        row1.add(miningStopBtn, BorderLayout.EAST);
        p.add(row1);
        JPanel row2 = new JPanel(new BorderLayout(4, 4));
        row2.add(miningAddRockBtn, BorderLayout.CENTER);
        row2.add(miningClearRocksBtn, BorderLayout.EAST);
        p.add(row2);
        p.add(miningStatusLabel);
        p.add(miningOreCountLabel);
        p.add(miningRockCountLabel);
        return p;
    }

    // ------------------------------------------------------------------
    // Login tab
    // ------------------------------------------------------------------

    private JPanel buildLoginTab()
    {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Login"));

        // Header: "Saved characters:" + corner "+" button.
        addCornerBtn.setMargin(new Insets(0, 8, 0, 8));
        addCornerBtn.setToolTipText("Add credential");
        addCornerBtn.addActionListener(e -> onAddCredential());
        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.add(new JLabel("Saved characters:"), BorderLayout.CENTER);
        header.add(addCornerBtn, BorderLayout.EAST);

        // Credential rows go directly into a vertical box — no JScrollPane,
        // no scrollbar. With per-row trash/edit icons we don't expect a
        // long list.
        credListPanel.setLayout(new BoxLayout(credListPanel, BoxLayout.Y_AXIS));

        // Bottom: V1 login/stop row, V2 login + world row, status.
        JPanel v1Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        v1Row.add(loginBtn);
        v1Row.add(stopBtn);

        JPanel v2Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        v2Row.add(loginV2Btn);
        v2Row.add(new JLabel("World:"));
        v2Row.add(worldField);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        buttons.add(v1Row);
        buttons.add(v2Row);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(buttons, BorderLayout.NORTH);
        south.add(loginStatus, BorderLayout.CENTER);

        panel.add(header, BorderLayout.NORTH);
        panel.add(credListPanel, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        loginBtn.addActionListener(e -> onLogin());
        stopBtn.addActionListener(e -> onStopLogin());
        loginV2Btn.addActionListener(e -> onLoginV2());

        updateButtons();
        return panel;
    }

    private static final int CRED_ROW_HEIGHT = 26;
    private static final int CRED_ICON_SIZE = 14;
    // Stored in the credential slot for Jagex accounts — never typed into OSRS
    // (Jagex login bypasses the password form entirely). Non-empty so the
    // macOS Keychain security command accepts the write without error.
    static final String JAGEX_PW_SENTINEL = "jagex";

    /** Rebuild the credential rows from {@link #currentUsernames}. Each row is
     *  a clickable JPanel that selects the username; per-row buttons handle
     *  edit and delete. Must run on the EDT. */
    private void rebuildCredRows()
    {
        credListPanel.removeAll();
        if (currentUsernames.isEmpty())
        {
            JLabel empty = new JLabel("(no credentials saved)");
            empty.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            empty.setForeground(Color.GRAY);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            credListPanel.add(empty);
        }
        else
        {
            for (String username : currentUsernames)
            {
                credListPanel.add(buildCredRow(username));
            }
        }
        // Push everything to the top — without this glue, BoxLayout.Y_AXIS
        // stretches the last row to fill the tab.
        credListPanel.add(Box.createVerticalGlue());
        credListPanel.revalidate();
        credListPanel.repaint();
    }

    private JPanel buildCredRow(final String username)
    {
        final JPanel row = new JPanel(new BorderLayout(2, 0));
        row.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 2));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        // Cap height so BoxLayout.Y_AXIS doesn't stretch this row to fill
        // the rest of the tab.
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, CRED_ROW_HEIGHT));
        row.setPreferredSize(new Dimension(0, CRED_ROW_HEIGHT));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        boolean selected = username.equals(selectedUsername);
        row.setOpaque(true);
        row.setBackground(selected
            ? javax.swing.UIManager.getColor("List.selectionBackground")
            : credListPanel.getBackground());

        boolean isJagex = accountPrefs != null && accountPrefs.isJagex(username);
        JLabel nameLabel = new JLabel(isJagex ? "[J] " + username : username);
        nameLabel.setForeground(selected
            ? javax.swing.UIManager.getColor("List.selectionForeground")
            : credListPanel.getForeground());
        row.add(nameLabel, BorderLayout.CENTER);

        JButton editBtn = new JButton(new PencilIcon(CRED_ICON_SIZE));
        JButton delBtn  = new JButton(new TrashIcon(CRED_ICON_SIZE));
        for (JButton b : new JButton[]{editBtn, delBtn})
        {
            b.setMargin(new Insets(0, 4, 0, 4));
            b.setFocusable(false);
            b.setOpaque(false);
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        editBtn.setToolTipText("Edit credential");
        delBtn.setToolTipText("Delete credential");
        editBtn.addActionListener(e -> onEditCredential(username));
        delBtn.addActionListener(e -> onDeleteCredential(username));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(editBtn);
        actions.add(delBtn);
        row.add(actions, BorderLayout.EAST);

        // Click anywhere in the row (outside the action buttons) selects.
        MouseAdapter selectOnClick = new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e) { setSelectedUsername(username); }
        };
        row.addMouseListener(selectOnClick);
        nameLabel.addMouseListener(selectOnClick);

        return row;
    }

    /** Simple pencil drawn with Graphics2D — no PNG asset needed. */
    private static final class PencilIcon implements Icon
    {
        private final int size;
        PencilIcon(int size) { this.size = size; }
        @Override public int getIconWidth()  { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try
            {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c.getForeground());
                g2.setStroke(new BasicStroke(1.4f));
                int pad = Math.max(1, size / 7);
                int x0 = x + pad,         y0 = y + size - pad;
                int x1 = x + size - pad,  y1 = y + pad;
                // Pencil shaft (diagonal).
                g2.drawLine(x0, y0, x1, y1);
                // Tip — short perpendicular nick at the upper-right end.
                int nick = Math.max(2, size / 5);
                g2.drawLine(x1, y1, x1 - nick, y1 + 1);
                g2.drawLine(x1, y1, x1 - 1,    y1 + nick);
                // Eraser/cap — short perpendicular at the lower-left end.
                g2.drawLine(x0, y0, x0 + nick, y0 - 1);
                g2.drawLine(x0, y0, x0 + 1,    y0 - nick);
            }
            finally { g2.dispose(); }
        }
    }

    /** Simple trash can drawn with Graphics2D — no PNG asset needed. */
    private static final class TrashIcon implements Icon
    {
        private final int size;
        TrashIcon(int size) { this.size = size; }
        @Override public int getIconWidth()  { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try
            {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c.getForeground());
                g2.setStroke(new BasicStroke(1.2f));
                int pad     = Math.max(1, size / 8);
                int lidY    = y + pad + 1;
                int bodyTop = lidY + 2;
                int bodyBot = y + size - pad;
                int leftX   = x + pad + 1;
                int rightX  = x + size - pad - 1;
                // Lid handle (small arc at top center).
                int handleW = Math.max(3, size / 3);
                g2.drawLine(x + size/2 - handleW/2, lidY - 2,
                            x + size/2 + handleW/2, lidY - 2);
                // Lid (horizontal line spanning the full width).
                g2.drawLine(x + pad, lidY, x + size - pad, lidY);
                // Body sides.
                g2.drawLine(leftX,  bodyTop, leftX  + 1, bodyBot);
                g2.drawLine(rightX, bodyTop, rightX - 1, bodyBot);
                // Body bottom.
                g2.drawLine(leftX + 1, bodyBot, rightX - 1, bodyBot);
                // Inner vertical bars.
                int midX = x + size/2;
                g2.drawLine(midX, bodyTop + 2, midX, bodyBot - 2);
                int innerL = leftX + 2;
                int innerR = rightX - 2;
                g2.drawLine(innerL, bodyTop + 2, innerL, bodyBot - 2);
                g2.drawLine(innerR, bodyTop + 2, innerR, bodyBot - 2);
            }
            finally { g2.dispose(); }
        }
    }

    private void setSelectedUsername(String username)
    {
        if (java.util.Objects.equals(selectedUsername, username)) return;
        selectedUsername = username;
        persistLastSelected(username);
        rebuildCredRows();
        updateButtons();
        populateWorldField(username);
    }

    /** Fill the V2 world text field with the per-account last-used world,
     *  or leave it empty if no preference exists. EDT only. */
    private void populateWorldField(String username)
    {
        if (accountPrefs == null) return;
        if (username == null || username.isBlank()) { worldField.setText(""); return; }
        Integer w = accountPrefs.lastWorld(username);
        worldField.setText(w == null ? "" : w.toString());
    }

    /** Wire the debug overlay so the panel can clear/set its marked tile.
     *  The plugin creates the overlay and hands it in once both are ready. */
    public void setDebugOverlay(DebugOverlay overlay)
    {
        this.debugOverlay = overlay;
        // Reflect any pre-existing mark in the label.
        SwingUtilities.invokeLater(this::refreshMarkLabel);
    }

    /** Wire the tile-marker mouse listener for click-to-mark. */
    public void setTileMarker(TileMarker tm) { this.tileMarker = tm; }

    /** Wire the transport resolver for the "Mark object" annotator button. */
    public void setTransportResolver(TransportResolver tr) { this.transportResolver = tr; }

    /** Wire the route overlay. Task 6 will use this reference to push parsed
     *  routes via {@code routeOverlay.setRoute(...)} from the annotator buttons. */
    public void setRouteOverlay(RouteOverlay ro) { this.routeOverlay = ro; }

    /** Wire the annotator HUD overlay (constructed by the plugin). */
    public void setHudOverlay(AnnotatorHudOverlay h) { this.hudOverlay = h; }

    /** Wire the area selector (constructed by the plugin). */
    public void setAreaSelector(AreaSelector s) { this.areaSelector = s; }

    /** Wire the {@link LoginAssistant} so the Login button has somewhere to
     *  go. The plugin owns lifetime and constructs the assistant only when
     *  injection has succeeded. */
    public void setLoginAssistant(LoginAssistant la) { this.loginAssistant = la; }

    /** Wire the V2 login assistant. Independent from V1; both can be set. */
    public void setLoginAssistantV2(net.runelite.client.sequence.login.LoginAssistantV2 la)
    {
        this.loginAssistantV2 = la;
        SwingUtilities.invokeLater(this::updateButtons);
    }

    /** Wire the per-account preferences store (last-used world). */
    public void setAccountPrefs(net.runelite.client.sequence.login.AccountPrefs prefs)
    {
        this.accountPrefs = prefs;
        // Populate the world field if we already know who's selected.
        SwingUtilities.invokeLater(() -> populateWorldField(selectedUsername));
    }

    /** Wire the credential store used by the credential management buttons. */
    public void setCredentialStore(CredentialStore store)
    {
        this.credentialStore = store;
        refreshList();
    }

    private void onMarkTile()
    {
        // Two-step UX: button arms the trap; the very next left-click on the
        // canvas captures the tile and the click is consumed (no walk / no
        // attack). This way the user can hover a tile, click to mark, and
        // know they marked exactly the tile they clicked.
        if (tileMarker == null) { markedLabel.setText("marker unavailable"); return; }
        markedLabel.setText("click a tile in the world to mark…");
        tileMarker.arm(wp -> {
            if (wp == null)
            {
                markedLabel.setText("clicked off-tile — try again");
                return;
            }
            if (debugOverlay != null) debugOverlay.setMarked(wp);
            refreshMarkLabel();
        });
    }

    private void onWalkToMark()
    {
        if (dispatcher == null) { markedLabel.setText("dispatcher unavailable"); return; }
        WorldPoint wp = debugOverlay == null ? null : debugOverlay.getMarked();
        if (wp == null) { markedLabel.setText("nothing marked"); return; }
        markedLabel.setText("walking → " + wp.getX() + "," + wp.getY()
            + " (p=" + wp.getPlane() + ")");
        // Walk the single marked tile via HumanizedInputDispatcher directly.
        Thread t = new Thread(() -> {
            net.runelite.client.sequence.internal.ActionRequest req =
                net.runelite.client.sequence.internal.ActionRequest.builder()
                    .kind(net.runelite.client.sequence.internal.ActionRequest.Kind.WALK)
                    .channel(net.runelite.client.sequence.internal.ActionRequest.Channel.MOUSE)
                    .tile(wp)
                    .build();
            // TODO(Task 10): single ActionRequest.WALK only walks within minimap
            // click radius. Long hops will silently stop short until the new
            // WaypointEditor's Walk path / Walk to selected absorbs this button.
            dispatcher.dispatch(req);
            String err = dispatcher.lastErrorMessage();
            SwingUtilities.invokeLater(() -> {
                if (err != null) markedLabel.setText("walk failed: " + err);
                else markedLabel.setText("walked → " + wp.getX() + "," + wp.getY());
            });
        }, "walk-to-mark-status");
        t.setDaemon(true);
        t.start();
    }

    private void onClearMark()
    {
        if (debugOverlay != null) debugOverlay.setMarked(null);
        refreshMarkLabel();
    }

    private void refreshMarkLabel()
    {
        WorldPoint wp = debugOverlay == null ? null : debugOverlay.getMarked();
        markedLabel.setText(wp == null
            ? "Marked: (none)"
            : "Marked: " + wp.getX() + "," + wp.getY() + " p=" + wp.getPlane());
    }

    // ------------------------------------------------------------------
    // V2 Inspect tab — dump buttons + Plan A→B + overlay clear stub.
    // Per spec inspection-tooling section: writes JSON under
    // <RUNELITE_DIR>/recorder/inspect/. Each button spawns a worker
    // thread (Swing EDT must not block on file IO).
    // ------------------------------------------------------------------

    private JPanel buildV2InspectTab()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("V2 Inspection"));

        p.add(v2DumpRegionBtn);
        p.add(v2DumpNearbyBtn);
        p.add(v2DumpTransportsBtn);
        p.add(v2DumpEntitiesBtn);

        v2PlanFromField.setToolTipText("from x,y,plane (e.g. 3208,3213,0)");
        v2PlanToField.setToolTipText("to x,y,plane (e.g. 3236,3294,0)");
        JPanel planRow = new JPanel(new BorderLayout(4, 4));
        planRow.add(new JLabel("From:"), BorderLayout.WEST);
        planRow.add(v2PlanFromField, BorderLayout.CENTER);
        p.add(planRow);
        JPanel planRow2 = new JPanel(new BorderLayout(4, 4));
        planRow2.add(new JLabel("To:"), BorderLayout.WEST);
        planRow2.add(v2PlanToField, BorderLayout.CENTER);
        p.add(planRow2);

        // Preset row: drop-down + Apply. Selecting + clicking Apply fills
        // From/To. Keeps the readiness UI flat — same fields drive Plan,
        // Readiness, Corridor.
        JPanel presetRow = new JPanel(new BorderLayout(4, 4));
        presetRow.add(new JLabel("Preset:"), BorderLayout.WEST);
        presetRow.add(v2PresetCombo, BorderLayout.CENTER);
        presetRow.add(v2PresetApplyBtn, BorderLayout.EAST);
        p.add(presetRow);

        p.add(v2PlanAToBBtn);

        p.add(Box.createVerticalStrut(8));
        p.add(v2ClearOverlayRouteBtn);

        v2InspectStatusLabel.putClientProperty("html.disable", null);
        v2InspectStatusLabel.setText("<html>Last dump: (none)</html>");
        p.add(v2InspectStatusLabel);
        return p;
    }

    private void onV2DumpRegion()
    {
        if (inspectionDumper == null || client == null) return;
        if (clientThread == null) return;
        clientThread.invokeLater(() ->
        {
            net.runelite.api.Player self = client.getLocalPlayer();
            if (self == null || self.getWorldLocation() == null)
            {
                SwingUtilities.invokeLater(() ->
                    v2InspectStatusLabel.setText("Dump region: not logged in"));
                return;
            }
            WorldPoint loc = self.getWorldLocation();
            int regionId = net.runelite.client.plugins.recorder.worldmap.RegionIds
                .regionIdFor(loc.getX(), loc.getY());
            runDump(() -> inspectionDumper.dumpRegion(regionId),
                "region " + regionId);
        });
    }

    private void onV2DumpNearby()
    {
        if (inspectionDumper == null || client == null || clientThread == null) return;
        clientThread.invokeLater(() ->
        {
            net.runelite.api.Player self = client.getLocalPlayer();
            if (self == null || self.getWorldLocation() == null)
            {
                SwingUtilities.invokeLater(() ->
                    v2InspectStatusLabel.setText("Dump nearby: not logged in"));
                return;
            }
            WorldPoint loc = self.getWorldLocation();
            int regionId = net.runelite.client.plugins.recorder.worldmap.RegionIds
                .regionIdFor(loc.getX(), loc.getY());
            runDump(() -> inspectionDumper.dumpNearbyRegions(regionId),
                "nearby " + regionId);
        });
    }

    private void onV2DumpTransports()
    {
        if (inspectionDumper == null) return;
        runDump(inspectionDumper::dumpTransportGraph, "transport graph");
    }

    private void onV2DumpEntities()
    {
        if (inspectionDumper == null) return;
        runDump(inspectionDumper::dumpEntities, "entities");
    }

    private void onV2PlanAToB()
    {
        if (inspectionDumper == null) return;
        WorldPoint from = parseWorldPoint(v2PlanFromField.getText());
        WorldPoint to = parseWorldPoint(v2PlanToField.getText());
        if (from == null || to == null)
        {
            v2InspectStatusLabel.setText(
                "Plan A→B: enter \"x,y,plane\" in From and To fields");
            return;
        }
        Thread t = new Thread(() ->
        {
            String msg;
            try
            {
                net.runelite.client.plugins.recorder.worldmap.InspectionDumper.PlanOutcome o
                    = inspectionDumper.planAToB(from, to);
                if (!o.route().isEmpty())
                {
                    net.runelite.client.plugins.recorder.worldmap.WorldMapMinimapOverlay
                        .publishActiveRoute(o.route());
                }
                msg = "Plan A→B " + o.summary() + " → " + o.file().getAbsolutePath();
            }
            catch (RuntimeException ex)
            {
                msg = "Plan A→B failed: " + ex.getMessage();
            }
            final String finalMsg = msg;
            SwingUtilities.invokeLater(() ->
                v2InspectStatusLabel.setText("<html>" + finalMsg + "</html>"));
        }, "v2-inspect-plan");
        t.setDaemon(true);
        t.start();
    }

    private void onV2ClearOverlayRoute()
    {
        if (v2OverlayClearAction != null)
        {
            v2OverlayClearAction.run();
            v2InspectStatusLabel.setText("<html>Cleared overlay route.</html>");
        }
        else
        {
            v2InspectStatusLabel.setText(
                "<html>Overlay not registered yet (Phase 3.3).</html>");
        }
    }

    private void onV2ApplyPreset()
    {
        RoutePreset preset = (RoutePreset) v2PresetCombo.getSelectedItem();
        if (preset == null) return;
        v2PlanFromField.setText(preset.from().getX() + "," + preset.from().getY()
            + "," + preset.from().getPlane());
        v2PlanToField.setText(preset.to().getX() + "," + preset.to().getY()
            + "," + preset.to().getPlane());
        v2InspectStatusLabel.setText("<html>Preset \"" + preset.label() + "\" applied.</html>");
    }

    /** Run a dump on a worker thread; update the status label on the EDT
     *  with the resulting file path or any thrown exception. {@code label}
     *  is the user-facing dump description. */
    private void runDump(java.util.function.Supplier<java.io.File> task, String label)
    {
        Thread t = new Thread(() ->
        {
            String msg;
            try
            {
                java.io.File out = task.get();
                msg = "Dumped " + label + " → " + out.getAbsolutePath();
            }
            catch (RuntimeException ex)
            {
                msg = "Dump " + label + " failed: " + ex.getMessage();
            }
            final String finalMsg = msg;
            SwingUtilities.invokeLater(() ->
                v2InspectStatusLabel.setText("<html>" + finalMsg + "</html>"));
        }, "v2-inspect-dump");
        t.setDaemon(true);
        t.start();
    }


    /** Wire the chicken combat loop. Called by the plugin during startUp once
     *  the dispatcher is constructed. The loop's status callback updates
     *  {@link #chickenStatusLabel} via the EDT. */
    public void setChickenLoop(ChickenCombatLoop loop) { this.chickenLoop = loop; }

    private void onChickenStart()
    {
        if (chickenLoop == null)
        {
            chickenStatusLabel.setText("Combat: unavailable");
            return;
        }
        chickenLoop.start();
        chickenStatusLabel.setText("Combat: starting");
    }

    private void onChickenStop()
    {
        if (chickenLoop == null) return;
        chickenLoop.stop();
        chickenStatusLabel.setText("Combat: stopping");
    }

    // ------------------------------------------------------------------
    // Chicken farm tab — hand-coded Lumbridge bank ↔ pen script.
    // ------------------------------------------------------------------

    private JPanel buildChickenFarmTab()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        // V1 — hand-coded LumbridgeBankPenScript.
        JPanel v1 = new JPanel();
        v1.setLayout(new BoxLayout(v1, BoxLayout.Y_AXIS));
        v1.setBorder(BorderFactory.createTitledBorder(
            "V1 — hand-coded (LumbridgeBankPenScript)"));
        JPanel v1Row = new JPanel(new BorderLayout(4, 4));
        v1Row.add(lumbyStartBtn, BorderLayout.CENTER);
        v1Row.add(lumbyStopBtn, BorderLayout.EAST);
        v1.add(v1Row);
        v1.add(lumbyStatusLabel);
        v1.add(lumbyKillsLabel);
        p.add(v1);

        // V2 — same route via UniversalWalker (walker framework).
        JPanel v2 = new JPanel();
        v2.setLayout(new BoxLayout(v2, BoxLayout.Y_AXIS));
        v2.setBorder(BorderFactory.createTitledBorder(
            "V2 — walker framework (ChickenFarmV2Script)"));
        JPanel v2Row = new JPanel(new BorderLayout(4, 4));
        v2Row.add(v2StartBtn, BorderLayout.CENTER);
        v2Row.add(v2StopBtn, BorderLayout.EAST);
        v2.add(v2Row);
        v2.add(v2StatusLabel);
        v2.add(v2KillsLabel);
        p.add(v2);

        // V3 — recorded trails via TrailWalker.
        JPanel v3Box = new JPanel();
        v3Box.setLayout(new BoxLayout(v3Box, BoxLayout.Y_AXIS));
        v3Box.setBorder(BorderFactory.createTitledBorder("V3 (recorded trails)"));
        v3Box.setBackground(ColorScheme.DARK_GRAY_COLOR);
        v3Box.add(v3StatusLabel);
        v3Box.add(v3KillsLabel);
        JPanel v3Row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        v3Row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        v3Row.add(v3StartBtn);
        v3Row.add(v3StopBtn);
        v3Box.add(v3Row);
        v3StartBtn.addActionListener(e -> { if (chickenFarmV3 != null) chickenFarmV3.start(); });
        v3StopBtn.addActionListener(e -> { if (chickenFarmV3 != null) chickenFarmV3.stop(); });

        // Debug sub-section — walk-test toggles + force-phase buttons.
        // Lets you exercise just the OUTBOUND or RETURN walk without
        // running combat / banking, useful for pinning down navigation
        // regressions.
        JPanel debugBox = new JPanel();
        debugBox.setLayout(new BoxLayout(debugBox, BoxLayout.Y_AXIS));
        debugBox.setBorder(BorderFactory.createTitledBorder("Debug"));
        debugBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
        for (javax.swing.JCheckBox cb : new javax.swing.JCheckBox[]{
            v3DebugStopAfterArrivalCb, v3DebugSkipCombatCb, v3DebugSkipBankingCb})
        {
            cb.setBackground(ColorScheme.DARK_GRAY_COLOR);
            cb.setForeground(java.awt.Color.LIGHT_GRAY);
            debugBox.add(cb);
        }
        v3DebugStopAfterArrivalCb.addActionListener(e -> {
            if (chickenFarmV3 != null)
                chickenFarmV3.setStopAfterArrival(v3DebugStopAfterArrivalCb.isSelected());
        });
        v3DebugSkipCombatCb.addActionListener(e -> {
            if (chickenFarmV3 != null)
                chickenFarmV3.setSkipCombat(v3DebugSkipCombatCb.isSelected());
        });
        v3DebugSkipBankingCb.addActionListener(e -> {
            if (chickenFarmV3 != null)
                chickenFarmV3.setSkipBanking(v3DebugSkipBankingCb.isSelected());
        });
        JPanel debugBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        debugBtnRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        debugBtnRow.add(v3DebugWalkToPenBtn);
        debugBtnRow.add(v3DebugWalkToBankBtn);
        debugBox.add(debugBtnRow);
        v3DebugWalkToPenBtn.addActionListener(e -> {
            if (chickenFarmV3 != null) chickenFarmV3.startForcedOutbound();
        });
        v3DebugWalkToBankBtn.addActionListener(e -> {
            if (chickenFarmV3 != null) chickenFarmV3.startForcedReturn();
        });
        v3Box.add(debugBox);

        // Training-mode sub-section.
        JPanel trainBox = new JPanel();
        trainBox.setLayout(new BoxLayout(trainBox, BoxLayout.Y_AXIS));
        trainBox.setBorder(BorderFactory.createTitledBorder("Training mode"));
        trainBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
        trainBox.add(buildTrainSkillRow(trainAttackCb,   trainAttackLvlField));
        trainBox.add(buildTrainSkillRow(trainStrengthCb, trainStrengthLvlField));
        trainBox.add(buildTrainSkillRow(trainDefenceCb,  trainDefenceLvlField));
        JPanel retRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        retRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        retRow.add(new JLabel("Auto-retaliate:"));
        retRow.add(autoRetaliateCombo);
        trainBox.add(retRow);
        JPanel trainBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        trainBtnRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        trainBtnRow.add(v3StartTrainingBtn);
        trainBox.add(trainBtnRow);
        v3StartTrainingBtn.addActionListener(e -> startV3WithTraining());
        v3Box.add(trainBox);

        p.add(v3Box);

        return p;
    }

    private JComponent buildTrainSkillRow(javax.swing.JCheckBox cb, JTextField lvl)
    {
        cb.setBackground(ColorScheme.DARK_GRAY_COLOR);
        cb.setForeground(java.awt.Color.LIGHT_GRAY);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row.add(cb);
        row.add(new JLabel("→ level"));
        row.add(lvl);
        return row;
    }

    private void startV3WithTraining()
    {
        if (chickenFarmV3 == null) return;
        java.util.List<net.runelite.client.plugins.recorder.combat.SkillTarget> targets =
            new java.util.ArrayList<>();
        if (trainAttackCb.isSelected())
            targets.add(parseSkillTarget(net.runelite.api.Skill.ATTACK,   trainAttackLvlField));
        if (trainStrengthCb.isSelected())
            targets.add(parseSkillTarget(net.runelite.api.Skill.STRENGTH, trainStrengthLvlField));
        if (trainDefenceCb.isSelected())
            targets.add(parseSkillTarget(net.runelite.api.Skill.DEFENCE,  trainDefenceLvlField));
        targets.removeIf(java.util.Objects::isNull);
        if (targets.isEmpty())
        {
            v3StatusLabel.setText("V3: select at least one skill, level range 2..99");
            return;
        }
        String retaliate = (String) autoRetaliateCombo.getSelectedItem();
        boolean retLeave = "Leave alone".equals(retaliate);
        boolean retOn    = "ON".equals(retaliate);
        net.runelite.client.plugins.recorder.combat.TrainingPlan plan =
            retLeave
            ? new net.runelite.client.plugins.recorder.combat.TrainingPlan(
                targets, false, true, 1, 2,
                net.runelite.client.plugins.recorder.combat.TrainingPlan.DEFAULT_XP_HOVER_MIN_MS,
                net.runelite.client.plugins.recorder.combat.TrainingPlan.DEFAULT_XP_HOVER_MAX_MS)
            : net.runelite.client.plugins.recorder.combat.TrainingPlan.basic(targets, retOn);
        chickenFarmV3.setTrainingPlan(plan);
        persistTrainingPanelState();
        chickenFarmV3.start();
    }

    /** Capture the current training-tab UI selection and write it under
     *  the active account's hash, so the next session restores the same
     *  checkboxes / level fields / retaliate dropdown. */
    private void persistTrainingPanelState()
    {
        net.runelite.client.plugins.recorder.combat.TrainingPlanStore.Settings s =
            new net.runelite.client.plugins.recorder.combat.TrainingPlanStore.Settings();
        s.attackEnabled   = trainAttackCb.isSelected();
        s.strengthEnabled = trainStrengthCb.isSelected();
        s.defenceEnabled  = trainDefenceCb.isSelected();
        s.attackLevel     = parseLevelOrDefault(trainAttackLvlField,   s.attackLevel);
        s.strengthLevel   = parseLevelOrDefault(trainStrengthLvlField, s.strengthLevel);
        s.defenceLevel    = parseLevelOrDefault(trainDefenceLvlField,  s.defenceLevel);
        Object sel = autoRetaliateCombo.getSelectedItem();
        s.autoRetaliate = sel == null ? "Leave alone" : sel.toString();
        try { trainingPlanStore.save(client, s); }
        catch (RuntimeException ex)
        {
            // Don't block training-start on persistence failure — just log.
            org.slf4j.LoggerFactory.getLogger(RecorderPanel.class)
                .warn("training-plans: persist failed", ex);
        }
    }

    private static int parseLevelOrDefault(JTextField f, int fallback)
    {
        try { return Integer.parseInt(f.getText().trim()); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private net.runelite.client.plugins.recorder.combat.SkillTarget parseSkillTarget(
        net.runelite.api.Skill skill, JTextField lvlField)
    {
        try
        {
            int lvl = Integer.parseInt(lvlField.getText().trim());
            if (lvl < 2 || lvl > 99) return null;
            return new net.runelite.client.plugins.recorder.combat.SkillTarget(skill, lvl);
        }
        catch (NumberFormatException ex) { return null; }
    }

    /** Wire the script. The plugin constructs the script in startUp and
     *  hands it here; the panel only owns the UI surface. */
    public void setLumbyScript(LumbridgeBankPenScript script)
    {
        this.lumbyScript = script;
    }

    /** V2 wiring — same shape as {@link #setLumbyScript}. */
    public void setChickenFarmV2(ChickenFarmV2Script script)
    {
        this.chickenFarmV2 = script;
    }

    /** V3 wiring — trail-network version. */
    public void setChickenFarmV3(net.runelite.client.plugins.recorder.scripts.ChickenFarmV3Script s)
    {
        this.chickenFarmV3 = s;
        SwingUtilities.invokeLater(() -> {
            restoreTrainingPanelState();
            updateV3Controls();
        });
    }

    /** Restore the saved training-tab UI selection for the active
     *  account, if any. Called when the panel is wired to the V3 script
     *  AND on each refresh tick when we detect a username change (login,
     *  account switch). The latter matters because the plugin wires the
     *  panel at startup — long before the user logs in — so the initial
     *  restore runs against {@code client.getUsername() == ""} and reads
     *  the {@code "default"} key, which almost never matches what the
     *  user actually wants. */
    private void restoreTrainingPanelState()
    {
        String currentUsername = "";
        try
        {
            if (client != null)
            {
                String u = client.getUsername();
                if (u != null) currentUsername = u.trim();
            }
        }
        catch (Throwable ignored) { /* not logged in yet */ }
        if (currentUsername.equals(lastRestoredUsername)) return;
        net.runelite.client.plugins.recorder.combat.TrainingPlanStore.Settings s =
            trainingPlanStore.load(client);
        lastRestoredUsername = currentUsername;
        if (s == null) return;
        trainAttackCb.setSelected(s.attackEnabled);
        trainStrengthCb.setSelected(s.strengthEnabled);
        trainDefenceCb.setSelected(s.defenceEnabled);
        trainAttackLvlField.setText(Integer.toString(s.attackLevel));
        trainStrengthLvlField.setText(Integer.toString(s.strengthLevel));
        trainDefenceLvlField.setText(Integer.toString(s.defenceLevel));
        autoRetaliateCombo.setSelectedItem(
            s.autoRetaliate == null ? "Leave alone" : s.autoRetaliate);
    }

    private void updateV3Controls()
    {
        boolean ready = chickenFarmV3 != null;
        v3StartBtn.setEnabled(ready);
        v3StopBtn.setEnabled(ready);
        if (ready)
        {
            v3StatusLabel.setText("V3: " + chickenFarmV3.status());
            v3KillsLabel.setText("Kills: " + chickenFarmV3.killCount());
        }
    }

    public void setTrailRecorder(TrailRecorder rec)
    {
        this.trailRecorder = rec;
        SwingUtilities.invokeLater(this::updateTrailButtons);
    }

    public void setTrailRegistry(TrailRegistry reg)
    {
        this.trailRegistry = reg;
        SwingUtilities.invokeLater(() -> {
            refreshTrailNames();
            updateTrailButtons();
        });
    }

    /** Repopulate the trail-name dropdown from the registry, preserving
     *  whatever the user had typed/selected. Called after registry load
     *  + after each save so newly-recorded trails appear immediately. */
    private void refreshTrailNames()
    {
        if (trailRegistry == null) return;
        trailRegistry.load();   // pick up any disk changes
        Object selected = trailNameCombo.getEditor().getItem();
        trailNameCombo.removeAllItems();
        trailRegistry.all().stream()
            .map(net.runelite.client.plugins.recorder.trail.Trail::name)
            .sorted()
            .forEach(trailNameCombo::addItem);
        // Restore the user's pending text/selection so re-render doesn't
        // wipe a half-typed new name.
        if (selected != null) trailNameCombo.getEditor().setItem(selected);
    }

    private void updateTrailButtons()
    {
        boolean ready = trailRecorder != null && trailRegistry != null;
        boolean recording = ready && trailRecorder.isRecording();
        trailRecordBtn.setEnabled(ready && !recording);
        trailStopSaveBtn.setEnabled(ready && recording);
        trailWalkToBtn.setEnabled(ready && !recording);
        trailShowBtn.setEnabled(ready && !recording);
        trailNameCombo.setEnabled(ready && !recording);
        trailStatusLabel.setText("Trails: " + (recording
            ? "recording \"" + trailRecorder.currentName() + "\""
            : "idle"));
    }

    private JComponent buildTrailsSection()
    {
        JPanel out = new JPanel();
        out.setLayout(new BoxLayout(out, BoxLayout.Y_AXIS));
        out.setBackground(ColorScheme.DARK_GRAY_COLOR);
        out.setBorder(BorderFactory.createTitledBorder("Trails"));
        // Combo is editable so users can either pick an existing trail
        // (to display or stop-and-save into) or type a brand-new name
        // for a fresh recording.
        trailNameCombo.setEditable(true);
        trailNameCombo.setPrototypeDisplayValue("xxxxxxxxxxxxxxxxxxxxxx");
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        row1.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row1.add(new JLabel("Name:"));
        row1.add(trailNameCombo);
        row1.add(trailShowBtn);
        out.add(row1);
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        row2.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row2.add(trailRecordBtn);
        row2.add(trailStopSaveBtn);
        out.add(row2);
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        row3.setBackground(ColorScheme.DARK_GRAY_COLOR);
        row3.add(new JLabel("Walk to (x,y,p):"));
        row3.add(trailWalkToField);
        row3.add(trailWalkToBtn);
        out.add(row3);
        out.add(trailStatusLabel);
        trailRecordBtn.addActionListener(e -> startTrailRecord());
        trailStopSaveBtn.addActionListener(e -> stopTrailAndSave());
        trailShowBtn.addActionListener(e -> showSelectedTrail());
        trailWalkToBtn.addActionListener(e -> walkToTarget());
        updateTrailButtons();
        return out;
    }

    /** Pull the currently-typed/selected name from the combo. Returns
     *  empty string when nothing's there — callers handle as "name
     *  required." */
    private String currentTrailName()
    {
        Object sel = trailNameCombo.getEditor().getItem();
        return sel == null ? "" : sel.toString().trim();
    }

    private void startTrailRecord()
    {
        if (trailRecorder == null) return;
        String name = currentTrailName();
        if (name.isEmpty())
        {
            trailStatusLabel.setText("Trails: name required");
            return;
        }
        try { trailRecorder.start(name); }
        catch (Throwable t)
        {
            trailStatusLabel.setText("Trails: " + t.getMessage());
            return;
        }
        updateTrailButtons();
    }

    private void stopTrailAndSave()
    {
        if (trailRecorder == null || trailRegistry == null) return;
        try
        {
            var trail = trailRecorder.stopAndBuild();
            trailRegistry.save(trail);
            trailStatusLabel.setText("Trails: saved \"" + trail.name() + "\" ("
                + trail.events().size() + " events)");
            refreshTrailNames();
            trailNameCombo.getEditor().setItem(trail.name());
        }
        catch (Throwable t)
        {
            trailStatusLabel.setText("Trails: save failed: " + t.getMessage());
            log.warn("trail save failed", t);
        }
        updateTrailButtons();
    }

    /** Look up the trail named in the combo and publish it to the
     *  {@link net.runelite.client.plugins.recorder.trail.TrailOverlay}
     *  so the user can eyeball the recorded route on the world map +
     *  minimap. Passing an empty / unknown name clears the overlay. */
    private void showSelectedTrail()
    {
        if (trailRegistry == null) return;
        String name = currentTrailName();
        if (name.isEmpty())
        {
            net.runelite.client.plugins.recorder.trail.TrailOverlay
                .publishActiveTrail(null);
            trailStatusLabel.setText("Trails: overlay cleared");
            return;
        }
        net.runelite.client.plugins.recorder.trail.Trail t =
            trailRegistry.byName(name);
        if (t == null)
        {
            // Maybe it was added on disk after the last load.
            trailRegistry.load();
            t = trailRegistry.byName(name);
        }
        if (t == null)
        {
            trailStatusLabel.setText("Trails: \"" + name + "\" not found "
                + "(have: " + trailRegistry.all().size() + " total)");
            return;
        }
        net.runelite.client.plugins.recorder.trail.TrailOverlay
            .publishActiveTrail(
                net.runelite.client.plugins.recorder.trail.TrailPath.fromTrail(t));
        trailStatusLabel.setText("Trails: showing \"" + t.name()
            + "\" (" + t.events().size() + " events)");
    }

    private void walkToTarget()
    {
        if (trailRegistry == null) return;
        String txt = trailWalkToField.getText().trim();
        WorldPoint target = parseWorldPoint(txt);
        if (target == null)
        {
            trailStatusLabel.setText("Trails: bad target — use \"x,y,p\"");
            return;
        }
        trailRegistry.load();   // pick up any trail saved this session
        TrailGraph graph = TrailGraph.build(trailRegistry.all());
        TrailPlanner planner = new TrailPlanner(graph);
        WorldPoint here;
        try { here = onClientThreadGetWorldPoint(); }
        catch (Throwable t) { trailStatusLabel.setText("Trails: " + t); return; }
        if (here == null) { trailStatusLabel.setText("Trails: player not loaded"); return; }
        var pathOpt = planner.plan(here, target);
        if (pathOpt.isEmpty())
        {
            trailStatusLabel.setText("Trails: no trail covers this route — record one");
            return;
        }
        TrailPath path = pathOpt.get();
        trailStatusLabel.setText("Trails: walking " + path.size() + " legs");
        // Off-thread driver so the EDT stays responsive.
        Thread t = new Thread(() -> driveTrailWalker(path), "trail-walker-test");
        t.setDaemon(true);
        trailWalkerThread = t;
        t.start();
    }

    private void driveTrailWalker(TrailPath path)
    {
        TrailWalker w = new TrailWalker(client, clientThread, dispatcher);
        try
        {
            while (!Thread.currentThread().isInterrupted())
            {
                TrailWalker.Status s = w.tick(path);
                final String label = "Trails: walking — leg " + (w.currentLegIndex() + 1)
                    + "/" + path.size() + " — " + s;
                SwingUtilities.invokeLater(() -> trailStatusLabel.setText(label));
                if (s == TrailWalker.Status.ARRIVED || s == TrailWalker.Status.STUCK
                    || s == TrailWalker.Status.ERROR)
                {
                    final String done = "Trails: " + s;
                    SwingUtilities.invokeLater(() -> trailStatusLabel.setText(done));
                    return;
                }
                SequenceSleep.sleep(client, 600);
            }
        }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @Nullable
    private static WorldPoint parseWorldPoint(String txt)
    {
        String[] parts = txt.split(",");
        if (parts.length != 3) return null;
        try
        {
            return new WorldPoint(
                Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
        }
        catch (NumberFormatException ex) { return null; }
    }

    private WorldPoint onClientThreadGetWorldPoint() throws InterruptedException
    {
        java.util.concurrent.atomic.AtomicReference<WorldPoint> ref = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try
            {
                var local = client.getLocalPlayer();
                ref.set(local == null ? null : local.getWorldLocation());
            }
            finally { latch.countDown(); }
        });
        latch.await();
        return ref.get();
    }

    private void onLumbyStart()
    {
        if (lumbyScript == null)
        {
            lumbyStatusLabel.setText("Chicken farm: unavailable");
            return;
        }
        lumbyScript.start();
        lumbyStatusLabel.setText("Chicken farm: starting");
    }

    private void onLumbyStop()
    {
        if (lumbyScript == null) return;
        lumbyScript.stop();
        lumbyStatusLabel.setText("Chicken farm: stopping");
    }

    private void onV2Start()
    {
        if (chickenFarmV2 == null)
        {
            v2StatusLabel.setText("V2: unavailable");
            return;
        }
        chickenFarmV2.start();
        v2StatusLabel.setText("V2: starting");
    }

    private void onV2Stop()
    {
        if (chickenFarmV2 == null) return;
        chickenFarmV2.stop();
        v2StatusLabel.setText("V2: stopping");
    }

    /** Mirror script state into the labels. Polled by refreshTimer. */
    private void refreshLumby()
    {
        if (lumbyScript == null)
        {
            lumbyStatusLabel.setText("Chicken farm: unavailable");
            lumbyKillsLabel.setText("Kills: 0");
        }
        else
        {
            lumbyStatusLabel.setText("V1: " + lumbyScript.state()
                + " — " + lumbyScript.status());
            lumbyKillsLabel.setText("Kills: " + lumbyScript.killCount());
        }
        if (chickenFarmV2 == null)
        {
            v2StatusLabel.setText("V2: unavailable");
            v2KillsLabel.setText("Kills: 0");
        }
        else
        {
            v2StatusLabel.setText("V2: " + chickenFarmV2.state()
                + " — " + chickenFarmV2.status());
            v2KillsLabel.setText("Kills: " + chickenFarmV2.killCount());
        }
        updateV3Controls();
    }

    // ────────────────────────────────────────────────────────────────
    // Cooking tab
    // ────────────────────────────────────────────────────────────────

    private JPanel buildCookingTab()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Cooking"));

        for (CookingLocation l : CookingLocations.all()) cookLocationBox.addItem(l);
        for (CookingFood.Entry e : CookingFood.all())     cookFoodBox.addItem(e);

        // BoxLayout.Y_AXIS stretches every child to fill leftover height
        // unless the child has a bounded maximum. JComboBox doesn't bound
        // itself, so the dropdowns balloon to ~33% of the tab. Pin each
        // row's max height to its preferred height with unbounded width.
        JPanel locationRow = labelledRow("Location:", cookLocationBox);
        JPanel foodRow     = labelledRow("Food:",     cookFoodBox);
        // Per-version max-duration rows: hr + min text fields. 0/0 = no
        // cap. Empty body labels keep "h" / "m" inline with the fields.
        JPanel v2DurationFields = new JPanel(new java.awt.FlowLayout(
            java.awt.FlowLayout.LEFT, 4, 0));
        v2DurationFields.add(cookV2MaxHoursField);
        v2DurationFields.add(new JLabel("h"));
        v2DurationFields.add(cookV2MaxMinutesField);
        v2DurationFields.add(new JLabel("m"));
        JPanel v2DurationRow = labelledRow("V2 max time:", v2DurationFields);
        JPanel v3DurationFields = new JPanel(new java.awt.FlowLayout(
            java.awt.FlowLayout.LEFT, 4, 0));
        v3DurationFields.add(cookV3MaxHoursField);
        v3DurationFields.add(new JLabel("h"));
        v3DurationFields.add(cookV3MaxMinutesField);
        v3DurationFields.add(new JLabel("m"));
        JPanel v3DurationRow = labelledRow("V3 max time:", v3DurationFields);
        // Two start buttons (V2 + V3) share one Stop button. The Stop
        // handler calls stop() on whichever is currently running.
        JPanel startsRow = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
        startsRow.add(cookStartV2Btn);
        startsRow.add(cookStartV3Btn);
        capHeight(startsRow);
        JPanel buttons     = new JPanel(new BorderLayout(4, 4));
        buttons.add(startsRow, BorderLayout.CENTER);
        buttons.add(cookStopBtn, BorderLayout.EAST);
        capHeight(buttons);

        p.add(locationRow);
        p.add(foodRow);
        p.add(v2DurationRow);
        p.add(v3DurationRow);
        p.add(buttons);
        p.add(cookStatusLabel);
        p.add(cookCountsLabel);
        // Glue absorbs the remaining vertical space so the rows above
        // don't get pushed apart.
        p.add(Box.createVerticalGlue());
        return p;
    }

    /** Build a "Label: <field>" row whose max height is pinned to its
     *  preferred height — needed inside Y_AXIS BoxLayouts so JComboBox
     *  doesn't stretch to fill available vertical space. */
    private static JPanel labelledRow(String labelText, JComponent field)
    {
        JPanel row = new JPanel(new BorderLayout(4, 4));
        row.add(new JLabel(labelText), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        capHeight(row);
        return row;
    }

    /** Pin a component's maximum height to its preferred height — fixes
     *  the BoxLayout-stretching behavior for any non-button child. */
    private static void capHeight(JComponent c)
    {
        Dimension pref = c.getPreferredSize();
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));
    }

    /** Tab scroll wrapper. Keeps default horizontal-scroll policy
     *  (AS_NEEDED) — some tabs have wide overlay tables that legitimately
     *  need horizontal scroll. Per-tab content that should wrap instead
     *  must use HTML width hints on its labels, not blanket-disable the
     *  scrollbar here. */
    private static JScrollPane tabScroll(JComponent inner)
    {
        JScrollPane sp = new JScrollPane(inner);
        sp.setBorder(null);
        return sp;
    }

    private JPanel buildCooksQuestTab()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Cook's Assistant Quest"));

        JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
        buttons.add(questStartBtn);
        buttons.add(questStopBtn);
        capHeight(buttons);

        JLabel desc = new JLabel("<html>Pre-conditions: pot of flour in inv;<br>"
            + "bucket of milk OR empty bucket in inv.</html>");
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        questStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        capHeight(questStatusLabel);

        p.add(desc);
        p.add(Box.createVerticalStrut(4));
        p.add(buttons);
        p.add(questStatusLabel);
        p.add(Box.createVerticalGlue());
        return p;
    }

    public void setCooksAssistantScript(CooksAssistantScript script)
    {
        this.cooksAssistantScript = script;
    }

    private void onQuestStart()
    {
        if (cooksAssistantScript == null)
        {
            questStatusLabel.setText("Quest: unavailable");
            return;
        }
        cooksAssistantScript.start();
        questStatusLabel.setText("Quest: starting");
    }

    private void onQuestStop()
    {
        if (cooksAssistantScript == null) return;
        cooksAssistantScript.stop();
        questStatusLabel.setText("Quest: stopping");
    }

    private void refreshCooksQuest()
    {
        if (cooksAssistantScript == null)
        {
            questStatusLabel.setText("Quest: unavailable");
            return;
        }
        questStatusLabel.setText(cooksAssistantScript.state()
            + " — " + cooksAssistantScript.status());
    }

    private JPanel buildPieDishTab()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Pie Dish Crafter"));

        JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
        buttons.add(pieDishStartBtn);
        buttons.add(pieDishStopBtn);
        capHeight(buttons);

        JLabel desc = new JLabel("<html>Buys pie dishes, flour &amp; water at GE,<br>"
            + "crafts pastry dough + pie shells, then sells.<br>"
            + "Start near GE (Varrock).</html>");

        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        pieDishStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        capHeight(pieDishStatusLabel);

        p.add(desc);
        p.add(Box.createVerticalStrut(4));
        p.add(buttons);
        p.add(pieDishStatusLabel);
        p.add(Box.createVerticalGlue());

        pieDishStartBtn.addActionListener(e -> onPieDishStart());
        pieDishStopBtn.addActionListener(e -> onPieDishStop());
        return p;
    }

    public void setPieDishScript(net.runelite.client.plugins.recorder.scripts.PieDishScript script)
    {
        this.pieDishScript = script;
    }

    private void onPieDishStart()
    {
        if (pieDishScript == null)
        {
            pieDishStatusLabel.setText("Pie Dish: unavailable");
            return;
        }
        pieDishScript.start();
        pieDishStatusLabel.setText("Pie Dish: starting");
    }

    private void onPieDishStop()
    {
        if (pieDishScript == null) return;
        pieDishScript.stop();
        pieDishStatusLabel.setText("Pie Dish: stopping");
    }

    private void refreshPieDish()
    {
        if (pieDishScript == null)
        {
            pieDishStatusLabel.setText("Pie Dish: unavailable");
            return;
        }
        pieDishStatusLabel.setText(pieDishScript.state()
            + " — " + pieDishScript.status());
    }

    private JPanel buildPizzaTab()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Pizza maker (Lumbridge)"));

        // GridLayout(1,2) gives Start + Stop equal width, matching the
        // Cooking tab. Plain BorderLayout(CENTER, EAST) starves Start
        // when the panel is narrow.
        JPanel buttons = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
        buttons.add(pizzaStartBtn);
        buttons.add(pizzaStopBtn);
        capHeight(buttons);

        JLabel desc = new JLabel("<html>"
            + "Bank tomatoes, pizza bases, cheese.<br>"
            + "Combines them into uncooked pizzas,<br>"
            + "cooks at the Lumbridge range.<br>"
            + "Start at Lumbridge bank.</html>");

        // BoxLayout Y_AXIS positions each child by its alignmentX, which
        // defaults to CENTER (0.5). Short components (checkbox, status,
        // counts) end up indented from the left edge — visually wasted
        // space. Pin every child to LEFT_ALIGNMENT.
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        pizzaTomatoBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        pizzaCheeseBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        pizzaCookBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        pizzaAnchoviesBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        pizzaBreaksBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        pizzaStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        pizzaCountsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        pizzaBreakLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Pin label heights so BoxLayout doesn't stretch them vertically.
        capHeight(pizzaTomatoBox);
        capHeight(pizzaCheeseBox);
        capHeight(pizzaCookBox);
        capHeight(pizzaAnchoviesBox);
        capHeight(pizzaBreaksBox);
        capHeight(pizzaStatusLabel);
        capHeight(pizzaCountsLabel);
        capHeight(pizzaBreakLabel);

        p.add(desc);
        p.add(Box.createVerticalStrut(4));
        p.add(pizzaTomatoBox);
        p.add(pizzaCheeseBox);
        p.add(pizzaCookBox);
        p.add(pizzaAnchoviesBox);
        p.add(pizzaBreaksBox);
        p.add(buttons);
        p.add(pizzaStatusLabel);
        p.add(pizzaCountsLabel);
        p.add(pizzaBreakLabel);
        p.add(Box.createVerticalGlue());

        pizzaStartBtn.addActionListener(e -> onPizzaStart());
        pizzaStopBtn.addActionListener(e -> onPizzaStop());
        // Live-push every checkbox flip to the script — script reads
        // these in tickDecide so the change takes effect at the next
        // bank trip without restart.
        pizzaTomatoBox.addActionListener(e -> {
            if (pizzaScript != null) pizzaScript.setAddTomato(pizzaTomatoBox.isSelected());
        });
        pizzaCheeseBox.addActionListener(e -> {
            if (pizzaScript != null) pizzaScript.setAddCheese(pizzaCheeseBox.isSelected());
        });
        pizzaCookBox.addActionListener(e -> {
            if (pizzaScript != null) pizzaScript.setCookPizza(pizzaCookBox.isSelected());
        });
        pizzaAnchoviesBox.addActionListener(e -> {
            if (pizzaScript != null) pizzaScript.setAddAnchovies(pizzaAnchoviesBox.isSelected());
        });
        pizzaBreaksBox.addActionListener(e -> {
            if (pizzaScript != null) pizzaScript.setAfkBreaksEnabled(pizzaBreaksBox.isSelected());
        });
        return p;
    }

    public void setPizzaScript(net.runelite.client.plugins.recorder.scripts.PizzaScript script)
    {
        this.pizzaScript = script;
        if (script != null)
        {
            script.setAddTomato(pizzaTomatoBox.isSelected());
            script.setAddCheese(pizzaCheeseBox.isSelected());
            script.setCookPizza(pizzaCookBox.isSelected());
            script.setAddAnchovies(pizzaAnchoviesBox.isSelected());
            script.setAfkBreaksEnabled(pizzaBreaksBox.isSelected());
        }
    }

    private void onPizzaStart()
    {
        if (pizzaScript == null)
        {
            pizzaStatusLabel.setText("Pizza: unavailable");
            return;
        }
        // Mutual exclusion with the cooking V2/V3 scripts — they all
        // click the same character, so two running at once interleaves
        // input and produces garbage. Same guard pattern Cooking V2
        // uses against V3.
        if (cookingScriptV3 != null
            && cookingScriptV3.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV3.State.IDLE
            && cookingScriptV3.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV3.State.ABORTED)
        {
            pizzaStatusLabel.setText("Pizza: Cooking V3 still running — Stop first");
            return;
        }
        if (cookingScriptV2 != null
            && cookingScriptV2.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV2.State.IDLE
            && cookingScriptV2.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV2.State.ABORTED)
        {
            pizzaStatusLabel.setText("Pizza: Cooking V2 still running — Stop first");
            return;
        }
        pizzaScript.setAddTomato(pizzaTomatoBox.isSelected());
        pizzaScript.setAddCheese(pizzaCheeseBox.isSelected());
        pizzaScript.setCookPizza(pizzaCookBox.isSelected());
        pizzaScript.setAddAnchovies(pizzaAnchoviesBox.isSelected());
        pizzaScript.setAfkBreaksEnabled(pizzaBreaksBox.isSelected());
        pizzaScript.start();
        StringBuilder flags = new StringBuilder();
        if (pizzaTomatoBox.isSelected())    flags.append("T");
        if (pizzaCheeseBox.isSelected())    flags.append("C");
        if (pizzaCookBox.isSelected())      flags.append("K");
        if (pizzaAnchoviesBox.isSelected()) flags.append("A");
        pizzaStatusLabel.setText("Pizza: starting (loops: "
            + (flags.length() == 0 ? "none" : flags.toString()) + ")");
    }

    private void onPizzaStop()
    {
        if (pizzaScript == null) return;
        pizzaScript.stop();
        pizzaStatusLabel.setText("Pizza: stopping");
    }

    private void refreshPizza()
    {
        if (pizzaScript == null)
        {
            pizzaStatusLabel.setText("Pizza: unavailable");
            pizzaCountsLabel.setText("Made: 0");
            pizzaBreakLabel.setText("breaks: idle");
            return;
        }
        pizzaStatusLabel.setText(pizzaScript.state()
            + " — " + pizzaScript.status());
        pizzaBreakLabel.setText(pizzaScript.breakStatus());
        pizzaCountsLabel.setText("<html>Made — "
            + "Unfinished: <b>" + pizzaScript.incompleteMade() + "</b> | "
            + "Uncooked: <b>" + pizzaScript.uncookedMade() + "</b> | "
            + "Plain: <b>" + pizzaScript.plainMade() + "</b> | "
            + "Anchovy: <b>" + pizzaScript.anchovyMade() + "</b> | "
            + "Burnt: <b>" + pizzaScript.burntMade() + "</b>"
            + "</html>");
    }

    /** Wire the V2 cooking script. The plugin constructs the script in
     *  startUp and hands it here; the panel only owns the UI surface.
     *  Two start buttons (V2 + V3) share a Stop button. */
    public void setCookingScriptV2(net.runelite.client.plugins.recorder.scripts.CookingScriptV2 script)
    {
        this.cookingScriptV2 = script;
    }

    /** Wire the V3 cooking script (live-tracked booth click). Same
     *  shape as {@link #setCookingScriptV2}. */
    public void setCookingScriptV3(net.runelite.client.plugins.recorder.scripts.CookingScriptV3 script)
    {
        this.cookingScriptV3 = script;
    }

    /** Wire the GE Core script. RecorderPlugin constructs it after the
     *  cooking scripts and registers it on the eventBus for GameTick
     *  forwarding. The panel builds the GE Core tab on first wiring.
     *  {@code itemManager} powers the name → id lookup when the user
     *  types an item name. */
    public void setGrandExchangeScript(GrandExchangeScript script,
                                       net.runelite.client.game.ItemManager itemManager)
    {
        if (script == null) return;
        if (grandExchangeTab == null)
        {
            grandExchangeTab = new GrandExchangeTab(script, itemManager);
            tabs.addTab("GE Core", new javax.swing.JScrollPane(grandExchangeTab));
        }
    }

    /** Wire the V2 inspection dumper. The plugin constructs it once
     *  MapStore + EntityIndex + TransportIndex are live; the panel
     *  builds the V2 Inspect tab on first wiring. {@code overlayClearAction}
     *  is the no-arg callback the "Clear debug overlay route" button
     *  invokes — pass null on first wiring (Phase 3.2) and a real one
     *  in Phase 3.3 once {@code WorldMapMinimapOverlay} exists. */
    public void setInspectionDumper(
        net.runelite.client.plugins.recorder.worldmap.InspectionDumper dumper,
        Runnable overlayClearAction)
    {
        if (dumper == null) return;
        boolean firstWiring = (this.inspectionDumper == null);
        this.inspectionDumper = dumper;
        this.v2OverlayClearAction = overlayClearAction;
        if (firstWiring)
        {
            tabs.addTab("V2 Inspect", tabScroll(buildV2InspectTab()));
            v2DumpRegionBtn.addActionListener(e -> onV2DumpRegion());
            v2DumpNearbyBtn.addActionListener(e -> onV2DumpNearby());
            v2DumpTransportsBtn.addActionListener(e -> onV2DumpTransports());
            v2DumpEntitiesBtn.addActionListener(e -> onV2DumpEntities());
            v2PlanAToBBtn.addActionListener(e -> onV2PlanAToB());
            v2PresetApplyBtn.addActionListener(e -> onV2ApplyPreset());
            v2ClearOverlayRouteBtn.addActionListener(e -> onV2ClearOverlayRoute());
        }
    }

    private void onCookV2Start()
    {
        if (cookingScriptV2 == null)
        {
            cookStatusLabel.setText("Cooking V2: unavailable");
            return;
        }
        // Mutual exclusion — refuse to start V2 if V3 is already running.
        // Each version has its own dispatcher; running both at once would
        // interleave clicks and produce garbage.
        if (cookingScriptV3 != null
            && cookingScriptV3.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV3.State.IDLE
            && cookingScriptV3.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV3.State.ABORTED)
        {
            cookStatusLabel.setText("Cooking: V3 still running — Stop first");
            return;
        }
        CookingLocation loc = (CookingLocation) cookLocationBox.getSelectedItem();
        CookingFood.Entry food = (CookingFood.Entry) cookFoodBox.getSelectedItem();
        if (loc == null || food == null)
        {
            cookStatusLabel.setText("Cooking: pick location + food first");
            return;
        }
        cookingScriptV2.setLocation(loc);
        cookingScriptV2.setRawFoodId(food.rawId);
        long durationMs = parseDurationMs(cookV2MaxHoursField, cookV2MaxMinutesField);
        cookingScriptV2.setMaxDurationMs(durationMs);
        cookingScriptV2.start();
        cookStatusLabel.setText(durationMs > 0
            ? "Cooking V2: starting (cap " + (durationMs / 60_000L) + "m)"
            : "Cooking V2: starting (no cap)");
    }

    private void onCookV3Start()
    {
        if (cookingScriptV3 == null)
        {
            cookStatusLabel.setText("Cooking V3: unavailable");
            return;
        }
        if (cookingScriptV2 != null
            && cookingScriptV2.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV2.State.IDLE
            && cookingScriptV2.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV2.State.ABORTED)
        {
            cookStatusLabel.setText("Cooking: V2 still running — Stop first");
            return;
        }
        CookingLocation loc = (CookingLocation) cookLocationBox.getSelectedItem();
        CookingFood.Entry food = (CookingFood.Entry) cookFoodBox.getSelectedItem();
        if (loc == null || food == null)
        {
            cookStatusLabel.setText("Cooking: pick location + food first");
            return;
        }
        cookingScriptV3.setLocation(loc);
        cookingScriptV3.setRawFoodId(food.rawId);
        long durationMs = parseDurationMs(cookV3MaxHoursField, cookV3MaxMinutesField);
        cookingScriptV3.setMaxDurationMs(durationMs);
        cookingScriptV3.start();
        cookStatusLabel.setText(durationMs > 0
            ? "Cooking V3: starting (cap " + (durationMs / 60_000L) + "m)"
            : "Cooking V3: starting (no cap)");
    }

    /** Parse hour + minute text fields into a millisecond cap. Empty /
     *  invalid / negative values are treated as 0; total 0 disables the
     *  cap on the script side. */
    private static long parseDurationMs(JTextField hours, JTextField minutes)
    {
        long h = parseNonNegativeInt(hours.getText());
        long m = parseNonNegativeInt(minutes.getText());
        return (h * 3_600_000L) + (m * 60_000L);
    }

    private static long parseNonNegativeInt(String s)
    {
        if (s == null) return 0;
        try
        {
            int v = Integer.parseInt(s.trim());
            return Math.max(0, v);
        }
        catch (NumberFormatException nfe) { return 0; }
    }

    private void onCookStop()
    {
        // Shared Stop — call stop() on both. Each is a no-op if not
        // running, so calling both is safe and saves the panel having
        // to track which version is live.
        if (cookingScriptV2 != null) cookingScriptV2.stop();
        if (cookingScriptV3 != null) cookingScriptV3.stop();
        cookStatusLabel.setText("Cooking: stopping");
    }

    private void refreshCooking()
    {
        // Choose which script to display state for: prefer the one that
        // isn't IDLE, falling back to V3 when both are idle.
        boolean v2Live = cookingScriptV2 != null
            && cookingScriptV2.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV2.State.IDLE
            && cookingScriptV2.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV2.State.ABORTED;
        boolean v3Live = cookingScriptV3 != null
            && cookingScriptV3.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV3.State.IDLE
            && cookingScriptV3.state()
                != net.runelite.client.plugins.recorder.scripts.CookingScriptV3.State.ABORTED;

        if (v3Live)
        {
            cookStatusLabel.setText("Cooking V3: " + cookingScriptV3.state()
                + " — " + cookingScriptV3.status());
            cookCountsLabel.setText("Cooked: " + cookingScriptV3.cookedCount()
                + "  Burnt: " + cookingScriptV3.burntCount());
            return;
        }
        if (v2Live)
        {
            cookStatusLabel.setText("Cooking V2: " + cookingScriptV2.state()
                + " — " + cookingScriptV2.status());
            cookCountsLabel.setText("Cooked: " + cookingScriptV2.cookedCount()
                + "  Burnt: " + cookingScriptV2.burntCount());
            return;
        }
        if (cookingScriptV2 == null && cookingScriptV3 == null)
        {
            cookStatusLabel.setText("Cooking: unavailable");
            cookCountsLabel.setText("Cooked: 0  Burnt: 0");
            return;
        }
        // Neither is live — show V3's idle state by default (the new path).
        if (cookingScriptV3 != null)
        {
            cookStatusLabel.setText("Cooking V3: " + cookingScriptV3.state()
                + " — " + cookingScriptV3.status());
            cookCountsLabel.setText("Cooked: " + cookingScriptV3.cookedCount()
                + "  Burnt: " + cookingScriptV3.burntCount());
        }
        else
        {
            cookStatusLabel.setText("Cooking V2: " + cookingScriptV2.state()
                + " — " + cookingScriptV2.status());
            cookCountsLabel.setText("Cooked: " + cookingScriptV2.cookedCount()
                + "  Burnt: " + cookingScriptV2.burntCount());
        }
    }

    private void updateButtons()
    {
        boolean hasSel = selectedUsername != null;
        boolean inFlight = loginInFlight.get();
        loginBtn.setEnabled(hasSel && !inFlight);
        loginV2Btn.setEnabled(hasSel && !inFlight && loginAssistantV2 != null);
        addCornerBtn.setEnabled(!inFlight);
        stopBtn.setEnabled(inFlight);
    }

    private void refreshList()
    {
        Thread t = new Thread(() -> {
            java.util.Set<String> usernames;
            try
            {
                usernames = credentialStore != null ? credentialStore.list() : java.util.Collections.emptySet();
            }
            catch (CredentialStoreException cse)
            {
                SwingUtilities.invokeLater(() -> {
                    loginStatus.setText("list failed: " + cse.getMessage());
                    updateButtons();
                });
                return;
            }
            String last = readLastSelected();
            final java.util.List<String> sortedUsernames = new java.util.ArrayList<>(usernames);
            java.util.Collections.sort(sortedUsernames, String.CASE_INSENSITIVE_ORDER);
            SwingUtilities.invokeLater(() -> {
                currentUsernames.clear();
                currentUsernames.addAll(sortedUsernames);
                if (last != null && currentUsernames.contains(last)) selectedUsername = last;
                else if (!currentUsernames.isEmpty()) selectedUsername = currentUsernames.get(0);
                else selectedUsername = null;
                rebuildCredRows();
                updateButtons();
            });
        }, "creds-refresh");
        t.setDaemon(true);
        t.start();
    }

    private void onLogin()
    {
        if (loginAssistant == null) { setStatus("login unavailable"); return; }
        String user = selectedUsername;
        if (user == null) { setStatus("no character selected"); return; }
        if (!loginInFlight.compareAndSet(false, true)) return;
        updateButtons();
        persistLastSelected(user);

        LoginCredentials creds = new LoginCredentials(user, credentialStore);
        Thread t = new Thread(() -> {
            try
            {
                loginAssistant.login(creds, this::setStatus);
            }
            finally
            {
                loginInFlight.set(false);
                loginThread = null;
                SwingUtilities.invokeLater(this::updateButtons);
            }
        }, "login-assistant");
        t.setDaemon(true);
        loginThread = t;
        t.start();
    }

    private void onLoginV2()
    {
        if (loginAssistantV2 == null) { setStatus("login v2 unavailable"); return; }
        String user = selectedUsername;
        if (user == null) { setStatus("no character selected"); return; }
        if (!loginInFlight.compareAndSet(false, true)) return;
        updateButtons();
        persistLastSelected(user);

        // Capture world field on EDT before spawning the worker.
        String worldText = worldField.getText() == null ? "" : worldField.getText().trim();
        Integer targetWorld;
        if (worldText.isEmpty())
        {
            targetWorld = null;
        }
        else
        {
            try { targetWorld = Integer.parseInt(worldText); }
            catch (NumberFormatException nfe)
            {
                loginInFlight.set(false);
                updateButtons();
                setStatus("world must be a number");
                return;
            }
        }

        boolean jagex = accountPrefs != null && accountPrefs.isJagex(user);
        LoginCredentials creds = new LoginCredentials(user, credentialStore);
        final Integer fTargetWorld = targetWorld;
        Thread t = new Thread(() -> {
            try
            {
                boolean ok = loginAssistantV2.login(creds, fTargetWorld, this::setStatus, jagex);
                if (ok && fTargetWorld != null && accountPrefs != null)
                {
                    accountPrefs.setLastWorld(user, fTargetWorld);
                }
            }
            finally
            {
                loginInFlight.set(false);
                loginThread = null;
                SwingUtilities.invokeLater(this::updateButtons);
            }
        }, "login-v2");
        t.setDaemon(true);
        loginThread = t;
        t.start();
    }

    private void onStopLogin()
    {
        Thread t = loginThread;
        if (t != null && t.isAlive())
        {
            setStatus("stopping login…");
            t.interrupt();
        }
    }

    private void setStatus(String msg)
    {
        SwingUtilities.invokeLater(() -> loginStatus.setText(msg));
    }

    private void onAddCredential()
    {
        JTextField userField = new JTextField(20);
        JPasswordField pwField = new JPasswordField(20);
        JCheckBox jagexBox = new JCheckBox("Jagex account");
        jagexBox.addItemListener(e -> pwField.setEnabled(!jagexBox.isSelected()));
        Object[] form = { "Username:", userField, "Password:", pwField, jagexBox };
        int result = JOptionPane.showConfirmDialog(this, form,
            "Add credential", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String user = userField.getText() == null ? "" : userField.getText().trim();
        char[] pw = pwField.getPassword();
        boolean jagex = jagexBox.isSelected();
        if (user.isEmpty())
        {
            JOptionPane.showMessageDialog(this, "Username cannot be empty.", "Add credential", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!jagex && pw.length == 0)
        {
            int conf = JOptionPane.showConfirmDialog(this, "Save with empty password?",
                "Add credential", JOptionPane.YES_NO_OPTION);
            if (conf != JOptionPane.YES_OPTION) return;
        }

        final String fUser = user;
        final char[] fPw = pw;
        final boolean fJagex = jagex;
        Thread t = new Thread(() -> {
            try
            {
                if (credentialStore.list().contains(fUser))
                {
                    final int[] over = {-1};
                    try
                    {
                        SwingUtilities.invokeAndWait(() -> over[0] = JOptionPane.showConfirmDialog(this,
                            "Username '" + fUser + "' already saved. Overwrite the stored password?",
                            "Overwrite credential", JOptionPane.YES_NO_OPTION));
                    }
                    catch (Exception ex) { return; }
                    if (over[0] != JOptionPane.YES_OPTION) return;
                }
                credentialStore.write(fUser, fJagex ? JAGEX_PW_SENTINEL : new String(fPw));
                if (accountPrefs != null) accountPrefs.setJagex(fUser, fJagex);
                SwingUtilities.invokeLater(() -> loginStatus.setText("saved " + fUser));
                persistLastSelected(fUser);
                SwingUtilities.invokeLater(() -> selectedUsername = fUser);
                refreshList();
            }
            catch (CredentialStoreException cse)
            {
                log.warn("credential write failed", cse);
                SwingUtilities.invokeLater(() -> loginStatus.setText("save failed: " + cse.getMessage()));
            }
            finally
            {
                java.util.Arrays.fill(fPw, '\0');
            }
        }, "creds-add");
        t.setDaemon(true);
        t.start();
    }

    private void onDeleteCredential(final String username)
    {
        if (username == null) return;
        int conf = JOptionPane.showConfirmDialog(this,
            "Delete credentials for '" + username + "'?",
            "Delete credential", JOptionPane.YES_NO_OPTION);
        if (conf != JOptionPane.YES_OPTION) return;
        Thread t = new Thread(() -> {
            try
            {
                credentialStore.delete(username);
                if (accountPrefs != null) accountPrefs.setJagex(username, false);
                SwingUtilities.invokeLater(() -> {
                    loginStatus.setText("deleted " + username);
                    if (username.equals(selectedUsername))
                    {
                        selectedUsername = null;
                        persistLastSelected(null);
                    }
                });
                refreshList();
            }
            catch (CredentialStoreException cse)
            {
                log.warn("credential delete failed", cse);
                SwingUtilities.invokeLater(() -> loginStatus.setText("delete failed: " + cse.getMessage()));
            }
        }, "creds-delete");
        t.setDaemon(true);
        t.start();
    }

    /** Edit dialog: username prefilled and editable, password empty. Empty
     *  password means "don't change the stored password"; non-empty replaces
     *  it. If username is changed, the entry is renamed (delete + write under
     *  new key) — this is the only way the underlying store models a rename. */
    private void onEditCredential(final String oldUsername)
    {
        if (oldUsername == null) return;
        boolean oldJagex = accountPrefs != null && accountPrefs.isJagex(oldUsername);

        JTextField userField = new JTextField(oldUsername, 20);
        JPasswordField pwField = new JPasswordField(20);
        pwField.setEnabled(!oldJagex);
        JCheckBox jagexBox = new JCheckBox("Jagex account", oldJagex);
        jagexBox.addItemListener(e -> pwField.setEnabled(!jagexBox.isSelected()));
        Object[] form = {
            "Username:", userField,
            "Password (leave empty to keep current):", pwField,
            jagexBox
        };
        int result = JOptionPane.showConfirmDialog(this, form,
            "Edit credential", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        final String newUser = userField.getText() == null ? "" : userField.getText().trim();
        final char[] pw = pwField.getPassword();
        final boolean newJagex = jagexBox.isSelected();
        if (newUser.isEmpty())
        {
            JOptionPane.showMessageDialog(this, "Username cannot be empty.",
                "Edit credential", JOptionPane.WARNING_MESSAGE);
            java.util.Arrays.fill(pw, '\0');
            return;
        }

        final boolean usernameChanged = !newUser.equals(oldUsername);
        final boolean passwordChanged = pw.length > 0;
        final boolean jagexChanged = (newJagex != oldJagex);

        // Switching from Jagex → normal requires a new password (we never stored one).
        if (oldJagex && !newJagex && !passwordChanged)
        {
            JOptionPane.showMessageDialog(this,
                "Enter a password when removing the Jagex account flag.",
                "Edit credential", JOptionPane.WARNING_MESSAGE);
            java.util.Arrays.fill(pw, '\0');
            return;
        }

        if (!usernameChanged && !passwordChanged && !jagexChanged)
        {
            setStatus("no changes");
            java.util.Arrays.fill(pw, '\0');
            return;
        }

        Thread t = new Thread(() -> {
            try
            {
                if (usernameChanged && credentialStore.list().contains(newUser))
                {
                    final int[] over = {-1};
                    try
                    {
                        SwingUtilities.invokeAndWait(() -> over[0] = JOptionPane.showConfirmDialog(this,
                            "Username '" + newUser + "' already exists. Overwrite it?",
                            "Overwrite credential", JOptionPane.YES_NO_OPTION));
                    }
                    catch (Exception ex) { return; }
                    if (over[0] != JOptionPane.YES_OPTION) return;
                }

                String pwToWrite;
                if (passwordChanged)
                {
                    pwToWrite = new String(pw);
                }
                else if (newJagex)
                {
                    pwToWrite = JAGEX_PW_SENTINEL;
                }
                else
                {
                    // Keep existing password — read it from the store.
                    pwToWrite = credentialStore.read(oldUsername);
                    if (pwToWrite == null)
                    {
                        SwingUtilities.invokeLater(() -> loginStatus.setText(
                            "edit failed: existing password unreadable"));
                        return;
                    }
                }

                credentialStore.write(newUser, pwToWrite);
                if (usernameChanged) credentialStore.delete(oldUsername);
                if (accountPrefs != null)
                {
                    accountPrefs.setJagex(newUser, newJagex);
                    if (usernameChanged) accountPrefs.setJagex(oldUsername, false);
                }

                SwingUtilities.invokeLater(() -> {
                    loginStatus.setText("updated " + newUser);
                    selectedUsername = newUser;
                });
                persistLastSelected(newUser);
                refreshList();
            }
            catch (CredentialStoreException cse)
            {
                log.warn("credential edit failed", cse);
                SwingUtilities.invokeLater(() -> loginStatus.setText("edit failed: " + cse.getMessage()));
            }
            finally
            {
                java.util.Arrays.fill(pw, '\0');
            }
        }, "creds-edit");
        t.setDaemon(true);
        t.start();
    }

    private static java.nio.file.Path loginStatePath()
    {
        java.nio.file.Path dir = net.runelite.client.RuneLite.RUNELITE_DIR.toPath().resolve("recorder");
        try { java.nio.file.Files.createDirectories(dir); } catch (java.io.IOException ignored) {}
        return dir.resolve("login-state.json");
    }

    private void persistLastSelected(@javax.annotation.Nullable String username)
    {
        try
        {
            java.util.Set<String> users;
            try { users = credentialStore.list(); } catch (Exception e) { users = new java.util.HashSet<>(); }
            net.runelite.client.sequence.login.KeychainCredentialStore.writeKnownUsers(loginStatePath(), users, username);
        }
        catch (Exception e) { log.warn("persist last-selected failed", e); }
    }

    @javax.annotation.Nullable
    private String readLastSelected()
    {
        try
        {
            java.nio.file.Path p = loginStatePath();
            if (!java.nio.file.Files.exists(p)) return null;
            com.google.gson.JsonObject obj = new com.google.gson.Gson().fromJson(java.nio.file.Files.readString(p), com.google.gson.JsonObject.class);
            if (obj == null || !obj.has("lastSelected")) return null;
            return obj.get("lastSelected").getAsString();
        }
        catch (Exception e) { return null; }
    }

    private void onRecordToggle(ActionEvent e)
    {
        try
        {
            if (manager.getState() == RecorderState.IDLE)
            {
                manager.start();
            }
            else if (manager.getState() == RecorderState.RECORDING)
            {
                String label = JOptionPane.showInputDialog(this,
                    "Intent label for this recording?", "Stop recording", JOptionPane.PLAIN_MESSAGE);
                if (label == null) return;
                manager.stop(label);
            }
        }
        catch (IOException io)
        {
            log.error("record toggle failed", io);
            JOptionPane.showMessageDialog(this, "Failed: " + io.getMessage());
        }
        refresh();
    }

    private void onMarker()
    {
        // Side-panel typing isn't a "dialog" — just emit the marker. The dialog
        // open/close events are reserved for the hotkey-triggered MarkerDialog.
        String label = markerField.getText();
        if (label == null || label.isBlank()) return;
        manager.recordMarker(label.trim());
        markerField.setText("");
    }

    /** Run a value-returning task on the RuneLite client thread, blocking the
     *  caller until the result is available. Wraps scene/perspective reads
     *  ({@code Player.getWorldLocation()}, tile lookups, etc.) which assert
     *  on the client thread. Returns null if the task threw or timed out. */
    private <T> T onClient(Supplier<T> task)
    {
        if (clientThread == null) return task.get();   // tests / no-injection path
        if (client != null && client.isClientThread()) return task.get();
        CompletableFuture<T> fut = new CompletableFuture<>();
        clientThread.invoke(() -> {
            try { fut.complete(task.get()); }
            catch (Throwable th) { fut.completeExceptionally(th); }
        });
        try { return fut.get(2000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (ExecutionException | TimeoutException ex)
        {
            log.warn("client-thread task failed", ex);
            return null;
        }
    }

    private void refresh()
    {
        // If the user has just logged in (or switched accounts), pull the
        // saved training plan for that account into the panel. Cheap — a
        // no-op when the username hasn't changed since the last call.
        if (chickenFarmV3 != null) restoreTrainingPanelState();
        RecorderState st = manager.getState();
        stateLabel.setText("State: " + st);
        recordBtn.setText(st == RecorderState.RECORDING ? "Stop" : "Record");
        recordBtn.setEnabled(st != RecorderState.FINALISING);
        long elapsed = manager.elapsedMs();
        elapsedLabel.setText("Elapsed: " + (elapsed / 1000) + "s");
        totalLabel.setText("Events: " + manager.totalEvents());
        var counts = manager.eventCountsSnapshot();
        if (counts.isEmpty())
        {
            breakdownLabel.setText("<html>&nbsp;</html>");
        }
        else
        {
            StringBuilder sb = new StringBuilder("<html>");
            int shown = 0;
            for (var en : counts.entrySet())
            {
                if (shown++ >= 8) break;
                sb.append(en.getKey()).append(": ").append(en.getValue()).append("<br>");
            }
            sb.append("</html>");
            breakdownLabel.setText(sb.toString());
        }
        refreshRecent();
        refreshCombat();
        refreshMining();
        refreshLumby();
        refreshCooking();
        refreshCooksQuest();
        refreshPieDish();
        refreshPizza();
    }

    /** Mirror the bare chicken combat loop's state into the panel labels.
     *  Used by the Combat tab for ad-hoc combat at the pen — the V1 / V2
     *  chicken farm scripts have their own combat instance and surface
     *  status through their own panel sections. */
    private void refreshCombat()
    {
        if (chickenLoop == null) return;
        ChickenCombatLoop.State st = chickenLoop.state();
        chickenStatusLabel.setText("Combat: " + st.name().toLowerCase()
            + " — " + chickenLoop.latestStatus());
        chickenKillsLabel.setText("Kills: " + chickenLoop.killCount());
        chickenStartBtn.setEnabled(st == ChickenCombatLoop.State.IDLE
            || st == ChickenCombatLoop.State.ABORTED);
        chickenStopBtn.setEnabled(st != ChickenCombatLoop.State.IDLE
            && st != ChickenCombatLoop.State.ABORTED);
    }

    /** Replace the recent-list contents with the current snapshot. Auto-scroll
     *  to the bottom only if the user was already near the bottom — this lets
     *  them scroll up to inspect older items without us yanking the view back. */
    private void refreshRecent()
    {
        List<RecordedEvent> recent = manager.recentEventsSnapshot();
        JViewport vp = recentScroll.getViewport();
        Point viewPos = vp.getViewPosition();
        int viewMaxY = recentList.getHeight() - vp.getHeight();
        boolean wasAtBottom = recentModel.isEmpty() || viewMaxY <= 0 || (viewMaxY - viewPos.y) <= 20;

        recentModel.clear();
        for (RecordedEvent ev : recent)
        {
            recentModel.addElement(formatRecent(ev));
        }
        if (wasAtBottom && !recentModel.isEmpty())
        {
            recentList.ensureIndexIsVisible(recentModel.size() - 1);
        }
    }

    private static String formatRecent(RecordedEvent e)
    {
        String t = "[" + (e.tMs() / 1000) + "s] ";
        if (e instanceof Events.MenuClick mc)
        {
            String tgt = mc.target() == null || mc.target().isBlank() ? "" : " " + stripCol(mc.target());
            return t + mc.verb() + tgt;
        }
        if (e instanceof Events.WorldClick wc)
        {
            String name = wc.entityName() == null || wc.entityName().isBlank() ? "" : " " + wc.entityName();
            return t + "click " + wc.entityKind() + name + " (" + wc.worldX() + "," + wc.worldY() + ")";
        }
        if (e instanceof Events.WidgetClick wc)
        {
            String item = wc.itemId() > 0 ? " item=" + wc.itemId() : "";
            String slot = wc.slot() >= 0 ? " slot=" + wc.slot() : "";
            return t + "widget " + wc.widgetKind() + item + slot;
        }
        if (e instanceof Events.InvChange ic) return t + "inv " + summariseDeltas(ic.deltas());
        if (e instanceof Events.EquipChange ec) return t + "equip " + summariseDeltas(ec.deltas());
        if (e instanceof Events.BankChange bc) return t + "bank " + summariseDeltas(bc.deltas());
        if (e instanceof Events.Chat c)
        {
            String who = c.sender() == null || "system".equals(c.sender()) ? "" : c.sender() + ": ";
            return t + "[" + c.chatType() + "] " + who + truncate(c.message(), 60);
        }
        if (e instanceof Events.Marker m) return t + "MARKER \"" + m.label() + "\"";
        if (e instanceof Events.MarkerDialog md) return t + "marker dialog " + (md.opened() ? "OPEN" : "close");
        if (e instanceof Events.XpChange x) return t + "xp " + x.skill() + " +" + (x.after() - x.before());
        return t + e.type();
    }

    private static String summariseDeltas(java.util.List<Events.InvChange.SlotDelta> deltas)
    {
        if (deltas == null || deltas.isEmpty()) return "(empty)";
        Events.InvChange.SlotDelta d = deltas.get(0);
        int net = d.afterQty() - d.beforeQty();
        int id = d.afterId() > 0 ? d.afterId() : d.beforeId();
        String more = deltas.size() > 1 ? " (+" + (deltas.size() - 1) + " more)" : "";
        return (net >= 0 ? "+" : "") + net + " of id=" + id + more;
    }

    private static String stripCol(String s)
    {
        // Menu targets often embed RuneLite colour tags like <col=ff9040>; strip
        // them so the panel stays legible.
        return s.replaceAll("<[^>]+>", "");
    }

    private static String truncate(String s, int max)
    {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ----------------------------------------------------------------------
    // Mining section
    // ----------------------------------------------------------------------

    /** Wire the mining loop. Called by the plugin during startUp once the
     *  dispatcher is constructed. The loop's status callback updates
     *  {@link #miningStatusLabel} via the EDT (see {@link #onMiningStatus}). */
    public void setMiningLoop(MiningLoop loop) { this.miningLoop = loop; }

    /** Status callback handed to the loop's constructor. Marshalls onto the
     *  EDT for the label update. */
    public void onMiningStatus(String msg)
    {
        if (msg == null) return;
        SwingUtilities.invokeLater(() -> miningStatusLabel.setText("Mining: " + msg));
    }

    private void onMiningStart()
    {
        if (miningLoop == null) { miningStatusLabel.setText("Mining: unavailable"); return; }
        miningLoop.start();
    }

    private void onMiningStop()
    {
        if (miningLoop == null) return;
        miningLoop.stop();
    }

    private void onMiningAddRock()
    {
        if (miningLoop == null) { miningStatusLabel.setText("Mining: unavailable"); return; }
        WorldPoint here = onClient(() -> {
            var pl = client.getLocalPlayer();
            return pl == null ? null : pl.getWorldLocation();
        });
        if (here == null) { miningStatusLabel.setText("Mining: no local player"); return; }
        miningLoop.addCandidate(here, null);
        int n = miningLoop.candidatesSnapshot().size();
        miningStatusLabel.setText("Mining: added rock @ " + here.getX() + "," + here.getY());
        miningRockCountLabel.setText("Rocks: " + n);
    }

    private void onMiningClearRocks()
    {
        if (miningLoop == null) return;
        miningLoop.clearCandidates();
        miningRockCountLabel.setText("Rocks: 0");
        miningStatusLabel.setText("Mining: rocks cleared");
    }

    /** Per-tick refresh of the mining labels. Called from {@link #refresh}. */
    private void refreshMining()
    {
        if (miningLoop == null) return;
        MiningLoop.State st = miningLoop.state();
        miningStartBtn.setEnabled(st == MiningLoop.State.IDLE
            || st == MiningLoop.State.ABORTED);
        miningStopBtn.setEnabled(st != MiningLoop.State.IDLE
            && st != MiningLoop.State.ABORTED);
        miningOreCountLabel.setText("Ores: " + miningLoop.oresMined());
        miningRockCountLabel.setText("Rocks: " + miningLoop.candidatesSnapshot().size());
    }
}
