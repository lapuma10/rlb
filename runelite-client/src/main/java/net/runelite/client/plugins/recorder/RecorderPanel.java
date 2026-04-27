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
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.combat.ChickenCombatLoop;
import net.runelite.client.plugins.recorder.mining.MiningLoop;
import net.runelite.client.plugins.recorder.debug.DebugOverlay;
import net.runelite.client.plugins.recorder.debug.TileMarker;
import net.runelite.client.plugins.recorder.events.Events;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import net.runelite.client.plugins.recorder.transport.RouteParser;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;
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
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

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
    private final JTextArea testWalkArea = new JTextArea(6, 18);
    private final JButton testWalkBtn = new JButton("Walk path");
    private final JButton testWalkStopBtn = new JButton("Stop");
    private final JButton addCurPosBtn = new JButton("Add current");
    private final JButton addMarkedBtn = new JButton("Add marked");
    private final JButton saveRouteBtn = new JButton("Save");
    private final JButton loadRouteBtn = new JButton("Load");
    private final JLabel testWalkStatus = new JLabel(" ");
    private volatile Thread testWalkThread;
    /** Where named saved routes ("platforms") live. Each route is a text
     *  file with one waypoint per line — same format the test-walk text
     *  area accepts, so save/load is just a string round-trip. */
    private static final Path ROUTES_DIR = Paths.get(
        System.getProperty("user.home"), ".runelite", "sequencer", "routes");
    private final JButton markTileBtn = new JButton("Mark tile (next click)");
    private final JButton walkToMarkBtn = new JButton("Walk to mark");
    private final JButton clearMarkBtn = new JButton("Clear");
    private final JLabel markedLabel = new JLabel("Marked: (none)");
    private final DefaultListModel<String> credModel = new DefaultListModel<>();
    private final JList<String> credList = new JList<>(credModel);
    private final JButton addBtn = new JButton("Add…");
    private final JButton deleteBtn = new JButton("Delete");
    private final JButton loginBtn = new JButton("Log in");
    private final JButton dumpBtn = new JButton("Debug: dump open widgets");
    private final JLabel loginStatus = new JLabel(" ");
    private final java.util.concurrent.atomic.AtomicBoolean loginInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Thread loginThread = null;
    private final JButton stopBtn = new JButton("Stop");
    private LoginAssistant loginAssistant;
    private CredentialStore credentialStore;
    private DebugOverlay debugOverlay;
    private TileMarker tileMarker;
    private Timer refreshTimer;
    private final JButton chickenStartBtn = new JButton("Start chicken loop");
    private final JButton chickenStopBtn = new JButton("Stop");
    private final JLabel chickenStatusLabel = new JLabel("Chicken loop: idle");
    private final JLabel chickenKillsLabel = new JLabel("Kills: 0");
    private ChickenCombatLoop chickenLoop;
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
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        add(buildStatusHeader());
        add(Box.createVerticalStrut(6));
        add(buildControls());
        add(Box.createVerticalStrut(6));
        add(buildMarker());
        add(Box.createVerticalStrut(6));
        add(buildRecent());
        add(Box.createVerticalStrut(6));
        add(buildDebug());
        add(Box.createVerticalStrut(6));
        add(buildTestWalk());
        add(Box.createVerticalStrut(6));
        add(buildCombat());
        add(Box.createVerticalStrut(6));
        add(buildMining());
        add(Box.createVerticalStrut(6));
        add(buildLogin());

        recordBtn.addActionListener(this::onRecordToggle);
        chickenStartBtn.addActionListener(e -> onChickenStart());
        chickenStopBtn.addActionListener(e -> onChickenStop());
        miningStartBtn.addActionListener(e -> onMiningStart());
        miningStopBtn.addActionListener(e -> onMiningStop());
        miningAddRockBtn.addActionListener(e -> onMiningAddRock());
        miningClearRocksBtn.addActionListener(e -> onMiningClearRocks());
        markerBtn.addActionListener(e -> onMarker());
        testWalkBtn.addActionListener(e -> onTestWalk());
        testWalkStopBtn.addActionListener(e -> onTestWalkStop());
        addCurPosBtn.addActionListener(e -> onAddCurrentPos());
        addMarkedBtn.addActionListener(e -> onAddMarked());
        saveRouteBtn.addActionListener(e -> onSaveRoute());
        loadRouteBtn.addActionListener(e -> onLoadRoute());
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
        return p;
    }

    private JComponent buildControls()
    {
        JPanel p = new JPanel();
        p.setBorder(BorderFactory.createTitledBorder("Recording"));
        p.add(recordBtn);
        return p;
    }

    private JComponent buildMarker()
    {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createTitledBorder("Marker"));
        p.add(markerField, BorderLayout.CENTER);
        p.add(markerBtn, BorderLayout.EAST);
        return p;
    }

    private JComponent buildRecent()
    {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder("Recent (last 50)"));
        recentList.setVisibleRowCount(15);
        recentScroll.setPreferredSize(new Dimension(220, 320));
        recentScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        p.add(recentScroll, BorderLayout.CENTER);
        return p;
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

    /** Wire the {@link LoginAssistant} so the Login button has somewhere to
     *  go. The plugin owns lifetime and constructs the assistant only when
     *  injection has succeeded. */
    public void setLoginAssistant(LoginAssistant la) { this.loginAssistant = la; }

    /** Wire the credential store used by the credential management buttons. */
    public void setCredentialStore(CredentialStore store)
    {
        this.credentialStore = store;
        refreshList();
    }

    private JComponent buildDebug()
    {
        // Compact testing surface: snapshot the tile currently under the
        // cursor, then walk to it. Lets you mark a tile, walk away (so it's
        // off-screen / outside the loaded scene), then walk back to test
        // the dispatcher's out-of-sight handling.
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Debug + tile mark"));
        p.add(markTileBtn);
        JPanel row = new JPanel(new BorderLayout(4, 4));
        row.add(walkToMarkBtn, BorderLayout.CENTER);
        row.add(clearMarkBtn, BorderLayout.EAST);
        p.add(row);
        p.add(markedLabel);
        return p;
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
        // Route through walkRoute so we get the auto-subdivider for free —
        // a marked tile may be 60+ tiles away, well past the minimap's
        // single-click radius.
        Thread t = new Thread(() -> {
            walkRoute(java.util.List.of(Waypoint.walk(wp)));
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

    private JComponent buildTestWalk()
    {
        // Smoke-test surface for HumanizedInputDispatcher: paste world tiles
        // (one per line, "x,y" or "x,y,plane"), hit Walk path, watch the
        // cursor walk the route. Build routes by clicking "Add current" /
        // "Add marked" while you walk in-game; persist them via Save/Load.
        // The persisted routes are the "platforms" feature — a named list
        // of waypoints that the script can replay sequentially, giving the
        // walker enough variance to never trace a perfectly straight path.
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createTitledBorder("Test walk / Platforms"));
        testWalkArea.setLineWrap(false);
        testWalkArea.setToolTipText("<html>One waypoint per line — formats:<br>"
            + "&nbsp;&nbsp;x,y[,plane] — walk<br>"
            + "&nbsp;&nbsp;open:x,y[,plane] — open a door<br>"
            + "&nbsp;&nbsp;climb-up:x,y[,plane] — climb a stair/ladder up<br>"
            + "&nbsp;&nbsp;climb-down:x,y[,plane] — climb down<br>"
            + "&nbsp;&nbsp;interact:x,y[,plane]:Verb — generic action</html>");
        JScrollPane areaScroll = new JScrollPane(testWalkArea);
        areaScroll.setPreferredSize(new Dimension(220, 110));
        p.add(areaScroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        JPanel rowWalk = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        rowWalk.add(testWalkBtn);
        rowWalk.add(testWalkStopBtn);
        JPanel rowAdd = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        rowAdd.add(addCurPosBtn);
        rowAdd.add(addMarkedBtn);
        JPanel rowFile = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        rowFile.add(saveRouteBtn);
        rowFile.add(loadRouteBtn);
        buttons.add(rowWalk);
        buttons.add(rowAdd);
        buttons.add(rowFile);
        buttons.add(testWalkStatus);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private void onAddCurrentPos()
    {
        WorldPoint here = onClient(() -> {
            var pl = client.getLocalPlayer();
            return pl == null ? null : pl.getWorldLocation();
        });
        if (here == null) { testWalkStatus.setText("no local player"); return; }
        appendWaypoint(here);
        testWalkStatus.setText("added current → " + here.getX() + "," + here.getY()
            + (here.getPlane() == 0 ? "" : ",p=" + here.getPlane()));
    }

    private void onAddMarked()
    {
        WorldPoint wp = debugOverlay == null ? null : debugOverlay.getMarked();
        if (wp == null) { testWalkStatus.setText("nothing marked"); return; }
        appendWaypoint(wp);
        testWalkStatus.setText("added marked → " + wp.getX() + "," + wp.getY()
            + (wp.getPlane() == 0 ? "" : ",p=" + wp.getPlane()));
    }

    private void appendWaypoint(WorldPoint wp)
    {
        String existing = testWalkArea.getText();
        StringBuilder line = new StringBuilder();
        if (!existing.isEmpty() && !existing.endsWith("\n")) line.append("\n");
        line.append(wp.getX()).append(",").append(wp.getY());
        if (wp.getPlane() != 0) line.append(",").append(wp.getPlane());
        testWalkArea.append(line.toString());
    }

    private void onSaveRoute()
    {
        String name = JOptionPane.showInputDialog(this, "Route name?",
            "Save route", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        String safe = name.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
        try
        {
            Files.createDirectories(ROUTES_DIR);
            Files.writeString(ROUTES_DIR.resolve(safe + ".txt"), testWalkArea.getText());
            testWalkStatus.setText("saved → " + safe);
        }
        catch (IOException ex)
        {
            testWalkStatus.setText("save failed: " + ex.getMessage());
        }
    }

    private void onLoadRoute()
    {
        try
        {
            if (!Files.isDirectory(ROUTES_DIR))
            {
                testWalkStatus.setText("no saved routes yet");
                return;
            }
            java.util.List<String> names;
            try (var stream = Files.list(ROUTES_DIR))
            {
                names = stream
                    .filter(pp -> pp.toString().endsWith(".txt"))
                    .map(pp -> pp.getFileName().toString().replaceFirst("\\.txt$", ""))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
            }
            if (names.isEmpty()) { testWalkStatus.setText("no saved routes yet"); return; }
            Object chosen = JOptionPane.showInputDialog(this, "Load which route?",
                "Load route", JOptionPane.PLAIN_MESSAGE, null,
                names.toArray(), names.get(0));
            if (chosen == null) return;
            String content = Files.readString(ROUTES_DIR.resolve(chosen + ".txt"));
            testWalkArea.setText(content);
            testWalkStatus.setText("loaded ← " + chosen);
        }
        catch (IOException ex)
        {
            testWalkStatus.setText("load failed: " + ex.getMessage());
        }
    }

    /** Wire the chicken combat loop. Called by the plugin during startUp once
     *  the dispatcher is constructed. The loop's status callback updates
     *  {@link #chickenStatusLabel} via the EDT. */
    public void setChickenLoop(ChickenCombatLoop loop) { this.chickenLoop = loop; }

    private JComponent buildCombat()
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

    private void onChickenStart()
    {
        if (chickenLoop == null)
        {
            chickenStatusLabel.setText("Chicken loop: unavailable");
            return;
        }
        chickenLoop.start();
        chickenStatusLabel.setText("Chicken loop: starting");
    }

    private void onChickenStop()
    {
        if (chickenLoop == null) return;
        chickenLoop.stop();
        chickenStatusLabel.setText("Chicken loop: stopping");
    }

    private JComponent buildLogin()
    {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Login"));

        credList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        credList.setVisibleRowCount(4);
        JScrollPane scroll = new JScrollPane(credList);

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        buttons.add(addBtn);
        buttons.add(deleteBtn);
        buttons.add(loginBtn);
        buttons.add(stopBtn);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(buttons, BorderLayout.NORTH);
        south.add(loginStatus, BorderLayout.CENTER);
        south.add(dumpBtn, BorderLayout.SOUTH);

        panel.add(new JLabel("Saved characters:"), BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);

        addBtn.addActionListener(e -> onAddCredential());
        deleteBtn.addActionListener(e -> onDeleteCredential());
        loginBtn.addActionListener(e -> onLogin());
        stopBtn.addActionListener(e -> onStopLogin());
        dumpBtn.addActionListener(e -> onDumpWidgets());

        credList.addListSelectionListener(e -> updateButtons());
        updateButtons();
        return panel;
    }

    private void updateButtons()
    {
        boolean hasSel = credList.getSelectedIndex() >= 0;
        boolean inFlight = loginInFlight.get();
        deleteBtn.setEnabled(hasSel && !inFlight);
        loginBtn.setEnabled(hasSel && !inFlight);
        addBtn.setEnabled(!inFlight);
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
            SwingUtilities.invokeLater(() -> {
                credModel.clear();
                usernames.forEach(credModel::addElement);
                if (last != null && credModel.contains(last)) credList.setSelectedValue(last, true);
                else if (!credModel.isEmpty()) credList.setSelectedIndex(0);
                updateButtons();
            });
        }, "creds-refresh");
        t.setDaemon(true);
        t.start();
    }

    private void onLogin()
    {
        if (loginAssistant == null) { setStatus("login unavailable"); return; }
        String user = credList.getSelectedValue();
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
        Object[] form = { "Username:", userField, "Password:", pwField };
        int result = JOptionPane.showConfirmDialog(this, form,
            "Add credential", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        String user = userField.getText() == null ? "" : userField.getText().trim();
        char[] pw = pwField.getPassword();
        if (user.isEmpty())
        {
            JOptionPane.showMessageDialog(this, "Username cannot be empty.", "Add credential", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (pw.length == 0)
        {
            int conf = JOptionPane.showConfirmDialog(this, "Save with empty password?",
                "Add credential", JOptionPane.YES_NO_OPTION);
            if (conf != JOptionPane.YES_OPTION) return;
        }

        final String fUser = user;
        final char[] fPw = pw;
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
                credentialStore.write(fUser, new String(fPw));
                SwingUtilities.invokeLater(() -> loginStatus.setText("saved " + fUser));
                persistLastSelected(fUser);
                refreshList();
                SwingUtilities.invokeLater(() -> credList.setSelectedValue(fUser, true));
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

    private void onDeleteCredential()
    {
        String sel = credList.getSelectedValue();
        if (sel == null) return;
        int conf = JOptionPane.showConfirmDialog(this,
            "Delete credentials for '" + sel + "'?",
            "Delete credential", JOptionPane.YES_NO_OPTION);
        if (conf != JOptionPane.YES_OPTION) return;
        final int idx = credList.getSelectedIndex();
        Thread t = new Thread(() -> {
            try
            {
                credentialStore.delete(sel);
                SwingUtilities.invokeLater(() -> loginStatus.setText("deleted " + sel));
                refreshList();
                SwingUtilities.invokeLater(() -> {
                    int newSize = credModel.size();
                    if (newSize == 0) { credList.clearSelection(); persistLastSelected(null); }
                    else credList.setSelectedIndex(Math.min(idx, newSize - 1));
                });
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

    private void onDumpWidgets()
    {
        if (dispatcher == null || client == null) { setStatus("dispatcher unavailable"); return; }
        Thread t = new Thread(() -> {
            try
            {
                java.nio.file.Path out = dispatcher.runOnClient(() -> {
                    try { return net.runelite.client.plugins.recorder.debug.WidgetDumper.dump(client); }
                    catch (java.io.IOException ioe) { throw new RuntimeException(ioe); }
                });
                setStatus("widget dump written to " + out);
            }
            catch (Exception e)
            {
                log.warn("widget dump failed", e);
                setStatus("dump failed: " + e.getMessage());
            }
        }, "widget-dump");
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

    private void onTestWalk()
    {
        if (dispatcher == null) { testWalkStatus.setText("dispatcher unavailable"); return; }
        if (testWalkThread != null && testWalkThread.isAlive())
        {
            testWalkStatus.setText("already running — Stop first");
            return;
        }
        RouteParser.Result parsed = RouteParser.parse(testWalkArea.getText());
        if (parsed.waypoints().isEmpty())
        {
            String msg = parsed.hasErrors()
                ? "no valid waypoints (" + parsed.errors().get(0) + ")"
                : "no valid waypoints";
            testWalkStatus.setText(msg);
            return;
        }
        if (parsed.hasErrors())
        {
            log.info("route parse skipped {} bad lines: {}",
                parsed.errors().size(), parsed.errors());
        }
        java.util.List<Waypoint> waypoints = parsed.waypoints();
        testWalkStatus.setText("walking " + waypoints.size() + " step(s)…");
        testWalkThread = new Thread(() -> walkRoute(waypoints), "test-walk");
        testWalkThread.setDaemon(true);
        testWalkThread.start();
    }

    private void onTestWalkStop()
    {
        Thread t = testWalkThread;
        if (t != null) t.interrupt();
        testWalkStatus.setText("stop requested");
    }

    /** Maximum tile distance (Chebyshev, 2D) at which we consider ourselves
     *  "in interaction range" of a transport object. Set to 2 because the
     *  engine itself usually pathfinds to within 1 tile of the door before
     *  triggering the open animation; a tolerance of 2 gives the dispatcher
     *  a margin while still keeping the camera angle on the object. */
    private static final int INTERACTION_RANGE = 2;

    /** Walk a route, dispatching transports inline. For each waypoint:
     *  if WALK, walk-with-subdivision until the player is on / near the tile;
     *  if TRANSPORT, walk to within {@link #INTERACTION_RANGE}, then dispatch
     *  a {@code CLICK_GAME_OBJECT} that opens the door / climbs the ladder /
     *  generic interact, and wait for the corresponding state transition to
     *  fire (object id changes for doors; plane changes for climb).
     *
     *  <p>The reach and hop-distance are read dynamically from the actual
     *  minimap zoom on every loop iteration, so a zoomed-in minimap (where
     *  the engine's projection radius shrinks) doesn't make us pick hops
     *  the resolver can't project.
     *
     *  <p>Runs on a daemon thread so the EDT stays responsive. */
    private void walkRoute(java.util.List<Waypoint> waypoints)
    {
        for (int i = 0; i < waypoints.size(); i++)
        {
            if (Thread.currentThread().isInterrupted()) break;
            Waypoint wp = waypoints.get(i);
            int idx = i;
            boolean isFinal = (i == waypoints.size() - 1);
            if (wp.kind() == Waypoint.Kind.WALK)
            {
                if (!walkToTile(wp.tile(), idx, waypoints.size(), isFinal ? 0 : 3, false))
                    return;
            }
            else
            {
                // Approach with a generous tolerance so the dispatcher can
                // still see the object on screen. After arrival, fire the
                // transport click and wait for its state transition.
                if (!walkToTile(wp.tile(), idx, waypoints.size(), INTERACTION_RANGE, true))
                    return;
                if (!doTransport(wp, idx, waypoints.size())) return;
            }
        }
        SwingUtilities.invokeLater(() -> testWalkStatus.setText("done"));
    }

    /** Walk-with-subdivision toward {@code goal}, returning true when the
     *  player is within {@code arrivalTolerance} tiles of it (Chebyshev),
     *  false on failure (no player, plane mismatch, dispatcher error, stuck).
     *  When {@code allowSamePlaneOnly} is true, plane mismatch reports a
     *  transport-not-yet-handled error; the caller is expected to dispatch a
     *  transport before the next walk leg.
     *
     *  <p>If {@code arrivalTolerance > 0} and we're already inside that
     *  radius, returns immediately without dispatching anything. Used so a
     *  transport waypoint can ask "are we close enough yet?" without
     *  spinning when the previous leg already left us within range. */
    private boolean walkToTile(WorldPoint goal, int idx, int total,
                               int arrivalTolerance, boolean isTransportApproach)
    {
        int hopCount = 0;
        int sameClickCount = 0;
        WorldPoint lastClick = null;
        while (true)
        {
            if (Thread.currentThread().isInterrupted()) return false;
            WorldPoint here = onClient(() -> {
                var pl = client.getLocalPlayer();
                return pl == null ? null : pl.getWorldLocation();
            });
            if (here == null)
            {
                SwingUtilities.invokeLater(() -> testWalkStatus.setText(
                    "step " + (idx + 1) + " — no local player"));
                return false;
            }
            if (here.getPlane() != goal.getPlane())
            {
                SwingUtilities.invokeLater(() -> testWalkStatus.setText(
                    "step " + (idx + 1) + " — different plane (" + here.getPlane()
                        + " → " + goal.getPlane() + "); needs stair/ladder"));
                return false;
            }
            int dist = here.distanceTo(goal);
            if (dist <= arrivalTolerance) return true;
            int reach = dispatcher.pixelResolver().minimapRangeTiles();
            int hopDistance = Math.max(6, reach - 3);
            // For transport approach, aim a little bit short so the cursor
            // ends up close enough to interact but not standing on the door
            // tile (which can resolve to "Open" already at hover and trigger
            // a same-tile auto-open).
            final WorldPoint next;
            if (dist <= reach)
            {
                if (isTransportApproach && dist > arrivalTolerance + 1)
                {
                    next = stepToward(here, goal, Math.max(arrivalTolerance, dist - 1));
                }
                else
                {
                    next = goal;
                }
            }
            else
            {
                next = stepToward(here, goal, hopDistance);
            }
            if (next.equals(lastClick)) sameClickCount++;
            else sameClickCount = 0;
            lastClick = next;
            if (sameClickCount >= 4)
            {
                SwingUtilities.invokeLater(() -> testWalkStatus.setText(
                    "step " + (idx + 1) + " — stuck at " + here.getX() + "," + here.getY()
                        + " (no path); add an intermediate waypoint"));
                return false;
            }
            final int hopIdx = ++hopCount;
            SwingUtilities.invokeLater(() -> testWalkStatus.setText(
                "step " + (idx + 1) + "/" + total
                    + (next.equals(goal) ? " → " : " hop " + hopIdx + " → ")
                    + next.getX() + "," + next.getY()
                    + (next.equals(goal) ? "" : " (target " + dist + " away)")));
            ActionRequest req = ActionRequest.builder()
                .kind(ActionRequest.Kind.WALK)
                .channel(ActionRequest.Channel.MOUSE)
                .tile(next)
                .build();
            dispatcher.dispatch(req);
            while (dispatcher.isBusy())
            {
                if (Thread.currentThread().isInterrupted()) return false;
                try { Thread.sleep(50); } catch (InterruptedException ie)
                { Thread.currentThread().interrupt(); return false; }
            }
            String err = dispatcher.lastErrorMessage();
            if (err != null)
            {
                SwingUtilities.invokeLater(() -> testWalkStatus.setText(
                    "step " + (idx + 1) + " — " + err));
                return false;
            }
            // Wait until the player has arrived at the click target. Use the
            // arrival tolerance for the goal (whatever the caller asked for);
            // for intermediate hops, 3 tiles is fine because we re-aim next
            // iteration anyway.
            final WorldPoint nextFinal = next;
            final int hopArrivalTolerance =
                next.equals(goal) ? arrivalTolerance : 3;
            long timeout = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < timeout)
            {
                if (Thread.currentThread().isInterrupted()) return false;
                Boolean arrived = onClient(() -> {
                    var pl = client.getLocalPlayer();
                    return pl != null && pl.getWorldLocation() != null
                        && pl.getWorldLocation().distanceTo(nextFinal) <= hopArrivalTolerance;
                });
                if (Boolean.TRUE.equals(arrived)) break;
                try { Thread.sleep(120); } catch (InterruptedException ie)
                { Thread.currentThread().interrupt(); return false; }
            }
        }
    }

    /** Dispatch a {@code CLICK_GAME_OBJECT} for a transport waypoint and wait
     *  for the resulting state change. Returns true on success (object id
     *  changed for doors, plane changed for climbs, fixed wait elapsed for
     *  generic interacts), false on resolution failure. */
    private boolean doTransport(Waypoint wp, int idx, int total)
    {
        // Snapshot the pre-click state — we need the object id (for doors)
        // and the plane (for climb) so the post-click poller can detect the
        // transition. Also surface a clear error if the verb isn't on the
        // tile right now (e.g., door already open).
        TransportResolver tr = new TransportResolver(client);
        TransportResolver.Match preMatch = onClient(() -> tr.findTransport(wp.tile(), wp.verb()));
        if (preMatch == null || !preMatch.isSuccess())
        {
            String reason = preMatch == null ? "resolver returned null" : preMatch.failure();
            SwingUtilities.invokeLater(() -> testWalkStatus.setText(
                "step " + (idx + 1) + " — " + reason));
            return false;
        }
        int preObjectId = preMatch.matchedObjectId();
        Integer prePlane = onClient(() -> {
            var pl = client.getLocalPlayer();
            return pl == null || pl.getWorldLocation() == null ? null
                : pl.getWorldLocation().getPlane();
        });
        SwingUtilities.invokeLater(() -> testWalkStatus.setText(
            "step " + (idx + 1) + "/" + total + " " + wp.verb() + " → "
                + wp.tile().getX() + "," + wp.tile().getY()));
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(wp.tile())
            .verb(wp.verb())
            .build();
        dispatcher.dispatch(req);
        while (dispatcher.isBusy())
        {
            if (Thread.currentThread().isInterrupted()) return false;
            try { Thread.sleep(50); } catch (InterruptedException ie)
            { Thread.currentThread().interrupt(); return false; }
        }
        String err = dispatcher.lastErrorMessage();
        if (err != null)
        {
            SwingUtilities.invokeLater(() -> testWalkStatus.setText(
                "step " + (idx + 1) + " — " + err));
            return false;
        }
        // Wait for the state change appropriate to the transport kind.
        Waypoint.TransportKind tk = wp.transportKind();
        final WorldPoint tile = wp.tile();
        final String verb = wp.verb();
        if (tk == Waypoint.TransportKind.OPEN)
        {
            // Door: the object id flips when state changes. Cap at 4s.
            return awaitDoorTransition(tile, verb, preObjectId, idx, 4000L);
        }
        else if (tk == Waypoint.TransportKind.CLIMB_UP || tk == Waypoint.TransportKind.CLIMB_DOWN)
        {
            // Climb: the player's plane changes. Cap at 6s.
            int target = prePlane == null ? -1
                : (tk == Waypoint.TransportKind.CLIMB_UP ? prePlane + 1 : prePlane - 1);
            return awaitPlaneChange(prePlane, target, idx, 6000L);
        }
        else
        {
            // Generic interact — we can't detect the state change in the
            // general case. Give the engine 1.5s to apply the action.
            try { Thread.sleep(1500); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
            return true;
        }
    }

    /** Poll the tile until either its matched-verb object id differs from
     *  {@code preId} (door flipped state) or the verb is no longer present
     *  (the menu option vanished — also an indication the door opened). */
    private boolean awaitDoorTransition(WorldPoint tile, String verb, int preId,
                                        int idx, long capMs)
    {
        TransportResolver tr = new TransportResolver(client);
        long timeout = System.currentTimeMillis() + capMs;
        while (System.currentTimeMillis() < timeout)
        {
            if (Thread.currentThread().isInterrupted()) return false;
            try { Thread.sleep(120); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
            TransportResolver.Match cur = onClient(() -> tr.findTransport(tile, verb));
            if (cur == null) continue;
            if (!cur.isSuccess())
            {
                // Verb no longer matches anything on the tile — the door is
                // open (its current state advertises "Close" instead).
                return true;
            }
            if (cur.matchedObjectId() != preId)
            {
                // Object id flipped — definitively transitioned.
                return true;
            }
        }
        SwingUtilities.invokeLater(() -> testWalkStatus.setText(
            "step " + (idx + 1) + " — door did not open within " + (capMs / 1000) + "s"));
        return false;
    }

    /** Poll until the player's plane differs from {@code preplane}. If
     *  {@code expectedPlane} is non-negative, requires that exact plane;
     *  otherwise any change counts. */
    private boolean awaitPlaneChange(Integer prePlane, int expectedPlane, int idx, long capMs)
    {
        if (prePlane == null) return false;
        long timeout = System.currentTimeMillis() + capMs;
        while (System.currentTimeMillis() < timeout)
        {
            if (Thread.currentThread().isInterrupted()) return false;
            try { Thread.sleep(150); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
            Integer now = onClient(() -> {
                var pl = client.getLocalPlayer();
                return pl == null || pl.getWorldLocation() == null ? null
                    : pl.getWorldLocation().getPlane();
            });
            if (now == null) continue;
            if (expectedPlane >= 0 ? now == expectedPlane : !now.equals(prePlane)) return true;
        }
        SwingUtilities.invokeLater(() -> testWalkStatus.setText(
            "step " + (idx + 1) + " — plane did not change within " + (capMs / 1000) + "s"));
        return false;
    }

    /** Return a {@link WorldPoint} that is at most {@code maxDist} tiles
     *  along the straight line from {@code from} toward {@code to}. If the
     *  total distance is already &le; maxDist, returns {@code to}. Used to
     *  generate intermediate hops for routes longer than the minimap can
     *  reach in a single click. Plane is taken from {@code to}. */
    private static WorldPoint stepToward(WorldPoint from, WorldPoint to, int maxDist)
    {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        double mag = Math.hypot(dx, dy);
        if (mag <= maxDist) return to;
        double scale = maxDist / mag;
        return new WorldPoint(
            from.getX() + (int) Math.round(dx * scale),
            from.getY() + (int) Math.round(dy * scale),
            to.getPlane());
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

    // Note: legacy parseWaypoints / reachabilityHint helpers were superseded
    // by RouteParser + Waypoint. Removed to keep the panel focused on UI
    // wiring and route execution; see net.runelite.client.plugins.recorder
    // .transport.RouteParser for the line grammar.

    @SuppressWarnings("unused")
    private static java.util.List<WorldPoint> parseWaypoints(String text)
    {
        java.util.List<WorldPoint> out = new java.util.ArrayList<>();
        if (text == null) return out;
        for (String raw : text.split("\\R"))
        {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s*,\\s*");
            if (parts.length < 2) continue;
            try
            {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int plane = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
                out.add(new WorldPoint(x, y, plane));
            }
            catch (NumberFormatException ignored) { /* skip malformed */ }
        }
        return out;
    }

    private void refresh()
    {
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
    }

    /** Mirror the chicken loop's state into the panel labels. The loop runs
     *  on a daemon thread; the timer polls (~500ms) so any state change shows
     *  up promptly without us having to register a listener on the loop. */
    private void refreshCombat()
    {
        if (chickenLoop == null) return;
        ChickenCombatLoop.State st = chickenLoop.state();
        chickenStatusLabel.setText("Chicken loop: " + st.name().toLowerCase()
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
    // Mining section. Added at the end of the class so it doesn't conflict
    // with the parallel agent's edits to the Combat section above.
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

    private JComponent buildMining()
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
