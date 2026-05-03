package net.runelite.client.plugins.recorder.trail;

import java.awt.Rectangle;
import java.awt.Shape;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.recorder.walker.Reachability.ReachabilityMap;

/** "Can the player see this game-object right now?" — adapted from
 *  {@code combat/TargetVisibility} which answers the same question for
 *  NPCs. The 6-stage cull pipeline is the same shape; the projection
 *  check uses the object's convex hull (passed in, computed by the
 *  caller from the resolved {@code WallObject} / {@code GameObject} /
 *  etc.) instead of an NPC's tile poly.
 *
 *  <p>Stages, in order — first failure short-circuits and returns a
 *  {@link Reason}:
 *
 *  <ol>
 *    <li>{@code PLANE_MISMATCH} — target tile not on player's plane.</li>
 *    <li>{@code NOT_REACHABLE} — target tile not in the BFS-reachable
 *        set the corridor walker already snapshotted this tick.</li>
 *    <li>{@code OFF_CANVAS} — object's convex hull is null (off-screen
 *        or behind the camera).</li>
 *    <li>{@code OUTSIDE_VIEWPORT} — hull centroid pixel sits outside
 *        the playable viewport rect.</li>
 *    <li>{@code UNDER_OPEN_MENU} — open right-click menu covers the
 *        centroid.</li>
 *    <li>{@code UNDER_HUD} — resizable mode only; a contentful HUD
 *        widget (sprite/text/item/model) covers the centroid.</li>
 *  </ol>
 *
 *  <p>Two reasons are <i>fixable</i> by camera rotation: {@code OFF_CANVAS}
 *  and {@code OUTSIDE_VIEWPORT}. The other three either need the player
 *  to walk closer (HUD/menu cover the same screen position regardless
 *  of camera) or are unreachable. Callers branch on the reason — see
 *  TrailWalker's transport flow.
 *
 *  <p>Implementation notes mirroring {@code ClientVisibility}: the HUD
 *  occlusion walk only counts widgets with actual visual content
 *  (sprite/text/item/model), not transparent layout containers — the
 *  same trap that culled chickens in the open courtyard. The chatbox
 *  is checked explicitly because some layouts park it outside
 *  {@code HUD_CONTAINER_FRONT}. */
public interface ObjectVisibility
{
    enum Reason
    {
        NULL_INPUT,
        PLANE_MISMATCH,
        NOT_REACHABLE,
        OFF_CANVAS,
        OUTSIDE_VIEWPORT,
        UNDER_OPEN_MENU,
        UNDER_HUD;

        /** True when {@link TrailWalker}'s transport flow can fix this
         *  by panning the camera. {@code OFF_CANVAS} and
         *  {@code OUTSIDE_VIEWPORT} are positional projection failures
         *  that rotation moves into view; everything else either
         *  requires walking closer (HUD/menu) or can't be fixed at
         *  all (plane mismatch, unreachable). */
        public boolean fixableByRotation()
        {
            return this == OFF_CANVAS || this == OUTSIDE_VIEWPORT;
        }
    }

    /** Returns the first cull reason, or {@code null} if the object
     *  is fully visible. All inputs are pre-resolved snapshots — the
     *  implementation never calls {@code getLocalPlayer().getWorldLocation()}
     *  or any other client-thread-required accessor. Caller is
     *  responsible for snapshotting on the client thread.
     *
     *  <p>Threading note: the v1 mistake was taking {@code Player self}
     *  here and calling {@code self.getWorldLocation()} inside the
     *  pipeline, which asserts under {@code -ea} when invoked from a
     *  worker thread. v2 takes the player's already-resolved
     *  {@code WorldPoint} so this method is safe from any thread. */
    @Nullable
    Reason whyHidden(WorldPoint targetTile,
                     @Nullable Shape objectHull,
                     @Nullable WorldPoint selfTile,
                     @Nullable ReachabilityMap reach);

    default boolean canSee(WorldPoint targetTile, @Nullable Shape objectHull,
                           @Nullable WorldPoint selfTile, @Nullable ReachabilityMap reach)
    {
        return whyHidden(targetTile, objectHull, selfTile, reach) == null;
    }

    /** Test stub — every object passes. */
    static ObjectVisibility alwaysVisible()
    {
        return (tile, hull, selfTile, reach) -> null;
    }

    /** Production checker bound to a live client. */
    static ObjectVisibility forClient(Client client)
    {
        return new ClientObjectVisibility(client);
    }
}

@Slf4j
final class ClientObjectVisibility implements ObjectVisibility
{
    private final Client client;

    ClientObjectVisibility(Client client) { this.client = client; }

    @Override
    public Reason whyHidden(WorldPoint targetTile, @Nullable Shape objectHull,
                            @Nullable WorldPoint selfTile, @Nullable ReachabilityMap reach)
    {
        if (targetTile == null || selfTile == null) return Reason.NULL_INPUT;

        // 0. Plane match.
        if (selfTile.getPlane() != targetTile.getPlane())
        {
            log.debug("cull obj at {} — plane mismatch (self {} vs tgt {})",
                targetTile, selfTile.getPlane(), targetTile.getPlane());
            return Reason.PLANE_MISMATCH;
        }
        // 1. Walking reachability — uses the same BFS snapshot the
        //    corridor walker built this tick. If the snapshot wasn't
        //    provided we can't make a confident reachability call,
        //    so skip this stage rather than over-reject.
        if (reach != null && !reach.isReachable(targetTile))
        {
            log.debug("cull obj at {} — not in reachable set", targetTile);
            return Reason.NOT_REACHABLE;
        }
        // 2. Object has a renderable hull. A null hull means the
        //    object is off-screen, behind the camera, or not yet
        //    streamed in — same answer for our purposes: can't see it.
        if (objectHull == null)
        {
            log.debug("cull obj at {} — null convex hull", targetTile);
            return Reason.OFF_CANVAS;
        }
        Rectangle bb = objectHull.getBounds();
        if (bb == null || bb.isEmpty())
        {
            log.debug("cull obj at {} — empty hull bounds", targetTile);
            return Reason.OFF_CANVAS;
        }
        int cx = bb.x + bb.width / 2;
        int cy = bb.y + bb.height / 2;
        // 3. Inside the playable viewport.
        int vx = client.getViewportXOffset();
        int vy = client.getViewportYOffset();
        int vw = client.getViewportWidth();
        int vh = client.getViewportHeight();
        if (cx < vx || cx >= vx + vw || cy < vy || cy >= vy + vh)
        {
            log.debug("cull obj at {} — pixel ({},{}) outside viewport ({},{} {}x{})",
                targetTile, cx, cy, vx, vy, vw, vh);
            return Reason.OUTSIDE_VIEWPORT;
        }
        // 4. Open right-click menu covers the centroid.
        if (client.isMenuOpen())
        {
            Menu menu = client.getMenu();
            if (menu != null)
            {
                int mx = menu.getMenuX();
                int my = menu.getMenuY();
                int mw = menu.getMenuWidth();
                int mh = menu.getMenuHeight();
                if (cx >= mx && cx < mx + mw && cy >= my && cy < my + mh)
                {
                    log.debug("cull obj at {} — pixel ({},{}) under open menu", targetTile, cx, cy);
                    return Reason.UNDER_OPEN_MENU;
                }
            }
        }
        // 5. HUD widget occlusion — resizable mode only; fixed mode
        //    excludes HUD via the viewport rect at stage 3.
        if (client.isResized() && isUnderHudWidget(cx, cy))
        {
            log.debug("cull obj at {} — pixel ({},{}) under HUD widget", targetTile, cx, cy);
            return Reason.UNDER_HUD;
        }
        return null;
    }

    /** Mirror of {@code ClientVisibility.isUnderHudWidget}. Kept
     *  duplicated rather than extracted to a shared util because the
     *  two callers may diverge (NPCs vs static objects have slightly
     *  different "what counts as occluding" rules — e.g., a banker
     *  desk model crosses behind transparent HUD layouts that an
     *  NPC tile poly doesn't). For now they're identical. */
    private boolean isUnderHudWidget(int cx, int cy)
    {
        Widget hudFront;
        try
        {
            int arrangement = client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT);
            hudFront = arrangement == 1
                ? client.getWidget(InterfaceID.ToplevelPreEoc.HUD_CONTAINER_FRONT)
                : client.getWidget(InterfaceID.ToplevelOsrsStretch.HUD_CONTAINER_FRONT);
        }
        catch (Throwable th) { return false; }
        if (hudFront == null) return false;
        if (containsContentfulChild(hudFront, cx, cy, 0)) return true;
        try
        {
            Widget chat = client.getWidget(InterfaceID.Chatbox.CHATAREA);
            if (chat != null && !chat.isHidden())
            {
                Rectangle b = chat.getBounds();
                if (b != null && b.contains(cx, cy)) return true;
            }
        }
        catch (Throwable ignored) { /* not all layouts expose chatbox */ }
        return false;
    }

    private static boolean containsContentfulChild(Widget parent, int cx, int cy, int depth)
    {
        if (parent == null || parent.isHidden() || depth > HUD_DESCENT_LIMIT) return false;
        Rectangle b = parent.getBounds();
        if (b == null || b.isEmpty() || !b.contains(cx, cy)) return false;
        if (depth > 0 && hasVisualContent(parent)) return true;
        Widget[] statics = parent.getStaticChildren();
        if (statics != null)
        {
            for (Widget c : statics)
            {
                if (containsContentfulChild(c, cx, cy, depth + 1)) return true;
            }
        }
        Widget[] dyn = parent.getDynamicChildren();
        if (dyn != null)
        {
            for (Widget c : dyn)
            {
                if (containsContentfulChild(c, cx, cy, depth + 1)) return true;
            }
        }
        Widget[] nested = parent.getNestedChildren();
        if (nested != null)
        {
            for (Widget c : nested)
            {
                if (containsContentfulChild(c, cx, cy, depth + 1)) return true;
            }
        }
        return false;
    }

    private static boolean hasVisualContent(Widget w)
    {
        if (w.getSpriteId() > 0) return true;
        String text = w.getText();
        if (text != null && !text.isEmpty()) return true;
        if (w.getItemId() > 0) return true;
        if (w.getModelId() > 0) return true;
        return false;
    }

    private static final int HUD_DESCENT_LIMIT = 6;
}
