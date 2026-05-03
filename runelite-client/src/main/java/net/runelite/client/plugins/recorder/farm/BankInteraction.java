package net.runelite.client.plugins.recorder.farm;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.activities.banking.BankActions;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.dispatch.SequenceSleep;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Drives bank-booth interactions (open, deposit, close).
 *
 * <p>The flow: find a banker / bank booth, click it, wait for the bank
 * widget to appear, click the "Deposit inventory" orb, close with Escape.
 * Each step uses the same humanized dispatcher the rest of the system
 * uses.
 *
 * <p><b>Threading:</b> {@link #clickBankBoothRandom} and {@link #closeBank}
 * hop to the client thread internally for widget / scene probes — safe to
 * call from any worker thread. {@link #isBankOpen}, {@link #clickBankBooth},
 * {@link #clickDepositInventory} read widgets directly — caller is
 * responsible for thread-safety in dev mode (under -ea the engine asserts
 * widget reads happen on the client thread).
 */
@Slf4j
public final class BankInteraction implements BankActions
{
    /** Search radius for booths/bankers (Chebyshev tiles, same plane).
     *  15 tiles covers every Lumbridge bank position from any reasonable
     *  starting tile inside the bank room. */
    public static final int BOOTH_SEARCH_RADIUS = 15;

    /** A booth/banker within this many tiles is picked deterministically
     *  (no point walking past a closer one). Beyond it, candidates are
     *  randomised so repeated trips don't always click the same target. */
    public static final int BOOTH_NEAR_RADIUS = 1;

    // ────────────────────────────────────────────────────────────────
    // Verified-primitive plumbing.
    //
    // Every {@code tryDepositAll}, {@code depositAllInventory}, and
    // {@code tryWithdraw*} below dispatches its click chain and then
    // POLLS the inventory until the change actually lands (or a timeout
    // elapses). Returning {@code true} therefore means "the engine
    // actually moved items", not just "the click was sent" — which is
    // the contract our scripts depend on but the old fire-and-hope
    // primitives could not honour.
    // ────────────────────────────────────────────────────────────────

    /** Max wait for a deposit-all click chain (orb or per-item) to empty
     *  the targeted slot(s). Set generously: large stacks of noted items
     *  occasionally take 2+ ticks to vanish from inventory after the
     *  Deposit-All menu pick fires. */
    private static final long DEPOSIT_VERIFY_TIMEOUT_MS  = 3_000L;

    /** Max wait for a withdraw click chain (Withdraw-X / -All / -1) to
     *  show up in inventory. Covers the pathological cases:
     *  Withdraw-X where the chatbox prompt is delayed by ~1 tick after
     *  the menu pick, plus the engine's drip-feed of items over 2-3
     *  ticks for very large stacks. */
    private static final long WITHDRAW_VERIFY_TIMEOUT_MS = 4_000L;

    /** Inventory polling cadence inside the verify loop. 80ms ≈ one
     *  game tick — we don't need a tighter beat than the engine's own. */
    private static final long POLL_INTERVAL_MS           = 80L;

    /** Player inventory size — used by the no-space pre-check that
     *  refuses a withdraw when all 28 slots are taken AND no slot of the
     *  received item form already exists (engine would silently drop
     *  every withdrawn item, looking like the click did nothing). */
    private static final int  INVENTORY_CAPACITY         = 28;

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    public BankInteraction(Client client, ClientThread clientThread,
                           HumanizedInputDispatcher dispatcher)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
    }

    /** Fail loud if a {@link BankActions} entry point is invoked on the OSRS
     *  client thread. The downstream flows in this class call
     *  {@link SequenceSleep#sleep} / {@link HumanizedInputDispatcher#typeChatboxAndEnter}
     *  / {@link HumanizedInputDispatcher#rightClickAndPickMenu}, every one of
     *  which sleeps the calling thread between input events. Sleeping the
     *  client thread freezes the render loop / cs2 / NPCs and the chatbox
     *  prompt this code is trying to type into never opens. See the
     *  "Threading model" section at the top of {@code CLAUDE.md} — engine
     *  steps must enqueue {@link ActionRequest.Kind#RUN_TASK} so the
     *  dispatcher worker runs the flow off-thread. */
    private void assertWorkerThread(String op)
    {
        if (client != null && client.isClientThread())
        {
            throw new IllegalStateException(
                "BankActions." + op + " called on the OSRS client thread — "
                    + "this would freeze the game (cs2 / chatbox prompt / "
                    + "NPCs) for the duration of the multi-step flow. Wrap "
                    + "the call in an ActionRequest.Kind.RUN_TASK and "
                    + "dispatcher.dispatch(...) it from your Step.onStart, "
                    + "then poll the WorldSnapshot in Step.check.");
        }
    }

    /** True if the bank-main widget is loaded and visible. Thread-safe:
     *  reads directly on the client thread, marshals through
     *  {@link #onClient} when called from any worker. The latter is the
     *  important path — both {@code client.getWidget()} and
     *  {@code Widget.isHidden()} assert the client thread under
     *  {@code -ea} and would crash a script that polled this from its
     *  own tick loop. */
    public boolean isBankOpen()
    {
        return readWidgetVisible(InterfaceID.Bankmain.UNIVERSE);
    }

    /** True if the Bank PIN keypad is up. The bot can't enter a PIN
     *  safely (keystroke pacing matters for that flow); callers should
     *  abort with a status when this is true rather than continue
     *  clicking the booth — repeated booth clicks during PIN entry can
     *  lock the player out for 5 minutes. Thread-safe via the same
     *  branch as {@link #isBankOpen()}. */
    public boolean isBankPinUp()
    {
        return readWidgetVisible(InterfaceID.BankpinKeypad.UNIVERSE);
    }

    /** Shared helper for the two thread-safe widget-visibility checks
     *  above. {@link Client#isClientThread()} lets us skip the latch
     *  hop on the hot path (engine-step callers) while keeping the
     *  off-thread path safe for worker-driven scripts. The catch
     *  re-asserts interrupt state and returns {@code false} — losing a
     *  poll is preferable to swallowing the interrupt or rethrowing
     *  through public methods that historically didn't declare it. */
    private boolean readWidgetVisible(int widgetId)
    {
        if (client.isClientThread())
        {
            Widget w = client.getWidget(widgetId);
            return w != null && !w.isHidden();
        }
        try
        {
            Boolean b = onClient(() -> {
                Widget w = client.getWidget(widgetId);
                return w != null && !w.isHidden();
            });
            return Boolean.TRUE.equals(b);
        }
        catch (InterruptedException ie)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Step 1 (legacy / simple variant): find a Banker / Bank booth NPC
     *  and click its convex hull. NPC-only — does not see GameObject
     *  bank booths. Prefer {@link #clickBankBoothRandom} for production
     *  use; this is kept for callers that want first-NPC semantics. */
    public boolean clickBankBooth() throws InterruptedException
    {
        NPC booth = findBankBoothNPC();
        if (booth == null) return false;
        Shape hull = booth.getConvexHull();
        if (hull == null) return false;
        Rectangle b = hull.getBounds();
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        dispatcher.clickCanvas(cx, cy);
        return true;
    }

    /**
     * Step 1 (preferred): scan NPC bankers AND GameObject bank booths
     * within {@link #BOOTH_SEARCH_RADIUS} tiles and click one.
     *
     * <ul>
     *   <li>If any candidate is within {@link #BOOTH_NEAR_RADIUS} tiles
     *       (Chebyshev), pick it deterministically — no point walking past
     *       a closer banker.</li>
     *   <li>Otherwise pick uniformly at random across all candidates so
     *       repeated banking trips don't always click the same one.</li>
     * </ul>
     *
     * <p>Both NPC bankers and GameObject booths are dispatched through the
     * humanized input pipeline ({@link ActionRequest.Kind#CLICK_NPC} /
     * {@link ActionRequest.Kind#CLICK_GAME_OBJECT}) so the mouse visibly
     * moves to the target, the camera rotates naturally, WindMouse drives
     * the cursor path, and the verb is verified before the click is
     * committed.  For NPC bankers whose left-click default is "Talk-to",
     * the dispatcher's right-click flow picks the "Bank" option from the
     * context menu automatically.
     *
     * <p>Returns true if a click was dispatched, false if no candidate
     * was visible (caller can retry next tick — the player may need to
     * walk closer).
     */
    public boolean tryClickBankBoothRandom() throws InterruptedException
    {
        BoothCandidate pick = onClient(this::findBoothCandidate);
        if (pick == null) return false;

        if (pick.npc != null)
        {
            // Full humanized NPC click: camera rotate → WindMouse to hull →
            // hover-verify → left-click if "Bank" is the default, otherwise
            // right-click → pick "Bank" from the context menu.
            Integer idx = onClient(() -> pick.npc.getIndex());
            if (idx == null) return false;
            log.info("bank: CLICK_NPC banker (index={}, verb={})", idx, pick.actionText);
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_NPC)
                .channel(ActionRequest.Channel.MOUSE)
                .npcIndex(idx)
                .verb(pick.actionText)
                .build());
            return true;
        }

        // GameObject booth — CLICK_GAME_OBJECT resolves the hull, rotates
        // the camera, moves the mouse via WindMouse, and picks the verb.
        WorldPoint tile = onClient(() -> pick.go.getWorldLocation());
        if (tile == null)
        {
            log.warn("bank: booth {} has no world location", onClient(() -> pick.go.getId()));
            return false;
        }
        log.info("bank: CLICK_GAME_OBJECT booth (tile={}, verb={})", tile, pick.actionText);
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(tile)
            .verb(pick.actionText)
            .build());
        return true;
    }

    /** {@inheritDoc} — dispatches a booth click, discarding the boolean result.
     *  Call {@link #tryClickBankBoothRandom()} directly when the boolean return
     *  matters for fail-counting. */
    @Override
    public void clickBankBoothRandom() throws InterruptedException
    {
        assertWorkerThread("clickBankBoothRandom");
        tryClickBankBoothRandom();
    }

    /** Outcome of {@link #ensureBoothInClickRange}. Lets the caller
     *  distinguish "nothing to do — go ahead and click" from "I just
     *  dispatched a walk; wait for the player to arrive before
     *  re-checking" so attempt counters / timeouts can stay accurate. */
    public enum BoothPrep { ALREADY_VISIBLE, WALKED_CLOSER, NO_CANDIDATE }

    /** Pre-flight before a booth click: pick the closest candidate, check
     *  if its convex hull (or tile poly fallback) actually projects onto
     *  the canvas. If not, dispatch a WALK toward the booth tile so the
     *  next tick has a chance to find it on-canvas, and return
     *  {@link BoothPrep#WALKED_CLOSER}. If the candidate is already
     *  on-canvas, return {@link BoothPrep#ALREADY_VISIBLE}; the caller
     *  should immediately call {@link #tryClickBankBoothRandom}.
     *
     *  <p>This solves the v1 silent-failure where a booth at dist=8 with
     *  the model behind a wall would return null hull → "pixel
     *  unresolvable" → click dropped. The dispatcher's gameObjectClick
     *  already does a non-forced rotateCameraToward, but rotation alone
     *  doesn't help if the model isn't being rendered at all. Walking
     *  closer drops us inside the render LOD where the hull becomes
     *  available.
     *
     *  <p>Safe to call from any worker thread — all client reads marshal
     *  via {@link #onClient}. */
    public BoothPrep ensureBoothInClickRange() throws InterruptedException
    {
        assertWorkerThread("ensureBoothInClickRange");
        BoothCandidate pick = onClient(this::findBoothCandidate);
        if (pick == null) return BoothPrep.NO_CANDIDATE;

        Boolean visible = onClient(() -> isBoothPixelResolvable(pick));
        if (Boolean.TRUE.equals(visible)) return BoothPrep.ALREADY_VISIBLE;

        WorldPoint tile = onClient(() ->
            pick.npc != null ? pick.npc.getWorldLocation() : pick.go.getWorldLocation());
        if (tile == null) return BoothPrep.NO_CANDIDATE;

        log.info("bank: booth at {} not on canvas (dist={}) — walking closer",
            tile, pick.distance);
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.WALK)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(tile)
            .build());
        return BoothPrep.WALKED_CLOSER;
    }

    /** Client-thread: true iff the booth's model has a non-empty convex
     *  hull on the canvas, OR (fallback) its tile poly has a non-empty
     *  bounding box. Either signal is sufficient for the dispatcher's
     *  hull/tile-poly resolution path to find a clickable pixel. */
    private boolean isBoothPixelResolvable(BoothCandidate pick)
    {
        Shape hull = null;
        if (pick.npc != null)
        {
            hull = pick.npc.getConvexHull();
        }
        else if (pick.go != null)
        {
            hull = pick.go.getConvexHull();
        }
        if (hull != null)
        {
            Rectangle bb = hull.getBounds();
            if (bb != null && !bb.isEmpty()) return true;
        }
        // Fallback: tile poly. The dispatcher's TILE_POLY strategy uses
        // this when HULL fails.
        WorldPoint loc = pick.npc != null ? pick.npc.getWorldLocation()
                                          : (pick.go != null ? pick.go.getWorldLocation() : null);
        if (loc == null) return false;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return false;
        net.runelite.api.coords.LocalPoint lp =
            net.runelite.api.coords.LocalPoint.fromWorld(wv, loc);
        if (lp == null) return false;
        java.awt.Polygon poly = net.runelite.api.Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return false;
        Rectangle bb = poly.getBounds();
        return bb != null && !bb.isEmpty();
    }

    /** V2-style booth picker: weighted-random across all candidates instead
     *  of short-circuiting on the adjacent one. {@code adjacencyBias} is the
     *  probability mass given to the closest candidate (range 0..1); the
     *  remaining mass is spread uniformly across the other candidates.
     *  At {@code adjacencyBias=1.0} this collapses to the V1 behaviour
     *  (always pick adjacent). At 0.5 the closest is picked half the
     *  time, the other half a different booth gets clicked — different
     *  tile, different camera angle, different click pixel.
     *
     *  <p>Returns true iff a click was dispatched. Mirrors
     *  {@link #tryClickBankBoothRandom} in dispatch shape (CLICK_NPC for
     *  bankers, CLICK_GAME_OBJECT for booths) so verb-pick + camera pan +
     *  WindMouse cursor path are identical to V1. */
    public boolean tryClickBankBoothVaried(double adjacencyBias) throws InterruptedException
    {
        java.util.List<BoothCandidate> all = onClient(this::collectBoothCandidates);
        if (all == null || all.isEmpty())
        {
            log.warn("bank: no booth candidates within {} tiles (varied)", BOOTH_SEARCH_RADIUS);
            return false;
        }
        BoothCandidate pick = pickWeightedRandom(all, adjacencyBias);

        if (pick.npc != null)
        {
            Integer idx = onClient(() -> pick.npc.getIndex());
            if (idx == null) return false;
            log.info("bank: CLICK_NPC banker (varied dist={}, verb={}, npc index={})",
                pick.distance, pick.actionText, idx);
            dispatcher.dispatch(ActionRequest.builder()
                .kind(ActionRequest.Kind.CLICK_NPC)
                .channel(ActionRequest.Channel.MOUSE)
                .npcIndex(idx)
                .verb(pick.actionText)
                .build());
            return true;
        }
        WorldPoint tile = onClient(() -> pick.go.getWorldLocation());
        if (tile == null)
        {
            log.warn("bank: varied booth pick has no world location");
            return false;
        }
        log.info("bank: CLICK_GAME_OBJECT booth (varied dist={}, tile={}, verb={})",
            pick.distance, tile, pick.actionText);
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(tile)
            .verb(pick.actionText)
            .build());
        return true;
    }

    /** Client-thread: collect ALL booth candidates within range, sorted by
     *  Chebyshev distance ascending. Mirrors {@link #findBoothCandidate}'s
     *  scan but does NOT short-circuit on adjacent — V2's weighted picker
     *  needs the full list. */
    private java.util.List<BoothCandidate> collectBoothCandidates()
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return java.util.Collections.emptyList();
        Player self = client.getLocalPlayer();
        if (self == null) return java.util.Collections.emptyList();
        WorldPoint here = self.getWorldLocation();
        if (here == null) return java.util.Collections.emptyList();
        int plane = here.getPlane();

        java.util.List<BoothCandidate> out = new ArrayList<>();
        for (NPC npc : wv.npcs())
        {
            if (npc == null) continue;
            String name = npc.getName();
            if (name == null) continue;
            if (!name.equalsIgnoreCase("Banker")
                && !name.equalsIgnoreCase("Bank booth")) continue;
            WorldPoint loc = npc.getWorldLocation();
            if (loc == null || loc.getPlane() != plane) continue;
            NPCComposition def = npc.getComposition();
            if (def == null) continue;
            String[] actions = def.getActions();
            if (actions == null) continue;
            int slot = bankActionSlot(actions);
            if (slot < 0) continue;
            int cheb = Math.max(
                Math.abs(loc.getX() - here.getX()),
                Math.abs(loc.getY() - here.getY()));
            if (cheb > BOOTH_SEARCH_RADIUS) continue;
            out.add(new BoothCandidate(npc, slot, actions[slot], cheb));
        }
        Scene scene = wv.getScene();
        if (scene != null)
        {
            Tile[][][] tiles = scene.getTiles();
            if (tiles != null && plane >= 0 && plane < tiles.length)
            {
                Tile[][] planeTiles = tiles[plane];
                int hereSx = here.getX() - wv.getBaseX();
                int hereSy = here.getY() - wv.getBaseY();
                int loSx = Math.max(0, hereSx - BOOTH_SEARCH_RADIUS);
                int loSy = Math.max(0, hereSy - BOOTH_SEARCH_RADIUS);
                int hiSx = Math.min(planeTiles.length - 1, hereSx + BOOTH_SEARCH_RADIUS);
                int hiSy = Math.min(planeTiles[0].length - 1, hereSy + BOOTH_SEARCH_RADIUS);
                for (int sx = loSx; sx <= hiSx; sx++)
                {
                    for (int sy = loSy; sy <= hiSy; sy++)
                    {
                        Tile t = planeTiles[sx][sy];
                        if (t == null) continue;
                        GameObject[] gos = t.getGameObjects();
                        if (gos == null) continue;
                        for (GameObject go : gos)
                        {
                            if (go == null) continue;
                            ObjectComposition baseDef = client.getObjectDefinition(go.getId());
                            if (baseDef == null) continue;
                            ObjectComposition def = baseDef;
                            if (baseDef.getImpostorIds() != null)
                            {
                                try
                                {
                                    ObjectComposition imp = baseDef.getImpostor();
                                    if (imp != null) def = imp;
                                }
                                catch (Throwable ignored) { /* fall back */ }
                            }
                            String name = def.getName();
                            String[] actions = def.getActions();
                            if (actions == null) continue;
                            int slot = bankActionSlot(actions);
                            if (slot < 0) continue;
                            if (name != null
                                && !name.toLowerCase().contains("bank")) continue;
                            WorldPoint loc = go.getWorldLocation();
                            if (loc == null) continue;
                            int cheb = Math.max(
                                Math.abs(loc.getX() - here.getX()),
                                Math.abs(loc.getY() - here.getY()));
                            if (cheb > BOOTH_SEARCH_RADIUS) continue;
                            out.add(new BoothCandidate(go, slot, actions[slot], cheb));
                        }
                    }
                }
            }
        }
        // Sort ascending by Chebyshev distance — picker uses index 0 as the
        // "closest" mass.
        out.sort((a, b) -> Integer.compare(a.distance, b.distance));
        return out;
    }

    /** Pick one candidate. The closest gets {@code bias} probability; the
     *  rest split (1-bias) uniformly. With a single candidate this just
     *  returns it. */
    private static BoothCandidate pickWeightedRandom(
        java.util.List<BoothCandidate> sorted, double bias)
    {
        if (sorted.size() == 1) return sorted.get(0);
        double clamped = Math.max(0.0, Math.min(1.0, bias));
        if (Math.random() < clamped) return sorted.get(0);
        int otherIdx = 1 + (int) (Math.random() * (sorted.size() - 1));
        return sorted.get(otherIdx);
    }

    /** Either an NPC banker or a GameObject bank booth, plus the slot
     *  whose action begins with "Bank". Used internally by the random-
     *  pick selector. */
    private static final class BoothCandidate
    {
        final NPC npc;
        final GameObject go;
        final int slot;
        final String actionText;
        final int distance;

        BoothCandidate(NPC n, int slot, String a, int d)
        { this.npc = n; this.go = null; this.slot = slot; this.actionText = a; this.distance = d; }
        BoothCandidate(GameObject g, int slot, String a, int d)
        { this.npc = null; this.go = g; this.slot = slot; this.actionText = a; this.distance = d; }
    }

    /** Collect candidates on the client thread, return one (adjacent if
     *  any, otherwise random). Returns null if nothing's in range. */
    private BoothCandidate findBoothCandidate()
    {
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        int plane = here.getPlane();

        List<BoothCandidate> candidates = new ArrayList<>();
        BoothCandidate adjacent = null;

        // 1) NPCs (Bankers, occasionally an NPC named "Bank booth").
        for (NPC npc : wv.npcs())
        {
            if (npc == null) continue;
            String name = npc.getName();
            if (name == null) continue;
            if (!name.equalsIgnoreCase("Banker")
                && !name.equalsIgnoreCase("Bank booth")) continue;
            WorldPoint loc = npc.getWorldLocation();
            if (loc == null || loc.getPlane() != plane) continue;
            NPCComposition def = npc.getComposition();
            if (def == null) continue;
            String[] actions = def.getActions();
            if (actions == null) continue;
            int slot = bankActionSlot(actions);
            if (slot < 0) continue;
            int cheb = Math.max(
                Math.abs(loc.getX() - here.getX()),
                Math.abs(loc.getY() - here.getY()));
            if (cheb > BOOTH_SEARCH_RADIUS) continue;
            BoothCandidate c = new BoothCandidate(npc, slot, actions[slot], cheb);
            if (cheb <= BOOTH_NEAR_RADIUS
                && (adjacent == null || cheb < adjacent.distance))
                adjacent = c;
            candidates.add(c);
        }

        // 2) Scene GameObjects — Bank booth scenery has multiple object
        //    IDs, so we match by name + Bank action, not id.
        Scene scene = wv.getScene();
        if (scene != null)
        {
            Tile[][][] tiles = scene.getTiles();
            if (tiles != null && plane >= 0 && plane < tiles.length)
            {
                Tile[][] planeTiles = tiles[plane];
                int hereSx = here.getX() - wv.getBaseX();
                int hereSy = here.getY() - wv.getBaseY();
                int loSx = Math.max(0, hereSx - BOOTH_SEARCH_RADIUS);
                int loSy = Math.max(0, hereSy - BOOTH_SEARCH_RADIUS);
                int hiSx = Math.min(planeTiles.length - 1, hereSx + BOOTH_SEARCH_RADIUS);
                int hiSy = Math.min(planeTiles[0].length - 1, hereSy + BOOTH_SEARCH_RADIUS);
                for (int sx = loSx; sx <= hiSx; sx++)
                {
                    for (int sy = loSy; sy <= hiSy; sy++)
                    {
                        Tile t = planeTiles[sx][sy];
                        if (t == null) continue;
                        GameObject[] gos = t.getGameObjects();
                        if (gos == null) continue;
                        for (GameObject go : gos)
                        {
                            if (go == null) continue;
                            ObjectComposition baseDef = client.getObjectDefinition(go.getId());
                            if (baseDef == null) continue;
                            ObjectComposition def = baseDef;
                            if (baseDef.getImpostorIds() != null)
                            {
                                try
                                {
                                    ObjectComposition imp = baseDef.getImpostor();
                                    if (imp != null) def = imp;
                                }
                                catch (Throwable ignored) { /* fall back */ }
                            }
                            String name = def.getName();
                            String[] actions = def.getActions();
                            if (actions == null) continue;
                            int slot = bankActionSlot(actions);
                            if (slot < 0) continue;
                            // Require name to look bank-ish so we don't
                            // grab some unrelated Bank-action object.
                            if (name != null
                                && !name.toLowerCase().contains("bank")) continue;
                            WorldPoint loc = go.getWorldLocation();
                            if (loc == null) continue;
                            int cheb = Math.max(
                                Math.abs(loc.getX() - here.getX()),
                                Math.abs(loc.getY() - here.getY()));
                            if (cheb > BOOTH_SEARCH_RADIUS) continue;
                            BoothCandidate c = new BoothCandidate(go, slot, actions[slot], cheb);
                            if (cheb <= BOOTH_NEAR_RADIUS
                                && (adjacent == null || cheb < adjacent.distance))
                                adjacent = c;
                            candidates.add(c);
                        }
                    }
                }
            }
        }

        if (adjacent != null)
        {
            log.info("bank: candidate adjacent (dist={}, kind={})",
                adjacent.distance, adjacent.npc != null ? "NPC" : "GameObject");
            return adjacent;
        }
        if (candidates.isEmpty())
        {
            log.warn("bank: no candidates within {} tiles", BOOTH_SEARCH_RADIUS);
            return null;
        }
        BoothCandidate randomPick = candidates.get(
            (int) (Math.random() * candidates.size()));
        log.info("bank: random candidate (n={}, picked dist={}, kind={})",
            candidates.size(), randomPick.distance,
            randomPick.npc != null ? "NPC" : "GameObject");
        return randomPick;
    }

    /** First slot in {@code actions} whose text starts with "Bank"
     *  (case-insensitive). -1 if no slot has a Bank action. */
    private static int bankActionSlot(String[] actions)
    {
        for (int i = 0; i < actions.length && i < 5; i++)
        {
            String a = actions[i];
            if (a == null) continue;
            if (a.toLowerCase().startsWith("bank")) return i;
        }
        return -1;
    }

    private NPC findBankBoothNPC() throws InterruptedException
    {
        CompletableFuture<NPC> fut = new CompletableFuture<>();
        clientThread.invoke(() -> {
            try
            {
                WorldView wv = client.getTopLevelWorldView();
                if (wv == null) { fut.complete(null); return; }
                for (NPC npc : wv.npcs())
                {
                    if (npc == null) continue;
                    String name = npc.getName();
                    if (name == null) continue;
                    if (name.equalsIgnoreCase("Banker")
                        || name.equalsIgnoreCase("Bank booth"))
                    {
                        fut.complete(npc);
                        return;
                    }
                }
                fut.complete(null);
            }
            catch (Throwable t)
            {
                fut.completeExceptionally(t);
            }
        });
        try
        {
            return fut.get(2, TimeUnit.SECONDS);
        }
        catch (TimeoutException | ExecutionException ex)
        {
            return null;
        }
    }

    /** Step 2: click the Deposit-inventory orb on the bank widget.
     *  Caller is responsible for verifying {@link #isBankOpen()} first. */
    public boolean clickDepositInventory() throws InterruptedException
    {
        Widget w = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
        if (w == null || w.isHidden()) return false;
        Rectangle b = w.getBounds();
        if (b == null || b.isEmpty()) return false;
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
        return true;
    }

    /** Step 3: close the bank widget. Tries the X close button first
     *  (clicking it via the humanized dispatcher), falls back to Escape
     *  only if the button can't be located. Returns true iff a click /
     *  key tap was dispatched.
     *
     *  <p>The X close button isn't enumerated in
     *  {@link InterfaceID.Bankmain} — it's a child of
     *  {@code Bankmain.UNIVERSE} whose first action is {@code "Close"}
     *  and which fires {@code MenuAction.CC_OP id=1} on left-click. We
     *  walk the widget tree to find it.
     *
     *  <p>Self-contained on threading: the widget probe hops to the
     *  client thread internally, the click dispatch goes through the
     *  dispatcher's own queue. Safe to call from any worker thread. */
    public boolean tryCloseBank() throws InterruptedException
    {
        Rectangle b = onClient(() -> {
            Widget root = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
            if (root == null || root.isHidden()) return null;
            Widget btn = findChildWithAction(root, "Close", 0, 6);
            if (btn == null) return null;
            Rectangle r = btn.getBounds();
            return r == null || r.isEmpty() ? null : r;
        });
        if (b != null)
        {
            log.info("bank: closing via X button (bounds={})", b);
            dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
            return true;
        }
        // Fallback — Escape works for some interfaces but the bank
        // widget tends to swallow it. Logged so we notice if we land
        // here in production.
        if (!isBankOpen()) return false;
        log.warn("bank: X close button not found — falling back to Escape");
        dispatcher.tapKey(KeyEvent.VK_ESCAPE);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void closeBank() throws InterruptedException
    {
        assertWorkerThread("closeBank");
        tryCloseBank();
    }

    // ────────────────────────────────────────────────────────────────
    // Withdraw helpers — read the bank ItemContainer to find a slot,
    // scroll the bank items widget to make it visible, dispatch a
    // right-click + "Withdraw-X" verb pick. Returns false on missing
    // item / non-resolvable widget so the caller can degrade gracefully
    // instead of crashing.
    // ────────────────────────────────────────────────────────────────

    /** True iff the {@link InventoryID#BANK} container is populated.
     *  The bank widget can be visible for a short window before the
     *  engine fills the container — calling {@link #bankContainsItem}
     *  during that window would return false and trip a "missing item"
     *  abort. Callers should gate item-presence checks on this flag. */
    public boolean bankReady() throws InterruptedException
    {
        Boolean ok = onClient(() -> client.getItemContainer(InventoryID.BANK) != null);
        return Boolean.TRUE.equals(ok);
    }

    /** True if the bank inventory container holds at least one of {@code itemId}.
     *  Returns false if the bank container hasn't loaded yet — callers
     *  must check {@link #bankReady()} first if they want to differentiate
     *  "not loaded" from "absent". Reads on the client thread. */
    public boolean bankContainsItem(int itemId) throws InterruptedException
    {
        Long n = onClient(() -> bankAmountClientThread(itemId));
        return n != null && n > 0;
    }

    /** Quantity of {@code itemId} currently in the bank, or 0 if none /
     *  bank not open. Reads on the client thread. */
    public long bankItemAmount(int itemId) throws InterruptedException
    {
        Long n = onClient(() -> bankAmountClientThread(itemId));
        return n == null ? 0L : n;
    }

    /** Total quantity of {@code itemId} in the player's inventory
     *  (sums stack quantities for stackables; counts each non-stackable
     *  occurrence as 1). Used by the verify-poll loops in
     *  {@link #tryDepositAll}, {@link #tryWithdrawAll}, {@link #tryWithdrawX}
     *  to detect when the engine has actually moved items. */
    private int inventoryAmount(int itemId) throws InterruptedException
    {
        Integer n = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return 0;
            Item[] items = inv.getItems();
            if (items == null) return 0;
            int total = 0;
            for (Item it : items)
                if (it != null && it.getId() == itemId)
                    total += Math.max(1, it.getQuantity());
            return total;
        });
        return n == null ? 0 : n;
    }

    /** Number of inventory slots holding any item (0..28). Used by the
     *  no-space pre-check in withdraw primitives and by
     *  {@link #depositAllInventory}'s verify loop. */
    private int inventorySlotsUsed() throws InterruptedException
    {
        Integer n = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return 0;
            Item[] items = inv.getItems();
            if (items == null) return 0;
            int count = 0;
            for (Item it : items)
                if (it != null && it.getId() > 0) count++;
            return count;
        });
        return n == null ? 0 : n;
    }

    /** Linked noted form of {@code itemId}, or -1 if the item has no
     *  noted form (e.g. coins, already-stackable items). Used by
     *  {@link #tryWithdrawAsNoteX} to know which item id will land in
     *  inventory after a noted withdraw. */
    private int notedIdFor(int itemId) throws InterruptedException
    {
        Integer noted = onClient(() -> {
            ItemComposition comp = client.getItemDefinition(itemId);
            return comp == null ? -1 : comp.getLinkedNoteId();
        });
        return noted == null ? -1 : noted;
    }

    /** Withdraw all of {@code itemId} from the bank via the slot's
     *  right-click "Withdraw-All" option. The bank must be open.
     *
     *  <p><b>Returns {@code true} only after at least one item of
     *  {@code itemId} has actually appeared in the inventory.</b>
     *  Failure paths (return {@code false} with a warn log):
     *  <ul>
     *    <li>{@link #ensureNoteMode} could not flip the bank to unnoted
     *        mode — {@code ensureNoteMode} logs why.</li>
     *    <li>Inventory is full (28/28) AND no existing slot already
     *        holds {@code itemId} — withdraw can't possibly land.</li>
     *    <li>Slot widget couldn't be located / scrolled to —
     *        {@link #withdrawWithVerb} logs the timeout.</li>
     *    <li>Click chain dispatched but inventory never moved within
     *        {@link #WITHDRAW_VERIFY_TIMEOUT_MS}.</li>
     *    <li>Bank widget closed mid-poll.</li>
     *  </ul> */
    public boolean tryWithdrawAll(int itemId) throws InterruptedException
    {
        assertWorkerThread("tryWithdrawAll");
        if (!ensureNoteMode(false)) return false;
        return withdrawAllAndVerify(itemId);
    }

    /** Click chain + inventory poll for "Withdraw-All". Shared by
     *  {@link #tryWithdrawAll} and any future caller that wants the
     *  verified contract. */
    private boolean withdrawAllAndVerify(int itemId) throws InterruptedException
    {
        int baseline   = inventoryAmount(itemId);
        int slotsUsed  = inventorySlotsUsed();
        if (baseline == 0 && slotsUsed >= INVENTORY_CAPACITY)
        {
            log.warn("bank: cannot withdrawAll itemId={} — inventory full ({}/{}) and no existing slot",
                itemId, slotsUsed, INVENTORY_CAPACITY);
            return false;
        }
        if (!withdrawWithVerb(itemId, "Withdraw-All")) return false;

        long deadline = System.currentTimeMillis() + WITHDRAW_VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            SequenceSleep.sleep(client, POLL_INTERVAL_MS);
            if (inventoryAmount(itemId) > baseline)
            {
                // Brief settle: engine drip-feeds large stacks over a
                // tick or two; sleep once more so the caller observes
                // the final stable count rather than a mid-transfer one.
                SequenceSleep.sleep(client, 200);
                return true;
            }
            if (!Boolean.TRUE.equals(onClient(this::isBankOpen)))
            {
                log.warn("bank: bank closed mid-withdrawAll for itemId={}", itemId);
                return false;
            }
        }
        log.warn("bank: withdrawAll verify TIMEOUT — no inventory change for itemId={} after {}ms",
            itemId, WITHDRAW_VERIFY_TIMEOUT_MS);
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void withdrawAll(int itemId) throws InterruptedException
    {
        assertWorkerThread("withdrawAll");
        tryWithdrawAll(itemId);
    }

    /** Withdraw 1 of {@code itemId}. Same verified-result contract as
     *  {@link #tryWithdrawAll}: returns {@code true} only after the
     *  inventory count for {@code itemId} has strictly increased. */
    public boolean tryWithdrawOne(int itemId) throws InterruptedException
    {
        assertWorkerThread("tryWithdrawOne");
        if (!ensureNoteMode(false)) return false;
        int baseline   = inventoryAmount(itemId);
        int slotsUsed  = inventorySlotsUsed();
        if (baseline == 0 && slotsUsed >= INVENTORY_CAPACITY)
        {
            log.warn("bank: cannot withdrawOne itemId={} — inventory full ({}/{})",
                itemId, slotsUsed, INVENTORY_CAPACITY);
            return false;
        }
        if (!withdrawWithVerb(itemId, "Withdraw-1")) return false;

        long deadline = System.currentTimeMillis() + WITHDRAW_VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            SequenceSleep.sleep(client, POLL_INTERVAL_MS);
            if (inventoryAmount(itemId) > baseline) return true;
            if (!Boolean.TRUE.equals(onClient(this::isBankOpen)))
            {
                log.warn("bank: bank closed mid-withdrawOne for itemId={}", itemId);
                return false;
            }
        }
        log.warn("bank: withdrawOne verify TIMEOUT — no inventory change for itemId={} after {}ms",
            itemId, WITHDRAW_VERIFY_TIMEOUT_MS);
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void withdrawOne(int itemId) throws InterruptedException
    {
        assertWorkerThread("withdrawOne");
        tryWithdrawOne(itemId);
    }

    /** Withdraw exactly {@code qty} of {@code itemId} via "Withdraw-X".
     *
     *  <p>Flow: right-click the bank slot → pick "Withdraw-X" → the game
     *  opens a chatbox quantity dialog → type the quantity as individual
     *  characters → press Enter to confirm. Then we POLL inventory until
     *  {@code itemId}'s quantity has risen by {@code qty} (full success),
     *  the deadline elapses (partial returns true with a log line, zero
     *  returns false), or the bank widget closes mid-flow.
     *
     *  <p>Returns {@code true} only after the engine actually moved at
     *  least one item into the inventory. */
    public boolean tryWithdrawX(int itemId, int qty) throws InterruptedException
    {
        assertWorkerThread("tryWithdrawX");
        if (qty <= 0) return false;
        if (!ensureNoteMode(false)) return false;
        return withdrawXAndVerify(itemId, qty, itemId);
    }

    /** Click chain only — right-click the slot, pick "Withdraw-X", type
     *  digits, press Enter. Returns {@code true} iff the chatbox prompt
     *  opened and was typed into. NOT verified against inventory; the
     *  caller in {@link #withdrawXAndVerify} does the verify poll. */
    private boolean withdrawXClickChain(int itemId, int qty) throws InterruptedException
    {
        boolean picked = withdrawWithVerb(itemId, "Withdraw-X");
        if (!picked) return false;
        // Withdraw-X opens a chatbox numeric prompt. Use the dispatcher's
        // shared helper that gates on VarClientID.MESLAYERMODE — widget
        // visibility on Chatbox.MES_LAYER lies for these prompts (the
        // hidden chain reports false even when the dialog is on screen).
        if (!dispatcher.typeChatboxAndEnter(Integer.toString(qty), 3500L))
        {
            log.warn("bank: withdraw-X chatbox prompt did not appear within 3.5s — aborting type");
            return false;
        }
        return true;
    }

    /** Click chain + inventory poll for Withdraw-X / Withdraw-as-note-X.
     *
     *  <p>{@code receivedId} is the item id we expect to land in
     *  inventory: same as {@code itemId} for unnoted withdraws, the
     *  linked noted form for noted withdraws (so noted-shells of
     *  unnoted-id 2315 are tracked under noted-id 2316). */
    private boolean withdrawXAndVerify(int itemId, int qty, int receivedId) throws InterruptedException
    {
        int baseline   = inventoryAmount(receivedId);
        int slotsUsed  = inventorySlotsUsed();
        if (baseline == 0 && slotsUsed >= INVENTORY_CAPACITY)
        {
            log.warn("bank: cannot withdraw {} of itemId={} — inventory full ({}/{}), no existing slot for received id {}",
                qty, itemId, slotsUsed, INVENTORY_CAPACITY, receivedId);
            return false;
        }

        if (!withdrawXClickChain(itemId, qty)) return false;

        long deadline = System.currentTimeMillis() + WITHDRAW_VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            SequenceSleep.sleep(client, POLL_INTERVAL_MS);
            int cur = inventoryAmount(receivedId);
            if (cur >= baseline + qty) return true;     // full requested qty arrived
            if (!Boolean.TRUE.equals(onClient(this::isBankOpen)))
            {
                if (cur > baseline)
                {
                    log.warn("bank: bank closed mid-withdraw — got partial {} of {} (itemId={})",
                        cur - baseline, qty, itemId);
                    return true;
                }
                log.warn("bank: bank closed mid-withdraw with zero items received (itemId={})", itemId);
                return false;
            }
        }
        int finalCur = inventoryAmount(receivedId);
        if (finalCur > baseline)
        {
            // Bank had less stock than requested: the engine gave us
            // what it had and the loop timed out waiting for the rest.
            // Counts as success — the caller will see the partial fill
            // and re-call if it wants more.
            log.info("bank: withdraw partial — got {} of {} for itemId={} (bank likely had less stock)",
                finalCur - baseline, qty, itemId);
            return true;
        }
        log.warn("bank: withdraw verify TIMEOUT — no inventory change after {}ms (itemId={}, qty={}, receivedId={})",
            WITHDRAW_VERIFY_TIMEOUT_MS, itemId, qty, receivedId);
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void withdrawX(int itemId, int qty) throws InterruptedException
    {
        assertWorkerThread("withdrawX");
        tryWithdrawX(itemId, qty);
    }

    /** Withdraw {@code qty} of {@code itemId} as a noted stack — flips the
     *  bank's "Note" toggle on first if needed, then runs the same right-
     *  click "Withdraw-X" flow as {@link #tryWithdrawX}. The result lands
     *  in a single inventory slot (a noted stack) regardless of qty.
     *
     *  <p>The verify poll watches the *noted* item id (resolved via
     *  {@link ItemComposition#getLinkedNoteId()}); if the unnoted id has
     *  no linked noted form, falls back to polling the unnoted id (the
     *  bank silently delivers unnoted in that case). */
    public boolean tryWithdrawAsNoteX(int itemId, int qty) throws InterruptedException
    {
        assertWorkerThread("tryWithdrawAsNoteX");
        if (qty <= 0) return false;
        if (!ensureNoteMode(true)) return false;
        int notedId = notedIdFor(itemId);
        return withdrawXAndVerify(itemId, qty, notedId > 0 ? notedId : itemId);
    }

    /** {@inheritDoc} */
    @Override
    public void withdrawAsNoteX(int itemId, int qty) throws InterruptedException
    {
        assertWorkerThread("withdrawAsNoteX");
        tryWithdrawAsNoteX(itemId, qty);
    }

    /** Force the bank's withdraw-mode varbit into the requested state —
     *  {@code wantNoted=true} for noted (varbit=1), {@code false} for
     *  item / unnoted (varbit=0). No-op if already in the requested state.
     *  Returns true iff the bank ends up in the requested mode.
     *
     *  <p><b>Why this is the only place note mode is ever changed:</b>
     *  the toggle is sticky across withdraws AND across bank close/open.
     *  Without this guard a {@code tryWithdrawAsNoteX} on cycle N would
     *  leave note mode on, and the next cycle's {@code tryWithdrawX}
     *  would silently land NOTED items in inventory — breaking any
     *  subsequent "Use X on Y" / cooking / crafting click since noted
     *  items can't be used on anything. Every public unnoted-withdraw
     *  entry point ({@code tryWithdrawAll}, {@code tryWithdrawOne},
     *  {@code tryWithdrawX}) calls this with {@code false};
     *  {@code tryWithdrawAsNoteX} calls it with {@code true}. Callers
     *  must not flip the toggle themselves.
     *
     *  <p>Called from inside the dispatcher worker (we run as part of
     *  {@code tryWithdraw*} which is itself wrapped in a {@code
     *  RUN_TASK}), so we already hold the dispatcher's busy flag. Use
     *  {@link HumanizedInputDispatcher#widgetClickOnWorker} for the
     *  toggle click — calling {@code dispatcher.dispatch(...)} from here
     *  would self-drop on the busy flag, and {@code awaitIdle} would
     *  deadlock waiting for ourselves to finish. */
    private boolean ensureNoteMode(boolean wantNoted) throws InterruptedException
    {
        int target = wantNoted ? 1 : 0;
        Integer state = onClient(() -> client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES));
        if (state != null && state == target) return true;

        dispatcher.widgetClickOnWorker(InterfaceID.Bankmain.NOTE);

        // Poll the varbit briefly — clicking the toggle bumps it within a
        // tick or two; if it doesn't, bail rather than withdraw in the
        // wrong mode.
        long deadline = System.currentTimeMillis() + 1500L;
        while (System.currentTimeMillis() < deadline)
        {
            SequenceSleep.sleep(client, 80L);
            Integer cur = onClient(() -> client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES));
            if (cur != null && cur == target) return true;
        }
        log.warn("bank: note-mode toggle did not flip to {} after click — aborting withdraw",
            wantNoted ? "noted" : "unnoted");
        return false;
    }

    /** Deposit all of {@code itemId} from the inventory via the slot's
     *  right-click "Deposit-All" option. The bank must be open.
     *
     *  <p><b>Verified contract:</b> returns {@code true} only after the
     *  inventory's quantity of {@code itemId} has dropped to 0 (or the
     *  caller had nothing of that item to begin with — short-circuit).
     *  Returns {@code false} on widget-unresolved, bank-closed-mid-poll,
     *  or verify timeout, each with a warn log naming the cause.
     *
     *  <p>Use this instead of the deposit-inventory orb whenever any
     *  inventory item must STAY across the bank → cook → bank loop
     *  (tinderbox for cooking, pickaxe for mining, etc.) — the orb is
     *  "deposit everything", and re-withdrawing a kept item every trip
     *  is both wasteful and very bot-tell. */
    public boolean tryDepositAll(int itemId) throws InterruptedException
    {
        assertWorkerThread("tryDepositAll");
        int baseline = inventoryAmount(itemId);
        if (baseline == 0) return true;     // nothing to deposit — already at the goal state

        Rectangle b = onClient(() -> resolveInvSlotBoundsForDeposit(itemId));
        if (b == null)
        {
            // Surface the silent failure so the FSM (and the user) can
            // see why no deposit happened. Most common cause: bank not
            // open / Bankside widget hidden, or item missing from inv.
            log.warn("bank: depositAll itemId={} — inv slot widget unresolved"
                + " (bank closed? Bankside.ITEMS hidden? item gone?)", itemId);
            return false;
        }
        // Sample inside the slot bounds with a small margin — clicking
        // dead-centre is mechanical, and a fuzzy edge can bleed into
        // the adjacent slot under engine hit-test variance.
        int marginX = Math.max(1, b.width / 6);
        int marginY = Math.max(1, b.height / 6);
        int cx = b.x + marginX
            + (int) (Math.random() * Math.max(1, b.width - 2 * marginX));
        int cy = b.y + marginY
            + (int) (Math.random() * Math.max(1, b.height - 2 * marginY));
        log.info("bank: deposit-all itemId={} (baseline qty={}) via inv slot bounds={}",
            itemId, baseline, b);
        dispatcher.rightClickAndPickMenu(cx, cy, "Deposit-All");

        long deadline = System.currentTimeMillis() + DEPOSIT_VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            SequenceSleep.sleep(client, POLL_INTERVAL_MS);
            if (inventoryAmount(itemId) == 0) return true;
            if (!Boolean.TRUE.equals(onClient(this::isBankOpen)))
            {
                log.warn("bank: bank closed mid-depositAll for itemId={}", itemId);
                return false;
            }
        }
        log.warn("bank: depositAll itemId={} — verify TIMEOUT (qty {} → {} after {}ms)",
            itemId, baseline, inventoryAmount(itemId), DEPOSIT_VERIFY_TIMEOUT_MS);
        return false;
    }

    /**
     * Click the bank's "Deposit inventory" orb (everything in inv).
     *
     * <p><b>Verified contract:</b> returns {@code true} only after every
     * inventory slot is empty. Returns {@code false} on
     * widget-unresolved (orb hidden / bank closed), bank-closed-mid-poll,
     * or verify timeout, each with a warn log.
     *
     * <p>Worker-thread only — sleeps inside the verify loop. Callers on
     * the client thread will hit {@link SequenceSleep}'s assertion.
     */
    public boolean depositAllInventory() throws InterruptedException
    {
        assertWorkerThread("depositAllInventory");
        int baseline = inventorySlotsUsed();
        if (baseline == 0) return true;     // already empty

        Rectangle b = onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Bankmain.DEPOSITINV);
            if (w == null || w.isHidden()) return null;
            Rectangle r = w.getBounds();
            return r == null || r.isEmpty() ? null : r;
        });
        if (b == null)
        {
            log.warn("bank: depositAllInventory — orb widget not found / hidden"
                + " (bank closed? Bankmain.DEPOSITINV unrendered?)");
            return false;
        }
        log.info("bank: deposit-all-inventory orb (baseline {} slots used)", baseline);
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);

        long deadline = System.currentTimeMillis() + DEPOSIT_VERIFY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline)
        {
            SequenceSleep.sleep(client, POLL_INTERVAL_MS);
            int curSlots = inventorySlotsUsed();
            if (curSlots == 0) return true;
            if (!Boolean.TRUE.equals(onClient(this::isBankOpen)))
            {
                log.warn("bank: bank closed mid-depositAllInventory ({} slots still used)", curSlots);
                return false;
            }
        }
        log.warn("bank: depositAllInventory — verify TIMEOUT, {} slots still used after {}ms",
            inventorySlotsUsed(), DEPOSIT_VERIFY_TIMEOUT_MS);
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void depositAll(int itemId) throws InterruptedException
    {
        assertWorkerThread("depositAll");
        tryDepositAll(itemId);
    }

    /** Client-thread: locate the inventory slot widget holding
     *  {@code itemId} and return its bounds.
     *
     *  <p>While the bank is open, the standalone inventory tab at
     *  {@link InterfaceID.Inventory#ITEMS} (group 149) is HIDDEN — the
     *  engine renders the player's inventory under
     *  {@link InterfaceID.Bankside#ITEMS} on the right side of the bank
     *  UI, and that's where right-click "Deposit-1/5/10/X/All" entries
     *  fire from. Clicking the standalone-tab bounds while the bank is
     *  open silently resolves to a "Walk here" on the underlying tile.
     *
     *  <p>So: try the bank-side inventory first, fall back to the
     *  standalone tab for any non-bank caller (none today, kept cheap). */
    private Rectangle resolveInvSlotBoundsForDeposit(int itemId)
    {
        Widget parent = client.getWidget(InterfaceID.Bankside.ITEMS);
        if (parent == null || parent.isHidden())
        {
            parent = client.getWidget(InterfaceID.Inventory.ITEMS);
        }
        if (parent == null || parent.isHidden()) return null;
        ItemContainer inv = client.getItemContainer(InventoryID.INV);
        if (inv == null) return null;
        Item[] items = inv.getItems();
        if (items == null) return null;
        int slotIdx = -1;
        for (int i = 0; i < items.length; i++)
        {
            Item it = items[i];
            if (it != null && it.getId() == itemId) { slotIdx = i; break; }
        }
        if (slotIdx < 0) return null;
        Widget child = parent.getChild(slotIdx);
        if (child == null || child.isSelfHidden()) return null;
        Rectangle r = child.getBounds();
        return r == null || r.isEmpty() ? null : r;
    }

    /** True iff the bank slot for {@code itemId} is currently inside
     *  the visible window of {@link InterfaceID.Bankmain#ITEMS} — i.e.
     *  no scroll is needed before clicking it. Returns false if the
     *  item isn't in the bank, the bank widget isn't loaded, or the
     *  slot exists but is scrolled out.
     *
     *  <p>Used by callers that want to choose withdraw order based on
     *  what's currently on screen (don't scroll past a visible item to
     *  reach a hidden one — saves scroll cycles and looks more human). */
    public boolean isItemVisible(int itemId) throws InterruptedException
    {
        Boolean v = onClient(() -> isItemVisibleClientThread(itemId));
        return Boolean.TRUE.equals(v);
    }

    /** Client-thread half of {@link #isItemVisible}. Mirrors the
     *  visibility test inside {@link #resolveBankSlotInfo} but without
     *  computing scroll directives — pure observation. */
    private boolean isItemVisibleClientThread(int itemId)
    {
        Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
        if (container == null || container.isHidden()) return false;
        ItemContainer bankInv = client.getItemContainer(InventoryID.BANK);
        if (bankInv == null) return false;
        Item[] items = bankInv.getItems();
        if (items == null) return false;
        int slotIdx = -1;
        for (int i = 0; i < items.length; i++)
        {
            Item it = items[i];
            if (it != null && it.getId() == itemId) { slotIdx = i; break; }
        }
        if (slotIdx < 0) return false;
        Widget match = container.getChild(slotIdx);
        if (match == null || match.isSelfHidden()) return false;
        int containerH = container.getHeight();
        int relY = match.getRelativeY();
        int slotH = match.getHeight() <= 0 ? 36 : match.getHeight();
        int scrollY = container.getScrollY();
        int top = relY - scrollY;
        int bottom = top + slotH;
        return top >= 0 && bottom <= containerH;
    }

    private boolean withdrawWithVerb(int itemId, String verb) throws InterruptedException
    {
        // Self-contained scroll-and-click. Loops internally: re-resolves
        // visibility after each small wheel burst, scrolls again if still
        // not visible, click+verb when it is. Small bursts (1-3 notches)
        // with 150-400ms gaps mimic a real player flicking the wheel a
        // few notches, glancing, flicking again — vs. one big 5-notch
        // burst followed by a 2s caller-side pace, which reads as choppy.
        // Returns true on click dispatched, false on item missing or
        // scroll-find timeout.
        long deadline = System.currentTimeMillis() + 10_000L;
        int safety = 30;   // upper iter cap as belt-and-braces against
                            // a runaway loop (scrollY mis-reporting, etc.)
        while (safety-- > 0 && System.currentTimeMillis() < deadline)
        {
            ScrollOrBounds sb = onClient(() -> resolveBankSlotInfo(itemId));
            if (sb == null) return false;
            if (sb.bounds != null)
            {
                log.info("bank: withdraw '{}' itemId={} at bounds={}",
                    verb, itemId, sb.bounds);
                int cx = sb.bounds.x + sb.bounds.width / 2;
                int cy = sb.bounds.y + sb.bounds.height / 2;
                dispatcher.rightClickAndPickMenu(cx, cy, verb);
                return true;
            }
            // resolveBankSlotInfo caps at 5 per call; trim to 1-3 here so
            // each visible burst is small and we re-look between bursts.
            int notches = Math.max(1, Math.min(3, sb.scrollNotches));
            log.info("bank: scroll burst toward itemId={} (dir={}, notches={})",
                itemId, sb.scrollDirection, notches);
            dispatcher.wheelScroll(sb.scrollX, sb.scrollY,
                sb.scrollDirection, notches);
            // Brief glance-pause before the next burst — the inter-burst
            // beat is what makes wheel-scrolling look like a person
            // searching, not an automated scrollbar drag.
            SequenceSleep.sleep(client, 150L + (long) (Math.random() * 250L));
        }
        log.warn("bank: scroll-to-find timed out for itemId={}", itemId);
        return false;
    }

    /** Scroll directive returned to {@link #withdrawWithVerb} when the
     *  slot isn't currently visible. Either {@link #bounds} is set
     *  (slot is visible — caller can click directly) or the scroll
     *  fields are populated (caller dispatches a wheel scroll). */
    private static final class ScrollOrBounds
    {
        final Rectangle bounds;
        final int scrollX;
        final int scrollY;
        final int scrollDirection;
        final int scrollNotches;

        ScrollOrBounds(Rectangle b)
        { this.bounds = b; this.scrollX = this.scrollY = this.scrollDirection = this.scrollNotches = 0; }

        ScrollOrBounds(int x, int y, int dir, int notches)
        { this.bounds = null; this.scrollX = x; this.scrollY = y; this.scrollDirection = dir; this.scrollNotches = notches; }
    }

    /** Client-thread: count item {@code id} in the bank container.
     *  Returns null if the container is not loaded. */
    private Long bankAmountClientThread(int id)
    {
        ItemContainer bank = client.getItemContainer(InventoryID.BANK);
        if (bank == null) return null;
        Item[] items = bank.getItems();
        if (items == null) return 0L;
        long n = 0;
        for (Item it : items)
        {
            if (it != null && it.getId() == id) n += it.getQuantity();
        }
        return n;
    }

    /** Client-thread: locate the bank slot widget for {@code itemId}
     *  and decide whether the caller should click it directly (bounds
     *  visible) or scroll the bank widget toward it (visible after a
     *  wheel scroll). Returns null only when the item isn't in the
     *  bank container / bank widget isn't loaded.
     *
     *  <p>The bank's items widget is {@code Bankmain.ITEMS} (NOT
     *  {@code ITEMS_CONTAINER}, which is the surrounding layout
     *  group). Slots are accessed by index via {@code getChild(slot)},
     *  matching the bank-tags plugin's
     *  {@code LayoutManager#layout} pattern. */
    private ScrollOrBounds resolveBankSlotInfo(int itemId)
    {
        // 0x000c_000c — Bankmain.ITEMS, the actual scrollable items
        // widget the bank-tags plugin treats as authoritative.
        Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
        if (container == null || container.isHidden()) return null;

        // Resolve the bank slot index from the ItemContainer (not the
        // widget tree), so we don't have to scan every child by id.
        // Bank items can be on different tabs — the widget at slot N
        // in ITEMS corresponds 1:1 with the ItemContainer's slot N.
        ItemContainer bankInv = client.getItemContainer(InventoryID.BANK);
        if (bankInv == null) return null;
        Item[] items = bankInv.getItems();
        if (items == null) return null;
        int slotIdx = -1;
        for (int i = 0; i < items.length; i++)
        {
            Item it = items[i];
            if (it != null && it.getId() == itemId) { slotIdx = i; break; }
        }
        if (slotIdx < 0) return null;
        Widget match = container.getChild(slotIdx);
        if (match == null || match.isSelfHidden()) return null;

        // Visible window of the items container in widget-local coords.
        int containerH = container.getHeight();
        int relY = match.getRelativeY();
        int slotH = match.getHeight() <= 0 ? 36 : match.getHeight();
        int scrollY = container.getScrollY();
        int top = relY - scrollY;
        int bottom = top + slotH;
        boolean visible = top >= 0 && bottom <= containerH;

        if (!visible)
        {
            // Compute scroll direction + how many wheel notches to
            // send. OSRS bank wheel = ~30px per notch (verified via
            // engine source). We send a few notches per script tick so
            // the scroll plays out over multiple ticks — matches a
            // human flicking the wheel and looking for the item rather
            // than snap-scrolling to the exact target. The next script
            // tick re-reads bounds; if not visible yet, sends more
            // notches.
            int delta = top < 0 ? top : (bottom - containerH);
            // Direction: positive delta (slot below view) → scroll
            // toward the user (down) → wheel rotation +1.
            // Negative (slot above view) → wheel rotation -1.
            int direction = delta > 0 ? 1 : -1;
            int approxNotches = Math.max(1, Math.min(5, Math.abs(delta) / 30 + 1));
            // Wheel events are consumed at the cursor position — pick
            // a point inside the bank items area.
            Rectangle cb = container.getBounds();
            if (cb == null || cb.isEmpty()) return null;
            int wx = cb.x + cb.width / 2;
            int wy = cb.y + cb.height / 2;
            return new ScrollOrBounds(wx, wy, direction, approxNotches);
        }
        Rectangle r = match.getBounds();
        if (r == null || r.isEmpty()) return null;
        return new ScrollOrBounds(r);
    }

    /** Recursive descent: return the first widget in the subtree of
     *  {@code parent} whose action list contains {@code wantedAction}.
     *  Depth-bounded to avoid pathological / cyclic widget trees. */
    private static Widget findChildWithAction(Widget parent, String wantedAction,
                                              int depth, int maxDepth)
    {
        if (parent == null || parent.isHidden() || depth > maxDepth) return null;
        String[] actions = parent.getActions();
        if (actions != null)
        {
            for (String a : actions)
            {
                if (wantedAction.equals(a)) return parent;
            }
        }
        Widget[] statics = parent.getStaticChildren();
        if (statics != null)
        {
            for (Widget c : statics)
            {
                Widget hit = findChildWithAction(c, wantedAction, depth + 1, maxDepth);
                if (hit != null) return hit;
            }
        }
        Widget[] dyn = parent.getDynamicChildren();
        if (dyn != null)
        {
            for (Widget c : dyn)
            {
                Widget hit = findChildWithAction(c, wantedAction, depth + 1, maxDepth);
                if (hit != null) return hit;
            }
        }
        Widget[] nested = parent.getNestedChildren();
        if (nested != null)
        {
            for (Widget c : nested)
            {
                Widget hit = findChildWithAction(c, wantedAction, depth + 1, maxDepth);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** Run a Supplier on the client thread and wait for the result. Used
     *  by {@link #clickBankBoothRandom} which needs to read NPC + Scene
     *  state. Bounded at 2s so a wedged client thread doesn't hang the
     *  caller.
     *
     *  <p><b>Threading:</b> short-circuits when the caller is already on
     *  the client thread — running the supplier inline. Otherwise marshals
     *  via {@link ClientThread#invoke(Runnable)} which queues for the
     *  client thread and signals the latch on completion. The short-
     *  circuit prevents a deadlock when banking steps are dispatched
     *  inside a sequence-engine tick that already runs on the client
     *  thread (e.g. {@code GrandExchangeScript} bank-prep variants drive
     *  {@code engine.advanceTick()} via {@code clientThread.invokeLater},
     *  putting {@code OpenBankStep.onStart} on the client thread). Without
     *  the short-circuit, the queued lambda would never run because the
     *  client thread is blocked on the latch. */
    private <T> T onClient(java.util.function.Supplier<T> s) throws InterruptedException
    {
        // Short-circuit: already on the client thread → run inline. This
        // mirrors {@link HumanizedInputDispatcher#onClient} and
        // {@link net.runelite.client.sequence.internal.ClientObserver#onClient}
        // and is required for callers that drive the engine on the client
        // thread (Phase B GE bank-prep). Test environments without a
        // real client are covered by the null check above the call site.
        if (client != null && client.isClientThread())
        {
            try { return s.get(); }
            catch (Throwable th) { log.warn("bank: onClient threw inline", th); return null; }
        }
        java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(s.get()); }
            catch (Throwable th) { log.warn("bank: onClient threw", th); }
            finally { latch.countDown(); }
        });
        if (!latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS))
        {
            log.warn("bank: onClient timed out");
            return null;
        }
        return ref.get();
    }
}
