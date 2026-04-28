package net.runelite.client.plugins.recorder.cook;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.AnimationID;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Cooking-related primitives: light a fire from ground logs, use raw
 * food on a heat source, dismiss the level-up popup, confirm "Cook All"
 * on the Skillmulti chat dialogue.
 *
 * <p>Mirror's the {@code BankInteraction} shape: every method either
 * (a) returns a value through {@link #onClient(Supplier)} for client-thread
 * reads, or (b) dispatches a click via the injected
 * {@link HumanizedInputDispatcher}. Caller threads outside the client
 * thread are fine — internal hops handle the assertion-on-thread engine
 * checks.
 *
 * <p>None of these methods crash on missing scene state or missing
 * inventory items — they return false / null and let the script's FSM
 * decide whether to retry, walk, abort, etc.
 */
@Slf4j
public final class CookingInteraction
{
    /** Cooking on a fire. */
    public static final int ANIM_COOKING_FIRE  = AnimationID.COOKING_FIRE;   // 897
    /** Cooking on a range. */
    public static final int ANIM_COOKING_RANGE = AnimationID.COOKING_RANGE;  // 896
    /** Lighting a fire with a tinderbox. */
    public static final int ANIM_FIREMAKING    = AnimationID.FIREMAKING;     // 733
    /** Newer "cooking loop" animation that some food types use. */
    public static final int ANIM_COOKING_LOOP  = 11735;

    /** Search radius for heat sources / ground logs (Chebyshev tiles,
     *  same plane as player). 12 covers a reasonable working area without
     *  picking up noise from neighbouring rooms. */
    public static final int SEARCH_RADIUS = 12;

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;

    public CookingInteraction(Client client, ClientThread clientThread,
                              HumanizedInputDispatcher dispatcher)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
    }

    // ────────────────────────────────────────────────────────────────
    // Inventory / animation reads
    // ────────────────────────────────────────────────────────────────

    /** Lowest-index inventory slot holding {@code itemId}, or -1 if none.
     *  Reads on the client thread. */
    public int inventorySlotOf(int itemId) throws InterruptedException
    {
        Integer s = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return -1;
            Item[] items = inv.getItems();
            if (items == null) return -1;
            for (int i = 0; i < items.length; i++)
            {
                Item it = items[i];
                if (it != null && it.getId() == itemId) return i;
            }
            return -1;
        });
        return s == null ? -1 : s;
    }

    /** Count items with {@code itemId} in the inventory, summing stack
     *  quantities (cookable food is non-stackable, so this is just the
     *  slot count for raw fish / tinderboxes / logs). */
    public int inventoryAmount(int itemId) throws InterruptedException
    {
        Integer n = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return 0;
            Item[] items = inv.getItems();
            if (items == null) return 0;
            int total = 0;
            for (Item it : items)
            {
                if (it != null && it.getId() == itemId) total += Math.max(1, it.getQuantity());
            }
            return total;
        });
        return n == null ? 0 : n;
    }

    /** True if the local player is actively cooking on a fire OR range. */
    public boolean isCooking() throws InterruptedException
    {
        Integer a = onClient(this::playerAnimationOnClient);
        if (a == null || a == -1) return false;
        return a == ANIM_COOKING_FIRE || a == ANIM_COOKING_RANGE
            || a == ANIM_COOKING_LOOP;
    }

    /** True if the local player is currently lighting a fire. */
    public boolean isFiremaking() throws InterruptedException
    {
        Integer a = onClient(this::playerAnimationOnClient);
        return a != null && a == ANIM_FIREMAKING;
    }

    private int playerAnimationOnClient()
    {
        Player p = client.getLocalPlayer();
        return p == null ? -1 : p.getAnimation();
    }

    // ────────────────────────────────────────────────────────────────
    // Scene scans — heat source + ground logs
    // ────────────────────────────────────────────────────────────────

    /** Result of a scene scan. World tile + the live GameObject (for
     *  hull resolution) or TileItem (for ground items). One of the two
     *  is non-null on success. */
    public static final class Match
    {
        public final WorldPoint tile;
        public final GameObject gameObject;
        public final TileItem tileItem;

        Match(WorldPoint t, GameObject go, TileItem ti)
        { tile = t; gameObject = go; tileItem = ti; }
    }

    /** Find the closest GameObject within {@link #SEARCH_RADIUS} of the
     *  player whose composition name matches {@code namePattern}
     *  (case-insensitive equality). Used for "Fire" and "Range" objects.
     *  Returns null if none in range. */
    public Match findHeatSource(String namePattern) throws InterruptedException
    {
        return onClient(() -> findGameObjectByName(namePattern));
    }

    /** Find the closest TileItem with {@code itemId} on the player's
     *  plane within {@link #SEARCH_RADIUS}. Used for "Logs" ground item
     *  spawns. Returns null if none in range.
     *
     *  <p>Per the OSRS engine logs on the floor are TileItems (a.k.a.
     *  ground items), not GameObjects — there is no separate "Logs"
     *  GameObject id. */
    public Match findGroundLogs(int itemId) throws InterruptedException
    {
        return onClient(() -> findTileItemById(itemId));
    }

    private Match findGameObjectByName(String namePattern)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Scene scene = wv.getScene();
        if (scene == null) return null;
        Tile[][][] tiles = scene.getTiles();
        int plane = here.getPlane();
        if (tiles == null || plane < 0 || plane >= tiles.length) return null;
        Tile[][] planeTiles = tiles[plane];

        int hereSx = here.getX() - wv.getBaseX();
        int hereSy = here.getY() - wv.getBaseY();

        Match best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++)
        {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++)
            {
                int sx = hereSx + dx, sy = hereSy + dy;
                if (sx < 0 || sy < 0
                    || sx >= planeTiles.length
                    || sy >= planeTiles[0].length) continue;
                Tile t = planeTiles[sx][sy];
                if (t == null) continue;
                GameObject[] gos = t.getGameObjects();
                if (gos == null) continue;
                for (GameObject go : gos)
                {
                    if (go == null) continue;
                    ObjectComposition def = client.getObjectDefinition(go.getId());
                    if (def == null) continue;
                    if (def.getImpostorIds() != null)
                    {
                        try
                        {
                            ObjectComposition imp = def.getImpostor();
                            if (imp != null) def = imp;
                        }
                        catch (Throwable ignored) { /* base def */ }
                    }
                    String name = def.getName();
                    if (name == null) continue;
                    if (!name.equalsIgnoreCase(namePattern)) continue;
                    int cheb = Math.max(Math.abs(dx), Math.abs(dy));
                    if (cheb >= bestDist) continue;
                    LocalPoint lp = go.getLocalLocation();
                    if (lp == null) continue;
                    WorldPoint wp = WorldPoint.fromLocal(client, lp);
                    bestDist = cheb;
                    best = new Match(wp, go, null);
                }
            }
        }
        return best;
    }

    private Match findTileItemById(int itemId)
    {
        Player self = client.getLocalPlayer();
        if (self == null) return null;
        WorldPoint here = self.getWorldLocation();
        if (here == null) return null;
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return null;
        Scene scene = wv.getScene();
        if (scene == null) return null;
        Tile[][][] tiles = scene.getTiles();
        int plane = here.getPlane();
        if (tiles == null || plane < 0 || plane >= tiles.length) return null;
        Tile[][] planeTiles = tiles[plane];

        int hereSx = here.getX() - wv.getBaseX();
        int hereSy = here.getY() - wv.getBaseY();

        Match best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++)
        {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++)
            {
                int sx = hereSx + dx, sy = hereSy + dy;
                if (sx < 0 || sy < 0
                    || sx >= planeTiles.length
                    || sy >= planeTiles[0].length) continue;
                Tile t = planeTiles[sx][sy];
                if (t == null) continue;
                java.util.List<TileItem> items = t.getGroundItems();
                if (items == null) continue;
                for (TileItem ti : items)
                {
                    if (ti == null) continue;
                    if (ti.getId() != itemId) continue;
                    int cheb = Math.max(Math.abs(dx), Math.abs(dy));
                    if (cheb >= bestDist) continue;
                    WorldPoint wp = new WorldPoint(
                        here.getX() + dx, here.getY() + dy, plane);
                    bestDist = cheb;
                    best = new Match(wp, null, ti);
                }
            }
        }
        return best;
    }

    // ────────────────────────────────────────────────────────────────
    // Use-on flows — light fire, use food on heat source
    // ────────────────────────────────────────────────────────────────

    /** Step 1 of lighting a fire: select the tinderbox in the inventory
     *  with verb "Use" — engine enters use-mode where the next click is
     *  interpreted as the use-target.
     *
     *  <p>The dispatcher's CLICK_INV_ITEM with a non-default verb performs
     *  a hover → right-click → menu-pick if "Use" isn't already the
     *  L-click default (it usually is for the tinderbox).
     *
     *  <p>Blocks the calling thread until the dispatch completes — the
     *  follow-up canvas click for the use-target must NOT interleave
     *  with the inventory cursor move, so the caller relies on this
     *  method returning after the dispatcher's async worker finishes. */
    public boolean useTinderbox() throws InterruptedException
    {
        int slot = inventorySlotOf(ItemID.TINDERBOX);
        if (slot < 0) return false;
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_INV_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .slot(slot)
            .verb("Use")
            .build();
        dispatcher.dispatch(req);
        waitForDispatcherIdle();
        return true;
    }

    /** Step 2 of lighting a fire: click the ground-logs tile after
     *  use-mode is engaged on the tinderbox. The L-click default in
     *  use-mode is "Use Tinderbox -&gt; Logs", so a plain canvas click
     *  on the logs hull / tile resolves to it without any menu navigation.
     *
     *  <p>Pans the camera toward the logs before clicking — keeps the
     *  click target on-screen if the player is mid-rotation. Returns
     *  false if the logs tile no longer projects onto the canvas
     *  (player walked too far / scene unloaded) — caller retries on
     *  the next tick. */
    public boolean clickLogsForLight(Match logsMatch) throws InterruptedException
    {
        if (logsMatch == null || logsMatch.tile == null) return false;
        dispatcher.rotateCameraToward(logsMatch.tile);
        Rectangle b = onClient(() -> tileItemBounds(logsMatch));
        if (b == null) return false;
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        log.info("cook: clicking ground-logs tile {} at ({},{}) [light fire]",
            logsMatch.tile, cx, cy);
        dispatcher.clickCanvas(cx, cy);
        return true;
    }

    /** Step 1 of cooking: select the raw food in the inventory with
     *  verb "Use" — engine enters use-mode for the next click.
     *  Blocks until the dispatch completes (same reason as
     *  {@link #useTinderbox}). Returns false if the food isn't in
     *  the inventory. */
    public boolean useRawFood(int rawFoodId) throws InterruptedException
    {
        int slot = inventorySlotOf(rawFoodId);
        if (slot < 0) return false;
        ActionRequest req = ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_INV_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .slot(slot)
            .verb("Use")
            .build();
        dispatcher.dispatch(req);
        waitForDispatcherIdle();
        return true;
    }

    /** Step 2 of cooking: click the heat source (Fire / Range) after
     *  use-mode is engaged on a raw-food slot. Plain canvas click on the
     *  hull — the L-click default is "Use raw-food -&gt; Fire/Range",
     *  same use-mode semantics as the firemaking step.
     *
     *  <p>Pans the camera toward the heat source if the tile isn't
     *  already comfortably on-screen — same humanization the
     *  dispatcher's NPC/object clicks use. Falls back to the tile
     *  polygon if the hull resolution returns null (some particle-
     *  effect objects render without a clickable model). */
    public boolean clickHeatSourceForCook(Match heatMatch) throws InterruptedException
    {
        if (heatMatch == null || heatMatch.tile == null) return false;
        dispatcher.rotateCameraToward(heatMatch.tile);
        Rectangle b = onClient(() -> {
            Rectangle hull = gameObjectBounds(heatMatch);
            if (hull != null) return hull;
            // Fallback: use the canvas tile poly when the model hull
            // isn't available (rare, but defensive).
            return tileItemBounds(heatMatch);
        });
        if (b == null) return false;
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        log.info("cook: clicking heat source {} at ({},{})", heatMatch.tile, cx, cy);
        dispatcher.clickCanvas(cx, cy);
        return true;
    }

    private Rectangle gameObjectBounds(Match m)
    {
        if (m == null || m.gameObject == null) return null;
        Shape h = m.gameObject.getConvexHull();
        if (h == null) return null;
        Rectangle r = h.getBounds();
        return r == null || r.isEmpty() ? null : r;
    }

    private Rectangle tileItemBounds(Match m)
    {
        if (m == null || m.tile == null) return null;
        // Ground items render on the tile centre; use the canvas tile
        // polygon. We don't have a model hull for ground items in
        // RuneLite without a custom path.
        try
        {
            WorldView wv = client.getTopLevelWorldView();
            if (wv == null) return null;
            LocalPoint lp = LocalPoint.fromWorld(wv, m.tile);
            if (lp == null) return null;
            java.awt.Polygon poly = net.runelite.api.Perspective
                .getCanvasTilePoly(client, lp);
            if (poly == null) return null;
            Rectangle r = poly.getBounds();
            return r == null || r.isEmpty() ? null : r;
        }
        catch (Throwable th) { return null; }
    }

    // ────────────────────────────────────────────────────────────────
    // Skillmulti (Cook-X) + level-up dialog
    // ────────────────────────────────────────────────────────────────

    /** True if the Skillmulti ("Cook X" / "Make X") interface is open. */
    public boolean isCookMenuOpen() throws InterruptedException
    {
        Boolean v = onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Skillmulti.UNIVERSE);
            return w != null && !w.isHidden();
        });
        return v != null && v;
    }

    /** Confirm "Cook All" on the Skillmulti interface. Sends Space —
     *  OSRS maps the spacebar to the Make/Cook All action when this
     *  dialogue is open (verified via runelite#4946 and longstanding
     *  community behavior). The widget click is a fallback if Space
     *  doesn't take effect.
     *
     *  <p>Returns true when a dispatch was made (Space tapped). */
    public boolean confirmCookAll() throws InterruptedException
    {
        if (!isCookMenuOpen()) return false;
        log.info("cook: Skillmulti open — pressing Space (Cook All)");
        dispatcher.tapKey(KeyEvent.VK_SPACE);
        return true;
    }

    /** Click the Skillmulti.ALL widget directly. Used as a backup if
     *  the Space-key path doesn't trigger (engine-version drift). */
    public boolean clickCookAllWidget() throws InterruptedException
    {
        Rectangle b = onClient(() -> {
            Widget w = client.getWidget(InterfaceID.Skillmulti.ALL);
            if (w == null || w.isHidden()) return null;
            Rectangle r = w.getBounds();
            return r == null || r.isEmpty() ? null : r;
        });
        if (b == null) return false;
        dispatcher.clickCanvas(b.x + b.width / 2, b.y + b.height / 2);
        return true;
    }

    /** True if the Cooking-skill (or any-skill) level-up popup is on
     *  screen. We check the {@code LEVELUP_DISPLAY} group's UNIVERSE
     *  child — visible iff the dialog is up. */
    public boolean isLevelUpVisible() throws InterruptedException
    {
        Boolean v = onClient(() -> {
            Widget w = client.getWidget(InterfaceID.LevelupDisplay.UNIVERSE);
            return w != null && !w.isHidden();
        });
        return v != null && v;
    }

    /** Dismiss the level-up popup with Space. Identical mechanism to
     *  the NPC "click here to continue" dialog — the engine maps space
     *  to the Continue button. Returns true iff a dispatch was made. */
    public boolean dismissLevelUp() throws InterruptedException
    {
        if (!isLevelUpVisible()) return false;
        log.info("cook: level-up dialog visible — pressing Space");
        dispatcher.tapKey(KeyEvent.VK_SPACE);
        return true;
    }

    // ────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────

    /** Block until the dispatcher's async worker is idle. Delegates to
     *  the dispatcher's own {@code awaitIdle}; the cap is generous
     *  (covers a humanized cursor path + 2 humanized clicks). On
     *  timeout we proceed anyway — the caller's next action will set
     *  its own error if the dispatcher really is wedged. */
    private void waitForDispatcherIdle() throws InterruptedException
    {
        if (!dispatcher.awaitIdle(4000L))
        {
            log.warn("cook: dispatcher busy timeout — proceeding anyway");
        }
    }

    private <T> T onClient(Supplier<T> s) throws InterruptedException
    {
        java.util.concurrent.atomic.AtomicReference<T> ref =
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        clientThread.invokeLater(() -> {
            try { ref.set(s.get()); }
            catch (Throwable th) { log.warn("cook: onClient threw", th); }
            finally { latch.countDown(); }
        });
        if (!latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS))
        {
            log.warn("cook: onClient timed out");
            return null;
        }
        return ref.get();
    }
}
