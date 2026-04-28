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
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
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

    public HumanizedInputDispatcher(Client client) { this(client, null); }

    public HumanizedInputDispatcher(Client client, ClientThread clientThread)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.input = new CanvasInput(client);
        this.resolver = new PixelResolver(client);
        this.wind = new WindMouse();
    }

    /** Last failure reason from the most recent dispatch — used by the side
     *  panel to surface "out of reach" / "menu mismatch" instead of silent
     *  no-ops. Cleared at the start of each dispatch. */
    public String lastErrorMessage() { return lastError.get(); }

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
        Thread.sleep(80 + rng.nextInt(220));
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
        if (target == null) return;
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
            Thread.sleep(stepMs);
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
            case CLICK_GROUND_ITEM -> groundItemClick(req.getTile(), req.getItemId());
            case CLICK_WIDGET -> widgetClick(req.getWidgetId());
            case CLICK_INV_ITEM -> {
                // Inventory child widget id = parent (149) << 16 | slot.
                int wid = (149 << 16) | Math.max(0, req.getSlot());
                if (req.getVerb() == null || req.getVerb().isBlank()) widgetClick(wid);
                else widgetVerbClick(wid, req.getVerb());
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
        Thread.sleep(50);

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
            Thread.sleep(30);
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
            Thread.sleep(s.dtMs());
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
        Thread.sleep(180 + rng.nextInt(320));   // 180..500ms
        input.mousePress(button);
        Thread.sleep(40 + rng.nextInt(40));     // 40..80ms button-down
        input.mouseRelease(button);
        Thread.sleep(100 + rng.nextInt(250));   // 100..350ms post-click hold
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
        Thread.sleep(180 + rng.nextInt(220));   // 180..400ms

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
        Thread.sleep(40 + rng.nextInt(40));     // 40..80ms final settle

        // Phase 7 — verify hover is `verb` on OUR npc, just before press.
        Boolean topMatches = onClient(() -> isTopVerbOnNpc(verb, npcIndex));
        if (Boolean.TRUE.equals(topMatches))
        {
            // Phase 8 — left-click. Settle above already covered the
            // "commit" beat; another long pre-click sleep would just give
            // the chicken time to step out from under us.
            input.mousePress(MouseEvent.BUTTON1);
            Thread.sleep(40 + rng.nextInt(40));
            input.mouseRelease(MouseEvent.BUTTON1);
            Thread.sleep(100 + rng.nextInt(250));
            return;
        }

        // Phase 9 — right-click flow. Hover-default isn't our verb (another
        // actor under cursor, or `verb` isn't this NPC's default action).
        // Open the context menu and click the matching entry.
        log.info("npc {} not hover-default for '{}' — right-click flow", npcIndex, verb);
        clickPress(MouseEvent.BUTTON3);
        Thread.sleep(120);
        MenuRow row = onClient(() -> findMenuRow(
            e -> VerbMatcher.matches(verb, e.getOption()) && e.getIdentifier() == npcIndex));
        if (row == null)
        {
            lastError.set("menu missing '" + verb + "' on npc " + npcIndex);
            log.info("right-click menu did not contain '{}' for npc {}", verb, npcIndex);
            return;
        }
        moveCursorTo(row.x, row.y);
        Thread.sleep(40 + rng.nextInt(60));
        input.mousePress(MouseEvent.BUTTON1);
        Thread.sleep(40 + rng.nextInt(40));
        input.mouseRelease(MouseEvent.BUTTON1);
        Thread.sleep(80 + rng.nextInt(180));
    }

    /** Click target capture for {@link #npcClick}. {@code world} feeds the
     *  camera-rotate step; {@code preAim} is the rough cursor target for
     *  the long humanized move. Final click pixel is re-resolved later. */
    private record NpcAim(@javax.annotation.Nullable WorldPoint world, Point preAim) {}

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
            return VerbMatcher.matches("Take", top.getOption())
                && top.getIdentifier() == itemId;
        }
        catch (Throwable th) { return false; }
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
    private void groundItemClick(WorldPoint tile, int itemId) throws InterruptedException
    {
        if (tile == null) { lastError.set("null tile for groundItemClick"); return; }
        // Phase 1 — verify the item is on the tile and find its world point.
        Boolean exists = onClient(() -> tileHasItem(tile, itemId));
        if (!Boolean.TRUE.equals(exists))
        {
            lastError.set("item " + itemId + " no longer on tile " + tile);
            return;
        }
        // Phase 2 — rotate camera so the tile is centred-ish.
        rotateCameraToward(tile);
        // Phase 3 — pre-aim on the actual item sprite, not just somewhere
        // inside the tile poly. Ground items occupy a small clickbox at the
        // tile centre; sampling the full tile polygon routinely landed on
        // a corner where the right-click menu had no "Take" entry for our
        // item. resolveGroundItemPixel uses Perspective.getClickbox on the
        // item's own model and falls back to the tile pixel if that fails.
        Point preAim = onClient(() -> resolver.resolveGroundItemPixel(tile, itemId));
        if (preAim == null)
        {
            lastError.set("ground item pixel unresolvable at " + tile);
            return;
        }
        moveCursorTo(preAim.getX(), preAim.getY());
        // Phase 4 — pre-click settle.
        Thread.sleep(180 + rng.nextInt(220));   // 180..400ms
        // Phase 5 — verify hover top is "Take" with our item id. Item tiles
        // don't have a separate "model hull" worth re-resolving; the tile
        // is the interactable area, so no Phase-5-style re-aim.
        Boolean topMatches = onClient(() -> isTopTakeOnItem(itemId));
        if (Boolean.TRUE.equals(topMatches))
        {
            input.mousePress(MouseEvent.BUTTON1);
            Thread.sleep(40 + rng.nextInt(40));
            input.mouseRelease(MouseEvent.BUTTON1);
            Thread.sleep(100 + rng.nextInt(250));
            return;
        }
        // Phase 6 — top of menu is a different action (Walk here, or
        // "Take" for a different item in the stack — chickens drop feather
        // + raw chicken + bones, the engine picks one for the left-click
        // default). Right-click is safe here in a way it isn't for NPC
        // clicks: tileHasItem confirmed our item IS on this tile, so a
        // right-click WILL include our "Take" entry. No risk of opening
        // a menu on empty grass.
        log.info("ground item {} not at top of menu at {} — right-click flow", itemId, tile);
        clickPress(MouseEvent.BUTTON3);
        Thread.sleep(120);
        MenuRow row = onClient(() -> findMenuRow(
            e -> VerbMatcher.matches("Take", e.getOption()) && e.getIdentifier() == itemId));
        if (row == null)
        {
            lastError.set("menu missing 'Take' on item " + itemId + " at " + tile);
            log.info("right-click menu did not contain 'Take' for item {} at {}", itemId, tile);
            return;
        }
        moveCursorTo(row.x, row.y);
        Thread.sleep(40 + rng.nextInt(60));
        input.mousePress(MouseEvent.BUTTON1);
        Thread.sleep(40 + rng.nextInt(40));
        input.mouseRelease(MouseEvent.BUTTON1);
        Thread.sleep(80 + rng.nextInt(180));
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
        Point pixel = onClient(() -> {
            if (match.wallObject() != null) return resolver.resolveWallObject(match.wallObject());
            if (match.gameObject() != null) return resolver.resolveGameObject(match.gameObject());
            // Decorative + Ground objects: use their tile polygon directly.
            // PixelResolver doesn't have a custom path for these, but the
            // generic walk-target resolver will pick a clean pixel inside
            // the tile, which is good enough.
            return resolver.resolveWalkTarget(tile);
        });
        if (pixel == null)
        {
            lastError.set("transport pixel unresolvable (off-screen?) at " + tile);
            log.info("gameObjectClick {} — pixel unresolvable", tile);
            return;
        }
        log.info("gameObjectClick → world {} via screen ({},{}) verb='{}' (matched '{}', id={})",
            tile, pixel.getX(), pixel.getY(), verb, match.matchedVerb(), match.matchedObjectId());
        moveCursorTo(pixel.getX(), pixel.getY());
        // Give the engine ~2 render frames to compute hover-state menu entries.
        Thread.sleep(60);

        // If the engine's top-of-menu (left-click action) already matches the
        // verb, a single left-click is enough — the same path a real player
        // takes when an object's primary action is what they want.
        boolean isTop = onClient(() -> isTopMenuVerb(verb));
        if (isTop)
        {
            log.info("verb '{}' is left-click default — single click", verb);
            clickPress(MouseEvent.BUTTON1);
            return;
        }
        // Verb is somewhere else in the menu — right-click to open, then
        // navigate the open menu.
        log.info("verb '{}' not at top of menu — right-click flow", verb);
        clickPress(MouseEvent.BUTTON3);
        // Wait one render frame so the engine actually populates / opens the
        // mini-menu. Engine ticks are ~50ms; one or two should be plenty.
        Thread.sleep(120);
        boolean ok = selectMenuVerb(verb);
        if (!ok)
        {
            lastError.set("menu open but verb '" + verb + "' not present");
            log.info("right-click menu did not contain verb '{}'", verb);
        }
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
        Thread.sleep(40 + rng.nextInt(60));
        input.mousePress(MouseEvent.BUTTON1);
        Thread.sleep(40 + rng.nextInt(40));
        input.mouseRelease(MouseEvent.BUTTON1);
        Thread.sleep(80 + rng.nextInt(180));
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

    private void widgetClick(int widgetId) throws InterruptedException
    {
        Point pixel = onClient(() -> resolver.resolveWidget(widgetId));
        if (pixel == null) { lastError.set("widget " + widgetId + " not found"); return; }
        moveCursorTo(pixel.getX(), pixel.getY());
        clickPress(MouseEvent.BUTTON1);
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
        Thread.sleep(60);
        boolean isTop = onClient(() -> isTopMenuVerb(verb));
        if (isTop)
        {
            clickPress(MouseEvent.BUTTON1);
            return;
        }
        clickPress(MouseEvent.BUTTON3);
        Thread.sleep(120);
        boolean ok = selectMenuVerb(verb);
        if (!ok)
        {
            lastError.set("widget " + widgetId + " menu open but verb '" + verb + "' not present");
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
        Thread.sleep(20 + rng.nextInt(30));
        int innerHold = 40 + rng.nextInt(60);
        input.keyTapWithModifier(keyCode, modifierMask, innerHold);
        Thread.sleep(20 + rng.nextInt(40));
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
