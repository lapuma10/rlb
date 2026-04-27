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
package net.runelite.client.plugins.sequencer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.sequence.SequenceManager;
import net.runelite.client.sequence.SequenceState;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.activities.StepFactory;
import net.runelite.client.sequence.activities.StepParam;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
public final class SequencerPanel extends PluginPanel {

    private final SequenceManager manager;

    private final JLabel stateLabel = new JLabel("State: IDLE");
    private final JLabel activeLabel = new JLabel("Active: —");
    private final DefaultListModel<StepRow> stepListModel = new DefaultListModel<>();
    private final JList<StepRow> stepList = new JList<>(stepListModel);
    private final DefaultListModel<String> logModel = new DefaultListModel<>();
    private final JList<String> logList = new JList<>(logModel);

    private final DefaultListModel<StepRow> reactiveListModel = new DefaultListModel<>();
    private final JList<StepRow> reactiveList = new JList<>(reactiveListModel);

    private final JButton runBtn = new JButton("Run");
    private final JButton pauseBtn = new JButton("Pause");
    private final JButton stopBtn = new JButton("Stop");
    private final JButton addBtn = new JButton("+ Add step");
    private final JButton addReactiveBtn = new JButton("+ Add reactive");

    private final Consumer<TelemetryRecord> telemetryListener = this::onTelemetry;
    private Timer refreshTimer;

    public SequencerPanel(SequenceManager manager) {
        super(false);
        this.manager = manager;
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        add(buildStatusHeader());
        add(Box.createVerticalStrut(6));
        add(buildSequenceSection());
        add(Box.createVerticalStrut(6));
        add(buildReactiveSection());
        add(Box.createVerticalStrut(6));
        add(buildControls());
        add(Box.createVerticalStrut(6));
        add(buildLog());

        runBtn.addActionListener(e -> onRun());
        pauseBtn.addActionListener(e -> onPauseToggle());
        stopBtn.addActionListener(e -> onStop());
        addBtn.addActionListener(e -> onAddStep(stepListModel, false));
        addReactiveBtn.addActionListener(e -> onAddStep(reactiveListModel, true));

        manager.subscribe(telemetryListener);

        refreshTimer = new Timer(500, e -> refreshStatus());
        refreshTimer.start();
    }

    /** Called by the plugin on shutDown so the panel doesn't leak its listener
     *  or its periodic Swing Timer. After this the panel is unusable; create a
     *  fresh one on next startUp. */
    public void dispose() {
        if (refreshTimer != null) {
            refreshTimer.stop();
            refreshTimer = null;
        }
        manager.unsubscribe(telemetryListener);
    }

    private JComponent buildStatusHeader() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder("Status"));
        p.add(stateLabel);
        p.add(activeLabel);
        return p;
    }

    private JComponent buildSequenceSection() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder("Sequence"));
        stepList.setVisibleRowCount(6);
        p.add(new JScrollPane(stepList), BorderLayout.CENTER);
        p.add(addBtn, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildReactiveSection() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder("Reactive"));
        reactiveList.setVisibleRowCount(3);
        p.add(new JScrollPane(reactiveList), BorderLayout.CENTER);
        p.add(addReactiveBtn, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildControls() {
        JPanel p = new JPanel();
        p.add(runBtn);
        p.add(pauseBtn);
        p.add(stopBtn);
        return p;
    }

    private JComponent buildLog() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder("Telemetry"));
        logList.setVisibleRowCount(8);
        p.add(new JScrollPane(logList), BorderLayout.CENTER);
        return p;
    }

    private void onAddStep(DefaultListModel<StepRow> targetModel, boolean reactive) {
        List<StepFactory> factories = manager.getRegistry().all();
        if (factories.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No step factories registered.");
            return;
        }
        Object choice = JOptionPane.showInputDialog(
            this, "Pick a step type", "Add step",
            JOptionPane.PLAIN_MESSAGE, null,
            factories.stream().map(StepFactory::displayName).toArray(),
            factories.get(0).displayName());
        if (choice == null) return;
        StepFactory chosen = factories.stream()
            .filter(f -> f.displayName().equals(choice)).findFirst().orElseThrow();

        Map<String, Object> args = collectParamsViaForm(chosen);
        if (args == null) return;   // cancelled
        Step s = chosen.build(args);
        targetModel.addElement(new StepRow(s));
        if (reactive) manager.register(s);
    }

    /** Builds a single dialog with one input row per StepParam. Returns null on cancel. */
    private Map<String, Object> collectParamsViaForm(StepFactory factory) {
        List<StepParam> params = factory.params();
        if (params.isEmpty()) return new HashMap<>();
        JPanel form = new JPanel(new GridLayout(params.size(), 2, 4, 4));
        List<JTextField> fields = new java.util.ArrayList<>();
        for (StepParam p : params) {
            form.add(new JLabel(p.name() + " (" + p.type() + "):"));
            JTextField tf = new JTextField(String.valueOf(p.defaultValue()), 16);
            form.add(tf);
            fields.add(tf);
        }
        int result = JOptionPane.showConfirmDialog(this, form,
            "Configure " + factory.displayName(), JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return null;
        Map<String, Object> args = new HashMap<>();
        for (int i = 0; i < params.size(); i++) {
            args.put(params.get(i).name(), parseParam(params.get(i), fields.get(i).getText()));
        }
        return args;
    }

    private static Object parseParam(StepParam p, String raw) {
        return switch (p.type()) {
            case INT, ITEM_ID -> Integer.parseInt(raw.trim());
            case BOOLEAN -> Boolean.parseBoolean(raw.trim());
            case WORLD_POINT -> {
                String[] parts = raw.split(",");
                yield new net.runelite.api.coords.WorldPoint(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0);
            }
            default -> raw;
        };
    }

    private void onRun() {
        if (stepListModel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Add at least one step.");
            return;
        }
        LinearSequence seq = new LinearSequence("user");
        for (int i = 0; i < stepListModel.size(); i++) {
            seq.then(stepListModel.get(i).step());
        }
        manager.run(seq);
    }

    private void onPauseToggle() {
        if (manager.state() == SequenceState.PAUSED) {
            manager.resume();
            pauseBtn.setText("Pause");
        } else if (manager.state() == SequenceState.RUNNING) {
            manager.pause();
            pauseBtn.setText("Resume");
        }
    }

    private void onStop() { manager.stop(); pauseBtn.setText("Pause"); }

    private void refreshStatus() {
        SequenceState s = manager.state();
        stateLabel.setText("State: " + s);
    }

    private void onTelemetry(TelemetryRecord r) {
        SwingUtilities.invokeLater(() -> {
            logModel.add(0, String.format("[%d] %s %s %s", r.tick(), r.event(), r.stepName(), r.payload()));
            if (logModel.size() > 200) logModel.remove(logModel.size() - 1);
        });
    }

    private record StepRow(Step step) {
        @Override public String toString() { return step.name(); }
    }
}
