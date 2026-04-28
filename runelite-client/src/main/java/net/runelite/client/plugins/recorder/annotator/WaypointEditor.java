package net.runelite.client.plugins.recorder.annotator;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.transport.RouteOverlay;
import net.runelite.client.plugins.recorder.transport.RouteParser;
import net.runelite.client.plugins.recorder.transport.Waypoint;

/**
 * Drilldown view for one route. Shows the toolbar + waypoint list. Each
 * mutation rewrites the file on disk and pushes a snapshot to the
 * per-route undo stack. Selection state drives the route overlay's
 * highlight.
 */
@Slf4j
public final class WaypointEditor extends JPanel
{
    public interface Hooks
    {
        /** User clicked Mark area. Caller starts the AreaSelector with the
         *  given prepopulated set (empty for new) and invokes the callback
         *  on commit. */
        void onMarkArea(@Nullable Waypoint editing, Consumer<Set<WorldPoint>> onCommit);

        /** User clicked Mark object. Caller fires findAnyTransport and
         *  emits a TRANSPORT waypoint when the click resolves. */
        void onMarkObject(Consumer<Waypoint> onCommit);

        /** User clicked Add current. Caller appends the player's current
         *  tile as a single-tile WALK waypoint. */
        void onAddCurrent(Consumer<Waypoint> onCommit);

        /** User clicked Add marked. Caller appends the most-recently-
         *  marked tile as a single-tile WALK waypoint. */
        void onAddMarked(Consumer<Waypoint> onCommit);

        /** User clicked Walk path. Caller drives the walker over the
         *  current waypoint list. */
        void onWalkPath(List<Waypoint> waypoints);

        /** User clicked Walk to selected. Caller drives the walker over
         *  waypoints[0..selectedIdx] inclusive. */
        void onWalkToSelected(List<Waypoint> waypoints, int selectedIdx);
    }

    private final Path routeFile;
    private final RouteOverlay routeOverlay;
    private final Hooks hooks;
    private final UndoStack undo = new UndoStack(3);
    private final JPanel listColumn = new JPanel();
    private final JLabel headerLabel = new JLabel();
    private final JButton undoBtn = new JButton("Undo (0)");
    private final JTextField nameField = new JTextField(8);
    private List<Waypoint> waypoints = new ArrayList<>();
    private int selectedIdx = -1;

    public WaypointEditor(Path routeFile, RouteOverlay routeOverlay,
                          Runnable onBack, Hooks hooks)
    {
        this.routeFile = routeFile;
        this.routeOverlay = routeOverlay;
        this.hooks = hooks;
        setLayout(new BorderLayout(0, 4));

        // Top: back link + name + FARM badge
        JPanel header = new JPanel(new BorderLayout(4, 0));
        JButton backBtn = new JButton("← Back");
        backBtn.addActionListener(e -> onBack.run());
        header.add(backBtn, BorderLayout.WEST);
        boolean isFarm = routeFile.equals(RoutesTab.FARM_ROUTE_FILE);
        String farmTag = isFarm ? "  [FARM]" : "";
        String displayName = routeFile.getFileName().toString().replaceFirst("\\.txt$", "");
        headerLabel.setText(displayName + farmTag);
        header.add(headerLabel, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        // Toolbar (above the waypoint list)
        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));

        JPanel rowName = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        rowName.add(new JLabel("Name:"));
        rowName.add(nameField);
        toolbar.add(rowName);

        JPanel rowMark = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton markAreaBtn = new JButton("Mark area");
        markAreaBtn.addActionListener(e -> doMarkArea(null));
        JButton markObjectBtn = new JButton("Mark object");
        markObjectBtn.addActionListener(e -> doMarkObject());
        rowMark.add(markAreaBtn);
        rowMark.add(markObjectBtn);
        toolbar.add(rowMark);

        JPanel rowAdd = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addCurBtn = new JButton("Add current");
        addCurBtn.addActionListener(e -> doAddCurrent());
        JButton addMarkBtn = new JButton("Add marked");
        addMarkBtn.addActionListener(e -> doAddMarked());
        rowAdd.add(addCurBtn);
        rowAdd.add(addMarkBtn);
        toolbar.add(rowAdd);

        JPanel rowWalk = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton walkBtn = new JButton("Walk path");
        walkBtn.addActionListener(e -> hooks.onWalkPath(List.copyOf(waypoints)));
        JButton walkToBtn = new JButton("Walk to selected");
        walkToBtn.addActionListener(e -> {
            if (selectedIdx >= 0) hooks.onWalkToSelected(List.copyOf(waypoints), selectedIdx);
        });
        rowWalk.add(walkBtn);
        rowWalk.add(walkToBtn);
        rowWalk.add(undoBtn);
        undoBtn.addActionListener(e -> doUndo());
        toolbar.add(rowWalk);

        add(toolbar, BorderLayout.PAGE_START);

        listColumn.setLayout(new BoxLayout(listColumn, BoxLayout.Y_AXIS));
        add(new JScrollPane(listColumn), BorderLayout.CENTER);

        load();
    }

    public Path routeFile() { return routeFile; }

    private void load()
    {
        try
        {
            selectedIdx = -1;
            routeOverlay.setSelected(null);
            String text = Files.exists(routeFile) ? Files.readString(routeFile) : "";
            RouteParser.Result r = RouteParser.parse(text);
            this.waypoints = new ArrayList<>(r.waypoints());
            renderList();
            routeOverlay.setRoute(this.waypoints);
        }
        catch (IOException ioe)
        {
            log.warn("load failed: {}: {}", routeFile, ioe.getMessage());
        }
    }

    private void renderList()
    {
        listColumn.removeAll();
        for (int i = 0; i < waypoints.size(); i++)
        {
            int idx = i;
            Waypoint wp = waypoints.get(i);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
            row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                Color.DARK_GRAY));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            JLabel idxLabel = new JLabel("#" + i);
            idxLabel.setPreferredSize(new Dimension(24, 18));
            row.add(idxLabel);
            JLabel nameLabel = new JLabel(wp.name() == null ? "(unnamed)" : wp.name());
            row.add(nameLabel);
            row.add(new JLabel(verbSummary(wp)));
            JButton up = new JButton("▲");
            JButton down = new JButton("▼");
            JButton edit = new JButton("✎");
            JButton del = new JButton("×");
            up.setEnabled(i > 0);
            down.setEnabled(i < waypoints.size() - 1);
            up.addActionListener(e -> doMove(idx, idx - 1));
            down.addActionListener(e -> doMove(idx, idx + 1));
            edit.addActionListener(e -> doEditBounds(idx));
            del.addActionListener(e -> doDelete(idx));
            row.add(up);
            row.add(down);
            row.add(edit);
            row.add(del);
            // Single-click row → select. Double-click name label → rename inline.
            row.addMouseListener(new MouseAdapter()
            {
                @Override public void mouseClicked(MouseEvent e)
                {
                    if (e.getClickCount() == 1) doSelect(idx);
                }
            });
            nameLabel.addMouseListener(new MouseAdapter()
            {
                @Override public void mouseClicked(MouseEvent e)
                {
                    if (e.getClickCount() == 2) doRename(idx);
                }
            });
            if (idx == selectedIdx)
            {
                row.setBackground(new Color(60, 80, 110));
            }
            listColumn.add(row);
        }
        listColumn.revalidate();
        listColumn.repaint();
        undoBtn.setText("Undo (" + undo.size() + ")");
        undoBtn.setEnabled(undo.size() > 0);
    }

    private String verbSummary(Waypoint wp)
    {
        switch (wp.kind())
        {
            case WALK_AREA:
            {
                if (wp.isRectangular())
                {
                    var a = wp.area();
                    return "walkbox " + a.getWidth() + "×" + a.getHeight();
                }
                return "walktiles ×" + wp.tiles().size();
            }
            case WALK: return "walk";
            case TRANSPORT:
                return wp.transportKind().name().toLowerCase().replace('_', '-');
            default: return "?";
        }
    }

    private void doSelect(int idx)
    {
        selectedIdx = idx;
        routeOverlay.setSelected(waypoints.get(idx));
        renderList();
    }

    private void doMove(int from, int to)
    {
        if (to < 0 || to >= waypoints.size() || from == to) return;
        snapshot();
        Waypoint w = waypoints.remove(from);
        waypoints.add(to, w);
        if (selectedIdx == from) selectedIdx = to;
        else if (from < selectedIdx && to >= selectedIdx) selectedIdx--;
        else if (from > selectedIdx && to <= selectedIdx) selectedIdx++;
        save();
        renderList();
    }

    private void doDelete(int idx)
    {
        snapshot();
        waypoints.remove(idx);
        if (selectedIdx == idx)
        {
            selectedIdx = -1;
            routeOverlay.setSelected(null);
        }
        else if (selectedIdx > idx) selectedIdx--;
        save();
        renderList();
    }

    private void doRename(int idx)
    {
        Waypoint old = waypoints.get(idx);
        String n = JOptionPane.showInputDialog(this, "Rename waypoint:",
            old.name() == null ? "" : old.name());
        if (n == null) return;
        String name = n.isBlank() ? null : n.trim();
        snapshot();
        waypoints.set(idx, withName(old, name));
        save();
        renderList();
    }

    private static Waypoint withName(Waypoint old, @Nullable String name)
    {
        switch (old.kind())
        {
            case WALK_AREA:
                return Waypoint.walkArea(name, old.tiles());
            case WALK:
                return name == null ? Waypoint.walk(old.tile()) : Waypoint.walkNamed(name, old.tile());
            case TRANSPORT:
                return name == null
                    ? Waypoint.transport(old.tile(), old.transportKind(), old.verb())
                    : Waypoint.transportNamed(name, old.tile(), old.transportKind(), old.verb());
            default: throw new IllegalStateException();
        }
    }

    private void doMarkArea(@Nullable Waypoint editing)
    {
        hooks.onMarkArea(editing, tiles -> {
            if (tiles == null || tiles.isEmpty()) return;
            snapshot();
            String n = nameField.getText().trim();
            Waypoint w = Waypoint.walkArea(n.isBlank() ? null : n, tiles);
            if (editing != null)
            {
                int idx = waypoints.indexOf(editing);
                if (idx >= 0) waypoints.set(idx, w);
                else waypoints.add(w);
            }
            else
            {
                waypoints.add(w);
            }
            nameField.setText("");
            save();
            renderList();
        });
    }

    private void doMarkObject()
    {
        hooks.onMarkObject(captured -> {
            if (captured == null) return;
            snapshot();
            String n = nameField.getText().trim();
            Waypoint w = n.isBlank() ? captured
                : Waypoint.transportNamed(n, captured.tile(),
                    captured.transportKind(), captured.verb());
            waypoints.add(w);
            nameField.setText("");
            save();
            renderList();
        });
    }

    private void doAddCurrent()
    {
        hooks.onAddCurrent(captured -> {
            if (captured == null) return;
            snapshot();
            waypoints.add(captured);
            save();
            renderList();
        });
    }

    private void doAddMarked()
    {
        hooks.onAddMarked(captured -> {
            if (captured == null) return;
            snapshot();
            waypoints.add(captured);
            save();
            renderList();
        });
    }

    private void doEditBounds(int idx)
    {
        Waypoint wp = waypoints.get(idx);
        if (wp.kind() == Waypoint.Kind.WALK_AREA || wp.kind() == Waypoint.Kind.WALK)
        {
            doMarkArea(wp);
        }
        else
        {
            // TRANSPORT — small dialog with tile coords + verb.
            String coords = wp.tile().getX() + "," + wp.tile().getY() + "," + wp.tile().getPlane();
            String input = JOptionPane.showInputDialog(this,
                "Edit transport coords (x,y,p):", coords);
            if (input == null) return;
            String[] parts = input.split(",");
            if (parts.length != 3)
            {
                JOptionPane.showMessageDialog(this, "Expected x,y,p");
                return;
            }
            try
            {
                WorldPoint nt = new WorldPoint(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
                snapshot();
                Waypoint replacement = wp.name() == null
                    ? Waypoint.transport(nt, wp.transportKind(), wp.verb())
                    : Waypoint.transportNamed(wp.name(), nt, wp.transportKind(), wp.verb());
                waypoints.set(idx, replacement);
                save();
                renderList();
            }
            catch (NumberFormatException nfe)
            {
                JOptionPane.showMessageDialog(this, "Non-numeric coord");
            }
        }
    }

    private void doUndo()
    {
        undo.pop().ifPresent(snapshot -> {
            try
            {
                Files.writeString(routeFile, snapshot);
                load();
            }
            catch (IOException ioe)
            {
                JOptionPane.showMessageDialog(this, "Undo write failed: " + ioe.getMessage());
            }
        });
    }

    private void snapshot()
    {
        try
        {
            String current = Files.exists(routeFile) ? Files.readString(routeFile) : "";
            undo.push(current);
        }
        catch (IOException ioe)
        {
            log.warn("snapshot read failed: {}: {}", routeFile, ioe.getMessage());
        }
    }

    private void save()
    {
        try
        {
            StringBuilder sb = new StringBuilder();
            for (Waypoint w : waypoints) sb.append(w).append('\n');
            Files.writeString(routeFile, sb);
            routeOverlay.setRoute(waypoints);
        }
        catch (IOException ioe)
        {
            JOptionPane.showMessageDialog(this, "Save failed: " + ioe.getMessage());
        }
    }
}
