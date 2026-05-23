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
package net.runelite.client.sequence.dispatch;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.session.AccountRng;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.transport.VerbMatcher;
import net.runelite.client.sequence.InputMode;
import net.runelite.client.sequence.internal.ActionRequest;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Drives the game with synthesized mouse and keyboard events on the AWT
 * canvas. Every click is preceded by a humanized cursor path; every key
 * press has a realistic hold duration. There is no path that bypasses
 * {@link CanvasInput} — i.e., no {@code client.menuAction} call.
 *
 * <p>Dispatch runs on a single internal worker so that the cursor path
 * for one click can play out before the next click starts. Concurrent
 * dispatch requests are rejected (see {@link #isBusy}); higher-level
 * planners are expected to serialise.
 */
@Slf4j
public class HumanizedInputDispatcher implements InputDispatcher
{
    private final Client client;
    private final ClientThread clientThread;
    private final CanvasInput input;
    private final PixelResolver resolver;
    private final WindMouse wind;
    private final Random rng;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<>(null);
    /** Callback to handle {@link ActionRequest.Kind#PICK_GE_SEARCH_RESULT}
     *  on the worker thread. Set by GE-script wiring at startup; null
     *  outside GE flows. Worker thread is safe to sleep on (per
     *  SequenceSleep contract) — this is the place that long-poll
     *  search-result widget lookups must run. */
    private volatile java.util.function.BiConsumer<Integer, String> geSearchResultPicker;

    /** Register the worker-thread handler for {@link ActionRequest.Kind#PICK_GE_SEARCH_RESULT}.
     *  Wired by {@code GrandExchangeScript} after constructing
     *  {@code GeInteraction}; calling without this set is a no-op + warn. */
    public void setGeSearchResultPicker(java.util.function.BiConsumer<Integer, String> picker)
    {
        this.geSearchResultPicker = picker;
    }

    public HumanizedInputDispatcher(Client client) { this(client, null); }

    public HumanizedInputDispatcher(Client client, ClientThread clientThread)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.input = new CanvasInput(client);
        this.resolver = new PixelResolver(client);
        AccountRng accountRng = new AccountRng(client);
        this.rng = accountRng.forAccount("dispatcher");
        this.wind = new WindMouse(accountRng.forAccount("windmouse"));
    }

    /** Consume and return the last failure reason (get-and-clear). Returns
     *  null when no error occurred. Callers that previously called this only
     *  to "clear" a stale error now get the clear for free. */
    public String lastErrorMessage() { return lastError.getAndSet(null); }

    /** Clear the last failure reason without treating it as the result of
     *  a just-finished action. */
    public void clearLastError() { lastError.set(null); }

    /** Underlying pixel resolver — exposed so route planners can ask for
     *  the engine's actual minimap range when sizing intermediate hops. */
    public PixelResolver pixelResolver() { return resolver; }

    /** Run a value-returning lambda on the client thread, blocking the
     *  caller (worker thread) until the result is available. RuneLite/OSRS
     *  scene & perspective methods assert on the client thread. */
    private <T> T onClient(Supplier<T> task) throws InterruptedException
    {
        if (clientThread == null)
        {
            // Best-effort: just call directly. Will throw if asserted, which
            // surfaces clearly via lastError instead of silently no-op'ing.
            return task.get();
        }
        CompletableFuture<T> fut = new CompletableFuture<>();
        clientThread.invoke(() -> {
            try { fut.complete(task.get()); }
            catch (Throwable th) { fut.completeExceptionally(th); }
        });
        try
        {
            // 2s is generous; should resolve within one client tick (~600ms).
            return fut.get(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        catch (java.util.concurrent.ExecutionException ee)
        {
            throw new RuntimeException(ee.getCause());
        }
        catch (java.util.concurrent.TimeoutException te)
        {
            throw new RuntimeException("client-thread task timed out", te);
        }
    }

    @Override
    public void dispatch(ActionRequest req)
    {
        if (!busy.compareAndSet(false, true))
        {
            log.info("dispatcher busy, dropping {}", req.getKind());
            return;
        }
        lastError.set(null);
        // Run the input chain off the EDT — sleep calls would freeze the UI.
        Thread t = new Thread(() -> {
            try
            {
                handle(req);
                // After the action, occasionally drift the cursor off the
                // canvas edge — real users park their hand between clicks
                // rather than holding the cursor on the last target.
                maybeParkOffEdge(req == null ? null : req.getKind());
            }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            catch (Throwable th)
            {
                log.warn("dispatch failed", th);
                lastError.set(th.getClass().getSimpleName() + ": " + th.getMessage());
            }
            finally { busy.set(false); }
        }, "humanized-input");
        t.setDaemon(true);
        t.start();
    }

    /** With a per-kind probability, send the cursor a few dozen pixels past
     *  the canvas edge so it sits "off-screen". Mimics the user dropping
     *  their hand between actions; not done after every click — sequences
     *  that have rapid follow-ups (inventory iteration, key presses) park
     *  rarely. */
    private void maybeParkOffEdge(ActionRequest.Kind kind) throws InterruptedException
    {
        double prob = parkProbability(kind);
        if (rng.nextDouble() >= prob) return;
        java.awt.Canvas c = client.getCanvas();
        if (c == null) return;
        int w = c.getWidth(), h = c.getHeight();
        if (w < 200 || h < 200) return;   // tiny canvas — skip
        // Brief pause before the drift starts. Real cursor moves don't
        // chain back-to-back at zero delay.
        SequenceSleep.sleep(client, 80 + rng.nextInt(220));
        int edge = rng.nextInt(4);
        int over = 6 + rng.nextInt(50);   // 6..55 px past the canvas edge
        int targetX, targetY;
        switch (edge)
        {
            case 0 -> { targetX = 30 + rng.nextInt(Math.max(1, w - 60)); targetY = -over; }
            case 1 -> { targetX = w + over; targetY = 30 + rng.nextInt(Math.max(1, h - 60)); }
            case 2 -> { targetX = 30 + rng.nextInt(Math.max(1, w - 60)); targetY = h + over; }
            default -> { targetX = -over; targetY = 30 + rng.nextInt(Math.max(1, h - 60)); }
        }
        log.info("park cursor → ({},{}) [edge={}]", targetX, targetY, edge);
        moveCursorTo(targetX, targetY);
    }

    /** Rotate the camera so it faces toward {@code target} from the player's
     *  current position, over 500–1500ms. The engine's
     *  {@link Client#setCameraYawTarget(int)} normally produces a quick
     *  snap-and-settle to the target; calling it once gives a "instant"
     *  feel that doesn't match a real player dragging the camera. Instead
     *  we *stream* the target — many small updates with smoothstep easing
     *  — so the engine's per-frame chase tracks a slowly-moving goal,
     *  yielding a visibly smooth controlled drag. Yaw is 11-bit (0..2047);
     *  0 = south, increasing counter-clockwise from above. */
    public void rotateCameraToward(WorldPoint target) throws InterruptedException
    {
        rotateCameraToward(target, false);
    }

    /** Force-variant: when {@code force} is true, the
     *  "already comfortably visible" early-exit is skipped. The yaw-tolerance
     *  check still applies, so we still no-op when already pointed at the
     *  target. Use this for clicks where the target tile may project into
     *  the viewport geometrically but be occluded by a wall (e.g. cooking
     *  on a fire across a wall in Lumbridge Castle P2): real players turn
     *  the camera to actually see the object before clicking, even if the
     *  underlying tile poly is technically inside the viewport rect. */
    public void rotateCameraToward(WorldPoint target, boolean force) throws InterruptedException
    {
        if (target == null) return;
        if (!force)
        {
            // If the target tile is already comfortably inside the viewport, a
            // real player wouldn't pan the camera — they can already see it.
            // Skipping here is the dominant humanization: after a kill, the
            // loot tile is right under where we were just fighting, so the
            // follow-up loot click rotates only when actually needed.
            Boolean alreadyVisible = onClient(() -> isTileComfortablyVisible(target));
            if (Boolean.TRUE.equals(alreadyVisible))
            {
                log.debug("rotate camera skipped — target {} already on screen", target);
                return;
            }
        }
        doRotateOnce(target, ROTATION_OFFSET_MIN_UNITS, ROTATION_OFFSET_MAX_UNITS);
    }

    /** Verify-and-retry variant of {@link #rotateCameraToward(WorldPoint, boolean)}.
     *  Runs the same humanized off-axis first pass, then checks whether
     *  the target ended up comfortably inside the viewport. If not, runs
     *  one tight corrective pass (≤30°) so the next operation that
     *  depends on the target being on-screen (e.g. {@link PixelResolver}
     *  pixel resolution for a verb-click on a far-away transport tile)
     *  actually has the geometry it needs.
     *
     *  <p>Opt-in: do not call from naturalistic-rotation contexts (ambient
     *  camera drift, walking-heading tracking) — the second pass actively
     *  frames the target, which changes their intended "off-axis but
     *  in-view" semantics. Lumby pizza→bank staircase 2026-05-19: wide
     *  humanized jitter landed the staircase past the FOV edge, causing
     *  ~6 unresolvable retries × 6.5 s settle = 40 s lockup. */
    public void rotateCameraTowardEnsuringVisible(WorldPoint target, boolean force) throws InterruptedException
    {
        if (target == null) return;
        if (!force)
        {
            Boolean alreadyVisible = onClient(() -> isTileComfortablyVisible(target));
            if (Boolean.TRUE.equals(alreadyVisible))
            {
                log.debug("rotate camera skipped (ensure-visible) — target {} already on screen", target);
                return;
            }
        }
        doRotateOnce(target, ROTATION_OFFSET_MIN_UNITS, ROTATION_OFFSET_MAX_UNITS);
        Boolean nowVisible = onClient(() -> isTileComfortablyVisible(target));
        if (Boolean.FALSE.equals(nowVisible))
        {
            log.debug("rotate camera (ensure-visible): target {} still off-screen — tight corrective", target);
            doRotateOnce(target, ROTATION_OFFSET_TIGHT_MIN_UNITS, ROTATION_OFFSET_TIGHT_MAX_UNITS);
        }
    }

    /** One yaw + pitch rotation pass toward {@code target}. The off-axis
     *  offset is randomly picked in {@code [minOffset, maxOffset]} yaw
     *  units (CW or CCW). Caller handles the already-visible early-exit
     *  and the verify-and-retry. */
    private void doRotateOnce(WorldPoint target, int minOffset, int maxOffset) throws InterruptedException
    {
        // Read player tile, compute base yaw + chebyshev distance to
        // target in ONE client-thread marshal. The chebyshev value is
        // what {@link #pickPitchForDistance} uses to decide how steep
        // a vertical tilt the camera should land at.
        RotationInput rotIn = onClient(() -> {
            var local = client.getLocalPlayer();
            if (local == null || local.getWorldLocation() == null) return null;
            WorldPoint here = local.getWorldLocation();
            int dx = target.getX() - here.getX();
            int dy = target.getY() - here.getY();
            if (dx == 0 && dy == 0) return null;
            double angle = Math.atan2(-dx, -dy);
            int yaw = ((int) Math.round(angle * 2048.0 / (2 * Math.PI))) & 0x7FF;
            int cheb = Math.max(Math.abs(dx), Math.abs(dy));
            return new RotationInput(yaw, cheb);
        });
        if (rotIn == null) return;
        int sign = rng.nextBoolean() ? 1 : -1;
        int range = Math.max(0, maxOffset - minOffset);
        int jitterMag = minOffset + (range > 0 ? rng.nextInt(range + 1) : 0);
        final int desiredYaw = (rotIn.baseYaw() + sign * jitterMag + 2048) & 0x7FF;

        // Pitch target picked from target distance (or null when the
        // ROTATE_USE_PITCH toggle is off). Lower JAU = camera tilted
        // up / horizon flatter; higher = top-down. Far targets pick
        // PITCH_FAR so the player can see them on the horizon; close
        // targets pick PITCH_CLOSE so the model isn't sliced off by
        // the screen edge.
        final Integer desiredPitch = ROTATE_USE_PITCH
            ? pickPitchForDistance(rotIn.chebyshev())
            : null;

        // Read current yaw target + actual pitch in ONE marshal so the
        // step loop's deltas are off the same snapshot.
        CameraState curState = onClient(() -> new CameraState(
            client.getCameraYawTarget() & 0x7FF,
            client.getCameraPitch()));
        if (curState == null) return;
        int yawSignedDiff = ((desiredYaw - curState.yaw() + 1024 + 2048) % 2048) - 1024;
        final boolean yawNeedsMove = Math.abs(yawSignedDiff) >= YAW_TOLERANCE;
        int pitchSignedDiff = desiredPitch != null ? desiredPitch - curState.pitch() : 0;
        final boolean pitchNeedsMove = desiredPitch != null
            && Math.abs(pitchSignedDiff) >= PITCH_TOLERANCE;
        if (!yawNeedsMove && !pitchNeedsMove) return;

        // OSRS arrow-key cadence: constant angular velocity, no easing.
        // {@code setCameraYawTarget} updates the rendered yaw on the
        // next frame — it doesn't interpolate by itself — so a real
        // animation needs us to walk the target from current → final in
        // small steps. Same applies to pitch. Step count is sized to
        // whichever axis has more travel so both axes finish together.
        int yawMag = yawNeedsMove ? Math.abs(yawSignedDiff) : 0;
        int pitchMag = pitchNeedsMove ? Math.abs(pitchSignedDiff) : 0;
        int totalMs = Math.max(yawMag, pitchMag) * MS_PER_YAW_UNIT;
        int stepMs = ROTATION_STEP_MS;
        int steps = Math.max(MIN_ROTATION_STEPS, totalMs / stepMs);
        log.debug("rotate camera: yaw {} → {} (Δ={}, jitter={}{}), pitch {} → {} (Δ={}, cheb={}), {}ms over {} steps",
            curState.yaw(), desiredYaw, yawSignedDiff, sign > 0 ? "+" : "-", jitterMag,
            curState.pitch(), desiredPitch, pitchSignedDiff, rotIn.chebyshev(),
            totalMs, steps);
        final int yawFrom = curState.yaw();
        final int yawDiff = yawSignedDiff;
        final int pitchFrom = curState.pitch();
        final int pitchDiff = pitchSignedDiff;
        for (int i = 1; i <= steps; i++)
        {
            double t = (double) i / steps;
            final int interimYaw = yawNeedsMove
                ? (yawFrom + (int) Math.round(yawDiff * t) + 2048) & 0x7FF
                : yawFrom;
            final int interimPitch = pitchNeedsMove
                ? clampInt(pitchFrom + (int) Math.round(pitchDiff * t), PITCH_MIN, PITCH_MAX)
                : pitchFrom;
            try
            {
                onClient(() -> {
                    if (yawNeedsMove) client.setCameraYawTarget(interimYaw);
                    if (pitchNeedsMove) client.setCameraPitchTarget(interimPitch);
                    return null;
                });
            }
            catch (Throwable ignored) { /* best-effort */ }
            SequenceSleep.sleep(client, stepMs);
        }
    }

    /** Choose a target camera pitch based on chebyshev distance from
     *  player to target. Closer targets get a steeper (more top-down)
     *  pitch so the model isn't clipped at the screen edge; far targets
     *  get a flatter pitch so the player can see them at all. ±{@link
     *  #PITCH_JITTER} of randomness avoids landing on the same pitch
     *  every rotation. */
    private int pickPitchForDistance(int chebyshev)
    {
        int base;
        if (chebyshev <= PITCH_CHEBYSHEV_CLOSE_MAX) base = PITCH_CLOSE;
        else if (chebyshev <= PITCH_CHEBYSHEV_MID_MAX) base = PITCH_MID;
        else base = PITCH_FAR;
        int jitter = rng.nextInt(PITCH_JITTER * 2 + 1) - PITCH_JITTER;
        return clampInt(base + jitter, PITCH_MIN, PITCH_MAX);
    }

    private static int clampInt(int v, int lo, int hi)
    {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Client-thread: true if the target tile's canvas polygon is fully
     *  inside the playable viewport with a comfortable margin. Used by
     *  {@link #rotateCameraToward} to skip pan-to-target when a real
     *  player would already see the tile and not bother adjusting. */
    private boolean isTileComfortablyVisible(WorldPoint target)
    {
        try
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), target);
            if (lp == null) return false;
            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) return false;
            Rectangle bb = poly.getBounds();
            int vx = client.getViewportXOffset();
            int vy = client.getViewportYOffset();
            int vw = client.getViewportWidth();
            int vh = client.getViewportHeight();
            // Margin keeps us from skipping when the tile is right at the
            // edge of the screen — those are the cases where panning a
            // little still looks natural. 16 px is roughly half a tile
            // at default zoom; if the tile poly sits half a tile inside
            // the viewport, a click can resolve cleanly without panning.
            // The original 40 px ≈ one tile was too generous and triggered
            // wide off-axis rotation in scripts where the target was
            // already plainly visible (rooftop agility — every obstacle).
            int margin = 16;
            return bb.x >= vx + margin
                && bb.y >= vy + margin
                && bb.x + bb.width <= vx + vw - margin
                && bb.y + bb.height <= vy + vh - margin;
        }
        catch (Throwable th) { return false; }
    }

    /** Don't bother rotating for headings within ~22° of current yaw —
     *  the small adjustment looks twitchy. */
    private static final int YAW_TOLERANCE = 128;

    /** Minimum random offset applied to the rotation target so the
     *  destination never sits dead-centre. 228 units ≈ 40°. */
    private static final int ROTATION_OFFSET_MIN_UNITS = 228;

    /** Maximum random offset applied to the rotation target. 342 units
     *  ≈ 60°. The original 683 (~120°) was wide enough to swing the
     *  target past the FOV edge — symptomatic on rooftop agility, where
     *  the camera would rotate "to the wrong side", the obstacle would
     *  fall out of view, and the next click would need another rotation
     *  to bring it back. 60° still keeps the target off dead-centre
     *  (min is 40°) but never throws it across the screen. */
    private static final int ROTATION_OFFSET_MAX_UNITS = 342;

    /** Minimum offset for the verify-and-retry tight pass. 28 units ≈ 5° —
     *  preserves the "never bolt-centred on target" rule so the camera
     *  doesn't land head-on with the player walking dead-away from view. */
    private static final int ROTATION_OFFSET_TIGHT_MIN_UNITS = 28;

    /** Maximum offset for the verify-and-retry tight pass when the first
     *  humanized pass leaves the target off-screen. 171 units ≈ 30° —
     *  comfortably inside OSRS's ~85° horizontal FOV at all pitches we
     *  use, so the target ends up well-framed for pixel resolution. */
    private static final int ROTATION_OFFSET_TIGHT_MAX_UNITS = 171;

    /** Linear rotation cadence: ms per yaw unit. 2 ms × 2048 units ≈
     *  4.1 s for a full 360°, matching OSRS arrow-key pan speed at
     *  default {@code cameraSpeed=1.0} (≈90°/s). */
    private static final int MS_PER_YAW_UNIT = 2;

    /** Frame interval between rotation steps. 25 ms ≈ 40 fps — high
     *  enough that consecutive interim yaws blend into smooth motion,
     *  low enough not to saturate the client thread with marshals on a
     *  long rotation. Fixed (not jittered) so the cadence is uniform. */
    private static final int ROTATION_STEP_MS = 25;

    /** Floor on step count so very small rotations don't snap. 8 steps
     *  × 25 ms = 200 ms minimum motion duration even for a 30° turn. */
    private static final int MIN_ROTATION_STEPS = 8;

    /** Master toggle for adding camera pitch (up/down tilt) to
     *  {@link #rotateCameraToward}. Flip to {@code false} to revert to
     *  yaw-only side-to-side rotation if the pitch behaviour ever
     *  causes regressions (the rest of the rotation logic is unaffected). */
    private static final boolean ROTATE_USE_PITCH = true;

    /** Pitch values are in JAU (1024 = full revolution). Lower numeric
     *  pitch = camera tilted up (more level with horizon, sees further
     *  out). Higher = more top-down (camera looks straight down at
     *  player). Defaults stay inside normal OSRS player limits so the
     *  Camera plugin's {@code relaxCameraPitch} toggle is not required. */
    private static final int PITCH_FAR   = 200;   // distant target → flatter, see further
    private static final int PITCH_MID   = 245;   // close to OSRS in-game default
    private static final int PITCH_CLOSE = 320;   // close target → more top-down
    private static final int PITCH_JITTER = 30;   // ±30 JAU random per rotation
    private static final int PITCH_MIN   = 128;
    private static final int PITCH_MAX   = 383;
    /** Skip pitch motion when actual pitch is already within this many
     *  JAU of the desired target — avoids twitchy micro-tilts. */
    private static final int PITCH_TOLERANCE = 40;
    /** Chebyshev distance bands used by {@link #pickPitchForDistance}. */
    private static final int PITCH_CHEBYSHEV_CLOSE_MAX = 4;
    private static final int PITCH_CHEBYSHEV_MID_MAX   = 12;

    /** Probability of a post-action park, by action kind. Walking and
     *  long-tap actions park more often (the user's hand naturally drops);
     *  inventory iteration and key presses park rarely (next click is
     *  imminent). */
    private double parkProbability(ActionRequest.Kind kind)
    {
        if (kind == null) return 0.10;
        return switch (kind)
        {
            case WALK, CLICK_TILE -> 0.30;
            case CLICK_NPC -> 0.20;
            // After opening a door / climbing a ladder, the next action is
            // typically immediate (continuing the route) — don't park.
            case CLICK_GAME_OBJECT, CLICK_GROUND_ITEM -> 0.05;
            case CLICK_WIDGET, CLICK_INV_ITEM -> 0.08;
            case KEY -> 0.04;
            default -> 0.12;
        };
    }

    private void handle(ActionRequest req) throws InterruptedException
    {
        switch (req.getKind())
        {
            case WALK, CLICK_TILE -> walkClick(req.getTile(), req.getKind() == ActionRequest.Kind.WALK && req.isStrictWalk());
            case CLICK_NPC -> npcClick(req.getNpcIndex(),
                req.getVerb() == null || req.getVerb().isBlank() ? "Attack" : req.getVerb());
            case CLICK_GAME_OBJECT -> gameObjectClick(req.getTile(), req.getVerb(), req.isLiveTracked(), req.isEnsureVisibleRotation());
            case CLICK_GROUND_ITEM -> groundItemClick(req.getTile(), req.getItemId(), req.getVerb());
            case CLICK_WIDGET -> {
                String verb = req.getVerb();
                if (verb == null || verb.isBlank()) widgetClick(req.getWidgetId());
                else if (verb.indexOf('|') < 0) widgetVerbClick(req.getWidgetId(), verb);
                else widgetAnyVerbClick(req.getWidgetId(),
                    java.util.Arrays.stream(verb.split("\\|"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList());
            }
            case CLICK_INV_ITEM -> invSlotClick(Math.max(0, req.getSlot()), req.getVerb());
            case CLICK_BOUNDS -> {
                String verb = req.getVerb();
                if (verb != null && !verb.isBlank() && verb.indexOf('|') >= 0)
                {
                    boundsAnyVerbClick(req.getBounds(),
                        java.util.Arrays.stream(verb.split("\\|"))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList());
                }
                else boundsClick(req.getBounds(), verb);
            }
            case TYPE_CHATBOX -> typeChatboxInternal(
                req.getTypeText(),
                req.getTypeAwaitMs(),
                req.getTypeDwellMinMs(),
                req.getTypeDwellMaxMs(),
                req.isTypePressEnter());
            case PICK_GE_SEARCH_RESULT -> {
                java.util.function.BiConsumer<Integer, String> picker = geSearchResultPicker;
                if (picker == null) {
                    log.warn("PICK_GE_SEARCH_RESULT dispatched but no picker registered");
                } else {
                    picker.accept(req.getItemId(), req.getPickName());
                }
            }
            case RUN_TASK -> {
                BlockingTask task = req.getTask();
                String taskName = req.getTaskName() == null ? "<unnamed>" : req.getTaskName();
                if (task == null) {
                    log.warn("RUN_TASK dispatched without a task ({})", taskName);
                } else {
                    log.info("RUN_TASK begin: {}", taskName);
                    long t0 = System.currentTimeMillis();
                    try { task.run(); }
                    finally {
                        log.info("RUN_TASK end:   {} ({}ms)", taskName,
                            System.currentTimeMillis() - t0);
                    }
                }
            }
            case KEY -> keyTap(req.getKeyCode());
            default -> log.debug("unhandled kind {}", req.getKind());
        }
    }

    /** Walk to a world tile via canvas click — no menuAction shortcut.
     *
     *  <p>All scene/perspective reads happen on the client thread (RuneLite
     *  asserts on it for {@code Perspective.localToCanvas},
     *  {@code getCanvasTilePoly}, etc.). Cursor moves and click presses run
     *  on the worker thread because they sleep between samples.
     *
     *  <p>{@code strict} disables the silent minimap fallback when
     *  {@code isLeftClickWalk} reports a non-walk top verb. The caller
     *  (V2's executor) requires a different-tile pick instead of a
     *  modality switch, per the spec's HARD CONSTRAINT.
     */
    private void walkClick(WorldPoint target, boolean strict) throws InterruptedException
    {
        if (target == null) { lastError.set("null target"); return; }
        // Skip if we're already standing on the target tile — clicking your
        // own tile resolves to 'Cancel' and wastes a humanization round trip.
        Boolean alreadyHere = onClient(() -> {
            var local = client.getLocalPlayer();
            return local != null && target.equals(local.getWorldLocation());
        });
        if (Boolean.TRUE.equals(alreadyHere))
        {
            log.info("walk skipped — already on target tile {}", target);
            return;
        }
        // Rotate camera toward the walk vector if the target is well off
        // to the side or behind us. Real players turn the camera in the
        // direction of travel — keeps the destination visible in main-view
        // and looks coherent.
        rotateCameraToward(target);
        Point pixel = onClient(() -> resolver.resolveWalkTarget(target));
        if (pixel == null)
        {
            lastError.set("not resolvable (out of reach / off-camera)");
            log.info("walk target {} not resolvable", target);
            return;
        }
        log.info("walk → world {} via screen ({},{})", target, pixel.getX(), pixel.getY());
        moveCursorTo(pixel.getX(), pixel.getY());
        // Give the engine ~2 render frames to compute hover-state menu entries.
        SequenceSleep.sleep(client, 50);

        // Minimap hover never produces 'Walk here' menu entries — the engine
        // routes minimap left-clicks via widget hit-test on press, not via
        // the menu system. So the menu check is meaningless for those
        // candidates: skip it and click directly. The pre-check still
        // matters for main-view candidates so we don't accidentally
        // "Attack Chicken" instead of walking.
        boolean onMinimap = onClient(() -> resolver.isMinimapPixel(pixel));
        if (onMinimap)
        {
            log.info("minimap click → clicking without menu pre-check");
            press(MouseEvent.BUTTON1, ClickIntent.MINIMAP, PressTiming.STANDARD);
            return;
        }

        boolean walkOk = onClient(this::isLeftClickWalk);
        if (!walkOk)
        {
            String top = onClient(this::topMenuLabel);
            String dump = onClient(this::fullMenuDump);
            if (strict)
            {
                lastError.set("strict-walk: menu was '" + top + "' — caller must pick a different tile");
                log.info("strict walk at ({},{}) — top='{}' not WALK; aborting (no minimap fallback). {}",
                    pixel.getX(), pixel.getY(), top, dump);
                return;
            }
            log.info("hover at ({},{}) resolved to '{}' (not WALK) — minimap fallback. {}",
                pixel.getX(), pixel.getY(), top, dump);
            Point mm = onClient(() -> resolver.resolveMinimapOnly(target));
            if (mm == null)
            {
                lastError.set("menu was '" + top + "', minimap fallback unavailable");
                log.warn("minimap fallback unavailable for {} (initial menu='{}')", target, top);
                return;
            }
            log.info("minimap fallback → ({},{}) [no menu pre-check]", mm.getX(), mm.getY());
            moveCursorTo(mm.getX(), mm.getY());
            SequenceSleep.sleep(client, 30);
            // Don't re-check the menu — minimap clicks never populate it.
            press(MouseEvent.BUTTON1, ClickIntent.MINIMAP, PressTiming.STANDARD);
            return;
        }
        // Main-view world click. If the dead-zone guard blocks (cursor
        // ended up in chatbox / side panel / orb / minimap), retry via
        // the same minimap-fallback shape used when isLeftClickWalk
        // reported a non-walk top verb.
        if (press(MouseEvent.BUTTON1, ClickIntent.WORLD, PressTiming.STANDARD))
        {
            return;
        }
        if (strict)
        {
            log.info("strict walk at ({},{}) — press blocked by dead-zone; aborting",
                pixel.getX(), pixel.getY());
            return;
        }
        log.info("press at ({},{}) blocked by dead-zone — minimap fallback",
            pixel.getX(), pixel.getY());
        Point mm = onClient(() -> resolver.resolveMinimapOnly(target));
        if (mm == null)
        {
            lastError.set("press blocked by dead-zone, minimap fallback unavailable");
            log.warn("minimap fallback unavailable for {} after dead-zone block", target);
            return;
        }
        log.info("minimap fallback → ({},{}) [no menu pre-check]", mm.getX(), mm.getY());
        moveCursorTo(mm.getX(), mm.getY());
        SequenceSleep.sleep(client, 30);
        press(MouseEvent.BUTTON1, ClickIntent.MINIMAP, PressTiming.STANDARD);
    }

    private void moveCursorTo(int x, int y) throws InterruptedException
    {
        int startX = input.cursorX() < 0 ? client.getCanvas().getWidth() / 2 : input.cursorX();
        int startY = input.cursorY() < 0 ? client.getCanvas().getHeight() / 2 : input.cursorY();
        for (var s : wind.path(startX, startY, x, y))
        {
            input.mouseMove(s.x(), s.y());
            SequenceSleep.sleep(client, s.dtMs());
        }
    }

    /** The single chokepoint every press goes through. Combines
     *  off-canvas rejection, UI dead-zone rejection (WORLD intent only),
     *  and timing-driven sleeps that mirror humanized cursor traces.
     *  Returns {@code true} when the press fired; {@code false} when a
     *  guard blocked it ({@link #lastError} is set with the reason).
     *
     *  <p>See the spec at
     *  {@code docs/superpowers/specs/2026-05-22-chatbox-deadzone-click-guard.md}.
     *
     *  <p>Guards by intent:
     *  <ul>
     *    <li>{@link ClickIntent#WORLD} — off-canvas + UI dead-zone
     *        ({@link UiDeadZones#worldIntersectsAt}). Refuses to press
     *        when the cursor is inside chatbox / side panel / compass /
     *        orb cluster / minimap.</li>
     *    <li>{@link ClickIntent#MINIMAP}, {@link ClickIntent#UI},
     *        {@link ClickIntent#MENU_ROW} — off-canvas only. These
     *        intents legitimately click into UI surfaces.</li>
     *    <li>{@link ClickIntent#RAW} — no guards. Reserved for test
     *        hooks and low-level escape hatches.</li>
     *  </ul>
     *
     *  <p>Grep invariant: {@code input.mousePress} and
     *  {@code input.mouseRelease} appear in exactly this one method.
     *  Any other survivor is a regression. */
    private boolean press(int button, ClickIntent intent, PressTiming timing)
        throws InterruptedException
    {
        int cx = input.cursorX();
        int cy = input.cursorY();

        // Off-canvas: applies to every intent, including RAW.
        // (A test hook that wants to fire outside the canvas can
        // call input.mousePress / mouseRelease directly — but that
        // path is exactly what we're closing off in production code.)
        if (!onCanvas(cx, cy))
        {
            lastError.set("press blocked: off-canvas at (" + cx + "," + cy
                + ") intent=" + intent);
            log.info("press blocked off-canvas at ({},{}) intent={}",
                cx, cy, intent);
            return false;
        }

        // WORLD-intent dead-zone: chatbox, sidebars, compass, orb
        // cluster, minimap drawable area. Other intents legitimately
        // click into these surfaces and bypass.
        if (intent == ClickIntent.WORLD)
        {
            java.awt.Rectangle hit = onClient(
                () -> UiDeadZones.worldIntersectsAt(client, cx, cy));
            if (hit != null)
            {
                lastError.set("press blocked: cursor in UI dead-zone " + hit
                    + " at (" + cx + "," + cy + ") intent=WORLD");
                log.info("press blocked dead-zone {} at ({},{}) intent=WORLD",
                    hit, cx, cy);
                return false;
            }
        }

        // Timing-driven sleeps. Pre-dwell only applies when the caller
        // hasn't already settled (STANDARD profile); the FAST-COMMIT
        // profiles dial it down because the caller's last move/menu
        // step already gave the engine settle time.
        SequenceSleep.sleep(client, timing.samplePreMs());
        input.mousePress(button);
        SequenceSleep.sleep(client, timing.sampleHoldMs());
        input.mouseRelease(button);
        SequenceSleep.sleep(client, timing.samplePostMs());
        return true;
    }

    /** True when {@code (x, y)} is inside the canvas with a small
     *  inset margin (matches {@link PixelResolver}'s on-canvas rule). */
    private boolean onCanvas(int x, int y)
    {
        java.awt.Canvas c = client.getCanvas();
        if (c == null) return false;
        return x >= 4 && y >= 4 && x < c.getWidth() - 4 && y < c.getHeight() - 4;
    }

    /** True if the engine's would-be left-click action at the current cursor
     *  is WALK or SET_HEADING. Reads {@code Menu.getMenuEntries()} which
     *  the engine populates per frame based on cursor position. The top of
     *  menu (= last array index) is the left-click action. */
    private boolean isLeftClickWalk()
    {
        try
        {
            MenuEntry[] entries = client.getMenu() == null ? null : client.getMenu().getMenuEntries();
            if (entries == null || entries.length == 0) return false;
            MenuEntry top = entries[entries.length - 1];
            MenuAction t = top.getType();
            return t == MenuAction.WALK || t == MenuAction.SET_HEADING;
        }
        catch (Throwable th)
        {
            log.debug("menu read failed", th);
            return false;
        }
    }

    private String topMenuLabel()
    {
        try
        {
            MenuEntry[] entries = client.getMenu().getMenuEntries();
            if (entries == null || entries.length == 0) return "(empty)";
            MenuEntry top = entries[entries.length - 1];
            String opt = top.getOption() == null ? "?" : top.getOption();
            String tgt = top.getTarget() == null ? "" : top.getTarget();
            return opt + " " + tgt;
        }
        catch (Throwable th) { return "(error)"; }
    }

    /** Comma-separated list of every menu entry the engine has computed at
     *  the current cursor position, plus the engine's own perception of
     *  where that cursor is. Only used for diagnostics when a click
     *  unexpectedly resolves to 'Cancel'. */
    private String fullMenuDump()
    {
        try
        {
            net.runelite.api.Point p = client.getMouseCanvasPosition();
            String pos = p == null ? "?" : ("(" + p.getX() + "," + p.getY() + ")");
            MenuEntry[] entries = client.getMenu() == null ? null : client.getMenu().getMenuEntries();
            if (entries == null || entries.length == 0) return "engineCursor=" + pos + " menu=(empty)";
            StringBuilder sb = new StringBuilder("engineCursor=").append(pos).append(" menu=[");
            for (int i = entries.length - 1; i >= 0; i--)
            {
                MenuEntry e = entries[i];
                if (i < entries.length - 1) sb.append(", ");
                sb.append("'").append(e.getOption()).append("'");
                if (e.getTarget() != null && !e.getTarget().isEmpty())
                    sb.append(" '").append(e.getTarget()).append("'");
                sb.append(" type=").append(e.getType());
            }
            sb.append("]");
            return sb.toString();
        }
        catch (Throwable th) { return "(dump error: " + th.getClass().getSimpleName() + ")"; }
    }

    /** Click an NPC with full humanization sequence: camera rotate toward
     *  target, rough pre-aim move, settle, then re-resolve the actual
     *  interactable pixel and press immediately.
     *
     *  <p>The "humanize then measure" ordering is deliberate: cursor
     *  humanization + pre-click settle takes ~700-1500ms, during which the
     *  chicken can step or the camera can drift. Resolving the click pixel
     *  AFTER the bulk of the wait means the press lands on the chicken's
     *  current model hull, not its position from a second ago.
     *
     *  <p>Smart click selection: if the engine's left-click default at the
     *  freshly-aimed pixel is already {@code verb} on this NPC (typical for
     *  combat NPCs in an empty area), press immediately. Otherwise fall
     *  through to a right-click + menu-row pick — necessary when other
     *  actors share the cursor pixel or {@code verb} isn't the default
     *  action (e.g. "Talk-to" on a shopkeeper).
     */
    private void npcClick(int npcIndex, String verb) throws InterruptedException
    {
        boolean attackVerb = VerbMatcher.matches("Attack", verb);
        // Phase 1 — read the NPC's world tile + a rough on-canvas pre-aim
        // point (tile centre is "in the area"; final aim uses the model
        // hull a few hundred ms from now).
        NpcAim aim = onClient(() -> {
            NPC npc = findNpc(npcIndex);
            if (npc == null) return null;
            WorldPoint world = npc.getWorldLocation();
            Polygon tilePoly = npc.getCanvasTilePoly();
            if (tilePoly == null) return null;
            Rectangle bb = tilePoly.getBounds();
            return new NpcAim(world, new Point(bb.x + bb.width / 2, bb.y + bb.height / 2));
        });
        if (aim == null || aim.preAim() == null)
        {
            lastError.set("npc " + npcIndex + " not on screen");
            return;
        }

        // Phase 2 — rotate camera toward the NPC. Centres the target in the
        // viewport and gives the click a natural "look then click" rhythm.
        // No-op if already pointed there.
        if (aim.world() != null) rotateCameraToward(aim.world());

        // Phase 3 — long humanized cursor move toward the rough target.
        moveCursorTo(aim.preAim().getX(), aim.preAim().getY());

        // Phase 4 — pre-click settle. Attack verb on NPCs that walk every
        // tick (chickens, ducks, sheep) gets a tighter 60–140ms window:
        // every ~250ms of settle on top of phase-3 cursor travel hands the
        // target another step of headstart, so by the time we press we've
        // committed to the tile they were standing on a tick ago.
        int phase4Floor = attackVerb ? 60 : 180;
        int phase4Span  = attackVerb ? 80 : 220;
        SequenceSleep.sleep(client, phase4Floor + rng.nextInt(phase4Span));

        // Phase 5 — re-resolve against the live NPC: world tile AND model
        // hull. The hull projection already reflects the NPC's current 3D
        // position, but logging the tile change separately surfaces "the
        // chicken moved during our aim" in traces, and keeps the world
        // tile available for downstream prediction work.
        AimRefresh refresh = onClient(() -> {
            NPC npc = findNpc(npcIndex);
            if (npc == null) return null;
            return new AimRefresh(npc.getWorldLocation(), resolver.resolveNpc(npc));
        });
        if (refresh == null || refresh.clickPixel() == null)
        {
            lastError.set("npc " + npcIndex + " disappeared during aim");
            return;
        }
        Point clickPixel = refresh.clickPixel();
        if (refresh.world() != null && aim.world() != null
            && !refresh.world().equals(aim.world()))
        {
            log.debug("npc {} moved during aim: {} → {}",
                npcIndex, aim.world(), refresh.world());
        }

        // Phase 6 — final aim adjustment if the fresh pixel is off the
        // pre-aim. Tighter threshold for attack verb (2px instead of 4px)
        // so a 1-tile drift always triggers a fresh aim — chickens are
        // small enough that the hull center moves > 2px on any step.
        int dx = clickPixel.getX() - aim.preAim().getX();
        int dy = clickPixel.getY() - aim.preAim().getY();
        int reaimThresholdSq = attackVerb ? 4 : 16;   // 2px² vs 4px²
        if (dx * dx + dy * dy > reaimThresholdSq)
        {
            moveCursorTo(clickPixel.getX(), clickPixel.getY());
        }
        int phase6Floor = attackVerb ? 20 : 40;
        int phase6Span  = attackVerb ? 20 : 40;
        SequenceSleep.sleep(client, phase6Floor + rng.nextInt(phase6Span));

        Boolean claimedByOther = attackVerb
            ? onClient(() -> isNpcClaimedByOtherPlayer(npcIndex))
            : Boolean.FALSE;
        if (Boolean.TRUE.equals(claimedByOther))
        {
            lastError.set("npc already in combat with another player " + npcIndex);
            log.info("npc {} already in combat with another player — refusing attack click", npcIndex);
            return;
        }

        // Phase 7 — verify hover is `verb` on OUR npc, just before press.
        Boolean topMatches = onClient(() -> isTopVerbOnNpc(verb, npcIndex));
        if (Boolean.TRUE.equals(topMatches))
        {
            // Phase 8 — left-click. Settle above already covered the
            // "commit" beat; another long pre-click sleep would just give
            // the chicken time to step out from under us.
            claimedByOther = attackVerb
                ? onClient(() -> isNpcClaimedByOtherPlayer(npcIndex))
                : Boolean.FALSE;
            if (Boolean.TRUE.equals(claimedByOther))
            {
                lastError.set("npc already in combat with another player " + npcIndex);
                log.info("npc {} became claimed before left-click — refusing attack", npcIndex);
                return;
            }
            press(MouseEvent.BUTTON1, ClickIntent.WORLD, PressTiming.FAST_COMMIT);
            return;
        }

        // Phase 9 — right-click flow. Hover-default isn't our verb (another
        // actor under cursor, or `verb` isn't this NPC's default action).
        // Open the context menu and click the matching entry.
        log.info("npc {} not hover-default for '{}' — right-click flow", npcIndex, verb);
        press(MouseEvent.BUTTON3, ClickIntent.WORLD, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        MenuRow row = onClient(() -> findMenuRow(
            e -> VerbMatcher.matches(verb, e.getOption()) && e.getIdentifier() == npcIndex));
        if (row == null)
        {
            lastError.set("menu missing '" + verb + "' on npc " + npcIndex);
            log.info("right-click menu did not contain '{}' for npc {}", verb, npcIndex);
            return;
        }
        claimedByOther = attackVerb
            ? onClient(() -> isNpcClaimedByOtherPlayer(npcIndex))
            : Boolean.FALSE;
        if (Boolean.TRUE.equals(claimedByOther))
        {
            lastError.set("npc already in combat with another player " + npcIndex);
            log.info("npc {} became claimed before menu click — refusing attack", npcIndex);
            dismissMenu();
            SequenceSleep.sleep(client, 120);
            return;
        }
        moveCursorTo(row.x, row.y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        claimedByOther = attackVerb
            ? onClient(() -> isNpcClaimedByOtherPlayer(npcIndex))
            : Boolean.FALSE;
        if (Boolean.TRUE.equals(claimedByOther))
        {
            lastError.set("npc already in combat with another player " + npcIndex);
            log.info("npc {} became claimed before menu left-click — refusing attack", npcIndex);
            dismissMenu();
            SequenceSleep.sleep(client, 120);
            return;
        }
        press(MouseEvent.BUTTON1, ClickIntent.MENU_ROW, PressTiming.MENU_SELECTION);
    }

    /** Click target capture for {@link #npcClick}. {@code world} feeds the
     *  camera-rotate step; {@code preAim} is the rough cursor target for
     *  the long humanized move. Final click pixel is re-resolved later. */
    private record NpcAim(@javax.annotation.Nullable WorldPoint world, Point preAim) {}

    /** Refreshed NPC aim — captured right before the press. {@code world}
     *  is the NPC's current tile (may differ from {@link NpcAim#world()}
     *  if it stepped during phases 3–4); {@code clickPixel} is the fresh
     *  model-hull projection. */
    private record AimRefresh(@javax.annotation.Nullable WorldPoint world,
                              @javax.annotation.Nullable Point clickPixel) {}

    /** Inputs to {@link #rotateCameraToward} computed in a single
     *  client-thread marshal: base yaw to the target (pre-jitter) and
     *  chebyshev distance from player to target (drives pitch). */
    private record RotationInput(int baseYaw, int chebyshev) {}

    /** Camera snapshot read in a single marshal — current yaw target
     *  (so we can size the rotation step count) and actual pitch (so
     *  pitch motion deltas are computed off the same point in time). */
    private record CameraState(int yaw, int pitch) {}

    private boolean isNpcClaimedByOtherPlayer(int npcIndex)
    {
        try
        {
            Player self = client.getLocalPlayer();
            NPC npc = findNpc(npcIndex);
            if (self == null || npc == null) return false;
            Actor interacting = npc.getInteracting();
            return interacting instanceof Player && interacting != self;
        }
        catch (Throwable th)
        {
            return false;
        }
    }

    /** Client-thread check: is the engine's current left-click action
     *  "Take" targeting ground item id {@code itemId}? */
    private boolean isTopTakeOnItem(int itemId)
    {
        try
        {
            MenuEntry[] entries = client.getMenu() == null ? null
                : client.getMenu().getMenuEntries();
            if (entries == null || entries.length == 0) return false;
            MenuEntry top = entries[entries.length - 1];
            return isTakeOnGroundItemEntry(top, itemId);
        }
        catch (Throwable th) { return false; }
    }

    static boolean isTakeOnGroundItemEntry(MenuEntry entry, int itemId)
    {
        return entry != null
            && VerbMatcher.matches("Take", entry.getOption())
            && entry.getIdentifier() == itemId
            && isGroundItemMenuAction(entry.getType());
    }

    static boolean isGroundItemMenuAction(MenuAction action)
    {
        return action == MenuAction.GROUND_ITEM_FIRST_OPTION
            || action == MenuAction.GROUND_ITEM_SECOND_OPTION
            || action == MenuAction.GROUND_ITEM_THIRD_OPTION
            || action == MenuAction.GROUND_ITEM_FOURTH_OPTION
            || action == MenuAction.GROUND_ITEM_FIFTH_OPTION;
    }

    /** Client-thread check used immediately before a loot left-click. Safe
     *  means: the engine's current left-click action is the exact ground-item
     *  row we want, and no other actor is standing on the pile tile. Keeping
     *  this as one final check right before the press makes the left-click path
     *  human-like while still refusing obvious attack races. */
    private boolean isSafeLeftClickTake(WorldPoint tile, int itemId)
    {
        return isTopTakeOnItem(itemId) && !tileHasConflictingActor(tile);
    }

    /** Client-thread check: is there another actor standing on this loot tile?
     *  If so, force right-click selection rather than trusting a left click.
     *  This is the "chicken wandered onto the feather pile" safety net: a
     *  right-click may fail cleanly if the cursor drifted onto the NPC, but a
     *  left click can accidentally start combat. */
    boolean tileHasConflictingActor(WorldPoint tile)
    {
        if (tile == null) return false;
        try
        {
            WorldView wv = client.getTopLevelWorldView();
            if (wv == null) return false;
            Player self = client.getLocalPlayer();
            for (NPC npc : wv.npcs())
            {
                if (npc == null) continue;
                WorldPoint loc = npc.getWorldLocation();
                if (tile.equals(loc)) return true;
            }
            for (Player p : wv.players())
            {
                if (p == null || p == self) continue;
                WorldPoint loc = p.getWorldLocation();
                if (tile.equals(loc)) return true;
            }
        }
        catch (Throwable th)
        {
            return false;
        }
        return false;
    }

    /** Take a ground item with id {@code itemId} from {@code tile}. Same
     *  humanize-then-measure pattern as {@link #npcClick}: rotate camera,
     *  pre-aim at the tile, settle, verify hover, press. Aborts cleanly
     *  if the item is gone or hover doesn't match — no right-click flow.
     *
     *  <p>Items have stable tile coordinates (unlike NPCs they don't walk),
     *  so the staleness window is much narrower than for NPC clicks. The
     *  failure modes that matter are: item already picked up by another
     *  player, ground stack changed (hover shows a different item on top),
     *  the cursor landing on a non-interactable corner of the tile. */
    private void groundItemClick(WorldPoint tile, int itemId, String verb) throws InterruptedException
    {
        if (verb == null || verb.isBlank()) verb = "Take";
        final String v = verb;
        final boolean isTake = "Take".equalsIgnoreCase(v);
        if (tile == null) { lastError.set("null tile for groundItemClick"); return; }
        // Phase 1 — verify the item is on the tile.
        Boolean exists = onClient(() -> tileHasItem(tile, itemId));
        if (!Boolean.TRUE.equals(exists))
        {
            lastError.set("item " + itemId + " no longer on tile " + tile);
            return;
        }
        // Phase 2 — rotate camera so the tile is centred-ish.
        rotateCameraToward(tile);
        // Phase 3 — pre-aim on the actual item sprite, not just somewhere
        // inside the tile poly. resolveGroundItemPixel uses the item's own
        // clickbox, which avoids landing on UI panels that may overlap the
        // tile poly (e.g. the inventory panel at Lumbridge P2 bank area).
        Point preAim = onClient(() -> resolver.resolveGroundItemPixel(tile, itemId));
        if (preAim == null)
        {
            lastError.set("ground item pixel unresolvable at " + tile);
            return;
        }
        moveCursorTo(preAim.getX(), preAim.getY());
        // Phase 4 — pre-click settle.
        SequenceSleep.sleep(client, 180 + rng.nextInt(220));   // 180..400ms
        // Phase 5 — immediate pre-press verification.
        // For Take: do one final client-thread check immediately before the
        // click with no extra sleep afterwards. If the engine's current
        // left-click action is still the exact ground-item row we want AND no
        // NPC/other player is on the tile, take the human left-click path.
        // Otherwise degrade to right-click exact-row selection.
        Boolean topMatches = isTake
            ? onClient(() -> isSafeLeftClickTake(tile, itemId))
            : onClient(() -> isTopMenuVerb(v));
        if (Boolean.TRUE.equals(topMatches))
        {
            press(MouseEvent.BUTTON1, ClickIntent.WORLD, PressTiming.FAST_COMMIT);
            return;
        }
        if (isTake)
        {
            log.info("ground item {} left-click unsafe at {} — right-click flow",
                itemId, tile);
        }
        // Phase 6 — verb is not the left-click default; right-click and pick
        // from the context menu. tileHasItem confirmed the item IS on this
        // tile, so the verb should appear in the menu if it's valid for this
        // item (e.g. "Light" requires a tinderbox in inventory).
        log.info("ground item {} verb='{}' not at top of menu at {} — right-click flow",
            itemId, v, tile);
        press(MouseEvent.BUTTON3, ClickIntent.WORLD, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        // For "Take": also match item ID so we pick OUR item in a stack.
        // For other verbs: verb match alone is sufficient.
        MenuRow row = onClient(() -> isTake
            ? findMenuRow(e -> isTakeOnGroundItemEntry(e, itemId))
            : findMenuRow(e -> VerbMatcher.matches(v, e.getOption())));
        if (row == null)
        {
            lastError.set("menu missing '" + v + "' on item " + itemId + " at " + tile);
            log.info("right-click menu did not contain '{}' for item {} at {}", v, itemId, tile);
            dismissMenu();
            clearSelectedWidgetTargetMode();
            return;
        }
        moveCursorTo(row.x, row.y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        press(MouseEvent.BUTTON1, ClickIntent.MENU_ROW, PressTiming.MENU_SELECTION);
    }

    /** Client-thread check: does {@code tile} have a TileItem with id
     *  {@code itemId} currently spawned? Used as a pre-flight before we
     *  spend a humanized cursor move on a phantom drop. */
    private boolean tileHasItem(WorldPoint tile, int itemId)
    {
        try
        {
            LocalPoint lp = LocalPoint.fromWorld(client, tile);
            if (lp == null) return false;
            Tile sceneTile = client.getScene().getTiles()
                [tile.getPlane()][lp.getSceneX()][lp.getSceneY()];
            if (sceneTile == null) return false;
            java.util.List<TileItem> items = sceneTile.getGroundItems();
            if (items == null) return false;
            for (TileItem it : items)
            {
                if (it != null && it.getId() == itemId) return true;
            }
            return false;
        }
        catch (Throwable th) { return false; }
    }

    /** Client-thread check: is the engine's current left-click action
     *  "Attack" targeting npc index {@code npcIndex}? */
    private boolean isTopAttackOnNpc(int npcIndex)
    {
        return isTopVerbOnNpc("Attack", npcIndex);
    }

    /** Client-thread check: is the engine's current left-click action
     *  {@code verb} targeting npc index {@code npcIndex}? Generalises
     *  {@link #isTopAttackOnNpc} to any menu verb (Talk-to, Pickpocket,
     *  Trade, etc.). */
    private boolean isTopVerbOnNpc(String verb, int npcIndex)
    {
        try
        {
            MenuEntry[] entries = client.getMenu() == null ? null
                : client.getMenu().getMenuEntries();
            if (entries == null || entries.length == 0) return false;
            MenuEntry top = entries[entries.length - 1];
            return VerbMatcher.matches(verb, top.getOption())
                && top.getIdentifier() == npcIndex;
        }
        catch (Throwable th) { return false; }
    }

    private NPC findNpc(int index)
    {
        for (NPC n : client.getTopLevelWorldView().npcs())
        {
            if (n != null && n.getIndex() == index) return n;
        }
        return null;
    }

    /** Click a tile object at {@code tile} that exposes the menu option
     *  matching {@code verb} (case- and hyphen-insensitive). Doors / gates
     *  are typically wall objects; ladders / stairs / trapdoors are game
     *  objects; the verb dictates which we accept.
     *
     *  <p>If the verb is already the engine's left-click default for the
     *  hovered pixel, this issues a single left-click. Otherwise it
     *  right-clicks to open the context menu, finds the matching entry, and
     *  left-clicks that entry's row — exactly like a human would.
     */
    private void gameObjectClick(WorldPoint tile, String verb, boolean liveTracked,
                                 boolean ensureVisibleRotation) throws InterruptedException
    {
        if (tile == null) { lastError.set("null tile for gameObjectClick"); return; }
        if (verb == null || verb.isBlank())
        { lastError.set("null verb for gameObjectClick"); return; }
        // Reject cross-plane clicks: the target object lives on a different
        // floor than the player. Common cause is a trail-walker that queued
        // an opportunistic transport click for the previous-plane staircase
        // tile after the descent had already started; resolving the pixel
        // for that tile against the post-descent scene lands on random
        // scenery on the new floor.
        Integer playerPlane = onClient(() -> {
            var local = client.getLocalPlayer();
            return local == null ? null : local.getWorldLocation().getPlane();
        });
        if (playerPlane != null && playerPlane != tile.getPlane())
        {
            lastError.set("cross-plane click refused: player plane="
                + playerPlane + " tile plane=" + tile.getPlane());
            log.info("gameObjectClick {} verb='{}' — cross-plane (player={}, tile={}), refusing",
                tile, verb, playerPlane, tile.getPlane());
            return;
        }
        // Resolve the object + pixel on the client thread.
        TransportResolver tr = new TransportResolver(client);
        TransportResolver.Match match = onClient(() -> tr.findTransport(tile, verb));
        if (match == null || !match.isSuccess())
        {
            lastError.set(match == null ? "transport resolve null" : match.failure());
            log.info("gameObjectClick {} verb='{}' — {}", tile, verb,
                match == null ? "null match" : match.failure());
            return;
        }
        // Camera rotation toward the tile keeps the object visible. For
        // far-away transport tiles whose pixel resolution would fail if
        // the wide humanized jitter lands the target past the FOV edge,
        // the caller can opt into the verify-and-retry variant via
        // {@link ActionRequest#isEnsureVisibleRotation()}.
        if (ensureVisibleRotation) rotateCameraTowardEnsuringVisible(tile, false);
        else rotateCameraToward(tile);

        if (liveTracked && match.gameObject() != null)
        {
            tryGameObjectAttemptTracked(match, tile, verb);
            return;
        }

        // Sample candidate pixels from the engine's own hit-test geometry
        // — {@link TileObject#getClickbox()} first (same Shape RuneLite's
        // own AgilityOverlay draws and tests against the mouse), then
        // convex hulls and the tile poly as fallbacks. The candidates
        // are ordered best-first; we hover each one and ask the engine
        // what the left-click would do. First candidate where the
        // engine reports the expected verb wins. No more "compute one
        // clever pixel and pray" — the engine's hit-test is the source
        // of truth, we just search it.
        if (tryGameObjectClickbox(match, tile, verb)) return;
        log.info("gameObjectClick {} verb='{}' — all candidates exhausted, giving up",
            tile, verb);
    }

    /** Live-tracked GameObject click — see {@link ActionRequest#isLiveTracked()}.
     *
     *  <p>The cursor moves to the model's hull along a humanized wind
     *  path, but the path is regenerated mid-flight if the booth's hull
     *  centroid drifts more than a small threshold while the camera
     *  follows the still-walking player. After motion settles, one
     *  short corrective hop pulls the cursor back inside the live hull
     *  if it ended up outside. Verb mismatch aborts with no right-click
     *  fallback — the previous fallback is what produced the
     *  "Walk here Sademedicus" / "Withdraw-1 Burnt pizza" misclick logs.
     *
     *  <p>Wobble is preserved: the wind path itself is humanized, and
     *  each re-aim picks a slightly different point inside the hull (we
     *  call {@link PixelResolver#resolveGameObject} which samples a
     *  humanized point inside the hull). A real player's eye doesn't
     *  pixel-perfect track either — they re-aim a few times along the
     *  way and accept that the click lands on a slightly different
     *  spot of the model each trip. */
    private boolean tryGameObjectAttemptTracked(TransportResolver.Match match,
                                                WorldPoint tile, String verb)
        throws InterruptedException
    {
        // Tunables — kept conservative to avoid jitter on small camera
        // tweaks. See plan in commit message / CookingScriptV3 javadoc.
        final int reaimThresholdPx = 22;
        final int reaimThresholdSq = reaimThresholdPx * reaimThresholdPx;
        final long reaimMinSegmentMs = 80L;
        final long reaimMaxSegmentMs = 160L;
        final int maxReaims = 3;

        // Initial aim: humanized point inside the live hull, plus its
        // bbox centroid as a stable "where is the booth now" signal.
        Point initial = onClient(() ->
            resolver.resolveGameObject(match.gameObject(),
                PixelResolver.GameObjectStrategy.HULL));
        Point lastCentroid = onClient(() -> hullCentroid(match.gameObject()));
        if (initial == null || lastCentroid == null)
        {
            lastError.set("tracked: hull off-canvas at " + tile);
            log.info("gameObjectClick tracked {} verb='{}' — hull off-canvas at start",
                tile, verb);
            return false;
        }
        log.info("gameObjectClick tracked → world {} initial pixel ({},{}) verb='{}' "
                + "(matched '{}', id={})",
            tile, initial.getX(), initial.getY(), verb,
            match.matchedVerb(), match.matchedObjectId());

        Point target = initial;
        int reaims = 0;
        // Iterative wind-path motion. Between samples we periodically
        // peek the live centroid; if it has drifted past threshold,
        // regenerate the remainder of the path toward a fresh hull
        // sample. Up to maxReaims regenerations per click.
        while (true)
        {
            int sx = input.cursorX() < 0 ? client.getCanvas().getWidth() / 2 : input.cursorX();
            int sy = input.cursorY() < 0 ? client.getCanvas().getHeight() / 2 : input.cursorY();
            var path = wind.path(sx, sy, target.getX(), target.getY());
            long segStart = System.currentTimeMillis();
            long segBudget = reaimMinSegmentMs
                + rng.nextInt((int) (reaimMaxSegmentMs - reaimMinSegmentMs + 1));
            boolean reaimed = false;
            for (var s : path)
            {
                input.mouseMove(s.x(), s.y());
                SequenceSleep.sleep(client, s.dtMs());
                if (reaims >= maxReaims) continue;
                if (System.currentTimeMillis() - segStart < segBudget) continue;
                Point liveCentroid = onClient(() -> hullCentroid(match.gameObject()));
                if (liveCentroid == null)
                {
                    lastError.set("tracked: hull went off-canvas mid-flight at " + tile);
                    log.info("gameObjectClick tracked {} verb='{}' — hull off-canvas mid-flight",
                        tile, verb);
                    return false;
                }
                int dx = liveCentroid.getX() - lastCentroid.getX();
                int dy = liveCentroid.getY() - lastCentroid.getY();
                if (dx * dx + dy * dy > reaimThresholdSq)
                {
                    Point fresh = onClient(() ->
                        resolver.resolveGameObject(match.gameObject(),
                            PixelResolver.GameObjectStrategy.HULL));
                    if (fresh == null)
                    {
                        lastError.set("tracked: hull unresolvable mid-flight at " + tile);
                        return false;
                    }
                    reaims++;
                    log.info("gameObjectClick tracked {} verb='{}' — re-aim {} drift~{}px → ({},{})",
                        tile, verb, reaims,
                        (int) Math.sqrt(dx * dx + dy * dy),
                        fresh.getX(), fresh.getY());
                    target = fresh;
                    lastCentroid = liveCentroid;
                    reaimed = true;
                    break;
                }
                // Drift below threshold — keep the current target, but
                // reset the budget so we'll check again later in the path.
                segStart = System.currentTimeMillis();
                segBudget = reaimMinSegmentMs
                    + rng.nextInt((int) (reaimMaxSegmentMs - reaimMinSegmentMs + 1));
            }
            if (!reaimed) break;
        }

        // Last-mile correction: if cursor ended outside the live hull,
        // do ONE short humanized corrective hop into a fresh hull
        // sample. Humans do this — overshoot a bit, then nudge back.
        Boolean inside = onClient(() ->
            pointInsideHull(match.gameObject(), input.cursorX(), input.cursorY()));
        if (Boolean.FALSE.equals(inside))
        {
            Point correction = onClient(() ->
                resolver.resolveGameObject(match.gameObject(),
                    PixelResolver.GameObjectStrategy.HULL));
            if (correction == null)
            {
                lastError.set("tracked: hull off-canvas at last-mile " + tile);
                log.info("gameObjectClick tracked {} verb='{}' — last-mile hull off-canvas",
                    tile, verb);
                return false;
            }
            log.info("gameObjectClick tracked {} verb='{}' — last-mile correction → ({},{})",
                tile, verb, correction.getX(), correction.getY());
            int sx = input.cursorX() < 0 ? correction.getX() : input.cursorX();
            int sy = input.cursorY() < 0 ? correction.getY() : input.cursorY();
            for (var s : wind.path(sx, sy, correction.getX(), correction.getY()))
            {
                input.mouseMove(s.x(), s.y());
                SequenceSleep.sleep(client, s.dtMs());
            }
        }

        // Settle ~2 frames so the engine populates the hover menu, then
        // verify. If the requested verb isn't the left-click default, fall
        // back to right-click + menu select — Lumbridge plane-1 staircase is
        // the canonical case: left-click is "Climb" (dialogue), the
        // "Climb-up" / "Climb-down" options live in the right-click menu.
        SequenceSleep.sleep(client, 60);
        boolean isTop = onClient(() -> isTopMenuVerb(verb));
        if (isTop)
        {
            log.info("gameObjectClick tracked {} verb='{}' — verb is left-click default, single click",
                tile, verb);
            press(MouseEvent.BUTTON1, ClickIntent.WORLD, PressTiming.STANDARD);
            return true;
        }
        String topLbl = onClient(this::topMenuLabel);
        log.info("gameObjectClick tracked {} verb='{}' not at top (top='{}') — right-click flow",
            tile, verb, topLbl);
        press(MouseEvent.BUTTON3, ClickIntent.WORLD, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        if (selectMenuVerb(verb)) return true;
        String menuDump = onClient(this::fullMenuDump);
        lastError.set("tracked: verb '" + verb + "' not in right-click menu (top='"
            + topLbl + "', " + menuDump + ")");
        log.info("gameObjectClick tracked {} verb='{}' — right-click menu missing verb "
                + "(top='{}', {})",
            tile, verb, topLbl, menuDump);
        dismissMenu();
        SequenceSleep.sleep(client, 120);
        return false;
    }

    /** Client-thread: bounding-box centroid of {@code obj}'s convex
     *  hull. Returns null if the hull is unavailable (model not
     *  rendered / off-canvas). Used as a stable "where is the booth
     *  now" signal — much less noisy than the random-sampled point
     *  used as the actual click target. */
    private static Point hullCentroid(GameObject obj)
    {
        Shape hull = obj.getConvexHull();
        if (hull == null) return null;
        Rectangle bb = hull.getBounds();
        if (bb == null || bb.width <= 0 || bb.height <= 0) return null;
        return new Point(bb.x + bb.width / 2, bb.y + bb.height / 2);
    }

    /** Client-thread: true iff (x,y) lies inside {@code obj}'s convex
     *  hull. Used at the last-mile correction step — if cursor drifted
     *  off the hull during motion, do a corrective hop. Returns true
     *  for null hulls (treat as "good enough"; we'd rather click than
     *  spin re-aiming a model that's between renders). */
    private static boolean pointInsideHull(GameObject obj, int x, int y)
    {
        Shape hull = obj.getConvexHull();
        if (hull == null) return true;
        return hull.contains(x, y);
    }

    /** Click the tile object referenced by {@code match} by searching
     *  the engine's own hit-test geometry until we land on a pixel the
     *  engine accepts.
     *
     *  <p>Flow:
     *  <ol>
     *    <li>Ask {@link PixelResolver#objectClickCandidates(TileObject)}
     *        for an ordered list of pixels — {@link TileObject#getClickbox()}
     *        centre first, then hull samples, then tile-poly samples.</li>
     *    <li>For each candidate: move cursor, settle ~2 frames, read
     *        the engine's current top menu entry via {@link
     *        #isTopMenuVerb(String)}. If it matches the requested verb,
     *        left-click. Done.</li>
     *    <li>If no candidate produced the verb at the top, right-click
     *        on the first candidate (clickbox centre — the best single
     *        guess) and look for the verb anywhere in the open menu.</li>
     *    <li>Otherwise give up; caller decides whether to retry next tick.</li>
     *  </ol>
     *
     *  <p>This replaces the old "try HULL strategy, then try TILE_POLY
     *  strategy" two-shot. The old code committed to one pixel per
     *  strategy — fine for short objects, but tall walls and rooftop
     *  obstacles project hulls whose centroid lands off the engine's
     *  hit region. The new search uses the engine's own clickbox as
     *  the source of truth and walks several pixels inside it, so a
     *  bad first guess doesn't kill the obstacle.
     *
     *  <p>Returns {@code true} when a click landed; {@code false} after
     *  the right-click fallback also missed. On a {@code false} return
     *  any open menu has been dismissed and the cursor is settled.
     */
    private boolean tryGameObjectClickbox(TransportResolver.Match match,
                                          WorldPoint tile, String verb)
        throws InterruptedException
    {
        TileObject obj = pickTileObject(match);
        if (obj == null)
        {
            // Match has no classified TileObject (rare degraded path) —
            // degrade to a single tile-polygon sample so we at least try
            // once instead of bailing.
            Point fb = onClient(() -> resolver.resolveWalkTarget(tile));
            if (fb == null)
            {
                lastError.set("no click candidates at " + tile + " (off-screen?)");
                log.info("gameObjectClick {} — no candidates resolvable", tile);
                return false;
            }
            log.info("gameObjectClick → world {} verb='{}' (matched '{}', id={}) — "
                    + "tile-poly fallback at ({},{})",
                tile, verb, match.matchedVerb(), match.matchedObjectId(),
                fb.getX(), fb.getY());
            moveCursorTo(fb.getX(), fb.getY());
            SequenceSleep.sleep(client, 60);
            if (onClient(() -> isTopMenuVerb(verb)))
            {
                press(MouseEvent.BUTTON1, ClickIntent.WORLD, PressTiming.STANDARD);
                return true;
            }
            log.info("  tile-poly fallback ({},{}) — top='{}' (miss)",
                fb.getX(), fb.getY(), onClient(this::topMenuLabel));
            return false;
        }

        log.info("gameObjectClick → world {} verb='{}' (matched '{}', id={}) — "
                + "live-sampling from {}",
            tile, verb, match.matchedVerb(), match.matchedObjectId(),
            obj.getClass().getSimpleName());

        // Live-sample the object's clickbox on EVERY attempt. The previous
        // implementation computed a 12-candidate list once and hovered each
        // pixel in sequence — that's a stale snapshot. The engine
        // interpolates the camera toward setCameraYawTarget over multiple
        // frames after we set it, so the camera is often still drifting
        // when we hover candidate 1, and by candidate 12 the clickbox has
        // re-projected to a different screen region. Every cached candidate
        // ends up pointing at the same wrong tile and every hover misses.
        // Re-sampling per iteration tracks the live screen-space position.
        final int maxAttempts = 12;
        java.util.List<Point> tried = new java.util.ArrayList<>(maxAttempts);
        Point firstSample = null;
        int attemptsUsed = 0;
        for (int i = 0; i < maxAttempts; i++)
        {
            Point p = onClient(() -> {
                List<Point> fresh = resolver.objectClickCandidates(obj);
                if (fresh.isEmpty()) return null;
                // Walk the freshly-sampled candidates and skip near-
                // duplicates of pixels we've already hovered. Without
                // dedup, attempt #1 (centroid) would repeat forever
                // since the resolver's centroid sample is deterministic
                // when the clickbox hasn't moved.
                for (Point fp : fresh)
                {
                    boolean dup = false;
                    for (Point t : tried)
                    {
                        int dx = fp.getX() - t.getX();
                        int dy = fp.getY() - t.getY();
                        if (dx * dx + dy * dy < 36) { dup = true; break; }
                    }
                    if (!dup) return fp;
                }
                return null;
            });
            if (p == null)
            {
                log.info("  no fresh candidate (attempt {}/{}) — sampling exhausted",
                    i + 1, maxAttempts);
                break;
            }
            if (firstSample == null) firstSample = p;
            tried.add(p);
            attemptsUsed = i + 1;
            moveCursorTo(p.getX(), p.getY());
            // ~2 render frames for the engine to recompute hover state.
            SequenceSleep.sleep(client, 60);
            boolean isTop = onClient(() -> isTopMenuVerb(verb));
            if (isTop)
            {
                log.info("  attempt {}/{} ({},{}) — verb at top, left-click",
                    i + 1, maxAttempts, p.getX(), p.getY());
                press(MouseEvent.BUTTON1, ClickIntent.WORLD, PressTiming.STANDARD);
                return true;
            }
            String topLbl = onClient(this::topMenuLabel);
            log.info("  attempt {}/{} ({},{}) — top='{}' (miss)",
                i + 1, maxAttempts, p.getX(), p.getY(), topLbl);
        }

        if (firstSample == null)
        {
            lastError.set("no click candidates at " + tile + " (off-screen?)");
            log.info("gameObjectClick {} — no candidates resolvable", tile);
            return false;
        }

        // Phase 2 — right-click the most recent fresh sample. Some verbs
        // are never the engine's default (Climb-up on a staircase that has
        // a left-click "Climb" dialogue, e.g.) so the right-click menu is
        // the legitimate path even when no attempt produced the verb at
        // the top. Using the LAST tried pixel (not the first) means the
        // right-click happens on the most up-to-date projection.
        Point best = tried.isEmpty() ? firstSample : tried.get(tried.size() - 1);
        log.info("  right-click fallback at ({},{})", best.getX(), best.getY());
        moveCursorTo(best.getX(), best.getY());
        SequenceSleep.sleep(client, 60);
        press(MouseEvent.BUTTON3, ClickIntent.WORLD, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        if (selectMenuVerb(verb)) return true;

        String menuDump = onClient(this::fullMenuDump);
        lastError.set("verb '" + verb + "' not in menu after "
            + attemptsUsed + " attempts (" + menuDump + ")");
        log.info("  right-click fallback missed verb '{}' ({})", verb, menuDump);
        dismissMenu();
        SequenceSleep.sleep(client, 120);
        return false;
    }

    /** Pick whichever concrete TileObject subtype the resolver found.
     *  Match guarantees exactly one of these is non-null on success;
     *  returns {@code null} for a degraded fallback Match. */
    @javax.annotation.Nullable
    private static TileObject pickTileObject(TransportResolver.Match m)
    {
        if (m.gameObject() != null) return m.gameObject();
        if (m.wallObject() != null) return m.wallObject();
        if (m.decorativeObject() != null) return m.decorativeObject();
        if (m.groundObject() != null) return m.groundObject();
        return null;
    }

    /** True if the engine's left-click action at the current cursor position
     *  matches {@code verb}. Reads {@code Menu.getMenuEntries()}; the top of
     *  menu (= last array index) is the default action. */
    private boolean isTopMenuVerb(String verb)
    {
        try
        {
            MenuEntry[] entries = client.getMenu() == null ? null
                : client.getMenu().getMenuEntries();
            if (entries == null || entries.length == 0) return false;
            MenuEntry top = entries[entries.length - 1];
            String opt = top.getOption();
            if (VerbMatcher.matches(verb, opt)) return true;
            // Composite fallback — Skretzo's TSV stores some verbs as the
            // full menu line ("Climb-up Staircase") but the engine splits
            // option ("Climb-up") and target ("<col=ffff>Staircase").
            String tgt = top.getTarget();
            if (tgt == null || tgt.isEmpty()) return false;
            String tgtClean = tgt.replaceAll("<[^>]+>", "").trim();
            if (tgtClean.isEmpty()) return false;
            return VerbMatcher.matches(verb, opt + " " + tgtClean);
        }
        catch (Throwable th) { return false; }
    }

    /** With the right-click menu open, find the entry whose option matches
     *  {@code verb}, move the cursor onto its row, and left-click. Returns
     *  false if the menu is closed or no entry matches.
     *
     *  <p>The row position is computed from the menu's open bounds —
     *  {@code Menu.getMenuY()} is the y-coordinate of the title bar, with the
     *  first selectable row 19px below it (engine constant) and each
     *  subsequent row 15px tall. Entries are stored bottom-up in the array,
     *  so {@code entries.length - 1} is rendered just under the header. We
     *  compute the row index from the menu position to be layout-agnostic.
     */
    private boolean selectMenuVerb(String verb) throws InterruptedException
    {
        // Snapshot the menu state on the client thread. This includes the
        // entries array, the menu's open position, and width — so we can
        // compute the click target without re-reading anything off-thread.
        MenuRow row = onClient(() -> findMenuRow(verb));
        if (row == null) return false;
        // Move cursor to the row and left-click. Pre-click settle is shorter
        // here than for a fresh aim — the cursor is already over the menu and
        // the user's eye has already locked onto the option.
        moveCursorTo(row.x, row.y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        press(MouseEvent.BUTTON1, ClickIntent.MENU_ROW, PressTiming.MENU_SELECTION);
        return true;
    }

    /** Computed pixel target for a particular menu row, returned from
     *  {@link #findMenuRow}. Only {@link #x} / {@link #y} matter to the
     *  caller; the rest are diagnostic. */
    private static final class MenuRow
    {
        final int x, y;
        final int rowIdx;
        final String option;
        MenuRow(int x, int y, int rowIdx, String opt) { this.x = x; this.y = y; this.rowIdx = rowIdx; this.option = opt; }
    }

    /** Client-thread body for {@link #selectMenuVerb}. Reads the open menu's
     *  position + size + entries, matches the requested verb, and returns
     *  the canvas pixel to click. */
    @javax.annotation.Nullable
    private MenuRow findMenuRow(String verb)
    {
        // Bare-vs-composite verb match. Skretzo's TSV stores some verbs as
        // the full menu line ("Climb-up Staircase") but the engine's
        // MenuEntry splits option ("Climb-up") and target ("<col=ffff>Staircase").
        // Try the bare option first, fall back to "<option> <target>" with
        // RuneLite colour tags stripped. Same fix shape as TransportResolver.matchedAction.
        return findMenuRow(e -> {
            String opt = e.getOption();
            if (VerbMatcher.matches(verb, opt)) return true;
            String tgt = e.getTarget();
            if (tgt == null || tgt.isEmpty()) return false;
            String tgtClean = tgt.replaceAll("<[^>]+>", "").trim();
            if (tgtClean.isEmpty()) return false;
            return VerbMatcher.matches(verb, opt + " " + tgtClean);
        });
    }

    /** As {@link #findMenuRow(String)} but with a custom entry predicate.
     *  Used when the match needs more than the verb (e.g. NPC index, item
     *  id). Iterates top-to-bottom (engine's array is stored bottom-up). */
    @javax.annotation.Nullable
    private MenuRow findMenuRow(java.util.function.Predicate<MenuEntry> match)
    {
        Menu menu = client.getMenu();
        if (menu == null) return null;
        MenuEntry[] entries = menu.getMenuEntries();
        if (entries == null || entries.length == 0) return null;
        // The menu array is stored bottom-up: entries[length - 1] is the top
        // row (just under the "Choose option" header). So the visual row
        // index from the top of the list is (length - 1 - i).
        int matchIdx = -1;
        String matchedOption = null;
        for (int i = entries.length - 1; i >= 0; i--)
        {
            MenuEntry e = entries[i];
            if (e == null) continue;
            if (match.test(e))
            {
                matchIdx = i;
                matchedOption = e.getOption();
                break;
            }
        }
        if (matchIdx < 0) return null;
        int visualRow = entries.length - 1 - matchIdx;
        int menuX = menu.getMenuX();
        int menuY = menu.getMenuY();
        int menuW = menu.getMenuWidth();
        // Header (title bar) is 19px tall; each row is 15px. These are the
        // engine's render constants — well-known and stable across layouts.
        // We aim a pixel inside the row, biased slightly off-centre so two
        // consecutive selections of the same verb don't both land on the
        // exact same dot.
        final int HEADER_H = 19;
        final int ROW_H = 15;
        int rowTop = menuY + HEADER_H + visualRow * ROW_H;
        int x = menuX + Math.max(8, menuW / 4) + rng.nextInt(Math.max(1, menuW / 2));
        int y = rowTop + 3 + rng.nextInt(Math.max(1, ROW_H - 6));
        // Clamp to canvas in case the menu opened near the edge.
        java.awt.Canvas c = client.getCanvas();
        if (c != null)
        {
            x = clampInt(x, 4, Math.max(5, c.getWidth() - 4));
            y = clampInt(y, 4, Math.max(5, c.getHeight() - 4));
        }
        log.info("menu row pick: option='{}' visualRow={} menu=({},{} w={}) → ({},{})",
            matchedOption, visualRow, menuX, menuY, menuW, x, y);
        return new MenuRow(x, y, visualRow, matchedOption);
    }

    /** Click an inventory slot — slot widgets are NOT addressable by
     *  {@code (149 << 16) | slot} (that resolves to the parent inventory
     *  widget, whose bounds cover the whole inventory tab; the cursor
     *  ends up on a random other slot). The slot widget itself is a
     *  child of the parent — accessed via {@code parent.getChild(slot)}.
     *  This was the root cause of "Use" tinderbox flow misclicking onto
     *  whatever item happened to be near the inventory's center.
     *
     *  <p>Pattern mirrored from RuneLite's bank-tags
     *  {@code LayoutManager#layout}, which uses
     *  {@code itemContainer.getChild(i)} the same way. */
    private void invSlotClick(int slot, String verb) throws InterruptedException
    {
        Rectangle bounds = onClient(() -> resolveInvSlotBounds(slot));
        if (bounds == null)
        {
            lastError.set("inv slot " + slot + " not resolvable");
            return;
        }
        // Sample a pixel inside the slot bounds, away from the edges so
        // the click can't bleed into an adjacent slot. Don't aim dead
        // centre — looks mechanical.
        int marginX = Math.max(1, bounds.width / 6);
        int marginY = Math.max(1, bounds.height / 6);
        int x = bounds.x + marginX
            + rng.nextInt(Math.max(1, bounds.width - 2 * marginX));
        int y = bounds.y + marginY
            + rng.nextInt(Math.max(1, bounds.height - 2 * marginY));
        if (verb == null || verb.isBlank())
        {
            moveCursorTo(x, y);
            press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
            return;
        }
        // Verb-aware: hover, check L-click default, fall back to right-click.
        moveCursorTo(x, y);
        SequenceSleep.sleep(client, 60);
        boolean isTop = onClient(() -> isTopMenuVerb(verb));
        if (isTop)
        {
            press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
            return;
        }
        press(MouseEvent.BUTTON3, ClickIntent.UI, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        boolean ok = selectMenuVerb(verb);
        if (!ok)
        {
            lastError.set("inv slot " + slot + " menu open but verb '" + verb + "' not present");
            dismissMenu();
        }
    }

    private void widgetClick(int widgetId) throws InterruptedException
    {
        Point pixel = onClient(() -> resolver.resolveWidget(widgetId));
        if (pixel == null) { lastError.set("widget " + widgetId + " not found"); return; }
        moveCursorTo(pixel.getX(), pixel.getY());
        press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
    }

    /** Resolve the screen bounds of inventory slot {@code slot}. The visible
     *  inventory widget depends on which interface is currently overlaying
     *  the canvas:
     *  <ul>
     *    <li>{@link net.runelite.api.gameval.InterfaceID.GeOffersSide#ITEMS}
     *        (group 467) — when the GE offer-setup is open. The regular
     *        {@code Inventory.ITEMS} is hidden behind it; clicking those
     *        bounds would silently miss because the engine renders
     *        {@code GeOffersSide.ITEMS} on top with different actions
     *        (Offer-1/.../Offer-All) for the inv slots.</li>
     *    <li>{@link net.runelite.api.gameval.InterfaceID.Bankside#ITEMS}
     *        (group 763) — when the bank is open. Same overlay pattern;
     *        actions become Deposit-1/.../Deposit-All.</li>
     *    <li>{@link net.runelite.api.gameval.InterfaceID.Inventory#ITEMS}
     *        (group 149) — the default sidebar inventory; used when
     *        nothing overlays.</li>
     *  </ul>
     *  Probe in priority order; return the first whose container + child
     *  resolve to a non-empty visible rect. Verified via click-inspector
     *  2026-05-01: the user's manual sell click landed on widget id
     *  {@code 0x01d3_0000} ({@code GeOffersSide.ITEMS}) at the same
     *  bounds {@code [584,495 36x32]} where {@code Inventory.ITEMS}
     *  normally sits — the widget swap is invisible to the user but
     *  the click destination changed.
     *
     *  <p>Must be called on the client thread. */
    private Rectangle resolveInvSlotBounds(int slot)
    {
        // Priority order: overlays first, sidebar fallback last.
        int[] candidateGroups = new int[] {
            net.runelite.api.gameval.InterfaceID.GeOffersSide.ITEMS >>> 16,
            net.runelite.api.gameval.InterfaceID.Bankside.ITEMS >>> 16,
            net.runelite.api.gameval.InterfaceID.Inventory.ITEMS >>> 16,
        };
        for (int group : candidateGroups)
        {
            Widget parent = client.getWidget(group, 0);
            if (parent == null || parent.isHidden()) continue;
            Widget child = parent.getChild(slot);
            if (child == null || child.isSelfHidden()) continue;
            Rectangle r = child.getBounds();
            if (r != null && !r.isEmpty()) return r;
        }
        return null;
    }

    /** Worker-callable wrapper around {@link #boundsClick} — exposed for
     *  multi-step worker tasks (e.g. {@code GeInteraction.runPickSearchResult})
     *  that already hold the busy flag and need to issue another bounds
     *  click without re-acquiring it. */
    public void boundsClickOnWorker(java.awt.Rectangle bounds, String verb) throws InterruptedException
    {
        boundsClick(bounds, verb);
    }

    /** Worker-callable: move cursor inside {@code bounds}, settle, and verify
     *  an action of {@code verb} on a target whose color-stripped text
     *  contains every fragment in {@code targetFragments} (case-insensitive)
     *  is available — first as the L-click default, falling back to the
     *  right-click context menu if not. On match: clicks the matching entry
     *  and returns true. On miss in BOTH paths: sets {@link #lastError},
     *  escapes any open menu, returns false WITHOUT firing a click on the
     *  wrong target.
     *
     *  <p>Right-click fallback handles the "logs respawned on top of a fire"
     *  case — the L-click default at the fire's tile resolves to the logs
     *  (topmost interactable) so target check fails on the L-click, but the
     *  full menu still contains "Use Raw chicken -&gt; Fire" further down. */
    public boolean boundsClickVerifiedAction(java.awt.Rectangle bounds,
                                             String verb,
                                             String... targetFragments)
        throws InterruptedException
    {
        if (bounds == null || bounds.isEmpty())
        {
            lastError.set("boundsClickVerifiedAction: null or empty bounds");
            return false;
        }
        int marginX = Math.max(1, bounds.width / 6);
        int marginY = Math.max(1, bounds.height / 6);
        int x = bounds.x + marginX
            + rng.nextInt(Math.max(1, bounds.width - 2 * marginX));
        int y = bounds.y + marginY
            + rng.nextInt(Math.max(1, bounds.height - 2 * marginY));
        moveCursorTo(x, y);
        SequenceSleep.sleep(client, 60);

        // Phase 1 — L-click verification.
        Boolean topOk = onClient(() -> {
            try
            {
                MenuEntry[] entries = client.getMenu() == null ? null
                    : client.getMenu().getMenuEntries();
                if (entries == null || entries.length == 0) return false;
                return menuEntryMatches(entries[entries.length - 1], verb, targetFragments);
            }
            catch (Throwable th) { return false; }
        });
        if (Boolean.TRUE.equals(topOk))
        {
            press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
            return true;
        }

        // Phase 2 — right-click + menu search. Handles the case where another
        // interactable (e.g. respawned logs on top of a fire) takes priority
        // for the L-click but the desired action is still in the full menu.
        String topLbl = onClient(this::topMenuLabel);
        log.info("boundsClickVerifiedAction: L-click mismatch (top='{}') — right-click flow for verb='{}' fragments={}",
            topLbl, verb, java.util.Arrays.toString(targetFragments));
        press(MouseEvent.BUTTON3, ClickIntent.UI, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        MenuRow row = onClient(() -> findMenuRow(
            e -> menuEntryMatches(e, verb, targetFragments)));
        if (row == null)
        {
            String want = "verb='" + verb + "' target~"
                + java.util.Arrays.toString(targetFragments);
            String dump = onClient(this::fullMenuDump);
            lastError.set("boundsClickVerifiedAction: action not in menu — wanted "
                + want);
            log.info("boundsClickVerifiedAction: menu missing wanted={} (top='{}', menu={})",
                want, topLbl, dump);
            dismissMenu();
            clearSelectedWidgetTargetMode();
            return false;
        }
        moveCursorTo(row.x, row.y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        press(MouseEvent.BUTTON1, ClickIntent.MENU_ROW, PressTiming.MENU_SELECTION);
        return true;
    }

    /** Worker-callable inventory click variant that verifies both verb and
     *  target fragments before clicking. This is stricter than
     *  {@link #invSlotClickOnWorker}: selecting a cooking item must show
     *  "Use Raw {food}" on that exact slot, never "Eat" or "Use" on cooked
     *  food that landed in the slot during a Cook-All race. */
    public boolean invSlotClickVerifiedOnWorker(int slot, String verb,
                                                String... targetFragments)
        throws InterruptedException
    {
        Rectangle bounds = onClient(() -> resolveInvSlotBounds(slot));
        if (bounds == null)
        {
            lastError.set("inv slot " + slot + " not resolvable");
            return false;
        }
        int marginX = Math.max(1, bounds.width / 6);
        int marginY = Math.max(1, bounds.height / 6);
        int x = bounds.x + marginX
            + rng.nextInt(Math.max(1, bounds.width - 2 * marginX));
        int y = bounds.y + marginY
            + rng.nextInt(Math.max(1, bounds.height - 2 * marginY));
        moveCursorTo(x, y);
        SequenceSleep.sleep(client, 60);

        Boolean topOk = onClient(() -> {
            try
            {
                MenuEntry[] entries = client.getMenu() == null ? null
                    : client.getMenu().getMenuEntries();
                if (entries == null || entries.length == 0) return false;
                return menuEntryMatchesDirectTarget(entries[entries.length - 1],
                    verb, targetFragments);
            }
            catch (Throwable th) { return false; }
        });
        if (Boolean.TRUE.equals(topOk))
        {
            press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
            return true;
        }

        String topLbl = onClient(this::topMenuLabel);
        log.info("invSlotClickVerifiedOnWorker: L-click mismatch slot={} top='{}' verb='{}' fragments={}",
            slot, topLbl, verb, java.util.Arrays.toString(targetFragments));
        press(MouseEvent.BUTTON3, ClickIntent.UI, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        MenuRow row = onClient(() -> findMenuRow(
            e -> menuEntryMatchesDirectTarget(e, verb, targetFragments)));
        if (row == null)
        {
            String dump = onClient(this::fullMenuDump);
            lastError.set("inv slot " + slot + " menu missing verified verb '"
                + verb + "' target~" + java.util.Arrays.toString(targetFragments));
            log.info("invSlotClickVerifiedOnWorker: menu missing slot={} verb='{}' fragments={} ({})",
                slot, verb, java.util.Arrays.toString(targetFragments), dump);
            dismissMenu();
            clearSelectedWidgetTargetMode();
            return false;
        }
        moveCursorTo(row.x, row.y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        press(MouseEvent.BUTTON1, ClickIntent.MENU_ROW, PressTiming.MENU_SELECTION);
        return true;
    }

    /** Cancel use-mode (a selected inventory item / spell target) by
     *  re-clicking the source widget — same as a real player cancelling:
     *  click the highlighted item again to toggle it off.
     *
     *  <p>Detects use-mode via {@link Client#getSelectedWidget()} (the
     *  engine's canonical source — non-null means an item is selected).
     *  Fetches its bounds on the client thread, picks a humanized pixel
     *  inside, and dispatches a left-click on the same widget; OSRS
     *  toggles off the selection.
     *
     *  <p>{@code VK_ESCAPE} is NOT used here — it just closes the
     *  inventory / sidebar tab while the engine's selected-widget state
     *  stays set, so the next click would still be interpreted as
     *  use-on-target. {@link #clearSelectedWidgetTargetMode()} is the
     *  fast engine-state-only equivalent for tight recovery loops where
     *  humanization isn't required.
     *
     *  @return true if use-mode was active and the cancel click was
     *          dispatched; false if no item was selected. */
    public boolean cancelUseModeIfActive() throws InterruptedException
    {
        Rectangle bounds = onClient(() -> {
            Widget sel = client.getSelectedWidget();
            if (sel == null) return null;
            Rectangle b = sel.getBounds();
            return (b == null || b.isEmpty()) ? null : b;
        });
        if (bounds == null) return false;

        int marginX = Math.max(1, bounds.width / 8);
        int marginY = Math.max(1, bounds.height / 8);
        int x = bounds.x + marginX
            + rng.nextInt(Math.max(1, bounds.width - 2 * marginX));
        int y = bounds.y + marginY
            + rng.nextInt(Math.max(1, bounds.height - 2 * marginY));
        moveCursorTo(x, y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
        return true;
    }

    /** Dismiss any open right-click context menu safely.
     *
     *  <p>Single entry point — every menu-miss recovery path in this
     *  dispatcher (and any external caller) should use this rather than
     *  tap {@code VK_ESCAPE} directly.
     *
     *  <p>Strategy: move the cursor a few dozen pixels outside the menu's
     *  bounding rect. The OSRS engine auto-dismisses the context menu as
     *  soon as the cursor leaves it, and unlike {@code VK_ESCAPE} this
     *  has no side effects on open interfaces (Grand Exchange, bank,
     *  shop, prayer book, …) — ESC is overloaded as "close topmost
     *  interface" and would close those out from under the next step.
     *
     *  <p>If move-away fails (rare — menu geometry unreadable, or the
     *  engine ignored the cursor move) we fall back to {@code VK_ESCAPE}
     *  so we never leave a stuck menu blocking the OSRS game thread.
     *  A stuck context menu halts cs2 scripts, NPC ticks, and queued
     *  click effects (CLAUDE.md §8). The ESC fallback may close an open
     *  interface as a side effect, but that's strictly preferable to a
     *  frozen client.
     *
     *  @return true if the menu is gone afterwards. */
    public boolean dismissMenu() throws InterruptedException
    {
        if (dismissMenuByMovingAway()) return true;
        log.info("dismissMenu: move-away did not close menu — falling back to VK_ESCAPE");
        tapKey(java.awt.event.KeyEvent.VK_ESCAPE);
        return Boolean.TRUE.equals(onClient(() -> !client.isMenuOpen()));
    }

    /** Move-away primitive — see {@link #dismissMenu()} for the public
     *  entry point. Kept private so callers always get the ESC fallback
     *  guarantee. */
    private boolean dismissMenuByMovingAway() throws InterruptedException
    {
        Boolean open = onClient(client::isMenuOpen);
        if (!Boolean.TRUE.equals(open)) return true;

        for (int attempt = 0; attempt < 2; attempt++)
        {
            Point target = onClient(this::menuDismissTarget);
            if (target == null) return false;
            moveCursorTo(target.getX(), target.getY());
            SequenceSleep.sleep(client, 80 + rng.nextInt(120));
            Boolean closed = onClient(() -> !client.isMenuOpen());
            if (Boolean.TRUE.equals(closed)) return true;
        }
        return Boolean.TRUE.equals(onClient(() -> !client.isMenuOpen()));
    }

    /** Clear any selected widget target mode (inventory item/spell "Use")
     *  without sending Escape. This is a recovery lever for cooking flows
     *  where stale use-mode is worse than a direct deselect. */
    public boolean clearSelectedWidgetTargetMode() throws InterruptedException
    {
        Boolean hadSelected = onClient(() -> client.getSelectedWidget() != null);
        if (!Boolean.TRUE.equals(hadSelected)) return false;
        onClient(() -> {
            client.setWidgetSelected(false);
            return null;
        });
        SequenceSleep.sleep(client, 60);
        return true;
    }

    private Point menuDismissTarget()
    {
        try
        {
            if (!client.isMenuOpen()) return null;
            Menu menu = client.getMenu();
            if (menu == null) return null;
            java.awt.Canvas c = client.getCanvas();
            if (c == null) return null;
            int canvasW = Math.max(10, c.getWidth());
            int canvasH = Math.max(10, c.getHeight());
            int x = menu.getMenuX();
            int y = menu.getMenuY();
            int w = Math.max(1, menu.getMenuWidth());
            int h = Math.max(1, menu.getMenuHeight());
            int offset = 12 + rng.nextInt(25);   // 12..36 px outside the menu
            int bleed = 6 + rng.nextInt(19);     // 6..24 px side slack

            int tx, ty;
            switch (rng.nextInt(4))
            {
                case 0 -> {
                    tx = x - offset;
                    ty = y - bleed + rng.nextInt(Math.max(1, h + 2 * bleed));
                }
                case 1 -> {
                    tx = x + w + offset;
                    ty = y - bleed + rng.nextInt(Math.max(1, h + 2 * bleed));
                }
                case 2 -> {
                    tx = x - bleed + rng.nextInt(Math.max(1, w + 2 * bleed));
                    ty = y - offset;
                }
                default -> {
                    tx = x - bleed + rng.nextInt(Math.max(1, w + 2 * bleed));
                    ty = y + h + offset;
                }
            }
            tx = clampInt(tx, 4, Math.max(5, canvasW - 4));
            ty = clampInt(ty, 4, Math.max(5, canvasH - 4));
            return new Point(tx, ty);
        }
        catch (Throwable th)
        {
            return null;
        }
    }

    /** Client-thread predicate: does {@code entry} have option matching
     *  {@code verb} (via {@link VerbMatcher}) AND a color-stripped target
     *  containing every fragment in {@code fragments} (case-insensitive)?
     *  Null/blank fragments are skipped. */
    private static boolean menuEntryMatches(MenuEntry entry, String verb,
                                            String[] fragments)
    {
        if (entry == null) return false;
        if (!VerbMatcher.matches(verb, entry.getOption())) return false;
        String target = entry.getTarget();
        String stripped = (target == null ? "" : target.replaceAll("<[^>]+>", ""))
            .toLowerCase();
        if (fragments == null) return true;
        for (String frag : fragments)
        {
            if (frag == null || frag.isBlank()) continue;
            if (!stripped.contains(frag.toLowerCase())) return false;
        }
        return true;
    }

    /** Like {@link #menuEntryMatches}, but rejects use-mode targets such as
     *  "Raw chicken -> Tinderbox". Inventory item selection needs this:
     *  hovering a slot while another item is already selected still shows
     *  option "Use" and contains the slot's item name, but clicking would use
     *  the currently selected item ON that slot instead of selecting it. */
    private static boolean menuEntryMatchesDirectTarget(MenuEntry entry,
                                                       String verb,
                                                       String[] fragments)
    {
        if (!menuEntryMatches(entry, verb, fragments)) return false;
        String target = entry.getTarget();
        String stripped = target == null ? "" : target.replaceAll("<[^>]+>", "");
        return !stripped.contains("->");
    }

    /** Worker-callable wrapper around {@link #typeChatboxInternal} — same
     *  rationale as {@link #boundsClickOnWorker}. */
    public boolean typeChatboxOnWorker(String text, long awaitMs,
                                        long minDwellMs, long maxDwellMs,
                                        boolean pressEnter)
        throws InterruptedException
    {
        return typeChatboxInternal(text, awaitMs, minDwellMs, maxDwellMs, pressEnter);
    }

    /** Worker-callable wrapper around {@link #widgetClick} — same rationale
     *  as {@link #boundsClickOnWorker}. Use from inside a {@code RUN_TASK}
     *  that needs to click a widget mid-flow without re-acquiring busy. */
    public void widgetClickOnWorker(int widgetId) throws InterruptedException
    {
        widgetClick(widgetId);
    }

    /** Worker-callable wrapper around {@link #widgetVerbClick} — same
     *  rationale as {@link #boundsClickOnWorker}. */
    public void widgetVerbClickOnWorker(int widgetId, String verb) throws InterruptedException
    {
        widgetVerbClick(widgetId, verb);
    }

    /** Worker-callable wrapper around {@link #invSlotClick} — same
     *  rationale as {@link #boundsClickOnWorker}. */
    public void invSlotClickOnWorker(int slot, String verb) throws InterruptedException
    {
        invSlotClick(slot, verb);
    }

    /** Worker-callable wrapper around {@link #npcClick} — same rationale
     *  as {@link #boundsClickOnWorker}. Use from inside a {@code RUN_TASK}
     *  (e.g. a {@code BankActions.clickBankBoothRandom} body invoked by
     *  the GE bank-prep flow) that needs to click an NPC mid-flow without
     *  re-acquiring busy. Calling {@link #dispatch(ActionRequest)} from
     *  inside the worker would self-drop because the dispatcher already
     *  holds {@link #busy} on our behalf. */
    public void npcClickOnWorker(int npcIndex, String verb) throws InterruptedException
    {
        npcClick(npcIndex, verb);
    }

    /** Worker-callable wrapper around {@link #gameObjectClick} — same
     *  rationale as {@link #npcClickOnWorker}. Use for GameObject bank
     *  booths inside a {@code RUN_TASK}. */
    public void gameObjectClickOnWorker(WorldPoint tile, String verb)
        throws InterruptedException
    {
        gameObjectClick(tile, verb, false, false);
    }

    /** Click inside a pre-resolved canvas rectangle with optional verb match.
     *  Caller has already located the exact bounds (typically by walking a
     *  parent widget's children and reading {@code child.getBounds()} on the
     *  client thread) — useful when the click target is a dynamic child whose
     *  {@code getId()} returns the parent's packed id, so packed-id resolution
     *  would land somewhere inside the parent rather than the specific child.
     *
     *  <p>Sampling and verb behaviour mirror {@link #invSlotClick}: humanized
     *  pixel inside the rect with edge margins, then either left-click (if
     *  the verb is the engine's L-click default) or right-click + menu
     *  select. */
    private void boundsClick(java.awt.Rectangle bounds, String verb) throws InterruptedException
    {
        if (bounds == null || bounds.isEmpty())
        {
            lastError.set("boundsClick: null or empty bounds");
            return;
        }
        // 1/4 inset (matches PixelResolver.resolveWidget) — keeps the click
        // well inside the engine's hit-test polygon. Observed live on the
        // GE search-results row of 2026-05-20: 1/6 inset on a 161×32 row
        // landed on a sibling result row badge (cornflour) often enough
        // that the wrong item got selected and the whole buy aborted. With
        // very small widgets (under 4 px on an axis) collapse the margin
        // so we still have ≥ 2 px of sample range.
        int marginX = Math.max(1, bounds.width / 4);
        int marginY = Math.max(1, bounds.height / 4);
        if (bounds.width  - 2 * marginX < 2) marginX = Math.max(1, (bounds.width  - 2) / 2);
        if (bounds.height - 2 * marginY < 2) marginY = Math.max(1, (bounds.height - 2) / 2);
        int x = bounds.x + marginX
            + rng.nextInt(Math.max(1, bounds.width - 2 * marginX));
        int y = bounds.y + marginY
            + rng.nextInt(Math.max(1, bounds.height - 2 * marginY));
        // Diagnostic — log the actual click pixel + bounds so we can
        // compare against the in-game widget overlay. If the click pixel
        // is inside the bounds but the engine routes it to the wrong
        // widget, the bounds-vs-hit-test polygon don't agree (e.g. the
        // GE COLLECTALL "whole toolbar" issue).
        log.info("boundsClick: bounds={} pixel=({},{}) verb={}", bounds, x, y, verb);
        moveCursorTo(x, y);
        if (verb == null || verb.isBlank())
        {
            press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
            return;
        }
        SequenceSleep.sleep(client, 60);
        boolean isTop = onClient(() -> isTopMenuVerb(verb));
        if (isTop)
        {
            press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
            return;
        }
        log.info("boundsClick: top-verb mismatch for \"{}\" — opening right-click menu", verb);
        press(MouseEvent.BUTTON3, ClickIntent.UI, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        boolean ok = selectMenuVerb(verb);
        if (!ok)
        {
            lastError.set("boundsClick: menu open but verb '" + verb + "' not present");
            // CRITICAL: a stuck right-click menu blocks ALL OSRS gameplay
            // (cs2 scripts, NPC movement, the queued click's effects)
            // until dismissed. Without this call the entire client appears
            // "throttled" until the dispatcher gives up. Symptomatic
            // failure: chatbox prompt never opens after a BUY click, then
            // opens after our timeout fires.
            log.warn("boundsClick: dismissing stuck right-click menu (verb '{}' not in entries)", verb);
            dismissMenu();
        }
    }

    /** Click a widget (typically an inventory slot) with a non-default verb
     *  via right-click → menu select → verb row. If the verb is the engine's
     *  current left-click default, falls through to a plain left-click on
     *  the widget instead — matches a real player who hovers and sees the
     *  desired action already on top.
     *
     *  <p>Used by {@code MiningLoop}'s power-mining strategy ("Drop") and
     *  potentially banking flows ("Deposit"); the menu navigation re-uses
     *  {@link #selectMenuVerb}, the same flow {@link #gameObjectClick} uses
     *  for non-default object verbs. */
    private void widgetVerbClick(int widgetId, String verb) throws InterruptedException
    {
        Point pixel = onClient(() -> resolver.resolveWidget(widgetId));
        if (pixel == null)
        {
            log.info("widgetVerbClick: widget 0x{} unresolved at click time (verb='{}')",
                Integer.toHexString(widgetId), verb);
            lastError.set("widget " + widgetId + " not found");
            return;
        }
        moveCursorTo(pixel.getX(), pixel.getY());
        // Give the engine ~2 render frames to populate hover-state menu entries.
        SequenceSleep.sleep(client, 60);
        String topVerb = onClient(this::topMenuVerbForDiag);
        boolean isTop = onClient(() -> isTopMenuVerb(verb));
        if (isTop)
        {
            log.info("widgetVerbClick: widget 0x{} left-click (verb='{}' top='{}')",
                Integer.toHexString(widgetId), verb, topVerb);
            press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
            return;
        }
        log.info("widgetVerbClick: widget 0x{} right-click fallback (wanted='{}' top='{}')",
            Integer.toHexString(widgetId), verb, topVerb);
        press(MouseEvent.BUTTON3, ClickIntent.UI, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        boolean ok = selectMenuVerb(verb);
        if (!ok)
        {
            log.info("widgetVerbClick: widget 0x{} menu select FAILED (verb='{}' not in menu)",
                Integer.toHexString(widgetId), verb);
            lastError.set("widget " + widgetId + " menu open but verb '" + verb + "' not present");
            dismissMenu();
        }
    }

    /** Verb-routed CLICK_WIDGET that accepts ANY of {@code verbs} as the
     *  matching top entry / selected menu row. Use when multiple default
     *  actions would satisfy the caller — e.g. GE COLLECTALL where both
     *  "Collect to inventory" and "Collect to bank" drain the slot and
     *  the user's toggle decides which one is top. Iterates verbs in
     *  priority order: first one matched (left- or right-click path) wins.
     *  Encoded over CLICK_WIDGET by joining verbs with "|" — the handle()
     *  router splits and dispatches here. */
    private void widgetAnyVerbClick(int widgetId, java.util.List<String> verbs)
        throws InterruptedException
    {
        if (verbs == null || verbs.isEmpty())
        {
            log.info("widgetAnyVerbClick: widget 0x{} no candidate verbs supplied",
                Integer.toHexString(widgetId));
            lastError.set("widget " + widgetId + " no candidate verbs");
            return;
        }
        Point pixel = onClient(() -> resolver.resolveWidget(widgetId));
        if (pixel == null)
        {
            log.info("widgetAnyVerbClick: widget 0x{} unresolved at click time (verbs={})",
                Integer.toHexString(widgetId), verbs);
            lastError.set("widget " + widgetId + " not found");
            return;
        }
        moveCursorTo(pixel.getX(), pixel.getY());
        SequenceSleep.sleep(client, 60);
        String topVerb = onClient(this::topMenuVerbForDiag);
        String topMatch = onClient(() -> {
            for (String v : verbs)
            {
                if (v != null && !v.isBlank() && isTopMenuVerb(v)) return v;
            }
            return null;
        });
        if (topMatch != null)
        {
            log.info("widgetAnyVerbClick: widget 0x{} left-click (matched='{}' top='{}')",
                Integer.toHexString(widgetId), topMatch, topVerb);
            press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
            return;
        }
        log.info("widgetAnyVerbClick: widget 0x{} right-click fallback (wanted={} top='{}')",
            Integer.toHexString(widgetId), verbs, topVerb);
        press(MouseEvent.BUTTON3, ClickIntent.UI, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        String[] matched = new String[1];
        MenuRow row = onClient(() -> {
            for (String v : verbs)
            {
                if (v == null || v.isBlank()) continue;
                MenuRow r = findMenuRow(v);
                if (r != null) { matched[0] = v; return r; }
            }
            return null;
        });
        if (row == null)
        {
            log.info("widgetAnyVerbClick: widget 0x{} menu select FAILED (none of {} in menu)",
                Integer.toHexString(widgetId), verbs);
            lastError.set("widget " + widgetId + " menu open but none of " + verbs + " present");
            dismissMenu();
            return;
        }
        log.info("widgetAnyVerbClick: widget 0x{} menu pick='{}'",
            Integer.toHexString(widgetId), matched[0]);
        moveCursorTo(row.x, row.y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        press(MouseEvent.BUTTON1, ClickIntent.MENU_ROW, PressTiming.MENU_SELECTION);
    }

    /** Bounds-based mirror of {@link #widgetAnyVerbClick}: accepts a list
     *  of acceptable verbs and clicks at a humanized pixel inside {@code
     *  bounds}, taking the left-click fast path if any of the verbs is the
     *  engine's top menu entry at the cursor, otherwise right-click +
     *  selectMenuVerb with the same verb list. Used when the caller has
     *  already resolved bounds on the client thread (e.g. a child of a
     *  larger toolbar widget) and doesn't want the dispatcher to re-resolve
     *  the parent's widget id. */
    private void boundsAnyVerbClick(java.awt.Rectangle bounds, java.util.List<String> verbs)
        throws InterruptedException
    {
        if (bounds == null || bounds.isEmpty())
        {
            lastError.set("boundsAnyVerbClick: null or empty bounds");
            return;
        }
        if (verbs == null || verbs.isEmpty())
        {
            lastError.set("boundsAnyVerbClick: no candidate verbs");
            return;
        }
        int marginX = Math.max(1, bounds.width / 4);
        int marginY = Math.max(1, bounds.height / 4);
        if (bounds.width  - 2 * marginX < 2) marginX = Math.max(1, (bounds.width  - 2) / 2);
        if (bounds.height - 2 * marginY < 2) marginY = Math.max(1, (bounds.height - 2) / 2);
        int x = bounds.x + marginX
            + rng.nextInt(Math.max(1, bounds.width - 2 * marginX));
        int y = bounds.y + marginY
            + rng.nextInt(Math.max(1, bounds.height - 2 * marginY));
        log.info("boundsAnyVerbClick: bounds={} pixel=({},{}) verbs={}",
            bounds, x, y, verbs);
        moveCursorTo(x, y);
        SequenceSleep.sleep(client, 60);
        String topVerb = onClient(this::topMenuVerbForDiag);
        String topMatch = onClient(() -> {
            for (String v : verbs)
            {
                if (v != null && !v.isBlank() && isTopMenuVerb(v)) return v;
            }
            return null;
        });
        if (topMatch != null)
        {
            log.info("boundsAnyVerbClick: left-click (matched='{}' top='{}')",
                topMatch, topVerb);
            press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
            return;
        }
        log.info("boundsAnyVerbClick: right-click fallback (wanted={} top='{}')",
            verbs, topVerb);
        press(MouseEvent.BUTTON3, ClickIntent.UI, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        String[] matched = new String[1];
        MenuRow row = onClient(() -> {
            for (String v : verbs)
            {
                if (v == null || v.isBlank()) continue;
                MenuRow r = findMenuRow(v);
                if (r != null) { matched[0] = v; return r; }
            }
            return null;
        });
        if (row == null)
        {
            log.info("boundsAnyVerbClick: menu select FAILED (none of {} in menu)", verbs);
            lastError.set("bounds menu open but none of " + verbs + " present");
            dismissMenu();
            return;
        }
        log.info("boundsAnyVerbClick: menu pick='{}'", matched[0]);
        moveCursorTo(row.x, row.y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        press(MouseEvent.BUTTON1, ClickIntent.MENU_ROW, PressTiming.MENU_SELECTION);
    }

    /** Diagnostic snapshot of the current cursor-position top menu entry. Used
     *  for log lines only — the real verb-routing path uses
     *  {@link #isTopMenuVerb(String)} which normalizes via VerbMatcher. Returns
     *  the raw option (no target / colour-tag stripping) for human reading.
     *  Must run on the client thread. */
    private String topMenuVerbForDiag()
    {
        try
        {
            MenuEntry[] entries = client.getMenu() == null ? null
                : client.getMenu().getMenuEntries();
            if (entries == null || entries.length == 0) return "(empty)";
            MenuEntry top = entries[entries.length - 1];
            String opt = top.getOption();
            String tgt = top.getTarget();
            if (tgt != null && !tgt.isEmpty())
            {
                String tgtClean = tgt.replaceAll("<[^>]+>", "").trim();
                if (!tgtClean.isEmpty()) return opt + " " + tgtClean;
            }
            return opt == null ? "(null)" : opt;
        }
        catch (Throwable th) { return "(err)"; }
    }

    private void keyTap(int keyCode) throws InterruptedException
    {
        int hold = 50 + rng.nextInt(50);   // realistic per-key dwell
        input.keyTap(keyCode, hold);
    }

    @Override public void cancel(ActionRequest req) { /* no in-flight cancel — let it finish */ }
    @Override public boolean isBusy() { return busy.get(); }
    @Override public InputMode mode() { return InputMode.HUMANIZED; }

    // ----- helpers below are exposed for special surfaces (e.g. title-screen
    // login) that don't fit the ActionRequest taxonomy. They run on the
    // *caller's* thread because the caller wants to compose multi-step
    // humanized sequences (move → click → settle → type) without relying on
    // the busy flag. The single-flight policy via dispatch() above does not
    // apply; LoginAssistant uses these directly off its own daemon thread
    // and so must serialise itself.

    /** Move the cursor along a humanized path and click at the given canvas
     *  pixel. No widget / world resolution — the caller picked the pixel.
     *  Used for the OSRS title screen, which has no widget hierarchy. */
    public void clickCanvas(int x, int y) throws InterruptedException
    {
        moveCursorTo(x, y);
        press(MouseEvent.BUTTON1, ClickIntent.RAW, PressTiming.STANDARD);
    }

    /** Proportional-coordinate overload: xProp and yProp are in [0.0, 1.0]
     *  and are multiplied by the current canvas dimensions to obtain pixels. */
    public void clickCanvas(double xProp, double yProp) throws InterruptedException
    {
        int w = client.getCanvasWidth();
        int h = client.getCanvasHeight();
        clickCanvas((int) (w * xProp), (int) (h * yProp));
    }

    /** Move the cursor to (x, y) along a humanized path. No click. */
    public void moveCursor(int x, int y) throws InterruptedException
    {
        moveCursorTo(x, y);
    }

    /** Resolve a humanized pixel inside {@code widgetId}, move the cursor
     *  there along a wind path, then dwell for a randomly-chosen duration
     *  in [{@code minDwellMs}, {@code maxDwellMs}]. Returns true if the
     *  hover landed (cursor reached the widget and we slept the full
     *  dwell), false if the widget couldn't be resolved (hidden / not
     *  present).
     *
     *  <p>Used to surface tooltips that the engine only renders while
     *  the cursor is over a target — e.g. "X exp to next level" on a
     *  Stats panel skill icon. Caller should typically wrap this in
     *  {@link #runExclusive} so combat/walker dispatches back off
     *  cleanly during the dwell. */
    public boolean hoverWidget(int widgetId, long minDwellMs, long maxDwellMs)
        throws InterruptedException
    {
        if (maxDwellMs < minDwellMs) maxDwellMs = minDwellMs;
        Point pixel = onClient(() -> resolver.resolveWidget(widgetId));
        if (pixel == null)
        {
            log.debug("hoverWidget({}) — widget not visible / not present", widgetId);
            return false;
        }
        moveCursorTo(pixel.getX(), pixel.getY());
        long dwell = minDwellMs == maxDwellMs
            ? minDwellMs
            : minDwellMs + rng.nextInt((int) Math.min(Integer.MAX_VALUE, maxDwellMs - minDwellMs + 1));
        SequenceSleep.sleep(client, dwell);
        return true;
    }

    /** Scroll the mouse wheel at canvas position (x, y) by
     *  {@code notches} notches in {@code direction} (+1 = down,
     *  -1 = up). Each notch has a humanized 80-220ms gap before the
     *  next so the scroll looks like a real player flicking the wheel,
     *  not a snap-to-position. The cursor is moved to (x, y) first if
     *  it isn't already there. */
    public void wheelScroll(int x, int y, int direction, int notches)
        throws InterruptedException
    {
        if (notches <= 0) return;
        int sgn = direction >= 0 ? 1 : -1;
        // Move cursor onto the target if it's far away — wheel events
        // are consumed by whatever widget the cursor is over.
        if (Math.abs(input.cursorX() - x) > 4 || Math.abs(input.cursorY() - y) > 4)
        {
            moveCursorTo(x, y);
            SequenceSleep.sleep(client, 60 + rng.nextInt(80));
        }
        for (int i = 0; i < notches; i++)
        {
            input.mouseWheel(x, y, sgn);
            SequenceSleep.sleep(client, 80 + rng.nextInt(140));
        }
    }

    /** Block the calling thread until the dispatcher's async worker is
     *  idle (or {@code timeoutMs} elapses). Useful when chaining
     *  {@link #dispatch(ActionRequest)} (async) into a follow-up
     *  {@link #clickCanvas} (sync) — the canvas click would otherwise
     *  interleave with the async dispatch's cursor move.
     *
     *  <p>Returns true when idle, false on timeout. Throws
     *  {@link InterruptedException} if the caller is interrupted. */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (busy.get())
        {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("awaitIdle interrupted");
            if (System.currentTimeMillis() > deadline) return false;
            SequenceSleep.sleep(client, 50);
        }
        return true;
    }

    /** Acquire the busy flag, run {@code work} on the calling thread, then
     *  release. Returns {@code true} if {@code work} ran (we held the flag
     *  for its full duration), {@code false} if the dispatcher was already
     *  busy and we did nothing.
     *
     *  <p>Use this to compose multi-step humanized sequences that mix the
     *  fire-and-forget {@link #dispatch(ActionRequest)} API with the sync
     *  helpers ({@link #moveCursor}, {@link #clickCanvas}). Inside the
     *  closure you own the cursor — the combat / walker / etc. dispatchers
     *  will see {@link #isBusy()} and back off until you return.
     *
     *  <p>The closure may itself call {@code dispatch(...)} (sub-clicks
     *  that go through the async path) — they'll see the flag set, but
     *  the closure can call {@link #awaitIdle} on entries from those
     *  sub-paths if needed. The outer flag stays set throughout. */
    public boolean runExclusive(ExclusiveTask work) throws InterruptedException
    {
        if (work == null) return false;
        if (!busy.compareAndSet(false, true)) return false;
        try
        {
            work.run();
            return true;
        }
        finally { busy.set(false); }
    }

    /** Closure type for {@link #runExclusive}. Allowed to throw
     *  {@link InterruptedException} so callers can sleep without wrapping. */
    @FunctionalInterface
    public interface ExclusiveTask
    {
        void run() throws InterruptedException;
    }

    /** Move to (x, y), then dispatch a humanized right-click + select the
     *  menu row whose option matches {@code verb}. If {@code verb} is
     *  already the engine's left-click default at the cursor, fires a
     *  single left-click instead — same shortcut path the
     *  widget/object verb-clicks use.
     *
     *  <p>Used by callers (e.g. bank-slot withdraw) that have a
     *  pre-resolved canvas pixel rather than a stable widget id, so the
     *  internal {@code widgetVerbClick} path doesn't apply. Sets
     *  {@link #lastError} on menu mismatch; never throws. */
    public void rightClickAndPickMenu(int x, int y, String verb) throws InterruptedException
    {
        if (verb == null || verb.isBlank())
        {
            lastError.set("rightClickAndPickMenu: empty verb");
            return;
        }
        moveCursorTo(x, y);
        SequenceSleep.sleep(client, 60);
        boolean isTop = onClient(() -> isTopMenuVerb(verb));
        if (isTop)
        {
            press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
            return;
        }
        press(MouseEvent.BUTTON3, ClickIntent.UI, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        boolean ok = selectMenuVerb(verb);
        if (!ok)
        {
            lastError.set("right-click menu did not contain verb '" + verb + "'");
            dismissMenu();
        }
    }

    /** Right-click at (x,y), then pick the first menu entry whose verb
     *  matches ANY candidate, iterated in priority order.  Returns the
     *  matched verb on success, or {@code null} if none matched (in
     *  which case the menu is dismissed and {@link #lastErrorMessage}
     *  is set).
     *
     *  <p>Same humanization shape as {@link #rightClickAndPickMenu} —
     *  including the left-click shortcut when a candidate is the slot's
     *  default action.  Verb match goes through
     *  {@link VerbMatcher#matches} so case / whitespace / hyphens are
     *  tolerant.
     *
     *  <p>Use when multiple verbs would satisfy the caller (e.g.
     *  {@code Withdraw-9} matches the slot's cached {@code Y} or the
     *  fixed {@code Withdraw-9} entry interchangeably). */
    public String rightClickAndPickFirstMatching(int x, int y,
                                                  java.util.List<String> verbs)
        throws InterruptedException
    {
        if (verbs == null || verbs.isEmpty())
        {
            lastError.set("rightClickAndPickFirstMatching: empty candidates");
            return null;
        }
        moveCursorTo(x, y);
        SequenceSleep.sleep(client, 60);
        // Left-click shortcut: if any candidate is the slot's default
        // (top-of-menu) action, just left-click — same fast path as
        // rightClickAndPickMenu.  Iterate in priority order so the
        // returned verb matches the candidate that actually fired.
        String topMatch = onClient(() -> {
            for (String v : verbs)
            {
                if (v != null && !v.isBlank() && isTopMenuVerb(v)) return v;
            }
            return null;
        });
        if (topMatch != null)
        {
            press(MouseEvent.BUTTON1, ClickIntent.UI, PressTiming.STANDARD);
            return topMatch;
        }
        press(MouseEvent.BUTTON3, ClickIntent.UI, PressTiming.STANDARD);
        SequenceSleep.sleep(client, 120);
        // Probe verbs in priority order: first one that appears in the
        // open menu wins.  findMenuRow takes a predicate so each verb
        // is one O(entries) scan; verb count in practice is 1–2.
        String[] matched = new String[1];
        MenuRow row = onClient(() -> {
            for (String v : verbs)
            {
                if (v == null || v.isBlank()) continue;
                MenuRow r = findMenuRow(v);
                if (r != null) { matched[0] = v; return r; }
            }
            return null;
        });
        if (row == null)
        {
            lastError.set("right-click menu did not contain any of " + verbs);
            dismissMenu();
            return null;
        }
        moveCursorTo(row.x, row.y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        press(MouseEvent.BUTTON1, ClickIntent.MENU_ROW, PressTiming.MENU_SELECTION);
        return matched[0];
    }

    /** Type a single printable character with a humanized hold time. The
     *  caller is responsible for inter-character pacing (see HumanizedTyping). */
    public void typeChar(char ch) throws InterruptedException
    {
        int hold = 40 + rng.nextInt(70);   // 40..110ms per-key dwell
        input.keyType(ch, hold);
    }

    /** Tap a single virtual key (KeyEvent.VK_*) — convenience for callers
     *  that don't want to build an ActionRequest. */
    public void tapKey(int keyCode) throws InterruptedException
    {
        keyTap(keyCode);
    }

    /** Wait until any chatbox text/numeric input prompt is open. Two
     *  distinct signals — both required because each on its own misses
     *  half the prompts:
     *
     *  <ol>
     *    <li>{@code VarClientID.MESLAYERMODE != 0} catches RuneLite-
     *        injected input modes ({@code SEARCH=11} for bank-search /
     *        GE-search, {@code PRIVATE_MESSAGE=6} for /tell, plus the
     *        client's own panels). See {@link net.runelite.api.vars.InputType}
     *        for the full enumeration — the named constants there are
     *        every value the OSRS client ever writes to this varc.</li>
     *
     *    <li>{@code VarClientID.MESLAYERINPUT} (the typed-text varc-str)
     *        changing from baseline catches the CS2 numeric prompts
     *        ("Enter amount:" for bank Withdraw-X, "Set quantity:" /
     *        "Set price:" for GE offers). Those prompts are NOT in
     *        {@link net.runelite.api.vars.InputType}, so MESLAYERMODE
     *        stays 0 even when the dialog is plainly on screen — the
     *        only authoritative signal is that the OSRS client backs
     *        the input field with this varc-str (initialised fresh on
     *        dialog open). We snapshot at entry and watch for any
     *        transition; null → "" is the typical edge.</li>
     *  </ol>
     *
     *  <p>Walking widget {@code isHidden()} up the parent chain is
     *  unreliable for these prompts (the layer's hidden flag flickers
     *  across script-driven sub-states) so we don't use it.
     *
     *  <p>Returns true if a prompt was visible before the deadline;
     *  false on timeout. */
    public boolean awaitChatboxInputPrompt(long timeoutMs) throws InterruptedException
    {
        // Let any in-flight click chain land before we poll, otherwise
        // we race the right-click → menu pick that opened the dialog.
        awaitIdle(Math.min(timeoutMs, 2000L));
        // Snapshot AFTER awaitIdle so any pending typing has settled.
        // Subsequent polls compare against this — a transition (incl.
        // null → "" on fresh dialog open) is the open-signal for the
        // CS2 numeric prompts.
        String baseline = onClient(() -> client.getVarcStrValue(
            net.runelite.api.gameval.VarClientID.MESLAYERINPUT));
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            Boolean open = onClient(() -> {
                // Signal 1: MESLAYERMODE != 0 — fires for InputType modes
                // SEARCH (GE / bank-search) and PRIVATE_MESSAGE. The GE
                // search prompt uses this; observed value 14 in live runs.
                int mode = client.getVarcIntValue(
                    net.runelite.api.gameval.VarClientID.MESLAYERMODE);
                if (mode != 0) return true;
                // Signal 2: MESLAYERINPUT transition from the pre-click
                // baseline — supposed to flip null → "" when a CS2 numeric
                // prompt opens. Useful when it works; in practice the
                // bank "Enter amount:" and GE Set quantity / Set price
                // prompts often leave it unchanged.
                String typed = client.getVarcStrValue(
                    net.runelite.api.gameval.VarClientID.MESLAYERINPUT);
                if (!java.util.Objects.equals(typed, baseline)) return true;
                // Signal 3: Chatbox.MES_LAYER renders the prompt title as
                // a static child whose text starts with "Enter ", "Set ",
                // or "What " (the OSRS prompt headers). Catches the CS2
                // numeric prompts that don't move either varc — observed
                // failure mode in 14:09 bank withdraw-X and 14:25 GE set
                // quantity. We only count VISIBLE widgets so a hidden
                // residual title doesn't false-positive.
                net.runelite.api.widgets.Widget mesLayer = client.getWidget(
                    net.runelite.api.gameval.InterfaceID.Chatbox.MES_LAYER);
                if (mesLayer == null || mesLayer.isHidden()) return false;
                net.runelite.api.widgets.Widget[] kids = mesLayer.getStaticChildren();
                if (kids != null) {
                    for (net.runelite.api.widgets.Widget k : kids) {
                        if (k == null || k.isHidden()) continue;
                        String t = k.getText();
                        if (t == null || t.isEmpty()) continue;
                        String lower = t.toLowerCase();
                        if (lower.startsWith("enter ")
                            || lower.startsWith("set ")
                            || lower.startsWith("what ")) return true;
                    }
                }
                return false;
            });
            if (Boolean.TRUE.equals(open)) return true;
            SequenceSleep.sleep(client, 80L);
        }
        return false;
    }

    /** Wait for a chatbox input prompt, then type {@code text} with
     *  humanized inter-key pacing and submit with Enter. The text is
     *  whatever the OSRS prompt accepts as a raw string — digits for
     *  bank/GE quantity & price (so {@code "10000"}, {@code "10k"},
     *  {@code "4m"} all work), the item name for GE search.
     *
     *  <p>Uses the same keystroke path as login (per-char {@link #typeChar}
     *  + final {@link #tapKey}({@code VK_ENTER})). Returns true on a
     *  successful type+Enter; false if the prompt never appeared within
     *  {@code awaitMs}. */
    public boolean typeChatboxAndEnter(String text, long awaitMs) throws InterruptedException
    {
        return typeChatboxAndEnter(text, awaitMs, 0L, 0L);
    }

    /** Like {@link #typeChatboxAndEnter(String, long)} but inserts a
     *  randomized human-dwell delay {@code [minDwellMs, maxDwellMs]}
     *  AFTER the prompt is detected and BEFORE the first keystroke —
     *  modelling a player reading the prompt and reaching for the
     *  keyboard. Use this for GE typings (search / quantity / price)
     *  where instant-typing-bot behaviour is the most obvious tell.
     *
     *  <p>{@code awaitMs} is the safety-net cap on prompt-open polling
     *  only; passing a generous value (e.g. 15s) is fine because the
     *  poll exits as soon as {@code MESLAYERMODE != 0}. The dwell is
     *  deterministic in expected duration (uniform in the given range)
     *  regardless of how fast the prompt actually opened.
     *
     *  <p>Pass {@code minDwellMs == maxDwellMs == 0} to skip the dwell. */
    public boolean typeChatboxAndEnter(String text, long awaitMs,
                                        long minDwellMs, long maxDwellMs)
        throws InterruptedException
    {
        return typeChatboxInternal(text, awaitMs, minDwellMs, maxDwellMs, true);
    }

    /** Type into the chatbox prompt WITHOUT submitting Enter. Use for the
     *  GE search where we want to inspect the result list before picking
     *  a row instead of letting the engine auto-pick the first match.
     *  Same dwell + per-key humanization as
     *  {@link #typeChatboxAndEnter(String, long, long, long)}. */
    public boolean typeChatbox(String text, long awaitMs,
                                long minDwellMs, long maxDwellMs)
        throws InterruptedException
    {
        return typeChatboxInternal(text, awaitMs, minDwellMs, maxDwellMs, false);
    }

    private boolean typeChatboxInternal(String text, long awaitMs,
                                         long minDwellMs, long maxDwellMs,
                                         boolean pressEnter)
        throws InterruptedException
    {
        if (text == null || text.isEmpty()) return false;
        if (!awaitChatboxInputPrompt(awaitMs)) return false;
        // Pre-type diagnostic: we need to know if the canvas is the
        // keyboard-focus owner BEFORE typing. dispatchEvent reaches the
        // canvas's listeners regardless of focus, but the deob client's
        // chatbox text router might filter on focus state — observed
        // failure mode is "title shows the prompt with cursor (*) but
        // MESLAYERINPUT stays empty after our keystrokes."
        try {
            Boolean focused = onClient(() -> {
                java.awt.Canvas cc = client.getCanvas();
                return cc != null && cc.isFocusOwner();
            });
            log.info("typeChatbox pre-type: canvas.isFocusOwner={}", focused);
        } catch (Exception ignored) {}
        if (maxDwellMs > 0 && maxDwellMs >= minDwellMs)
        {
            long dwell = minDwellMs
                + (maxDwellMs > minDwellMs
                    ? rng.nextInt((int) Math.min(Integer.MAX_VALUE, maxDwellMs - minDwellMs + 1))
                    : 0L);
            SequenceSleep.sleep(client, dwell);
        }
        for (char ch : text.toCharArray())
        {
            typeChar(ch);
            // Per-key cadence with two regimes:
            //   - 80% of keys: 180–380ms (~30–50 WPM body text).
            //   - 20% of keys: an added 350–1100ms "thinking pause".
            // Tuned after live feedback that 90–220ms felt like a paste —
            // the engine renders each char instantly so the eye sees no
            // hesitation. Real typists slow at word boundaries / on the
            // first char after a UI transition; the long-tail bucket
            // models that without making every key sluggish.
            long base = 180L + rng.nextInt(200);
            long pause = (rng.nextInt(100) < 20) ? (350L + rng.nextInt(750)) : 0L;
            SequenceSleep.sleep(client, base + pause);
        }
        if (pressEnter) tapKey(java.awt.event.KeyEvent.VK_ENTER);
        // Post-type diagnostic: log the engine's view of the chatbox after
        // typing finishes. If MESLAYERINPUT and the search-results widget
        // text disagree with what we typed, the cs2 input handler ate the
        // chars somewhere we don't see — turning a silent failure into an
        // observable one.
        try {
            String mli = onClient(() -> client.getVarcStrValue(
                net.runelite.api.gameval.VarClientID.MESLAYERINPUT));
            String resultsText = onClient(() -> {
                net.runelite.api.widgets.Widget w = client.getWidget(
                    net.runelite.api.gameval.InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
                if (w == null) return "(null)";
                net.runelite.api.widgets.Widget[] kids = w.getDynamicChildren();
                if (kids == null || kids.length == 0) return "(no kids)";
                String t = kids[0].getText();
                return "kids=" + kids.length + " [0].text=\""
                    + (t == null ? "null" : (t.length() > 80 ? t.substring(0, 80) + "…" : t)
                        .replaceAll("<[^>]+>", "")) + "\"";
            });
            // Also probe MES_LAYER's title text widget — OSRS GE search
            // renders the typed input AS PART OF the title ("What would
            // you like to buy? bread*"), not in MESLAYERINPUT. Walk the
            // static children and dump anything visible whose text starts
            // with "Enter ", "Set ", or "What " — that's the title.
            String titleText = onClient(() -> {
                net.runelite.api.widgets.Widget mes = client.getWidget(
                    net.runelite.api.gameval.InterfaceID.Chatbox.MES_LAYER);
                if (mes == null) return "(MES_LAYER null)";
                net.runelite.api.widgets.Widget[] kids = mes.getStaticChildren();
                if (kids == null) return "(no static kids)";
                StringBuilder sb = new StringBuilder();
                for (net.runelite.api.widgets.Widget k : kids) {
                    if (k == null || k.isHidden()) continue;
                    String t = k.getText();
                    if (t == null || t.isEmpty()) continue;
                    String stripped = t.replaceAll("<[^>]+>", "").trim();
                    if (stripped.startsWith("Enter ") || stripped.startsWith("Set ")
                        || stripped.startsWith("What ")) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(stripped);
                    }
                }
                return sb.length() == 0 ? "(no title text)" : sb.toString();
            });
            log.info("typeChatbox post-type: typed=\"{}\" enter={} MESLAYERINPUT=\"{}\" title=\"{}\" results={}",
                text, pressEnter, mli, titleText, resultsText);
        } catch (Exception ignored) {}
        return true;
    }

    /** Poll until the GE search results widget
     *  ({@code Chatbox.MES_LAYER_SCROLLCONTENTS}) has at least one
     *  result group rendered (groups of 3 dynamic children per row —
     *  see RuneLite's GrandExchangePlugin#highlightSearchMatches).
     *  Returns true if results were visible before the deadline; false
     *  on timeout (no results yet, or widget never rendered). */
    public boolean awaitSearchResultsPopulated(long timeoutMs) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            Boolean ready = onClient(() -> {
                net.runelite.api.widgets.Widget r = client.getWidget(
                    net.runelite.api.gameval.InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
                if (r == null || r.isHidden()) return false;
                net.runelite.api.widgets.Widget[] kids = r.getDynamicChildren();
                return kids != null && kids.length >= 3;
            });
            if (Boolean.TRUE.equals(ready)) return true;
            SequenceSleep.sleep(client, 80L);
        }
        return false;
    }

    /** Tap a chord key while {@code modifierKeyCode} is held. Used for
     *  Ctrl+V / Cmd+V paste. The modifier is pressed down, the chord key is
     *  pressed and released, then the modifier is released — a real OS
     *  combo, not a raw keystroke with a flag bit set. */
    public void tapKeyWithModifier(int modifierKeyCode, int modifierMask, int keyCode)
        throws InterruptedException
    {
        java.awt.Canvas c = client.getCanvas();
        long t = System.currentTimeMillis();
        // Press modifier (no release until after the chord).
        c.dispatchEvent(new java.awt.event.KeyEvent(c, java.awt.event.KeyEvent.KEY_PRESSED,
            t, modifierMask, modifierKeyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED));
        SequenceSleep.sleep(client, 20 + rng.nextInt(30));
        int innerHold = 40 + rng.nextInt(60);
        input.keyTapWithModifier(keyCode, modifierMask, innerHold);
        SequenceSleep.sleep(client, 20 + rng.nextInt(40));
        c.dispatchEvent(new java.awt.event.KeyEvent(c, java.awt.event.KeyEvent.KEY_RELEASED,
            t, 0, modifierKeyCode, java.awt.event.KeyEvent.CHAR_UNDEFINED));
    }

    /** One-shot park: drift the cursor to just past the canvas edge,
     *  unconditionally. Used by post-login flow so the cursor doesn't sit
     *  in the middle of the screen during the load-in transition. */
    public void parkCursor() throws InterruptedException
    {
        java.awt.Canvas c = client.getCanvas();
        if (c == null) return;
        int w = c.getWidth(), h = c.getHeight();
        if (w < 200 || h < 200) return;
        int edge = rng.nextInt(4);
        int over = 6 + rng.nextInt(50);
        int targetX, targetY;
        switch (edge)
        {
            case 0 -> { targetX = 30 + rng.nextInt(Math.max(1, w - 60)); targetY = -over; }
            case 1 -> { targetX = w + over; targetY = 30 + rng.nextInt(Math.max(1, h - 60)); }
            case 2 -> { targetX = 30 + rng.nextInt(Math.max(1, w - 60)); targetY = h + over; }
            default -> { targetX = -over; targetY = 30 + rng.nextInt(Math.max(1, h - 60)); }
        }
        moveCursorTo(targetX, targetY);
    }

    /** Run a value-returning lambda on the client thread (mirror of the
     *  internal helper so peer classes in this package can read scene state
     *  without rolling their own ClientThread plumbing). */
    public <T> T runOnClient(Supplier<T> task) throws InterruptedException
    {
        return onClient(task);
    }

    /** Expose the client this dispatcher is bound to so peer classes can
     *  reach the canvas/world state without re-injecting it. */
    public Client getClient() { return client; }
}
