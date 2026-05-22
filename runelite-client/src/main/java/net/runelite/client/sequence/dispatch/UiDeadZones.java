package net.runelite.client.sequence.dispatch;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/** UI regions where a {@code WORLD}-intent click must never land —
 *  chatbox, side panels, compass, minimap orb cluster, and the
 *  minimap drawable area itself. See the spec at
 *  {@code docs/superpowers/specs/2026-05-22-chatbox-deadzone-click-guard.md}.
 *
 *  <p>Each dead-zone is the live {@code getBounds()} of a widget
 *  looked up by id. Hidden / not-loaded widgets are silently
 *  skipped, so the lookup is cheap and self-pruning regardless of
 *  layout (fixed / resizable modern / resizable classic / OSM —
 *  each layout only renders a subset of the listed widget ids).
 *
 *  <p>Visibility check walks the parent chain so a side-panel
 *  child whose ancestor is hidden does not count as a dead-zone
 *  even if its own {@code isHidden()} flag is stale.
 *
 *  <p>All accessor methods MUST be called on the client thread —
 *  every {@code getWidget(...)} / {@code getBounds()} call reads
 *  scene state. */
public final class UiDeadZones
{
    private UiDeadZones() { /* static-only */ }

    /** Widgets included in {@link #worldDeadZones(Client)} but
     *  excluded from {@link #minimapOverlayDeadZones(Client)} —
     *  the minimap drawable area itself. WORLD intent clicks
     *  inside this are blocked (minimap is reserved for MINIMAP
     *  intent walks); MINIMAP-intent walks intentionally land here
     *  and must NOT be rejected by overlay clamping. */
    private static final int[] MINIMAP_IDS = {
        InterfaceID.Toplevel.MINIMAP,
        InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP,
        InterfaceID.ToplevelPreEoc.MAP_MINIMAP,
        InterfaceID.ToplevelOsm.MAP_MINIMAP,
    };

    /** Widgets that sit visually on top of the minimap (orbs,
     *  world-map button, wiki button, compass) AND general UI
     *  regions (chatbox, side panels). Included in both
     *  {@link #worldDeadZones(Client)} and
     *  {@link #minimapOverlayDeadZones(Client)} — they block WORLD
     *  clicks AND must be avoided when clamping a MINIMAP-intent
     *  pixel into the minimap disc.
     *
     *  <p>Per-orb ids are NOT enumerated here — each orb container
     *  ({@code Orbs.UNIVERSE} et al) is one widget whose bounds
     *  cover the whole cluster, which is precisely the rejection
     *  region we want. The four {@code Orbs*} variants are the
     *  four layouts' versions of the same container; hidden ones
     *  are skipped automatically. */
    private static final int[] OVERLAY_IDS = {
        // Chatbox — matches the region painted by ChatDebugOverlay.
        InterfaceID.Chatbox.UNIVERSE,
        // Side panels (current sidebar tab content) — one per layout.
        InterfaceID.Toplevel.SIDE_PANELS,
        InterfaceID.ToplevelOsrsStretch.SIDE_PANELS,
        InterfaceID.ToplevelPreEoc.SIDE_PANELS,
        InterfaceID.ToplevelOsm.SIDE_PANELS,
        // Compass — sits inside the minimap rectangle on resizable
        // layouts; its hit-rect steals WORLD clicks at the top edge.
        InterfaceID.Toplevel.COMPASSCLICK,
        InterfaceID.ToplevelOsrsStretch.COMPASSCLICK,
        InterfaceID.ToplevelPreEoc.COMPASSCLICK,
        InterfaceID.ToplevelOsm.COMPASSCLICK,
        // Orb cluster (run/prayer/health/spec/store/world-map/wiki).
        // UNIVERSE covers every child orb — one widget per layout.
        InterfaceID.Orbs.UNIVERSE,
        InterfaceID.OrbsNomap.UNIVERSE,
        InterfaceID.OrbsOsm.UNIVERSE,
        InterfaceID.OrbsOsmNomap.UNIVERSE,
    };

    /** Dead-zones for {@code WORLD}-intent presses and for resolver
     *  candidate-pixel rejection on WORLD targets (NPCs, game
     *  objects, ground items, walls, walk main-view samples).
     *  Includes the minimap drawable area: WORLD clicks must not
     *  land there. */
    public static List<Rectangle> worldDeadZones(Client client)
    {
        List<Rectangle> out = new ArrayList<>();
        collect(client, OVERLAY_IDS, out);
        collect(client, MINIMAP_IDS, out);
        return out;
    }

    /** Dead-zones for MINIMAP-intent pixel resolution — every overlay
     *  that visually sits ON the minimap (orbs / world-map button /
     *  compass) plus the general UI overlays (chatbox, side panels)
     *  for completeness. Excludes the minimap drawable area itself
     *  so legitimate minimap-walk pixels are not rejected. */
    public static List<Rectangle> minimapOverlayDeadZones(Client client)
    {
        List<Rectangle> out = new ArrayList<>();
        collect(client, OVERLAY_IDS, out);
        return out;
    }

    /** First WORLD dead-zone {@link Rectangle} containing {@code (x, y)},
     *  or {@code null} if the point is clear. Used by the
     *  {@code HumanizedInputDispatcher.press(...)} primitive as the
     *  last-mile WORLD-intent block. */
    public static Rectangle worldIntersectsAt(Client client, int x, int y)
    {
        for (Rectangle r : worldDeadZones(client))
        {
            if (r.contains(x, y)) return r;
        }
        return null;
    }

    /** Convenience: true iff {@code (x, y)} hits any minimap-overlay
     *  dead-zone. Used by resolver clamping to push a minimap pixel
     *  off a world-map button / orb / compass hit-rect. */
    public static boolean hitsMinimapOverlay(Client client, int x, int y)
    {
        for (Rectangle r : minimapOverlayDeadZones(client))
        {
            if (r.contains(x, y)) return true;
        }
        return false;
    }

    private static void collect(Client client, int[] widgetIds,
                                List<Rectangle> out)
    {
        // Null-client tolerance: PixelResolver and other consumers may
        // be constructed in test fixtures with a null Client (see
        // PixelResolverRepeatTest) — when that happens we have nothing
        // to query and behave as if no dead-zones are active.
        if (client == null) return;
        for (int id : widgetIds)
        {
            Widget w = client.getWidget(id);
            if (w == null) continue;
            if (isHiddenIncludingAncestors(w)) continue;
            Rectangle b = w.getBounds();
            if (b == null || b.isEmpty()) continue;
            // Defensive copy — callers may mutate.
            out.add(new Rectangle(b));
        }
    }

    private static boolean isHiddenIncludingAncestors(Widget w)
    {
        for (Widget cur = w; cur != null; cur = cur.getParent())
        {
            if (cur.isHidden()) return true;
        }
        return false;
    }
}
