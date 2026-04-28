package net.runelite.client.plugins.recorder.annotator;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.recorder.transport.RouteParser;

/**
 * Top-level Routes view: scans the route files on disk and lists each as
 * a row showing name, waypoint count, and a FARM badge when the file is
 * the one the farm bot loads at startup. Single-click selects; double-
 * click invokes the drilldown callback.
 */
@Slf4j
public final class RoutesTab extends JPanel
{
    public static final Path ROUTES_DIR = Paths.get(
        System.getProperty("user.home"),
        ".runelite", "sequencer", "routes");

    public static final Path FARM_ROUTE_FILE = ROUTES_DIR.resolve("lumbridge_bank_to_pen.txt");

    public static final class RouteEntry
    {
        public final Path path;
        public final String name;
        public final int waypointCount;
        public final boolean isFarmRoute;
        public final boolean hasErrors;

        RouteEntry(Path path, int waypointCount, boolean hasErrors)
        {
            this.path = path;
            this.name = stripExt(path.getFileName().toString());
            this.waypointCount = waypointCount;
            this.isFarmRoute = path.equals(FARM_ROUTE_FILE);
            this.hasErrors = hasErrors;
        }

        private static String stripExt(String f)
        {
            return f.endsWith(".txt") ? f.substring(0, f.length() - 4) : f;
        }

        @Override public String toString()
        {
            return (isFarmRoute ? "[FARM] " : "")
                + name
                + " (" + waypointCount + " wp"
                + (hasErrors ? ", parse errors" : "")
                + ")";
        }
    }

    private final DefaultListModel<RouteEntry> model = new DefaultListModel<>();
    private final JList<RouteEntry> list = new JList<>(model);
    private final Consumer<Path> onDrilldown;

    public RoutesTab(Consumer<Path> onDrilldown)
    {
        this.onDrilldown = onDrilldown;
        setLayout(new BorderLayout(0, 4));

        JPanel toolbarTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton newBtn = new JButton("+ New Route");
        newBtn.addActionListener(e -> onNew());
        toolbarTop.add(newBtn);
        add(toolbarTop, BorderLayout.NORTH);

        list.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseClicked(MouseEvent e)
            {
                if (e.getClickCount() == 2)
                {
                    RouteEntry sel = list.getSelectedValue();
                    if (sel != null) onDrilldown.accept(sel.path);
                }
            }
        });
        add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel toolbarBot = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton openBtn = new JButton("Open");
        openBtn.addActionListener(e -> {
            RouteEntry sel = list.getSelectedValue();
            if (sel != null) onDrilldown.accept(sel.path);
        });
        JButton renameBtn = new JButton("Rename");
        renameBtn.addActionListener(e -> onRename());
        JButton deleteBtn = new JButton("Delete");
        deleteBtn.addActionListener(e -> onDelete());
        toolbarBot.add(openBtn);
        toolbarBot.add(renameBtn);
        toolbarBot.add(deleteBtn);
        add(toolbarBot, BorderLayout.SOUTH);

        refresh();
    }

    /** Re-scan {@link #ROUTES_DIR} and reload the list. Safe to call from
     *  the EDT after every save / delete. */
    public void refresh()
    {
        model.clear();
        try
        {
            if (!Files.isDirectory(ROUTES_DIR))
            {
                Files.createDirectories(ROUTES_DIR);
                return;
            }
            List<RouteEntry> entries = new ArrayList<>();
            try (java.util.stream.Stream<Path> stream = Files.list(ROUTES_DIR))
            {
                stream.filter(p -> p.toString().endsWith(".txt"))
                    .forEach(p -> {
                        try
                        {
                            String text = Files.readString(p);
                            RouteParser.Result r = RouteParser.parse(text);
                            int n = r.waypoints().size();
                            entries.add(new RouteEntry(p, n, r.hasErrors()));
                        }
                        catch (IOException ioe)
                        {
                            log.warn("read failed: {}: {}", p, ioe.getMessage());
                            entries.add(new RouteEntry(p, 0, true));
                        }
                    });
            }
            entries.sort(Comparator
                .<RouteEntry, Boolean>comparing(e -> !e.isFarmRoute) // FARM first
                .thenComparing(e -> e.name));
            for (RouteEntry e : entries) model.addElement(e);
        }
        catch (IOException ioe)
        {
            log.warn("routes scan failed: {}", ioe.getMessage());
        }
    }

    private void onNew()
    {
        String name = JOptionPane.showInputDialog(this,
            "New route name (no extension)", "New Route", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        String safe = name.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
        Path target = ROUTES_DIR.resolve(safe + ".txt");
        try
        {
            Files.createDirectories(ROUTES_DIR);
            if (Files.exists(target))
            {
                JOptionPane.showMessageDialog(this, "Route already exists: " + safe);
                return;
            }
            Files.writeString(target, "# " + safe + "\n");
            refresh();
            onDrilldown.accept(target);
        }
        catch (IOException ioe)
        {
            JOptionPane.showMessageDialog(this, "Create failed: " + ioe.getMessage());
        }
    }

    private void onRename()
    {
        RouteEntry sel = list.getSelectedValue();
        if (sel == null) return;
        String name = JOptionPane.showInputDialog(this,
            "Rename to (no extension)", sel.name);
        if (name == null || name.isBlank()) return;
        String safe = name.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
        Path target = ROUTES_DIR.resolve(safe + ".txt");
        if (target.equals(sel.path)) return;
        try
        {
            Files.move(sel.path, target);
            refresh();
        }
        catch (IOException ioe)
        {
            JOptionPane.showMessageDialog(this, "Rename failed: " + ioe.getMessage());
        }
    }

    private void onDelete()
    {
        RouteEntry sel = list.getSelectedValue();
        if (sel == null) return;
        int answer = JOptionPane.showConfirmDialog(this,
            "Delete route '" + sel.name + "'? This cannot be undone.",
            "Delete Route",
            JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return;
        try
        {
            Files.delete(sel.path);
            refresh();
        }
        catch (IOException ioe)
        {
            JOptionPane.showMessageDialog(this, "Delete failed: " + ioe.getMessage());
        }
    }
}
