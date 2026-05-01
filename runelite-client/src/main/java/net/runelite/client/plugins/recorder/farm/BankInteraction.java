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

    /** True if the bank-main widget is loaded and visible. */
    public boolean isBankOpen()
    {
        Widget w = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
        return w != null && !w.isHidden();
    }

    /** True if the Bank PIN keypad is up. The bot can't enter a PIN
     *  safely (keystroke pacing matters for that flow); callers should
     *  abort with a status when this is true rather than continue
     *  clicking the booth — repeated booth clicks during PIN entry can
     *  lock the player out for 5 minutes. */
    public boolean isBankPinUp()
    {
        Widget w = client.getWidget(InterfaceID.BankpinKeypad.UNIVERSE);
        return w != null && !w.isHidden();
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

    /** Withdraw all of {@code itemId} from the bank via the slot's
     *  right-click "Withdraw-All" option. The bank must be open. Returns
     *  true iff a click was dispatched.
     *
     *  <p>Failure paths (returns false, sets no error):
     *  <ul>
     *    <li>bank not open / item not in bank — call
     *        {@link #bankContainsItem} first to differentiate.</li>
     *    <li>bank slot widget is scrolled out and we couldn't make it
     *        visible — caller falls back to a different strategy.</li>
     *  </ul> */
    public boolean tryWithdrawAll(int itemId) throws InterruptedException
    {
        if (!ensureNoteMode(false)) return false;
        return withdrawWithVerb(itemId, "Withdraw-All");
    }

    /** {@inheritDoc} */
    @Override
    public void withdrawAll(int itemId) throws InterruptedException
    {
        assertWorkerThread("withdrawAll");
        tryWithdrawAll(itemId);
    }

    /** Withdraw 1 of {@code itemId}. Same shape as {@link #tryWithdrawAll}.
     *  Returns true iff a click was dispatched. */
    public boolean tryWithdrawOne(int itemId) throws InterruptedException
    {
        if (!ensureNoteMode(false)) return false;
        return withdrawWithVerb(itemId, "Withdraw-1");
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
     *  characters → press Enter to confirm.
     *
     *  <p>Uses {@link HumanizedInputDispatcher#rightClickAndPickMenu} to
     *  scroll-and-right-click the slot (same as {@link #tryWithdrawAll} /
     *  {@link #tryWithdrawOne}), then {@link HumanizedInputDispatcher#typeChar}
     *  per digit and {@link HumanizedInputDispatcher#tapKey} for Enter.
     *
     *  <p>Returns true iff the slot was found, the "Withdraw-X" menu pick
     *  was dispatched, and the quantity was typed. The caller should wait
     *  for the quantity dialog to appear before expecting inventory change. */
    public boolean tryWithdrawX(int itemId, int qty) throws InterruptedException
    {
        if (qty <= 0) return false;
        if (!ensureNoteMode(false)) return false;
        return withdrawXInternal(itemId, qty);
    }

    /** Shared withdraw-X mechanics (right-click → "Withdraw-X" → chatbox
     *  digits → Enter) — does NOT touch the Note toggle. Both
     *  {@link #tryWithdrawX} (which forces note-OFF first) and
     *  {@link #tryWithdrawAsNoteX} (which forces note-ON first) call this
     *  so the toggle is set exactly once per call site, by the public
     *  entry point that knows what mode it wants. */
    private boolean withdrawXInternal(int itemId, int qty) throws InterruptedException
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
     *  in a single inventory slot (a noted stack) regardless of qty. */
    public boolean tryWithdrawAsNoteX(int itemId, int qty) throws InterruptedException
    {
        if (qty <= 0) return false;
        if (!ensureNoteMode(true)) return false;
        return withdrawXInternal(itemId, qty);
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
     *  right-click "Deposit-All" option. The bank must be open. Returns
     *  true iff a click was dispatched (item was found in inventory and
     *  the slot widget resolved); the caller checks
     *  {@link HumanizedInputDispatcher#lastErrorMessage()} on the next
     *  tick to see whether the menu pick actually landed.
     *
     *  <p>Use this instead of the deposit-inventory orb whenever any
     *  inventory item must STAY across the bank → cook → bank loop
     *  (tinderbox for cooking, pickaxe for mining, etc.) — the orb is
     *  "deposit everything", and re-withdrawing a kept item every trip
     *  is both wasteful and very bot-tell. */
    public boolean tryDepositAll(int itemId) throws InterruptedException
    {
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
        log.info("bank: deposit-all itemId={} via inv slot bounds={}", itemId, b);
        dispatcher.rightClickAndPickMenu(cx, cy, "Deposit-All");
        return true;
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
