package net.runelite.client.plugins.recorder.scripts;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
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
import net.runelite.client.game.ItemManager;
import net.runelite.client.sequence.activities.ge.BuyItemIntent;
import net.runelite.client.sequence.activities.ge.OfferWaitPolicy;
import net.runelite.client.sequence.activities.ge.PricePolicy;
import net.runelite.client.sequence.activities.ge.SellItemIntent;
import net.runelite.client.sequence.telemetry.TelemetryRecord;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.http.api.item.ItemPrice;

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
    private final ItemManager itemManager;

    private final JTextField itemField      = new JTextField("Abyssal whip", 12);
    private final JSpinner quantitySpinner  = new JSpinner(new SpinnerNumberModel(1, 1, 100_000, 1));
    private final JSpinner priceSpinner     = new JSpinner(new SpinnerNumberModel(1_500_000, 1, Integer.MAX_VALUE, 1000));
    private final JSpinner waitTicksSpinner = new JSpinner(new SpinnerNumberModel(300, 0, 100_000, 30));
    private final JCheckBox acceptPartial   = new JCheckBox("accept partial fill on timeout", true);
    private final JCheckBox bankPrep        = new JCheckBox("Prepare from bank first");
    private final JButton buyBtn            = new JButton("Buy Core");
    private final JButton sellBtn           = new JButton("Sell Core");
    private final JButton stopBtn           = new JButton("Stop");
    private final JTextArea statusArea      = new JTextArea("idle");
    private final JTextArea telemetryArea   = new JTextArea(10, 18);
    private final Timer refreshTimer;

    public GrandExchangeTab(GrandExchangeScript script, ItemManager itemManager) {
        this.script = script;
        this.itemManager = itemManager;
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setLayout(new BorderLayout(0, 6));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        if (script.bankPrepAvailable()) {
            bankPrep.setEnabled(true);
            bankPrep.setToolTipText("Open a bank booth at the GE and withdraw coins (buy) or items (sell) before placing the offer.");
        } else {
            bankPrep.setEnabled(false);
            bankPrep.setToolTipText("Available after sequence banking proof lands.");
        }

        buyBtn.addActionListener(e -> onBuy());
        sellBtn.addActionListener(e -> onSell());
        stopBtn.addActionListener(e -> onStop());

        // Status renders as a wrapping text area so a long step name (e.g.
        // "running: buy-with-prep 5x Pot of flour @ 55") wraps in place
        // instead of pushing the panel wider than the 225px sidebar.
        statusArea.setLineWrap(true);
        statusArea.setWrapStyleWord(true);
        statusArea.setEditable(false);
        statusArea.setFocusable(false);
        statusArea.setOpaque(false);
        statusArea.setBackground(ColorScheme.DARK_GRAY_COLOR);
        statusArea.setForeground(new JLabel().getForeground());
        statusArea.setFont(new JLabel().getFont());
        statusArea.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

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
        boolean phaseB = script != null && script.bankPrepAvailable();
        p.setBorder(BorderFactory.createTitledBorder(
            phaseB ? "GE Core (Phase B)" : "GE Core (Phase A)"));
        String hint = phaseB
            ? "Be at the Grand Exchange. If \"Prepare from bank first\" is "
                + "checked, the bot opens a bank booth and withdraws coins "
                + "(buy) or items (sell); otherwise inventory must already "
                + "hold them."
            : "Be at the Grand Exchange with coins (buy) or items (sell) "
                + "in inventory. Bank-prep is Phase B.";
        p.add(wrappedNote(hint));
        return p;
    }

    private JTextArea wrappedNote(String text) {
        JTextArea note = new JTextArea(text);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setEditable(false);
        note.setFocusable(false);
        note.setOpaque(false);
        note.setBackground(ColorScheme.DARK_GRAY_COLOR);
        note.setForeground(new JLabel().getForeground());
        note.setFont(new JLabel().getFont());
        note.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        return note;
    }

    private JPanel buildBody() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        GridBagConstraints lc = new GridBagConstraints();
        lc.insets = new Insets(2, 4, 2, 4);
        lc.anchor = GridBagConstraints.WEST;
        lc.gridx = 0; lc.weightx = 0;
        GridBagConstraints fc = new GridBagConstraints();
        fc.insets = new Insets(2, 4, 2, 4);
        fc.fill = GridBagConstraints.HORIZONTAL;
        fc.gridx = 1; fc.weightx = 1;

        int row = 0;
        itemField.setToolTipText("Type the item name (e.g. \"abyssal whip\") or its numeric id (e.g. 4151).");
        addRow(p, lc, fc, row++, "Item:", itemField);
        addRow(p, lc, fc, row++, "Quantity:", quantitySpinner);
        addRow(p, lc, fc, row++, "Price each:", priceSpinner);
        addRow(p, lc, fc, row++, "Wait (ticks):", waitTicksSpinner);

        GridBagConstraints full = new GridBagConstraints();
        full.gridx = 0; full.gridwidth = 2;
        full.insets = new Insets(2, 4, 2, 4);
        full.fill = GridBagConstraints.HORIZONTAL;
        full.weightx = 1;
        full.gridy = row++; p.add(acceptPartial, full);
        full.gridy = row++; p.add(bankPrep, full);

        // Buttons stacked vertically — sidebar is too narrow for side-by-side.
        JPanel btnStack = new JPanel();
        btnStack.setLayout(new BoxLayout(btnStack, BoxLayout.Y_AXIS));
        btnStack.setOpaque(false);
        for (JButton b : new JButton[]{buyBtn, sellBtn, stopBtn}) {
            b.setAlignmentX(Component.CENTER_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
            btnStack.add(b);
            btnStack.add(Box.createVerticalStrut(4));
        }
        full.gridy = row++; p.add(btnStack, full);

        full.gridy = row++; p.add(statusArea, full);

        // Soak up remaining vertical space so rows don't stretch.
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 0; filler.gridwidth = 2; filler.gridy = row;
        filler.weighty = 1; filler.fill = GridBagConstraints.VERTICAL;
        p.add(Box.createVerticalGlue(), filler);

        return p;
    }

    private static void addRow(JPanel p, GridBagConstraints lc, GridBagConstraints fc,
                               int row, String label, Component field) {
        lc.gridy = row; p.add(new JLabel(label), lc);
        fc.gridy = row; p.add(field, fc);
    }

    private JPanel buildFooter() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(ColorScheme.DARK_GRAY_COLOR);
        p.setBorder(BorderFactory.createTitledBorder("Telemetry (last 8)"));
        telemetryArea.setEditable(false);
        telemetryArea.setLineWrap(true);
        telemetryArea.setWrapStyleWord(true);
        // Keep within sidebar width so the outer scroll pane never shows a
        // horizontal bar. The telemetry pane scrolls vertically itself.
        int innerWidth = PluginPanel.PANEL_WIDTH - 24;
        JScrollPane scroll = new JScrollPane(telemetryArea);
        scroll.setPreferredSize(new Dimension(innerWidth, 160));
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    private void onBuy() {
        try {
            BuyItemIntent intent = buildBuyIntent();
            if (script == null) { setStatus("script not wired", null); return; }
            boolean ok = bankPrep.isSelected()
                ? script.startBuyWithPrep(intent)
                : script.startBuy(intent);
            setStatus(ok ? script.status() : ("rejected: " + script.status()), null);
        } catch (IllegalArgumentException ex) {
            setStatus("error: " + ex.getMessage(), ex.getMessage());
        } catch (Exception ex) {
            setStatus("error: " + ex.getMessage(), ex.getMessage());
            log.warn("ge-tab: onBuy", ex);
        }
    }

    private void onSell() {
        try {
            SellItemIntent intent = buildSellIntent();
            if (script == null) { setStatus("script not wired", null); return; }
            boolean ok = bankPrep.isSelected()
                ? script.startSellWithPrep(intent)
                : script.startSell(intent);
            setStatus(ok ? script.status() : ("rejected: " + script.status()), null);
        } catch (IllegalArgumentException ex) {
            setStatus("error: " + ex.getMessage(), ex.getMessage());
        } catch (Exception ex) {
            setStatus("error: " + ex.getMessage(), ex.getMessage());
            log.warn("ge-tab: onSell", ex);
        }
    }

    private void onStop() {
        if (script == null) { setStatus("script not wired", null); return; }
        script.stop();
        setStatus(script.status(), null);
    }

    /** Default buy-limit-cap stall detection window. ~18s (30 ticks * 600ms)
     *  of no completedQuantity progress is conservative enough that we
     *  won't false-positive on bursty fills, but short enough that we
     *  don't wait forever on a 4-hour buy-limit cap. */
    private static final int DEFAULT_STALL_TICKS = 30;

    private BuyItemIntent buildBuyIntent() {
        Resolved r = resolveItem(itemField.getText());
        int qty = (Integer) quantitySpinner.getValue();
        int price = (Integer) priceSpinner.getValue();
        int waitTicks = (Integer) waitTicksSpinner.getValue();
        // Stall detection is ALWAYS on — when the OSRS buy-limit caps a
        // fill mid-offer (e.g. asked for 200 of an item, got 160, no
        // more for 4h), we collect what we have and move on instead of
        // hanging until the limit window resets. The acceptPartial
        // checkbox additionally controls timeout-based partial accept.
        OfferWaitPolicy waitPolicy = acceptPartial.isSelected()
            ? OfferWaitPolicy.untilOrPartialStall(waitTicks, DEFAULT_STALL_TICKS)
            : new OfferWaitPolicy(waitTicks, false, DEFAULT_STALL_TICKS);
        return new BuyItemIntent(r.id, r.name, qty, new PricePolicy.Exact(price), waitPolicy);
    }

    private SellItemIntent buildSellIntent() {
        Resolved r = resolveItem(itemField.getText());
        int qty = (Integer) quantitySpinner.getValue();
        int price = (Integer) priceSpinner.getValue();
        int waitTicks = (Integer) waitTicksSpinner.getValue();
        OfferWaitPolicy waitPolicy = acceptPartial.isSelected()
            ? OfferWaitPolicy.untilOrPartialStall(waitTicks, DEFAULT_STALL_TICKS)
            : new OfferWaitPolicy(waitTicks, false, DEFAULT_STALL_TICKS);
        return new SellItemIntent(r.id, r.name, qty, new PricePolicy.Exact(price), waitPolicy);
    }

    /** Accept either a name or a numeric id and produce both. For numeric
     *  input we leave {@code name} as a placeholder; {@link
     *  net.runelite.client.sequence.activities.ge.GeInteraction#selectItem}
     *  resolves the canonical name on the client thread before typing. */
    private Resolved resolveItem(String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) {
            throw new IllegalArgumentException("enter an item name or id");
        }
        try {
            int id = Integer.parseInt(input);
            if (id <= 0) throw new IllegalArgumentException("item id must be > 0");
            return new Resolved(id, "item#" + id);
        } catch (NumberFormatException ignored) {
            // fall through to name search
        }
        if (itemManager == null) {
            throw new IllegalArgumentException("item lookup unavailable; enter a numeric id");
        }
        List<ItemPrice> matches = itemManager.search(input);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("no tradeable item matches \"" + input + "\"");
        }
        ItemPrice exact = null, shortest = null;
        for (ItemPrice ip : matches) {
            if (ip.getName().equalsIgnoreCase(input)) { exact = ip; break; }
            if (shortest == null || ip.getName().length() < shortest.getName().length()) {
                shortest = ip;
            }
        }
        ItemPrice pick = exact != null ? exact : shortest;
        return new Resolved(pick.getId(), pick.getName());
    }

    private record Resolved(int id, String name) {}

    private void setStatus(String text, String tooltip) {
        statusArea.setText(text);
        statusArea.setToolTipText(tooltip);
    }

    private void refresh() {
        if (script == null) return;
        SwingUtilities.invokeLater(() -> {
            statusArea.setText(script.status());

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
