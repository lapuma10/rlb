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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.RecorderConfig;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Debug overlay that draws every chicken's convex hull, colour-coded by what
 * the combat selector would do with it. Closest eligible chicken (the one
 * {@link ChickenCombatLoop} would attack next) gets a brighter cyan outline
 * with a thicker stroke so it stands out in a crowd.
 *
 * <p>Reads the same {@link NpcSelector#classify} logic the live selector
 * uses, so the overlay can never disagree with the bot. Per-category visibility
 * is driven by {@link RecorderConfig} toggles — eligible chickens are always
 * shown when the master toggle is on; rejected chickens only show when their
 * reason has been opted in. A small counts panel in the top-left summarises
 * the snapshot.
 *
 * <p>Hulls — not tile polys — are used because the selector's click target is
 * the model hull (the same shape OSRS uses for hover-default detection); tile
 * polys can be slightly off when chickens shift mid-tile.
 */
public final class ChickenOverlay extends Overlay
{
    /** Closest eligible chicken: bright cyan, thick stroke. The single one
     *  the combat loop would attack on the next selector tick. */
    private static final Color CLOSEST_ELIGIBLE_COLOUR = new Color(0, 255, 255, 230);
    /** Other eligible chickens: green. */
    private static final Color ELIGIBLE_COLOUR = new Color(60, 220, 90, 200);
    /** Out of range: amber. The chicken would be picked if the player walked closer. */
    private static final Color OUT_OF_RANGE_COLOUR = new Color(255, 200, 0, 170);
    /** Rejected by visibility filter (LOS / fence / canvas / HUD / open menu). */
    private static final Color NOT_VISIBLE_COLOUR = new Color(190, 190, 190, 140);
    /** Currently being attacked by another player. */
    private static final Color ENGAGED_BY_OTHER_COLOUR = new Color(220, 90, 220, 170);
    /** Different plane (rare for chickens — only relevant in a few regions). */
    private static final Color WRONG_PLANE_COLOUR = new Color(120, 120, 200, 140);
    /** Dying / dead — HP bar has emptied. Transient. */
    private static final Color DYING_COLOUR = new Color(110, 110, 110, 110);

    private static final float ELIGIBLE_STROKE = 1.8f;
    private static final float CLOSEST_STROKE = 3.0f;
    private static final float REJECTED_STROKE = 1.2f;

    private final Client client;
    private final RecorderConfig config;
    private final NpcSelector selector;
    private final TargetVisibility visibility;

    public ChickenOverlay(Client client, RecorderConfig config)
    {
        this.client = client;
        this.config = config;
        this.selector = new NpcSelector("Chicken");
        this.visibility = TargetVisibility.forClient(client);
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
        setPriority(Overlay.PRIORITY_LOW);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.chickenOverlay()) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Player self = client.getLocalPlayer();
        WorldPoint selfLoc = self == null ? null : self.getWorldLocation();
        if (selfLoc == null) return null;

        // Single pass: classify every NPC, remember the closest eligible
        // (lowest distance ties broken by lowest index — same tie-break as
        // NpcSelector.pick so the highlighted chicken is exactly the one the
        // selector would return).
        Map<NpcSelector.Rejection, Integer> counts = new EnumMap<>(NpcSelector.Rejection.class);
        Map<TargetVisibility.Reason, Integer> visCounts = new EnumMap<>(TargetVisibility.Reason.class);
        int eligibleCount = 0;
        NPC closestEligible = null;
        int closestDist = Integer.MAX_VALUE;
        int closestIdx = Integer.MAX_VALUE;
        java.util.List<Classified> chickens = new java.util.ArrayList<>();

        for (NPC npc : wv.npcs())
        {
            if (npc == null) continue;
            NpcSelector.Rejection r = selector.classify(npc, self, selfLoc, -1, wv, visibility);
            // NAME_MISMATCH means "not a chicken at all" — skip silently;
            // we don't draw cows. EXCLUDED can't happen here (we pass -1).
            if (r == NpcSelector.Rejection.NAME_MISMATCH) continue;
            chickens.add(new Classified(npc, r));
            if (r == null)
            {
                eligibleCount++;
                WorldPoint npcLoc = npc.getWorldLocation();
                if (npcLoc != null)
                {
                    int d = npcLoc.distanceTo(selfLoc);
                    int idx = npc.getIndex();
                    if (d < closestDist || (d == closestDist && idx < closestIdx))
                    {
                        closestDist = d;
                        closestIdx = idx;
                        closestEligible = npc;
                    }
                }
            }
            else
            {
                counts.merge(r, 1, Integer::sum);
                // Break the NOT_VISIBLE bucket down by which canSee stage
                // failed. This is the diagnostic that closes the loop —
                // "11 not visible" without the breakdown is unactionable;
                // "11 under hidden roof" tells us exactly what to fix.
                if (r == NpcSelector.Rejection.NOT_VISIBLE)
                {
                    TargetVisibility.Reason vr = visibility.whyHidden(npc, self, wv);
                    if (vr != null) visCounts.merge(vr, 1, Integer::sum);
                }
            }
        }

        Stroke prev = g.getStroke();
        for (Classified entry : chickens)
        {
            Color colour = colourFor(entry.reason, entry.npc == closestEligible);
            if (colour == null) continue;
            Shape hull = entry.npc.getConvexHull();
            if (hull == null) continue;
            float strokeWidth = entry.reason == null
                ? (entry.npc == closestEligible ? CLOSEST_STROKE : ELIGIBLE_STROKE)
                : REJECTED_STROKE;
            g.setStroke(new BasicStroke(strokeWidth));
            g.setColor(colour);
            g.draw(hull);
        }
        g.setStroke(prev);

        if (config.chickenOverlayShowCounts())
        {
            drawCountsPanel(g, eligibleCount, closestDist, counts, visCounts);
        }
        return null;
    }

    /** Returns the outline colour for a chicken, or {@code null} if the
     *  user has filtered out this category in config. */
    private Color colourFor(NpcSelector.Rejection r, boolean isClosestEligible)
    {
        if (r == null) return isClosestEligible ? CLOSEST_ELIGIBLE_COLOUR : ELIGIBLE_COLOUR;
        switch (r)
        {
            case OUT_OF_RANGE:
                return config.chickenOverlayShowOutOfRange() ? OUT_OF_RANGE_COLOUR : null;
            case NOT_VISIBLE:
                return config.chickenOverlayShowNotVisible() ? NOT_VISIBLE_COLOUR : null;
            case ENGAGED_BY_OTHER:
                return config.chickenOverlayShowEngagedByOther() ? ENGAGED_BY_OTHER_COLOUR : null;
            case WRONG_PLANE:
                return config.chickenOverlayShowWrongPlane() ? WRONG_PLANE_COLOUR : null;
            case DYING:
                return config.chickenOverlayShowDying() ? DYING_COLOUR : null;
            default:
                // EXCLUDED / NULL_LOC — internal states, never displayed.
                return null;
        }
    }

    private void drawCountsPanel(Graphics2D g, int eligible, int closestDist,
                                 Map<NpcSelector.Rejection, Integer> counts,
                                 Map<TargetVisibility.Reason, Integer> visCounts)
    {
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("Chickens");
        lines.add("eligible: " + eligible
            + (eligible > 0 ? "  closest=" + closestDist : ""));
        lines.add("out-of-range: " + counts.getOrDefault(NpcSelector.Rejection.OUT_OF_RANGE, 0));
        lines.add("not-visible:  " + counts.getOrDefault(NpcSelector.Rejection.NOT_VISIBLE, 0));
        // Break the not-visible count down by sub-stage, sorted by count
        // descending — biggest culprit first. Only include reasons with
        // count > 0 so the panel doesn't sprout empty rows.
        java.util.List<Map.Entry<TargetVisibility.Reason, Integer>> sortedVis =
            new java.util.ArrayList<>(visCounts.entrySet());
        sortedVis.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<TargetVisibility.Reason, Integer> e : sortedVis)
        {
            lines.add("  " + visReasonLabel(e.getKey()) + ": " + e.getValue());
        }
        lines.add("engaged-other:" + counts.getOrDefault(NpcSelector.Rejection.ENGAGED_BY_OTHER, 0));
        int wrongPlane = counts.getOrDefault(NpcSelector.Rejection.WRONG_PLANE, 0);
        int dying = counts.getOrDefault(NpcSelector.Rejection.DYING, 0);
        if (wrongPlane > 0) lines.add("wrong-plane:  " + wrongPlane);
        if (dying > 0) lines.add("dying:        " + dying);

        Font prev = g.getFont();
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 11);
        g.setFont(mono);
        FontMetrics fm = g.getFontMetrics();
        int lineH = fm.getHeight();
        int width = 0;
        for (String s : lines) width = Math.max(width, fm.stringWidth(s));
        int padding = 6;
        int boxW = width + padding * 2;
        int boxH = lines.size() * lineH + padding * 2;
        int vx = client.getViewportXOffset();
        int vy = client.getViewportYOffset();
        int x = vx + 8;
        int y = vy + 8;
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(x, y, boxW, boxH);
        g.setColor(new Color(180, 220, 255, 255));
        g.drawRect(x, y, boxW, boxH);
        int ty = y + padding + fm.getAscent();
        for (String s : lines)
        {
            // Title gets a different colour to separate it from the counts.
            g.setColor(s.equals("Chickens") ? Color.WHITE : new Color(220, 220, 220));
            g.drawString(s, x + padding, ty);
            ty += lineH;
        }
        g.setFont(prev);
    }

    private static String visReasonLabel(TargetVisibility.Reason r)
    {
        switch (r)
        {
            case NULL_INPUT:        return "null-input";
            case PLANE_MISMATCH:    return "plane";
            case NOT_REACHABLE:     return "unreachable";
            case OFF_CANVAS:        return "off-canvas";
            case OUTSIDE_VIEWPORT:  return "viewport";
            case UNDER_OPEN_MENU:   return "menu";
            case UNDER_HUD:         return "hud";
            default:                return r.name().toLowerCase();
        }
    }

    /** A chicken plus its classifier verdict — kept as a small struct so the
     *  render loop only walks the NPC iterable once. */
    private static final class Classified
    {
        final NPC npc;
        final NpcSelector.Rejection reason;

        Classified(NPC npc, NpcSelector.Rejection reason)
        {
            this.npc = npc;
            this.reason = reason;
        }
    }
}
