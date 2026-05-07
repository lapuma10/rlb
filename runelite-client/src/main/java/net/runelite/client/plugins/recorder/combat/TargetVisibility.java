/*
 * Copyright (c) 2026, RuneLite
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
package net.runelite.client.plugins.recorder.combat;

import java.awt.Polygon;
import java.awt.Rectangle;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * Strategy for "can the local player actually see this NPC right now?" — a
 * humanization filter for the combat selector. The bot is not allowed to
 * pick a target that a real player would not be able to click on:
 *
 * <ol start="0">
 *   <li>Plane match — same floor as the player.</li>
 *   <li>Tile-to-tile line-of-sight via {@link WorldArea#hasLineOfSightTo} —
 *       no wall / fence / closed door between player and NPC.</li>
 *   <li value="2">Walking reachability — a bounded BFS over
 *       {@link WorldArea#canTravelInDirection} catches closed gates and
 *       impassable terrain that LOS through a window would otherwise pass.</li>
 *   <li value="3">NPC's tile is currently projected onto the canvas (not
 *       behind the camera or off-screen).</li>
 *   <li value="4">NPC's projected pixel is inside the playable game viewport
 *       (in fixed-mode this excludes the chrome around the play area).</li>
 *   <li value="5">If a right-click menu is open, the NPC's pixel is not under
 *       that menu's rectangle.</li>
 *   <li value="6">Resizable mode only: NPC's pixel is not under the HUD
 *       widget tree (inventory / tabs / minimap / orbs / chatbox) — fixed
 *       mode already excludes those via the viewport rect.</li>
 * </ol>
 *
 * <p>Each check returns false (cull) on the first miss. Callers must invoke
 * on the client thread — {@link Player#getWorldArea()},
 * {@link NPC#getCanvasTilePoly()}, {@link Client#getViewportXOffset()},
 * {@link Client#isMenuOpen()}, {@link Scene#getRoofs()}, and the widget tree
 * traversal all assert it.
 *
 * <p>Tests use {@link #alwaysVisible()} to skip these checks; production
 * uses {@link #forClient(Client)} which reads the live engine state.
 */
public interface TargetVisibility
{
    /** Per-stage diagnostic — which of the canSee checks rejected the NPC.
     *  Surfaced by {@link #whyHidden} so callers (debug overlays, log
     *  diagnostics) can tell {@code "behind a fence"} from {@code "under
     *  the inventory"}. The order matches the canSee pipeline. */
    enum Reason
    {
        /** One of the input refs (npc / self / wv / world locations) was
         *  null — defensive guard, not a real visibility failure. */
        NULL_INPUT,
        /** Player and NPC are on different floors. */
        PLANE_MISMATCH,
        /** Tile-to-tile line-of-sight is blocked by a wall / fence /
         *  closed door. A clean LOS is required for melee click reliability:
         *  if a fence sits between the player tile and the chicken tile,
         *  the engine resolves the click to the wall and the right-click
         *  menu has no 'Attack' on it, producing the dispatch failure
         *  cascade we saw in the chicken-pen logs. */
        NO_LOS,
        /** Walking BFS can't reach the NPC's tile within
         *  {@code MAX_REACH_DEPTH}. Catches gates that are closed and
         *  islands behind impassable terrain. */
        NOT_REACHABLE,
        /** NPC's tile didn't project onto the canvas (off-screen or behind
         *  the camera). The engine's roof-removal state is already baked
         *  into this — if the engine is hiding the roof for our viewpoint
         *  the chicken projects normally. */
        OFF_CANVAS,
        /** Projected pixel sits outside the playable viewport rectangle. */
        OUTSIDE_VIEWPORT,
        /** Projected pixel sits under an open right-click menu. */
        UNDER_OPEN_MENU,
        /** Resizable mode: pixel sits under a HUD widget that has actual
         *  visual content (sprite / text / item / model) — i.e. inventory
         *  slots, chatbox text, minimap, orbs. Transparent layout
         *  containers are excluded so chickens in the open play area
         *  aren't culled by an invisible HUD sibling that happens to
         *  span across them. */
        UNDER_HUD
    }

    /** Returns the first canSee check that rejected {@code npc}, or
     *  {@code null} if every check passed (the NPC is visible). The boolean
     *  {@link #canSee} convenience is now a default wrapper around this. */
    @Nullable
    Reason whyHidden(NPC npc, @Nullable Player self, @Nullable WorldView wv);

    /** True when {@code self} can directly see {@code npc} on the canvas. */
    default boolean canSee(NPC npc, @Nullable Player self, @Nullable WorldView wv)
    {
        return whyHidden(npc, self, wv) == null;
    }

    /** Test stub — every NPC passes. Used by selector overloads that don't
     *  take a visibility checker, and by ChickenCombatLoop unit tests. */
    static TargetVisibility alwaysVisible()
    {
        return (npc, self, wv) -> null;
    }

    /** Production checker bound to the live client. Kept stateless apart
     *  from the {@code client} reference so a single instance can serve
     *  the loop for its entire lifetime. */
    static TargetVisibility forClient(Client client)
    {
        return new ClientVisibility(client);
    }
}

@Slf4j
final class ClientVisibility implements TargetVisibility
{
    /** BFS depth cap for reachability. Selector range is 6 tiles; +2 covers
     *  short detours around an obstacle without letting a single visibility
     *  check explore the whole scene for an unreachable target. */
    private static final int MAX_REACH_DEPTH = 8;

    private final Client client;

    ClientVisibility(Client client) { this.client = client; }

    @Override
    public Reason whyHidden(NPC npc, @Nullable Player self, @Nullable WorldView wv)
    {
        if (npc == null || self == null || wv == null) return Reason.NULL_INPUT;
        // 0. Plane match. OSRS only lets you interact with things on the
        //    same floor as you — the camera also doesn't render NPCs on a
        //    different plane unless the tile is flagged VIS_BELOW (a
        //    balcony / hole / second-storey gap). Cheapest filter, runs
        //    first.
        WorldPoint selfWp = self.getWorldLocation();
        WorldPoint npcWp = npc.getWorldLocation();
        if (selfWp == null || npcWp == null) return Reason.NULL_INPUT;
        if (selfWp.getPlane() != npcWp.getPlane())
        {
            log.debug("cull npc {} — plane mismatch (self {} vs npc {})",
                npc.getIndex(), selfWp.getPlane(), npcWp.getPlane());
            return Reason.PLANE_MISMATCH;
        }
        WorldArea selfArea = self.getWorldArea();
        WorldArea npcArea = npc.getWorldArea();
        if (selfArea == null || npcArea == null) return Reason.NULL_INPUT;
        // 1. Tile-to-tile line-of-sight. We previously skipped this in favour
        //    of pure walking-reachability with the rationale "you can walk
        //    around a fence to reach the chicken anyway". The chicken-pen
        //    logs disprove that: when a fence sits between the player and
        //    the chicken, the chicken's hull still projects on canvas (the
        //    fence just clips it), so the dispatcher resolves a click pixel,
        //    but the engine's hover lands on the fence wall and the
        //    right-click menu has no 'Attack', producing repeated 'menu
        //    missing Attack' dispatch failures and the false-IN_COMBAT
        //    knock-on. Cull these targets up front. Reachability still runs
        //    next as a coarser check for closed gates / impassable terrain.
        if (!selfArea.hasLineOfSightTo(wv, npcArea))
        {
            log.debug("cull npc {} — no line of sight from self {} to npc {}",
                npc.getIndex(), selfWp, npcWp);
            return Reason.NO_LOS;
        }
        // 2. Walking reachability. BFS over collision flags up to
        //    MAX_REACH_DEPTH. Catches "behind a closed gate" and similar
        //    cases where a tile has LOS through a window/grate but isn't
        //    walkable.
        if (!isReachable(selfArea, npcArea, wv))
        {
            log.debug("cull npc {} — not reachable on foot", npc.getIndex());
            return Reason.NOT_REACHABLE;
        }
        // 2. On-canvas — getCanvasTilePoly returns null when the tile is
        //    off-screen or behind the camera. A real player can't click
        //    something they can't see rendered.
        Polygon poly = npc.getCanvasTilePoly();
        if (poly == null)
        {
            log.debug("cull npc {} — no canvas tile poly (off-screen)", npc.getIndex());
            return Reason.OFF_CANVAS;
        }
        Rectangle bb = poly.getBounds();
        int cx = bb.x + bb.width / 2;
        int cy = bb.y + bb.height / 2;
        // 3. Inside playable viewport. In fixed-display mode the play area
        //    is a fixed rectangle inside the canvas; in resizable mode it
        //    fills the canvas. Either way the viewport rect is the same
        //    answer to "is this pixel where the world is drawn".
        int vx = client.getViewportXOffset();
        int vy = client.getViewportYOffset();
        int vw = client.getViewportWidth();
        int vh = client.getViewportHeight();
        if (cx < vx || cx >= vx + vw || cy < vy || cy >= vy + vh)
        {
            log.debug("cull npc {} — pixel ({},{}) outside viewport ({},{} {}x{})",
                npc.getIndex(), cx, cy, vx, vy, vw, vh);
            return Reason.OUTSIDE_VIEWPORT;
        }
        // 4. Open right-click menu occlusion. When a menu is open, anything
        //    under it is not visible to the player — clicking through it
        //    is a giveaway. Only relevant when isMenuOpen() is true; the
        //    hover-state menu does not occlude.
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
                    log.debug("cull npc {} — pixel ({},{}) under open menu ({},{} {}x{})",
                        npc.getIndex(), cx, cy, mx, my, mw, mh);
                    return Reason.UNDER_OPEN_MENU;
                }
            }
        }
        // 5. Persistent HUD widget occlusion — inventory panel, chatbox,
        //    minimap orbs, side tabs. Fixed-display mode already excludes
        //    the chrome via the viewport rect (step 3); resizable mode has
        //    HUD widgets floating ON TOP of the world. Recurse the toplevel
        //    HUD container's visible children, but only treat a widget as
        //    occluding if it carries actual visual content (sprite / text /
        //    item / model). Transparent layout containers do not count —
        //    that was the bug that culled chickens in the open courtyard.
        if (client.isResized() && isUnderHudWidget(cx, cy))
        {
            log.debug("cull npc {} — pixel ({},{}) under HUD widget",
                npc.getIndex(), cx, cy);
            return Reason.UNDER_HUD;
        }
        return null;
    }

    /** True if pixel {@code (cx, cy)} sits inside any visible HUD child
     *  with real visual content. */
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
        // Chatbox lives outside the HUD container in some layouts — check
        // it explicitly so chat-area pixels still get culled. The chatbox
        // background is itself a sprite so it has content.
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

    /** Recursive descent — depth capped to {@link #HUD_DESCENT_LIMIT} to
     *  bound widget-tree walks. Only a widget that both contains the pixel
     *  AND has actual rendered content (sprite / text / item / model)
     *  counts as occluding; transparent layout containers fall through to
     *  their children. */
    private static boolean containsContentfulChild(Widget parent, int cx, int cy, int depth)
    {
        if (parent == null || parent.isHidden() || depth > HUD_DESCENT_LIMIT) return false;
        Rectangle b = parent.getBounds();
        if (b == null || b.isEmpty() || !b.contains(cx, cy)) return false;
        // The HUD_CONTAINER_FRONT widget itself spans the canvas — it's
        // the layer the world renders THROUGH — so depth=0 never
        // occludes; only descendants are considered.
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

    /** True iff {@code w} carries a sprite, text, item, or model — i.e.
     *  something a player can actually see drawn on top of the world. */
    private static boolean hasVisualContent(Widget w)
    {
        if (w.getSpriteId() > 0) return true;
        String text = w.getText();
        if (text != null && !text.isEmpty()) return true;
        if (w.getItemId() > 0) return true;
        if (w.getModelId() > 0) return true;
        return false;
    }

    /** Bound widget-tree recursion depth — HUD trees in OSRS are flat
     *  (panel → tabs → orbs/buttons), 6 levels covers every observed case. */
    private static final int HUD_DESCENT_LIMIT = 6;

    /** Walking-reachability BFS from the player's tile. Returns true when
     *  any tile orthogonally adjacent to (or coincident with) the NPC's
     *  tile is reachable within {@link #MAX_REACH_DEPTH} steps using the
     *  engine's standard collision flags (delegated to
     *  {@link WorldArea#canTravelInDirection}, which already encodes the
     *  full wall/diagonal flag matrix). */
    private static boolean isReachable(WorldArea selfArea, WorldArea npcArea, WorldView wv)
    {
        if (selfArea.getPlane() != npcArea.getPlane()) return false;
        int sx = selfArea.getX(), sy = selfArea.getY();
        int tx = npcArea.getX(), ty = npcArea.getY();
        int plane = selfArea.getPlane();
        // Already adjacent (or on-tile) — trivially reachable.
        if (Math.abs(sx - tx) + Math.abs(sy - ty) <= 1) return true;

        java.util.HashSet<Long> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        visited.add(packXY(sx, sy));
        queue.add(new int[]{sx, sy, 0});

        final int[] DX = {0, 0, 1, -1};
        final int[] DY = {1, -1, 0, 0};
        while (!queue.isEmpty())
        {
            int[] cur = queue.poll();
            int x = cur[0], y = cur[1], d = cur[2];
            if (d >= MAX_REACH_DEPTH) continue;
            WorldArea here = new WorldArea(x, y, 1, 1, plane);
            for (int i = 0; i < 4; i++)
            {
                int nx = x + DX[i], ny = y + DY[i];
                long key = packXY(nx, ny);
                if (visited.contains(key)) continue;
                if (!here.canTravelInDirection(wv, DX[i], DY[i])) continue;
                if (Math.abs(nx - tx) + Math.abs(ny - ty) <= 1) return true;
                visited.add(key);
                queue.add(new int[]{nx, ny, d + 1});
            }
        }
        return false;
    }

    private static long packXY(int x, int y)
    {
        return ((long) x << 32) | (y & 0xffffffffL);
    }
}
