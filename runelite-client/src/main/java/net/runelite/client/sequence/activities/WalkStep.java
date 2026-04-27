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
package net.runelite.client.sequence.activities;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.sequence.*;
import net.runelite.client.sequence.blackboard.Blackboard;

public final class WalkStep implements Step {
    private final WorldPoint target;
    private final int arrivalRadius;

    public WalkStep(WorldPoint target) { this(target, 1); }
    public WalkStep(WorldPoint target, int arrivalRadius) {
        this.target = target;
        this.arrivalRadius = arrivalRadius;
    }

    public WorldPoint target() { return target; }
    public int arrivalRadius() { return arrivalRadius; }

    @Override public String name() { return "WalkTo " + target; }
    @Override public int priority() { return 50; }
    @Override public int timeoutTicks() { return 200; }
    @Override public PreemptionPolicy preemptionPolicy() { return PreemptionPolicy.WHEN_SAFE; }

    @Override public boolean isSafeToPause(WorldSnapshot s, Blackboard b) { return true; }

    @Override public boolean canStart(WorldSnapshot s, Blackboard b) {
        return s.player() != null;
    }

    @Override public void onStart(StepContext ctx) {
        clickToward(ctx);
    }

    @Override public void tick(StepContext ctx) {
        WorldSnapshot s = ctx.snapshot();
        if (s.player() == null) return;
        if (s.player().isIdle() && s.player().worldLocation().distanceTo(target) > arrivalRadius) {
            clickToward(ctx);
        }
    }

    @Override public Completion check(WorldSnapshot s, Blackboard b) {
        if (s.player() == null) return Completion.RUNNING;
        if (s.player().worldLocation().distanceTo(target) <= arrivalRadius) {
            return new Completion.Succeeded("arrived at " + target);
        }
        return Completion.RUNNING;
    }

    @Override public Recovery onFailure(Failure f, WorldSnapshot s, Blackboard b) {
        return new Recovery.Retry(2);
    }

    @Override public void onEvent(Object e, StepContext ctx) { /* unused */ }

    private void clickToward(StepContext ctx) {
        WorldPoint waypoint = pickReachableWaypointToward(target, ctx.snapshot());
        if (waypoint != null) ctx.actions().walkTo(waypoint);
    }

    /**
     * Spec §16: pick the loaded scene tile closest to {@code target} that is
     * inside the current scene. The dispatcher only converts via
     * {@code LocalPoint.fromWorld}, which returns null for off-scene targets —
     * so we bound the click to a tile we know is in-scene. Successive scene
     * loads make new tiles reachable; tick() re-clicks each idle frame.
     */
    static WorldPoint pickReachableWaypointToward(WorldPoint target, WorldSnapshot snap) {
        if (snap.player() == null) return null;
        WorldPoint pos = snap.player().worldLocation();
        if (pos == null) return null;

        // If target is in-scene already, click it directly. We approximate
        // "in-scene" by distance-to-player; full check requires Client.
        int dist = pos.distanceTo(target);
        if (dist <= 50) return target;   // well inside the 104-tile scene

        // Otherwise pick the point along the straight line toward target that
        // is ~50 tiles from the player, clamped to scene plane.
        int dx = Math.signum((float) (target.getX() - pos.getX())) > 0 ? 50 : -50;
        int dy = Math.signum((float) (target.getY() - pos.getY())) > 0 ? 50 : -50;
        // Stay on the same plane; cross-plane walks are not supported in v1.
        return new WorldPoint(pos.getX() + dx, pos.getY() + dy, pos.getPlane());
    }
}
