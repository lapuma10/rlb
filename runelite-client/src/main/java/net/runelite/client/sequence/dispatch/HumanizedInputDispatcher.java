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
import net.runelite.api.WorldView;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.transport.TransportResolver;
import net.runelite.client.plugins.recorder.transport.VerbMatcher;
import net.runelite.client.sequence.InputMode;
import net.runelite.client.sequence.internal.ActionRequest;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
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
    private final Random rng = new Random();
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
        this.wind = new WindMouse();
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
        Integer desired = onClient(() -> {
            var local = client.getLocalPlayer();
            if (local == null || local.getWorldLocation() == null) return null;
            WorldPoint here = local.getWorldLocation();
            int dx = target.getX() - here.getX();
            int dy = target.getY() - here.getY();
            if (dx == 0 && dy == 0) return null;
            double angle = Math.atan2(-dx, -dy);
            return ((int) Math.round(angle * 2048.0 / (2 * Math.PI))) & 0x7FF;
        });
        if (desired == null) return;
        // Jitter the final yaw so we don't land bolt-centred on every
        // rotation. ±150 yaw units ≈ ±26°: enough to leave the target
        // visibly off-axis but still well inside the viewport.
        int jitter = rng.nextInt(301) - 150;
        desired = (desired + jitter + 2048) & 0x7FF;
        Integer current = onClient(() -> client.getCameraYawTarget() & 0x7FF);
        if (current == null) return;
        int signedDiff = ((desired - current + 1024 + 2048) % 2048) - 1024;
        if (Math.abs(signedDiff) < YAW_TOLERANCE) return;

        // Total drag duration scales mildly with the rotation magnitude —
        // 90° rotations should feel slower than 30° ones. Floor 500ms so
        // even small rotations don't snap; cap 1500ms so 180° doesn't
        // become tedious.
        int magnitude = Math.abs(signedDiff);
        int totalMs = clampInt(500 + magnitude * 2, 500, 1500);
        int stepMs = 25 + rng.nextInt(15);   // 25..40ms per update
        int steps = Math.max(8, totalMs / stepMs);
        log.debug("rotate camera: yaw {} → {} (Δ={}, {}ms over {} steps)",
            current, desired, signedDiff, totalMs, steps);
        final int from = current;
        for (int i = 1; i <= steps; i++)
        {
            double linear = (double) i / steps;
            double t = linear * linear * (3 - 2 * linear);   // smoothstep
            final int interim = (from + (int) Math.round(signedDiff * t) + 2048) & 0x7FF;
            try
            {
                onClient(() -> { client.setCameraYawTarget(interim); return null; });
            }
            catch (Throwable ignored) { /* best-effort */ }
            SequenceSleep.sleep(client, stepMs);
        }
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
            // little still looks natural. ~40px ≈ a tile diagonal at
            // default zoom.
            int margin = 40;
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
            case WALK, CLICK_TILE -> walkClick(req.getTile());
            case CLICK_NPC -> npcClick(req.getNpcIndex(),
                req.getVerb() == null || req.getVerb().isBlank() ? "Attack" : req.getVerb());
            case CLICK_GAME_OBJECT -> gameObjectClick(req.getTile(), req.getVerb());
            case CLICK_GROUND_ITEM -> groundItemClick(req.getTile(), req.getItemId(), req.getVerb());
            case CLICK_WIDGET -> {
                String verb = req.getVerb();
                if (verb != null && !verb.isBlank()) widgetVerbClick(req.getWidgetId(), verb);
                else widgetClick(req.getWidgetId());
            }
            case CLICK_INV_ITEM -> invSlotClick(Math.max(0, req.getSlot()), req.getVerb());
            case CLICK_BOUNDS -> boundsClick(req.getBounds(), req.getVerb());
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
     */
    private void walkClick(WorldPoint target) throws InterruptedException
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
            clickPress(MouseEvent.BUTTON1);
            return;
        }

        boolean walkOk = onClient(this::isLeftClickWalk);
        if (!walkOk)
        {
            String top = onClient(this::topMenuLabel);
            String dump = onClient(this::fullMenuDump);
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
        }
        clickPress(MouseEvent.BUTTON1);
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

    /** Click, with humanized pre-/post-click holds. Real cursor traces show
     *  a 200–700ms settle on the target before pressing and a 100–400ms
     *  hold after release before the cursor moves again — both are
     *  conspicuously absent from naïve "moveTo + click" code, which is one
     *  of the easiest tells for a bot. */
    private void clickPress(int button) throws InterruptedException
    {
        // Settle on target before pressing. Wider pre-click window than
        // post-click — humans aim then commit; the post-click "did I get
        // it?" beat is shorter.
        SequenceSleep.sleep(client, 180 + rng.nextInt(320));   // 180..500ms
        input.mousePress(button);
        SequenceSleep.sleep(client, 40 + rng.nextInt(40));     // 40..80ms button-down
        input.mouseRelease(button);
        SequenceSleep.sleep(client, 100 + rng.nextInt(250));   // 100..350ms post-click hold
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

        // Phase 4 — pre-click settle (the human "did I get the right one"
        // beat). This is the LAST big delay before the press.
        SequenceSleep.sleep(client, 180 + rng.nextInt(220));   // 180..400ms

        // Phase 5 — re-resolve the click pixel using the model convex hull.
        // This is the freshest possible measurement; the press lands within
        // ~100ms of this point.
        Point clickPixel = onClient(() -> {
            NPC npc = findNpc(npcIndex);
            return npc == null ? null : resolver.resolveNpc(npc);
        });
        if (clickPixel == null)
        {
            lastError.set("npc " + npcIndex + " disappeared during aim");
            return;
        }

        // Phase 6 — small final aim adjustment ONLY if the fresh pixel is
        // meaningfully off the pre-aim. Sub-5px deltas don't need a move
        // (real cursors don't micro-correct that finely).
        int dx = clickPixel.getX() - aim.preAim().getX();
        int dy = clickPixel.getY() - aim.preAim().getY();
        if (dx * dx + dy * dy > 16)   // > ~4px
        {
            moveCursorTo(clickPixel.getX(), clickPixel.getY());
        }
        SequenceSleep.sleep(client, 40 + rng.nextInt(40));     // 40..80ms final settle

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
            input.mousePress(MouseEvent.BUTTON1);
            SequenceSleep.sleep(client, 40 + rng.nextInt(40));
            input.mouseRelease(MouseEvent.BUTTON1);
            SequenceSleep.sleep(client, 100 + rng.nextInt(250));
            return;
        }

        // Phase 9 — right-click flow. Hover-default isn't our verb (another
        // actor under cursor, or `verb` isn't this NPC's default action).
        // Open the context menu and click the matching entry.
        log.info("npc {} not hover-default for '{}' — right-click flow", npcIndex, verb);
        clickPress(MouseEvent.BUTTON3);
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
            try { tapKey(java.awt.event.KeyEvent.VK_ESCAPE); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
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
            try { tapKey(java.awt.event.KeyEvent.VK_ESCAPE); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
            SequenceSleep.sleep(client, 120);
            return;
        }
        input.mousePress(MouseEvent.BUTTON1);
        SequenceSleep.sleep(client, 40 + rng.nextInt(40));
        input.mouseRelease(MouseEvent.BUTTON1);
        SequenceSleep.sleep(client, 80 + rng.nextInt(180));
    }

    /** Click target capture for {@link #npcClick}. {@code world} feeds the
     *  camera-rotate step; {@code preAim} is the rough cursor target for
     *  the long humanized move. Final click pixel is re-resolved later. */
    private record NpcAim(@javax.annotation.Nullable WorldPoint world, Point preAim) {}

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
            input.mousePress(MouseEvent.BUTTON1);
            SequenceSleep.sleep(client, 40 + rng.nextInt(40));
            input.mouseRelease(MouseEvent.BUTTON1);
            SequenceSleep.sleep(client, 100 + rng.nextInt(250));
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
        clickPress(MouseEvent.BUTTON3);
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
            dismissOpenMenuByMovingAway();
            clearSelectedWidgetTargetMode();
            return;
        }
        moveCursorTo(row.x, row.y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        input.mousePress(MouseEvent.BUTTON1);
        SequenceSleep.sleep(client, 40 + rng.nextInt(40));
        input.mouseRelease(MouseEvent.BUTTON1);
        SequenceSleep.sleep(client, 80 + rng.nextInt(180));
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
    private void gameObjectClick(WorldPoint tile, String verb) throws InterruptedException
    {
        if (tile == null) { lastError.set("null tile for gameObjectClick"); return; }
        if (verb == null || verb.isBlank())
        { lastError.set("null verb for gameObjectClick"); return; }
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
        // Camera rotation toward the tile keeps the object visible.
        rotateCameraToward(tile);

        // Try the convex-hull pixel first (matches the visible model
        // body — works for most objects). If the hover doesn't produce
        // the verb in the engine's menu, retry inside the same call
        // with the tile-footprint polygon, which is the actual hit-test
        // region for transport-style GameObjects (Lumbridge stairs,
        // ladders, doors). This avoids the 2-3 s walker-driven retry
        // burning ticks while the staircase model is right under the
        // cursor — the engine just doesn't accept clicks on the upper
        // hull region.
        if (tryGameObjectAttempt(match, tile, verb,
                PixelResolver.GameObjectStrategy.HULL))
        {
            return;
        }
        log.info("gameObjectClick {} verb='{}' — HULL miss, retrying TILE_POLY",
            tile, verb);
        if (tryGameObjectAttempt(match, tile, verb,
                PixelResolver.GameObjectStrategy.TILE_POLY))
        {
            lastError.set(null);  // HULL partial failure; TILE_POLY succeeded — no net error
            return;
        }
        log.info("gameObjectClick {} verb='{}' — TILE_POLY miss, giving up",
            tile, verb);
    }

    /** One resolve+hover+click attempt for a GameObject under the
     *  requested {@link PixelResolver.GameObjectStrategy}. Returns
     *  {@code true} when the click landed (left-click default OR
     *  right-click menu match); {@code false} when the verb is missing
     *  from the menu under this strategy and the caller should retry
     *  with a different strategy. On a {@code false} return, any open
     *  right-click menu has been dismissed and the cursor is settled
     *  for the next attempt. */
    private boolean tryGameObjectAttempt(TransportResolver.Match match,
                                         WorldPoint tile, String verb,
                                         PixelResolver.GameObjectStrategy strategy)
        throws InterruptedException
    {
        Point pixel = onClient(() -> {
            if (match.wallObject() != null) return resolver.resolveWallObject(match.wallObject());
            if (match.gameObject() != null)
                return resolver.resolveGameObject(match.gameObject(), strategy);
            // Decorative + Ground objects: use their tile polygon directly.
            // PixelResolver doesn't have a custom path for these, but the
            // generic walk-target resolver will pick a clean pixel inside
            // the tile, which is good enough.
            return resolver.resolveWalkTarget(tile);
        });
        if (pixel == null)
        {
            lastError.set("transport pixel unresolvable (off-screen?) at " + tile
                + " strategy=" + strategy);
            log.info("gameObjectClick {} — pixel unresolvable (strategy={})",
                tile, strategy);
            return false;
        }
        log.info("gameObjectClick → world {} via screen ({},{}) verb='{}' "
                + "(matched '{}', id={}, strategy={})",
            tile, pixel.getX(), pixel.getY(), verb,
            match.matchedVerb(), match.matchedObjectId(), strategy);
        moveCursorTo(pixel.getX(), pixel.getY());
        // Give the engine ~2 render frames to compute hover-state menu entries.
        SequenceSleep.sleep(client, 60);

        // Left-click default? Single-click and we're done.
        boolean isTop = onClient(() -> isTopMenuVerb(verb));
        if (isTop)
        {
            log.info("verb '{}' is left-click default — single click (strategy={})",
                verb, strategy);
            clickPress(MouseEvent.BUTTON1);
            return true;
        }
        // Verb is somewhere else in the menu — right-click to open it.
        String topLbl = onClient(this::topMenuLabel);
        log.info("verb '{}' not at top of menu (top='{}') — right-click flow (strategy={})",
            verb, topLbl, strategy);
        clickPress(MouseEvent.BUTTON3);
        // Wait one render frame so the engine actually populates / opens the
        // mini-menu. Engine ticks are ~50ms; one or two should be plenty.
        SequenceSleep.sleep(client, 120);
        if (selectMenuVerb(verb)) return true;

        // Verb missing from the right-click menu under this strategy.
        // Dismiss the open menu so the next attempt can hover cleanly,
        // and report enough context for diagnosis.
        String menuDump = onClient(this::fullMenuDump);
        lastError.set("menu open but verb '" + verb + "' not present (strategy="
            + strategy + ")");
        log.info("right-click menu did not contain verb '{}' (strategy={}, top='{}', {})",
            verb, strategy, topLbl, menuDump);
        try { tapKey(java.awt.event.KeyEvent.VK_ESCAPE); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
        // Let the right-click menu actually close before the next hover.
        SequenceSleep.sleep(client, 120);
        return false;
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
            return VerbMatcher.matches(verb, top.getOption());
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
        input.mousePress(MouseEvent.BUTTON1);
        SequenceSleep.sleep(client, 40 + rng.nextInt(40));
        input.mouseRelease(MouseEvent.BUTTON1);
        SequenceSleep.sleep(client, 80 + rng.nextInt(180));
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
        return findMenuRow(e -> VerbMatcher.matches(verb, e.getOption()));
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
            clickPress(MouseEvent.BUTTON1);
            return;
        }
        // Verb-aware: hover, check L-click default, fall back to right-click.
        moveCursorTo(x, y);
        SequenceSleep.sleep(client, 60);
        boolean isTop = onClient(() -> isTopMenuVerb(verb));
        if (isTop)
        {
            clickPress(MouseEvent.BUTTON1);
            return;
        }
        clickPress(MouseEvent.BUTTON3);
        SequenceSleep.sleep(client, 120);
        boolean ok = selectMenuVerb(verb);
        if (!ok)
        {
            lastError.set("inv slot " + slot + " menu open but verb '" + verb + "' not present");
            try { tapKey(java.awt.event.KeyEvent.VK_ESCAPE); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    private void widgetClick(int widgetId) throws InterruptedException
    {
        Point pixel = onClient(() -> resolver.resolveWidget(widgetId));
        if (pixel == null) { lastError.set("widget " + widgetId + " not found"); return; }
        moveCursorTo(pixel.getX(), pixel.getY());
        clickPress(MouseEvent.BUTTON1);
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
            clickPress(MouseEvent.BUTTON1);
            return true;
        }

        // Phase 2 — right-click + menu search. Handles the case where another
        // interactable (e.g. respawned logs on top of a fire) takes priority
        // for the L-click but the desired action is still in the full menu.
        String topLbl = onClient(this::topMenuLabel);
        log.info("boundsClickVerifiedAction: L-click mismatch (top='{}') — right-click flow for verb='{}' fragments={}",
            topLbl, verb, java.util.Arrays.toString(targetFragments));
        clickPress(MouseEvent.BUTTON3);
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
            dismissOpenMenuByMovingAway();
            clearSelectedWidgetTargetMode();
            return false;
        }
        moveCursorTo(row.x, row.y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        input.mousePress(MouseEvent.BUTTON1);
        SequenceSleep.sleep(client, 40 + rng.nextInt(40));
        input.mouseRelease(MouseEvent.BUTTON1);
        SequenceSleep.sleep(client, 80 + rng.nextInt(180));
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
            clickPress(MouseEvent.BUTTON1);
            return true;
        }

        String topLbl = onClient(this::topMenuLabel);
        log.info("invSlotClickVerifiedOnWorker: L-click mismatch slot={} top='{}' verb='{}' fragments={}",
            slot, topLbl, verb, java.util.Arrays.toString(targetFragments));
        clickPress(MouseEvent.BUTTON3);
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
            dismissOpenMenuByMovingAway();
            clearSelectedWidgetTargetMode();
            return false;
        }
        moveCursorTo(row.x, row.y);
        SequenceSleep.sleep(client, 40 + rng.nextInt(60));
        input.mousePress(MouseEvent.BUTTON1);
        SequenceSleep.sleep(client, 40 + rng.nextInt(40));
        input.mouseRelease(MouseEvent.BUTTON1);
        SequenceSleep.sleep(client, 80 + rng.nextInt(180));
        return true;
    }

    /** Press Escape only when the current hover proves an item is selected
     *  for use-mode. This avoids the old blind-Escape path that could close
     *  the inventory/sidebar after a dropped dispatch or non-use mismatch. */
    public boolean cancelUseModeIfActiveOnCurrentHover() throws InterruptedException
    {
        SequenceSleep.sleep(client, 40);
        Boolean active = onClient(() -> {
            try
            {
                MenuEntry[] entries = client.getMenu() == null ? null
                    : client.getMenu().getMenuEntries();
                if (entries == null || entries.length == 0) return false;
                return menuEntryLooksLikeUseMode(entries[entries.length - 1]);
            }
            catch (Throwable th) { return false; }
        });
        if (!Boolean.TRUE.equals(active)) return false;
        tapKey(java.awt.event.KeyEvent.VK_ESCAPE);
        SequenceSleep.sleep(client, 80);
        return true;
    }

    /** Close an open right-click menu by moving the cursor just outside its
     *  bounds, instead of pressing Escape. Used on recovery paths where
     *  Escape can have unwanted side effects such as closing the inventory
     *  sidebar. Returns true if the menu is gone afterwards. */
    public boolean dismissOpenMenuByMovingAway() throws InterruptedException
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

    private static boolean menuEntryLooksLikeUseMode(MenuEntry entry)
    {
        if (entry == null || !VerbMatcher.matches("Use", entry.getOption()))
            return false;
        String target = entry.getTarget();
        String stripped = (target == null ? "" : target.replaceAll("<[^>]+>", ""));
        return stripped.contains("->");
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
        int marginX = Math.max(1, bounds.width / 6);
        int marginY = Math.max(1, bounds.height / 6);
        int x = bounds.x + marginX
            + rng.nextInt(Math.max(1, bounds.width - 2 * marginX));
        int y = bounds.y + marginY
            + rng.nextInt(Math.max(1, bounds.height - 2 * marginY));
        moveCursorTo(x, y);
        if (verb == null || verb.isBlank())
        {
            clickPress(MouseEvent.BUTTON1);
            return;
        }
        SequenceSleep.sleep(client, 60);
        boolean isTop = onClient(() -> isTopMenuVerb(verb));
        if (isTop)
        {
            clickPress(MouseEvent.BUTTON1);
            return;
        }
        log.info("boundsClick: top-verb mismatch for \"{}\" — opening right-click menu", verb);
        clickPress(MouseEvent.BUTTON3);
        SequenceSleep.sleep(client, 120);
        boolean ok = selectMenuVerb(verb);
        if (!ok)
        {
            lastError.set("boundsClick: menu open but verb '" + verb + "' not present");
            // CRITICAL: a stuck right-click menu blocks ALL OSRS gameplay
            // (cs2 scripts, NPC movement, the queued click's effects) until
            // the user dismisses it. We dismiss with Escape so the engine
            // can continue processing — without this the entire client
            // appears "throttled" until the dispatcher gives up and stops
            // querying. Symptomatic failure: chatbox prompt never opens
            // after a BUY click, then opens after our timeout fires.
            log.warn("boundsClick: dismissing stuck right-click menu (verb '{}' not in entries)", verb);
            try { tapKey(java.awt.event.KeyEvent.VK_ESCAPE); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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
        if (pixel == null) { lastError.set("widget " + widgetId + " not found"); return; }
        moveCursorTo(pixel.getX(), pixel.getY());
        // Give the engine ~2 render frames to populate hover-state menu entries.
        SequenceSleep.sleep(client, 60);
        boolean isTop = onClient(() -> isTopMenuVerb(verb));
        if (isTop)
        {
            clickPress(MouseEvent.BUTTON1);
            return;
        }
        clickPress(MouseEvent.BUTTON3);
        SequenceSleep.sleep(client, 120);
        boolean ok = selectMenuVerb(verb);
        if (!ok)
        {
            lastError.set("widget " + widgetId + " menu open but verb '" + verb + "' not present");
            try { tapKey(java.awt.event.KeyEvent.VK_ESCAPE); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
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
        clickPress(MouseEvent.BUTTON1);
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
            clickPress(MouseEvent.BUTTON1);
            return;
        }
        clickPress(MouseEvent.BUTTON3);
        SequenceSleep.sleep(client, 120);
        boolean ok = selectMenuVerb(verb);
        if (!ok)
        {
            lastError.set("right-click menu did not contain verb '" + verb + "'");
            try { tapKey(java.awt.event.KeyEvent.VK_ESCAPE); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
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
