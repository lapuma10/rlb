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
import net.runelite.api.Constants;
import net.runelite.api.Menu;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
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
 *   <li>World line-of-sight (no walls / fences between player and NPC) via
 *       {@link WorldArea#hasLineOfSightTo}.</li>
 *   <li value="2">Walking reachability — a bounded BFS over
 *       {@link WorldArea#canTravelInDirection} catches NPCs behind a fence
 *       that LOS alone would let through.</li>
 *   <li value="3">Roof occlusion — when the player is under a hidden roof
 *       ({@link Constants#TILE_FLAG_UNDER_ROOF} via
 *       {@link Scene#getRoofs()}), NPCs on rendered-roof tiles are culled.</li>
 *   <li value="4">NPC's tile is currently projected onto the canvas (not
 *       behind the camera or off-screen).</li>
 *   <li value="5">NPC's projected pixel is inside the playable game viewport
 *       (in fixed-mode this excludes the chrome around the play area).</li>
 *   <li value="6">If a right-click menu is open, the NPC's pixel is not under
 *       that menu's rectangle.</li>
 *   <li value="7">Resizable mode only: NPC's pixel is not under the HUD
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
    /** True when {@code self} can directly see {@code npc} on the canvas. */
    boolean canSee(NPC npc, @Nullable Player self, @Nullable WorldView wv);

    /** Test stub — every NPC passes. Used by selector overloads that don't
     *  take a visibility checker, and by ChickenCombatLoop unit tests. */
    static TargetVisibility alwaysVisible()
    {
        return (npc, self, wv) -> true;
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
    public boolean canSee(NPC npc, @Nullable Player self, @Nullable WorldView wv)
    {
        if (npc == null || self == null || wv == null) return false;
        // 0. Plane match. OSRS only lets you interact with things on the
        //    same floor as you — the camera also doesn't render NPCs on a
        //    different plane unless the tile is flagged VIS_BELOW (a
        //    balcony / hole / second-storey gap). Cheapest filter, runs
        //    first.
        WorldPoint selfWp = self.getWorldLocation();
        WorldPoint npcWp = npc.getWorldLocation();
        if (selfWp == null || npcWp == null) return false;
        if (selfWp.getPlane() != npcWp.getPlane())
        {
            log.debug("cull npc {} — plane mismatch (self {} vs npc {})",
                npc.getIndex(), selfWp.getPlane(), npcWp.getPlane());
            return false;
        }
        // 1. World line-of-sight — Bresenham over collision flags. Zero
        //    canvas reads, fastest filter so it goes first. Catches
        //    chickens behind fences/walls in the Lumbridge pen.
        WorldArea selfArea = self.getWorldArea();
        WorldArea npcArea = npc.getWorldArea();
        if (selfArea == null || npcArea == null) return false;
        if (!selfArea.hasLineOfSightTo(wv, npcArea))
        {
            log.debug("cull npc {} — no LOS", npc.getIndex());
            return false;
        }
        // 1a. Walking reachability. LOS alone does NOT mean we can melee
        //     the NPC — the half-fences in the Lumbridge chicken pen pass
        //     projectile LOS but block walking, so the bot would otherwise
        //     pick a chicken on the far side and waste cycles failing to
        //     engage. BFS over collision flags up to MAX_REACH_DEPTH.
        if (!isReachable(selfArea, npcArea, wv))
        {
            log.debug("cull npc {} — not reachable on foot", npc.getIndex());
            return false;
        }
        // 1b. Roof occlusion. If the NPC's tile is under a roof and the
        //     engine isn't currently removing that roof for our view, the
        //     player can't see the NPC. Conservative: if NPC is under a
        //     roof but the player isn't, treat as occluded (different
        //     building). Engine doc primitives:
        //       Constants.TILE_FLAG_UNDER_ROOF   — tile is under a roof
        //       Scene.getRoofs()                 — roof IDs per tile
        //       Scene.getRoofRemovalMode()       — bitmask of removed roofs
        if (isUnderHiddenRoof(selfWp, npcWp, wv))
        {
            log.debug("cull npc {} — under roof not visible to player",
                npc.getIndex());
            return false;
        }
        // 2. On-canvas — getCanvasTilePoly returns null when the tile is
        //    off-screen or behind the camera. A real player can't click
        //    something they can't see rendered.
        Polygon poly = npc.getCanvasTilePoly();
        if (poly == null)
        {
            log.debug("cull npc {} — no canvas tile poly (off-screen)", npc.getIndex());
            return false;
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
            return false;
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
                    return false;
                }
            }
        }
        // 5. Persistent HUD widget occlusion — inventory panel, chatbox,
        //    minimap. In fixed-display mode the viewport check at step 3
        //    already excluded the chrome area; in resizable mode the
        //    viewport equals the canvas and HUD widgets float ON TOP of
        //    the world render. Iterate the toplevel HUD container's
        //    visible children and treat any that contain our pixel as
        //    occluding. This naturally covers the inventory side panel,
        //    the chatbox, and the minimap orbs without hard-coding rects.
        if (client.isResized() && isUnderHudWidget(cx, cy))
        {
            log.debug("cull npc {} — pixel ({},{}) under HUD widget",
                npc.getIndex(), cx, cy);
            return false;
        }
        return true;
    }

    /** True if {@code npcWp}'s tile is under a roof that the engine isn't
     *  currently removing for our viewpoint. Uses
     *  {@link Constants#TILE_FLAG_UNDER_ROOF} on
     *  {@link WorldView#getTileSettings()} together with
     *  {@link Scene#getRoofs()} so we cull cross-building targets without
     *  hard-coding region IDs. */
    private boolean isUnderHiddenRoof(WorldPoint selfWp, WorldPoint npcWp, WorldView wv)
    {
        try
        {
            byte[][][] settings = wv.getTileSettings();
            if (settings == null) return false;
            LocalPoint selfLp = LocalPoint.fromWorld(wv, selfWp);
            LocalPoint npcLp = LocalPoint.fromWorld(wv, npcWp);
            if (selfLp == null || npcLp == null) return false;
            int plane = selfWp.getPlane();
            if (plane < 0 || plane >= settings.length) return false;
            int sx = selfLp.getSceneX(), sy = selfLp.getSceneY();
            int nx = npcLp.getSceneX(), ny = npcLp.getSceneY();
            if (!inSettingsBounds(settings[plane], sx, sy)) return false;
            if (!inSettingsBounds(settings[plane], nx, ny)) return false;
            boolean npcUnderRoof =
                (settings[plane][nx][ny] & Constants.TILE_FLAG_UNDER_ROOF) != 0;
            if (!npcUnderRoof) return false;
            boolean selfUnderRoof =
                (settings[plane][sx][sy] & Constants.TILE_FLAG_UNDER_ROOF) != 0;
            // Player outside, NPC inside → cull.
            if (!selfUnderRoof) return true;
            // Both indoors — different roof IDs ⇒ different buildings.
            Scene scene = wv.getScene();
            if (scene == null) return false;
            int[][][] roofs = scene.getRoofs();
            if (roofs == null) return false;
            int selfRoof = roofs[plane][sx][sy];
            int npcRoof = roofs[plane][nx][ny];
            return selfRoof != npcRoof;
        }
        catch (Throwable th) { return false; }
    }

    private static boolean inSettingsBounds(byte[][] plane, int x, int y)
    {
        return plane != null
            && x >= 0 && x < plane.length
            && y >= 0 && plane[x] != null && y < plane[x].length;
    }

    /** True if pixel {@code (cx, cy)} sits inside any visible child of the
     *  toplevel HUD container — i.e. the inventory panel, the chatbox, the
     *  minimap, or any open interface tab in resizable mode. */
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
        if (containsAnyVisibleChild(hudFront, cx, cy, 0)) return true;
        // Chatbox lives outside the HUD container in some layouts — check
        // it explicitly so chat-area pixels still get culled.
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
     *  avoid pathological widget trees. We treat any visible non-empty
     *  child whose bounds contain the pixel as occluding. */
    private static boolean containsAnyVisibleChild(Widget parent, int cx, int cy, int depth)
    {
        if (parent == null || parent.isHidden() || depth > HUD_DESCENT_LIMIT) return false;
        Rectangle b = parent.getBounds();
        if (b == null || b.isEmpty() || !b.contains(cx, cy)) return false;
        // The HUD_CONTAINER_FRONT widget itself spans the canvas — it's
        // the layer the world renders THROUGH — so we don't treat depth=0
        // as occluding; only its descendants count.
        if (depth > 0) return true;
        Widget[] statics = parent.getStaticChildren();
        if (statics != null)
        {
            for (Widget c : statics)
            {
                if (containsAnyVisibleChild(c, cx, cy, depth + 1)) return true;
            }
        }
        Widget[] dyn = parent.getDynamicChildren();
        if (dyn != null)
        {
            for (Widget c : dyn)
            {
                if (containsAnyVisibleChild(c, cx, cy, depth + 1)) return true;
            }
        }
        Widget[] nested = parent.getNestedChildren();
        if (nested != null)
        {
            for (Widget c : nested)
            {
                if (containsAnyVisibleChild(c, cx, cy, depth + 1)) return true;
            }
        }
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
