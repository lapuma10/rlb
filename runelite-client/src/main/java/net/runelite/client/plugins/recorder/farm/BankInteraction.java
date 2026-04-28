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
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;

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
public final class BankInteraction
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

    /** True if the bank-main widget is loaded and visible. */
    public boolean isBankOpen()
    {
        Widget w = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
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
     * <p>NPCs are clicked via {@link Client#menuAction} on the slot whose
     * action begins with "Bank" — bankers' L-click default is "Talk-to",
     * not "Bank", so we have to invoke the right slot explicitly.
     * GameObject booths are clicked via canvas hull click — their L-click
     * default is already "Bank Bank booth", so a plain click works.
     *
     * <p>Returns true if a click was dispatched, false if no candidate
     * was visible (caller can retry next tick — the player may need to
     * walk closer).
     */
    public boolean clickBankBoothRandom() throws InterruptedException
    {
        BoothCandidate pick = onClient(this::findBoothCandidate);
        if (pick == null) return false;
        if (pick.npc != null)
        {
            return onClient(() -> {
                MenuAction ma = npcOptionForSlot(pick.slot);
                if (ma == null) return false;
                log.info("bank: invoking '{}' on banker {} (slot {})",
                    pick.actionText, pick.npc.getName(), pick.slot);
                client.menuAction(0, 0, ma, pick.npc.getIndex(), -1,
                    pick.actionText, pick.npc.getName());
                return true;
            });
        }
        // GameObject — L-click default is already "Bank", hull click works.
        Rectangle b = onClient(() -> {
            Shape h = pick.go.getConvexHull();
            return h == null ? null : h.getBounds();
        });
        if (b == null)
        {
            log.warn("bank: booth at {} has no convex hull",
                pick.go.getWorldLocation());
            return false;
        }
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        log.info("bank: clicking bank booth (id={}) hull center ({},{})",
            pick.go.getId(), cx, cy);
        dispatcher.clickCanvas(cx, cy);
        return true;
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

    private static MenuAction npcOptionForSlot(int slot)
    {
        switch (slot)
        {
            case 0:  return MenuAction.NPC_FIRST_OPTION;
            case 1:  return MenuAction.NPC_SECOND_OPTION;
            case 2:  return MenuAction.NPC_THIRD_OPTION;
            case 3:  return MenuAction.NPC_FOURTH_OPTION;
            case 4:  return MenuAction.NPC_FIFTH_OPTION;
            default: return null;
        }
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
    public boolean closeBank() throws InterruptedException
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
     *  state. */
    private <T> T onClient(java.util.function.Supplier<T> s) throws InterruptedException
    {
        java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(s.get()); }
            catch (Throwable th) { log.warn("bank: onClient threw", th); }
            finally { latch.countDown(); }
        });
        latch.await();
        return ref.get();
    }
}
