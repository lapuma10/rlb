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
import net.runelite.client.plugins.recorder.combat.ChickenCombatLoop;
import net.runelite.client.plugins.recorder.debug.DebugOverlay;
import net.runelite.client.plugins.recorder.farm.ChickenFarmLoop;
import net.runelite.client.plugins.recorder.mining.MiningLoop;
import net.runelite.client.plugins.recorder.debug.TileMarker;
import net.runelite.client.plugins.recorder.events.Events;
import net.runelite.client.plugins.recorder.events.RecordedEvent;
import net.runelite.client.plugins.recorder.transport.RouteOverlay;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
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
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.io.IOException;
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
    private TransportResolver transportResolver;
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
    private RouteOverlay routeOverlay;
    private Timer refreshTimer;
    private final JButton chickenStartBtn = new JButton("Start chicken loop");
    private final JButton chickenStopBtn = new JButton("Stop");
    private final JLabel chickenStatusLabel = new JLabel("Chicken loop: idle");
    private final JLabel chickenKillsLabel = new JLabel("Kills: 0");
    private ChickenCombatLoop chickenLoop;
    private ChickenFarmLoop farmLoop;
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
    private final JTabbedPane tabs = new JTabbedPane();

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

        tabs.addTab("Routes", new JScrollPane(buildRoutesTab()));
        tabs.addTab("Combat", new JScrollPane(buildCombatTab()));
        tabs.addTab("Record", new JScrollPane(buildRecordTab()));
        tabs.addTab("Mining", new JScrollPane(buildMiningTab()));
        tabs.addTab("Login",  new JScrollPane(buildLoginTab()));
        add(tabs, BorderLayout.CENTER);

        recordBtn.addActionListener(this::onRecordToggle);
        chickenStartBtn.addActionListener(e -> onChickenStart());
        chickenStopBtn.addActionListener(e -> onChickenStop());
        miningStartBtn.addActionListener(e -> onMiningStart());
        miningStopBtn.addActionListener(e -> onMiningStop());
        miningAddRockBtn.addActionListener(e -> onMiningAddRock());
        miningClearRocksBtn.addActionListener(e -> onMiningClearRocks());
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
        return p;
    }

    // ------------------------------------------------------------------
    // Routes tab — placeholder for Task 11; debug tile-mark tools live here
    // until the WaypointEditor (Task 10) absorbs them.
    // ------------------------------------------------------------------

    private JPanel buildRoutesTab()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        // Placeholder label — wired in Task 11.
        p.add(new JLabel("Routes annotator — wired in Task 11"));
        p.add(Box.createVerticalStrut(8));

        // Debug + tile mark: compact testing surface — mark a tile, walk to
        // it, or clear. The WaypointEditor (Task 10) will absorb this into
        // its toolbar; until then the buttons live here.
        JPanel debug = new JPanel();
        debug.setLayout(new BoxLayout(debug, BoxLayout.Y_AXIS));
        debug.setBorder(BorderFactory.createTitledBorder("Debug + tile mark"));
        debug.add(markTileBtn);
        JPanel row = new JPanel(new BorderLayout(4, 4));
        row.add(walkToMarkBtn, BorderLayout.CENTER);
        row.add(clearMarkBtn, BorderLayout.EAST);
        debug.add(row);
        debug.add(markedLabel);
        p.add(debug);

        return p;
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

        credList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        credList.setVisibleRowCount(4);
        JScrollPane scroll = new JScrollPane(credList);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
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

    /** Wire the chicken combat loop. Called by the plugin during startUp once
     *  the dispatcher is constructed. The loop's status callback updates
     *  {@link #chickenStatusLabel} via the EDT. */
    public void setChickenLoop(ChickenCombatLoop loop) { this.chickenLoop = loop; }

    /** Wire the farm loop. Called by the plugin during startUp if the route
     *  file loads successfully; stays null (panel reports "unavailable") if
     *  the route file is missing. */
    public void setFarmLoop(ChickenFarmLoop fl) { this.farmLoop = fl; }

    private void onChickenStart()
    {
        if (farmLoop == null)
        {
            chickenStatusLabel.setText("Farm loop: unavailable (no route file?)");
            return;
        }
        farmLoop.start();
        chickenStatusLabel.setText("Farm loop: starting");
    }

    private void onChickenStop()
    {
        if (farmLoop == null) return;
        farmLoop.stop();
        chickenStatusLabel.setText("Farm loop: stopping");
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
     *  up promptly without us having to register a listener on the loop.
     *
     *  When the farm loop is available it takes precedence; the bare combat
     *  loop is kept as a fallback (null farm loop) so existing test workflows
     *  still work. */
    private void refreshCombat()
    {
        if (farmLoop != null)
        {
            ChickenFarmLoop.State st = farmLoop.state();
            chickenStatusLabel.setText("Farm: " + st.name().toLowerCase()
                + " — " + farmLoop.status());
            // killCount lives on the bare combat loop (the farm loop drives it
            // internally). Surface it if we still hold a reference.
            if (chickenLoop != null)
                chickenKillsLabel.setText("Kills: " + chickenLoop.killCount());
            chickenStartBtn.setEnabled(st == ChickenFarmLoop.State.IDLE
                || st == ChickenFarmLoop.State.ABORTED);
            chickenStopBtn.setEnabled(st != ChickenFarmLoop.State.IDLE
                && st != ChickenFarmLoop.State.ABORTED);
            return;
        }
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
