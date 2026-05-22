package net.runelite.client.plugins.recorder.agility;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript.RooftopCourseId;

@Slf4j
public final class AgilityCaptureTab extends JPanel
{
    private final AgilityCaptureSession session;
    private final ClientThread clientThread;

    // Form fields
    private final JComboBox<RooftopCourseId> courseCombo = new JComboBox<>(RooftopCourseId.values());
    private final JTextField labelField = new JTextField(20);
    private final JSpinner levelSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 99, 1));
    private final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(7, 1, 30, 1));

    // Buttons
    private final JButton startBtn   = new JButton("Start");
    private final JButton discardBtn = new JButton("Discard Lap");
    private final JButton saveBtn    = new JButton("Save");
    private final JButton cancelBtn  = new JButton("Cancel");

    // Status display
    private final JLabel promptLabel       = new JLabel(" ");                     // 5-second start prompt
    private final JLabel stateLabel        = new JLabel("Not started");
    private final JLabel lapProgressLabel  = new JLabel("Lap progress: -");
    private final JLabel matchingLapsLabel = new JLabel("Matching laps: 0/2");
    private final JLabel saveStatusLabel   = new JLabel(" ");
    private final JLabel approachLabel     = new JLabel("Approach tiles: -");
    private final JLabel startTilesLabel   = new JLabel("Start tiles: -");
    private final JLabel lapEndLabel       = new JLabel("Lap-end tile: -");
    private final JLabel validTilesLabel   = new JLabel("Valid tiles: -");

    // Obstacle rows — one JLabel per obstacle, rebuilt when expectedCount changes
    private final JPanel obstaclesPanel = new JPanel();
    private final List<JLabel> obstacleRows = new ArrayList<>();

    // Polling timer
    private final Timer refreshTimer = new Timer(500, this::onRefreshTick);

    // Start-prompt timer (5 second)
    private Timer startPromptTimer;

    /** Public hook for Task 14 to inject save-click behavior. */
    private Runnable saveAction = () -> log.warn("[agility-capture] save not wired");

    public AgilityCaptureTab(AgilityCaptureSession session, ClientThread clientThread)
    {
        this.session = session;
        this.clientThread = clientThread;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("Agility Capture"));

        add(buildForm());
        add(Box.createVerticalStrut(6));
        add(buildButtons());
        add(Box.createVerticalStrut(6));
        add(promptLabel);
        add(buildStatusGroup());
        add(Box.createVerticalStrut(6));
        add(buildObstaclesGroup());
        add(Box.createVerticalStrut(6));
        add(buildGeometryGroup());

        // Pre-fill defaults from the current dropdown selection.
        applyDefaultsFor((RooftopCourseId) courseCombo.getSelectedItem());

        courseCombo.addActionListener(this::onCourseChanged);
        startBtn.addActionListener(this::onStart);
        discardBtn.addActionListener(e -> session.discardCurrentLap());
        saveBtn.addActionListener(e -> saveAction.run());
        cancelBtn.addActionListener(this::onCancel);

        setControlsForState(false);     // not running yet
        refreshTimer.start();
    }

    public void setSaveAction(Runnable action)
    {
        this.saveAction = action;
    }

    // --- Build sections ---

    private JComponent buildForm()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(row("Course:", courseCombo));
        p.add(row("Label:",  labelField));
        p.add(row("Level:",  levelSpinner));
        p.add(row("Count:",  countSpinner));
        return p;
    }

    private JComponent buildButtons()
    {
        JPanel p = new JPanel(new GridLayout(1, 4, 4, 0));
        p.add(startBtn);
        p.add(discardBtn);
        p.add(saveBtn);
        p.add(cancelBtn);
        return p;
    }

    private JComponent buildStatusGroup()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Status"));
        p.add(stateLabel);
        p.add(lapProgressLabel);
        p.add(matchingLapsLabel);
        p.add(saveStatusLabel);
        return p;
    }

    private JComponent buildObstaclesGroup()
    {
        obstaclesPanel.setLayout(new BoxLayout(obstaclesPanel, BoxLayout.Y_AXIS));
        obstaclesPanel.setBorder(BorderFactory.createTitledBorder("Obstacles"));
        return obstaclesPanel;
    }

    private JComponent buildGeometryGroup()
    {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Geometry"));
        p.add(approachLabel);
        p.add(startTilesLabel);
        p.add(lapEndLabel);
        p.add(validTilesLabel);
        return p;
    }

    private static JPanel row(String text, Component field)
    {
        JPanel r = new JPanel(new BorderLayout(4, 0));
        r.add(new JLabel(text), BorderLayout.WEST);
        r.add(field, BorderLayout.CENTER);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height));
        return r;
    }

    // --- Event handlers ---

    private void onCourseChanged(ActionEvent e)
    {
        applyDefaultsFor((RooftopCourseId) courseCombo.getSelectedItem());
    }

    private void applyDefaultsFor(RooftopCourseId id)
    {
        RooftopCourseDefaults.Row row = RooftopCourseDefaults.ROWS.get(id);
        if (row == null) return;
        labelField.setText(row.label);
        levelSpinner.setValue(row.level);
        countSpinner.setValue(row.obstacleCount);
    }

    private void onStart(ActionEvent e)
    {
        RooftopCourseId id = (RooftopCourseId) courseCombo.getSelectedItem();
        String label = labelField.getText().trim();
        int level = (Integer) levelSpinner.getValue();
        int count = (Integer) countSpinner.getValue();

        // start() touches model + eventBus; safe on EDT (no client-thread state read).
        session.start(id, label, level, count);

        setControlsForState(true);
        showStartPrompt();
        rebuildObstacleRows(count);
    }

    private void onCancel(ActionEvent e)
    {
        session.cancel();
        setControlsForState(false);
        promptLabel.setText(" ");
        rebuildObstacleRows(0);
    }

    private void showStartPrompt()
    {
        promptLabel.setText("Stand at the course start. First successful obstacle click defines obstacle 0.");
        if (startPromptTimer != null) startPromptTimer.stop();
        startPromptTimer = new Timer(5000, ev -> promptLabel.setText(" "));
        startPromptTimer.setRepeats(false);
        startPromptTimer.start();
    }

    private void setControlsForState(boolean running)
    {
        courseCombo.setEnabled(!running);
        labelField.setEnabled(!running);
        levelSpinner.setEnabled(!running);
        countSpinner.setEnabled(!running);
        startBtn.setEnabled(!running);
        discardBtn.setEnabled(running);
        saveBtn.setEnabled(false);              // gated by checklist
        cancelBtn.setEnabled(running);
    }

    private void rebuildObstacleRows(int n)
    {
        obstaclesPanel.removeAll();
        obstacleRows.clear();
        for (int i = 0; i < n; i++)
        {
            JLabel row = new JLabel("obstacle " + i + "  —");
            obstacleRows.add(row);
            obstaclesPanel.add(row);
        }
        obstaclesPanel.revalidate();
        obstaclesPanel.repaint();
    }

    // --- Polling refresh (EDT) ---

    private void onRefreshTick(ActionEvent e)
    {
        if (!session.isActive())
        {
            return;     // nothing to display
        }

        CaptureModel m = session.getModel();
        if (m == null) return;

        stateLabel.setText("State: " + m.state);
        lapProgressLabel.setText("Lap progress: " + m.currentLapObs.size() + "/" + m.expectedObstacleCount);
        matchingLapsLabel.setText("Matching laps: " + m.cleanMatchingLaps + "/2");

        // Geometry
        approachLabel.setText("Approach tiles: " + m.approachTiles.size());
        startTilesLabel.setText("Start tiles: " + m.startTiles.size());
        lapEndLabel.setText("Lap-end tile: " + (m.lapEndTile == null ? "not yet" : m.lapEndTile.toString()));
        validTilesLabel.setText("Valid tiles: " + m.validTiles.size() + " (current lap: " + m.currentLapTiles.size() + ")");

        // Per-obstacle rows
        if (obstacleRows.size() != m.expectedObstacleCount)
        {
            rebuildObstacleRows(m.expectedObstacleCount);
        }
        for (int i = 0; i < obstacleRows.size(); i++)
        {
            String text;
            if (i < m.obstacles.size())
            {
                ObstacleObservation o = m.obstacles.get(i);
                text = "obstacle " + i
                    + "  " + (o.verbs.isEmpty() ? "?" : o.verbs.iterator().next())
                    + "  " + (o.objectIds.isEmpty() ? "?" : o.objectIds.iterator().next())
                    + "  stage:" + o.stageTiles.size()
                    + " obj:" + o.objectTiles.size()
                    + " succ:" + o.successTiles.size()
                    + " x" + o.successCount;
            }
            else if (i < m.currentLapObs.size())
            {
                ObstacleObservation o = m.currentLapObs.get(i);
                text = "obstacle " + i + "  [lap-buffer]  "
                    + (o.verbs.isEmpty() ? "?" : o.verbs.iterator().next());
            }
            else
            {
                text = "obstacle " + i + "  —";
            }
            obstacleRows.get(i).setText(text);
        }

        refreshSaveStatus(m);
    }

    /** Save-quality checklist per spec §14. Updates save button + save status label. */
    private void refreshSaveStatus(CaptureModel m)
    {
        String blocker = firstBlocker(m);
        if (blocker == null)
        {
            saveStatusLabel.setText("Save: Ready");
            saveBtn.setEnabled(true);
        }
        else
        {
            saveStatusLabel.setText("Save: " + blocker);
            saveBtn.setEnabled(false);
        }
    }

    /** Returns the first failing checklist item description, or null if all pass. */
    private static String firstBlocker(CaptureModel m)
    {
        // 1. cleanMatchingLaps >= 2
        if (m.cleanMatchingLaps < 2) return "need " + (2 - m.cleanMatchingLaps) + " more matching clean lap" + (2 - m.cleanMatchingLaps == 1 ? "" : "s");
        // 2. obstacles.size() == expectedObstacleCount
        if (m.obstacles.size() != m.expectedObstacleCount) return "obstacles " + m.obstacles.size() + "/" + m.expectedObstacleCount;
        // 3. lapEndTile != null
        if (m.lapEndTile == null) return "lapEndTile not captured";
        // 4. startTiles.size() >= 1
        if (m.startTiles.isEmpty()) return "no startTiles";
        // 5. approachTiles.size() >= 1
        if (m.approachTiles.isEmpty()) return "no approachTiles";
        // 6. per-obstacle invariants
        for (int i = 0; i < m.obstacles.size(); i++)
        {
            ObstacleObservation o = m.obstacles.get(i);
            boolean isFinal = (i == m.obstacles.size() - 1);
            if (o.objectIds.isEmpty())   return "obstacle " + i + " missing objectIds";
            if (o.verbs.isEmpty())       return "obstacle " + i + " missing verbs";
            if (o.stageTiles.isEmpty())  return "obstacle " + i + " missing stageTiles";
            if (o.objectTiles.isEmpty()) return "obstacle " + i + " missing objectTiles";
            if (!isFinal && o.successTiles.isEmpty()) return "obstacle " + i + " missing successTiles";
            if (o.successCount < 2)      return "obstacle " + i + " seen in only " + o.successCount + " clean lap(s)";
        }
        // 7. validTiles.containsAll(union of obstacles[*].stageTiles)
        Set<WorldPoint> union = new HashSet<>();
        for (ObstacleObservation o : m.obstacles) union.addAll(o.stageTiles);
        if (!m.validTiles.containsAll(union)) return "validTiles missing some stageTiles";
        // 8. pendingClick == null
        if (m.pendingClick != null) return "click pending — wait";
        return null;
    }
}
