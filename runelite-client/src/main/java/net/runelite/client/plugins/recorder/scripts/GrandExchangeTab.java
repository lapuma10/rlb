package net.runelite.client.plugins.recorder.scripts;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.sequence.activities.ge.BuyItemIntent;
import net.runelite.client.sequence.activities.ge.OfferWaitPolicy;
import net.runelite.client.sequence.activities.ge.PricePolicy;
import net.runelite.client.sequence.activities.ge.SellItemIntent;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import net.runelite.client.ui.ColorScheme;

/**
 * "GE Core Mode" tab for the recorder side panel.
 *
 * <p>Pre-flight note: requires the player to already be at the Grand
 * Exchange with coins (for buy) or sell items (for sell) in inventory.
 * Bank-prep is Phase B (disabled checkbox until then).
 */
@Slf4j
public final class GrandExchangeTab extends JPanel {

    private final GrandExchangeScript script;

    private final JTextField itemIdField    = new JTextField("4151", 8);
    private final JTextField itemNameField  = new JTextField("Abyssal whip", 16);
    private final JSpinner quantitySpinner  = new JSpinner(new SpinnerNumberModel(1, 1, 100_000, 1));
    private final JSpinner priceSpinner     = new JSpinner(new SpinnerNumberModel(1_500_000, 1, Integer.MAX_VALUE, 1000));
    private final JSpinner waitTicksSpinner = new JSpinner(new SpinnerNumberModel(300, 0, 100_000, 30));
    private final JCheckBox acceptPartial   = new JCheckBox("accept partial fill on timeout", true);
    private final JCheckBox bankPrep        = new JCheckBox("Prepare from bank first");
    private final JButton buyBtn            = new JButton("Buy Core");
    private final JButton sellBtn           = new JButton("Sell Core");
    private final JButton stopBtn           = new JButton("Stop");
    private final JLabel statusLabel        = new JLabel("idle");
    private final JTextArea telemetryArea   = new JTextArea(10, 40);
    private final Timer refreshTimer;

    public GrandExchangeTab(GrandExchangeScript script) {
        this.script = script;
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        bankPrep.setEnabled(false);
        bankPrep.setToolTipText("Available after sequence banking proof lands.");

        buyBtn.addActionListener(e -> onBuy());
        sellBtn.addActionListener(e -> onSell());
        stopBtn.addActionListener(e -> onStop());

        refreshTimer = new Timer(1000, e -> refresh());
        refreshTimer.start();
    }

    public void dispose() {
        if (refreshTimer != null) refreshTimer.stop();
    }

    private JPanel buildHeader() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(BorderFactory.createTitledBorder("GE Core Mode (Phase A)"));
        JLabel hint = new JLabel("<html>Requires you to already be at the Grand Exchange "
            + "and have coins (for buy) or items (for sell) in inventory.<br>"
            + "Bank-prep is Phase B and currently disabled.</html>");
        p.add(hint);
        return p;
    }

    private JPanel buildBody() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridy = 0;
        c.gridx = 0; p.add(new JLabel("Item id:"), c);
        c.gridx = 1; p.add(itemIdField, c);
        c.gridx = 2; p.add(new JLabel("Name (optional):"), c);
        c.gridx = 3; p.add(itemNameField, c);

        c.gridy = 1;
        c.gridx = 0; p.add(new JLabel("Quantity:"), c);
        c.gridx = 1; p.add(quantitySpinner, c);
        c.gridx = 2; p.add(new JLabel("Price each:"), c);
        c.gridx = 3; p.add(priceSpinner, c);

        c.gridy = 2;
        c.gridx = 0; p.add(new JLabel("Wait (ticks):"), c);
        c.gridx = 1; p.add(waitTicksSpinner, c);
        c.gridx = 2; c.gridwidth = 2; p.add(acceptPartial, c); c.gridwidth = 1;

        c.gridy = 3;
        c.gridx = 0; c.gridwidth = 4; p.add(bankPrep, c); c.gridwidth = 1;

        c.gridy = 4;
        c.gridx = 0; p.add(buyBtn, c);
        c.gridx = 1; p.add(sellBtn, c);
        c.gridx = 2; p.add(stopBtn, c);

        c.gridy = 5;
        c.gridx = 0; c.gridwidth = 4; p.add(statusLabel, c); c.gridwidth = 1;

        return p;
    }

    private JPanel buildFooter() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(BorderFactory.createTitledBorder("Telemetry (last 8)"));
        telemetryArea.setEditable(false);
        telemetryArea.setLineWrap(true);
        telemetryArea.setWrapStyleWord(true);
        telemetryArea.setPreferredSize(new Dimension(360, 160));
        p.add(new JScrollPane(telemetryArea), BorderLayout.CENTER);
        return p;
    }

    private void onBuy() {
        try {
            BuyItemIntent intent = buildBuyIntent();
            if (script == null) { statusLabel.setText("script not wired"); return; }
            boolean ok = script.startBuy(intent);
            statusLabel.setText(ok ? script.status() : ("rejected: " + script.status()));
        } catch (Exception ex) {
            statusLabel.setText("error: " + ex.getMessage());
            log.warn("ge-tab: onBuy", ex);
        }
    }

    private void onSell() {
        try {
            SellItemIntent intent = buildSellIntent();
            if (script == null) { statusLabel.setText("script not wired"); return; }
            boolean ok = script.startSell(intent);
            statusLabel.setText(ok ? script.status() : ("rejected: " + script.status()));
        } catch (Exception ex) {
            statusLabel.setText("error: " + ex.getMessage());
            log.warn("ge-tab: onSell", ex);
        }
    }

    private void onStop() {
        if (script == null) { statusLabel.setText("script not wired"); return; }
        script.stop();
        statusLabel.setText(script.status());
    }

    private BuyItemIntent buildBuyIntent() {
        int itemId = parseInt(itemIdField.getText(), "itemId");
        String name = itemNameField.getText().trim();
        if (name.isEmpty()) name = "item#" + itemId;
        int qty = (Integer) quantitySpinner.getValue();
        int price = (Integer) priceSpinner.getValue();
        int waitTicks = (Integer) waitTicksSpinner.getValue();
        OfferWaitPolicy waitPolicy = acceptPartial.isSelected()
            ? OfferWaitPolicy.untilOrPartial(waitTicks)
            : OfferWaitPolicy.until(waitTicks);
        return new BuyItemIntent(itemId, name, qty, new PricePolicy.Exact(price), waitPolicy);
    }

    private SellItemIntent buildSellIntent() {
        int itemId = parseInt(itemIdField.getText(), "itemId");
        String name = itemNameField.getText().trim();
        if (name.isEmpty()) name = "item#" + itemId;
        int qty = (Integer) quantitySpinner.getValue();
        int price = (Integer) priceSpinner.getValue();
        int waitTicks = (Integer) waitTicksSpinner.getValue();
        OfferWaitPolicy waitPolicy = acceptPartial.isSelected()
            ? OfferWaitPolicy.untilOrPartial(waitTicks)
            : OfferWaitPolicy.until(waitTicks);
        return new SellItemIntent(itemId, name, qty, new PricePolicy.Exact(price), waitPolicy);
    }

    private static int parseInt(String s, String field) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(field + " must be a number, got: '" + s + "'");
        }
    }

    private void refresh() {
        if (script == null) return;
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(script.status());

            StringBuilder sb = new StringBuilder();
            for (TelemetryRecord r : script.recentTelemetry()) {
                sb.append("t=").append(r.tick())
                  .append(" d=").append(r.frameDepth())
                  .append(" ").append(r.stepName())
                  .append(" ").append(r.event());
                if (r.payload() != null && !r.payload().isEmpty()) {
                    sb.append(" — ").append(r.payload());
                }
                sb.append('\n');
            }
            String text = sb.toString();
            if (!text.equals(telemetryArea.getText())) {
                telemetryArea.setText(text);
                telemetryArea.setCaretPosition(0);
            }
        });
    }
}
