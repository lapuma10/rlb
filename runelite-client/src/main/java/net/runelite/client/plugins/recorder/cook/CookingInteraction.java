package net.runelite.client.plugins.recorder.cook;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.AnimationID;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.scene.SceneScanner;
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
    /** Walk-merge cooking variant — engine plays this between cooks
     *  when the player has slight residual movement queued. Without
     *  it, isCooking() reports false during the gap and the script
     *  spuriously re-dispatches use-raw-on-fire, which by then locks
     *  onto a slot the engine already converted to cooked food.
     *  HUMAN_COOKING_WALKMERGE_NORESTART = 10795 per gameval. */
    public static final int ANIM_COOKING_WALKMERGE = 10795;

    /** Search radius for heat sources / ground logs (Chebyshev tiles,
     *  same plane as player). 12 covers a reasonable working area without
     *  picking up noise from neighbouring rooms. */
    public static final int SEARCH_RADIUS = 12;

    private final Client client;
    private final ClientThread clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final SceneScanner scanner;

    public CookingInteraction(Client client, ClientThread clientThread,
                              HumanizedInputDispatcher dispatcher)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dispatcher = dispatcher;
        this.scanner = new SceneScanner(client);
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

    /** True if the local player is actively cooking on a fire OR range.
     *  Recognises ALL known cooking animations: COOKING_FIRE (897),
     *  COOKING_RANGE (896), COOKING_LOOP (11735), and the walk-merge
     *  variant (10795) the engine plays between cooks during a Cook-All
     *  queue. Missing any one of them causes the script to think
     *  cooking has stopped mid-batch and to re-dispatch use-raw — which
     *  then locks use-mode onto a slot that the engine has already
     *  converted from raw to cooked. */
    public boolean isCooking() throws InterruptedException
    {
        Integer a = onClient(this::playerAnimationOnClient);
        if (a == null || a == -1) return false;
        return a == ANIM_COOKING_FIRE
            || a == ANIM_COOKING_RANGE
            || a == ANIM_COOKING_LOOP
            || a == ANIM_COOKING_WALKMERGE;
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

    /** Result of a scene scan. Alias for {@link SceneScanner.Match} —
     *  kept here so existing callers (CookingScript) don't have to
     *  switch import paths. New code should reach for
     *  {@link SceneScanner.Match} directly. */
    public static final class Match
    {
        public final WorldPoint tile;
        public final GameObject gameObject;
        public final TileItem tileItem;

        Match(WorldPoint t, GameObject go, TileItem ti)
        { tile = t; gameObject = go; tileItem = ti; }

        static Match wrap(SceneScanner.Match s)
        { return s == null ? null : new Match(s.tile, s.gameObject, s.tileItem); }
    }

    /** Find the closest GameObject within {@link #SEARCH_RADIUS} of the
     *  player whose composition name matches {@code namePattern}
     *  (case-insensitive equality). Used for "Fire" and "Range" objects.
     *  Returns null if none in range. */
    public Match findHeatSource(String namePattern) throws InterruptedException
    {
        return onClient(() -> Match.wrap(scanner.findGameObjectByName(namePattern, SEARCH_RADIUS)));
    }

    /** Find a TileItem with {@code itemId} on the player's plane within
     *  {@link #SEARCH_RADIUS}, picked uniformly at random among the
     *  matches within 2 tiles of the closest match. Used for "Logs"
     *  ground item spawns: a cluster of identical log piles surrounds
     *  the cook spot, and always lighting the strict-closest pile is
     *  bot-tell behaviour. The 2-tile jitter keeps the pick near us
     *  (no walking past closer logs to reach a far one) but varies
     *  WHICH near-pile we light each trip. */
    public Match findGroundLogs(int itemId) throws InterruptedException
    {
        // Find the closest log spawn; if multiple are equidistant, pick one
        // at random so the bot doesn't always light the same tile.
        return onClient(() -> Match.wrap(
            scanner.findTileItemById(itemId, SEARCH_RADIUS)));
    }

    /** Variant used by V2 cooking: picks uniformly at random among log
     *  piles within {@code jitter} Chebyshev tiles of the closest match.
     *  jitter=2 lights a different pile every trip when a cluster of
     *  spawns surrounds the cook spot. V1 still uses the strict-closest
     *  {@link #findGroundLogs} above. */
    public Match findGroundLogsVaried(int itemId, int jitter) throws InterruptedException
    {
        return onClient(() -> Match.wrap(
            scanner.findTileItemByIdRandomNear(itemId, SEARCH_RADIUS, jitter)));
    }

    /** Find a Fire game object whose composition name matches
     *  {@code namePattern} and whose tile equals {@code targetTile}.
     *  Used to verify the fire we just lit actually appeared — vs.
     *  picking up a random nearby fire that someone else lit. Returns
     *  null if no fire is on that tile.
     *
     *  <p>The OSRS engine sometimes spawns the fire on an adjacent tile
     *  if the click landed on a no-walk tile, so accept fires within 1
     *  tile of {@code targetTile} too. */
    public Match findFireAt(String namePattern, WorldPoint targetTile)
        throws InterruptedException
    {
        if (targetTile == null) return null;
        return onClient(() -> {
            Player self = client.getLocalPlayer();
            if (self == null) return null;
            WorldView wv = client.getTopLevelWorldView();
            if (wv == null) return null;
            net.runelite.api.Scene scene = wv.getScene();
            if (scene == null) return null;
            net.runelite.api.Tile[][][] tiles = scene.getTiles();
            int plane = targetTile.getPlane();
            if (tiles == null || plane < 0 || plane >= tiles.length) return null;
            net.runelite.api.Tile[][] planeTiles = tiles[plane];
            int tx = targetTile.getX() - wv.getBaseX();
            int ty = targetTile.getY() - wv.getBaseY();
            // Scan a 3x3 around the target tile to absorb the engine's
            // 1-tile drift on a "spawn fire on adjacent tile" path.
            for (int dx = -1; dx <= 1; dx++)
            {
                for (int dy = -1; dy <= 1; dy++)
                {
                    int sx = tx + dx, sy = ty + dy;
                    if (sx < 0 || sy < 0
                        || sx >= planeTiles.length
                        || sy >= planeTiles[0].length) continue;
                    net.runelite.api.Tile t = planeTiles[sx][sy];
                    if (t == null) continue;
                    net.runelite.api.GameObject[] gos = t.getGameObjects();
                    if (gos == null) continue;
                    for (net.runelite.api.GameObject go : gos)
                    {
                        if (go == null) continue;
                        net.runelite.api.ObjectComposition def =
                            client.getObjectDefinition(go.getId());
                        if (def == null) continue;
                        if (def.getImpostorIds() != null)
                        {
                            try
                            {
                                net.runelite.api.ObjectComposition imp = def.getImpostor();
                                if (imp != null) def = imp;
                            }
                            catch (Throwable ignored) { /* base def */ }
                        }
                        String name = def.getName();
                        if (name == null) continue;
                        if (!name.equalsIgnoreCase(namePattern)) continue;
                        LocalPoint lp = go.getLocalLocation();
                        if (lp == null) continue;
                        WorldPoint wp = WorldPoint.fromLocal(client, lp);
                        return new Match(wp, go, null);
                    }
                }
            }
            return null;
        });
    }

    // ────────────────────────────────────────────────────────────────
    // Use-on flows — light fire, use food on heat source
    // ────────────────────────────────────────────────────────────────

    /** Step 1 of lighting a fire: select the tinderbox in the inventory
     *  with verb "Use" — engine enters use-mode where the next click is
     *  interpreted as the use-target.
     *
     *  <p>Runs under the dispatcher's exclusive cursor ownership so the
     *  cooking flow does not rely on the older async CLICK_INV_ITEM path,
     *  whose generic recovery logic still uses Escape in non-cooking
     *  contexts. */
    public boolean useTinderbox() throws InterruptedException
    {
        AtomicBoolean ok = new AtomicBoolean(false);
        boolean ran = dispatcher.runExclusive(() -> ok.set(useTinderboxOnWorker()));
        if (!ran)
        {
            log.info("cook: useTinderbox skipped — dispatcher busy");
            return false;
        }
        return ok.get();
    }

    /** Exclusive/worker variant for lighting fallback. Verifies this is a
     *  clean direct inventory selection of Tinderbox, not an existing
     *  use-mode action like "Use Raw chicken -> Tinderbox". */
    public boolean useTinderboxOnWorker() throws InterruptedException
    {
        int slot = inventorySlotOf(ItemID.TINDERBOX);
        if (slot < 0) return false;
        dispatcher.clearLastError();
        if (!dispatcher.invSlotClickVerifiedOnWorker(slot, "Use", "Tinderbox"))
        {
            String err = dispatcher.lastErrorMessage();
            if (err != null) log.info("cook: verified tinderbox click failed — {}", err);
            return false;
        }
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
        // force=true: at e.g. Lumbridge Castle P2 the logs tile geometrically
        // projects inside the viewport but is occluded by a wall — without a
        // forced rotation the dispatcher's "already visible" short-circuit
        // skips the pan and we click a tile the player can't actually see.
        dispatcher.rotateCameraToward(logsMatch.tile, true);
        Rectangle b = onClient(() -> tileItemBounds(logsMatch));
        if (b == null) return false;
        int cx = b.x + b.width / 2;
        int cy = b.y + b.height / 2;
        log.info("cook: clicking ground-logs tile {} at ({},{}) [light fire]",
            logsMatch.tile, cx, cy);
        // Require the exact target text. "Use Raw chicken -> Logs" is also a
        // verb=Use hover, but it is the wrong selected item and must never be
        // clicked.
        if (!dispatcher.boundsClickVerifiedAction(b, "Use", "Tinderbox", "Logs"))
        {
            String err = dispatcher.lastErrorMessage();
            log.info("cook: logs click aborted (tinderbox-use guard) — {}", err);
            return false;
        }
        return true;
    }

    /** Right-click the ground-logs tile item and select "Light" from the
     *  context menu. Uses CLICK_GROUND_ITEM so the dispatcher resolves the
     *  item's own clickbox pixel (via resolveGroundItemPixel) rather than
     *  the raw tile-poly bounds — the tile poly can overlap the inventory
     *  panel at Lumbridge P2, causing the right-click to open an inventory
     *  item's context menu instead of the log's. Requires a Tinderbox in
     *  inventory — OSRS hides "Light" otherwise. */
    public boolean lightLogsViaClick(Match logsMatch) throws InterruptedException
    {
        if (logsMatch == null || logsMatch.tile == null || logsMatch.tileItem == null)
            return false;
        int itemId = logsMatch.tileItem.getId();
        log.info("cook: dispatching CLICK_GROUND_ITEM Light — tile={} itemId={}", logsMatch.tile, itemId);
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GROUND_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(logsMatch.tile)
            .itemId(itemId)
            .verb("Light")
            .build());
        waitForDispatcherIdle();
        String err = dispatcher.lastErrorMessage();
        if (err != null)
        {
            log.info("cook: logs Light click failed — {}", err);
            dispatcher.dismissOpenMenuByMovingAway();
            dispatcher.clearSelectedWidgetTargetMode();
            return false;
        }
        return true;
    }

    /** Select the raw food in the inventory with verb "Use" — engine enters
     *  use-mode for the next click.
     *
     *  <p>Runs under the dispatcher's exclusive cursor ownership so the
     *  cooking flow does not depend on the older async CLICK_INV_ITEM
     *  recovery path. Returns false if the food isn't in the inventory OR if
     *  the slot changed to cooked/burnt food before the verified click lands. */
    public boolean useRawFood(int rawFoodId) throws InterruptedException
    {
        AtomicBoolean ok = new AtomicBoolean(false);
        boolean ran = dispatcher.runExclusive(() -> ok.set(useRawFoodOnWorker(rawFoodId)));
        if (!ran)
        {
            log.info("cook: useRawFood skipped — dispatcher busy");
            return false;
        }
        return ok.get();
    }

    /** Worker-thread/exclusive variant of {@link #useRawFood}. The caller
     *  must already hold {@link HumanizedInputDispatcher#runExclusive}; this
     *  avoids the async dispatch race where the inventory click can be
     *  dropped while the script still continues to the fire click. */
    public boolean useRawFoodOnWorker(int rawFoodId) throws InterruptedException
    {
        int slot = inventorySlotOf(rawFoodId);
        if (slot < 0) return false;
        String rawName = itemName(rawFoodId);
        if (rawName == null || rawName.isBlank())
        {
            log.info("cook: raw food definition missing for id={} — refusing to click", rawFoodId);
            return false;
        }

        dispatcher.clearLastError();
        if (!dispatcher.invSlotClickVerifiedOnWorker(slot, "Use", rawName))
        {
            String err = dispatcher.lastErrorMessage();
            if (err != null) log.info("cook: verified raw-food click failed — {}", err);
            return false;
        }

        Integer slotId = onClient(() -> {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv == null) return -1;
            Item[] items = inv.getItems();
            if (items == null || slot >= items.length) return -1;
            Item it = items[slot];
            return it == null ? -1 : it.getId();
        });
        if (slotId == null || slotId != rawFoodId)
        {
            log.warn("cook: slot {} changed to id={} after verified click — cancelling use-mode", slot, slotId);
            dispatcher.clearSelectedWidgetTargetMode();
            return false;
        }
        return true;
    }

    /** Step 2 of cooking: click the heat source (Fire / Range) after
     *  use-mode is engaged on a raw-food slot. Verifies the cursor's
     *  L-click action reads "Use Raw {food} -&gt; {heatSourceName}" before
     *  committing the click — guards against (a) Cook-All having raced our
     *  use-mode dispatch and put cooked food in the slot we selected, and
     *  (b) an obstructing object (log pile, table, fence) sitting between
     *  us and the fire and intercepting the click.
     *
     *  <p>If the hover mismatches: clears use-mode only when the current
     *  hover confirms an item is actually selected, then returns false. The
     *  caller's next tick re-engages use-mode on a fresh raw slot.
     *
     *  <p>Camera is rotated toward the tile (force=true) so the heat source
     *  is in view even when wall occlusion would otherwise hide it. */
    public boolean clickHeatSourceForCook(Match heatMatch, int rawFoodId,
                                          String heatSourceName)
        throws InterruptedException
    {
        if (heatMatch == null || heatMatch.tile == null) return false;
        if (heatSourceName == null || heatSourceName.isBlank()) return false;
        dispatcher.rotateCameraToward(heatMatch.tile, true);
        // Look up the raw food's display name AND the heat-source bounds in
        // one client-thread hop.
        String rawName = itemName(rawFoodId);
        if (rawName == null || rawName.isBlank())
        {
            log.info("cook: raw food definition missing for id={} — refusing heat click", rawFoodId);
            return false;
        }
        Rectangle b = onClient(() -> {
            Rectangle hull = gameObjectBounds(heatMatch);
            if (hull != null) return hull;
            return tileItemBounds(heatMatch);
        });
        if (b == null) return false;
        log.info("cook: heat-source verify-click — wanted 'Use {} -> {}' bounds={}",
            rawName, heatSourceName, b);
        // Verify L-click action: option=='Use', target contains BOTH the raw
        // food name (e.g. "Raw chicken") AND the heat source name ("Fire").
        // Both fragments are required — "Raw" alone could match "Raw beef"
        // we accidentally have selected; "Fire" alone passes if cooked food
        // is in use-mode on the fire (the bug the user reported).
        boolean clicked = dispatcher.boundsClickVerifiedAction(
            b, "Use", rawName, heatSourceName);
        if (!clicked)
        {
            // Hover doesn't show "Use Raw {food} -> {heat}". Possible causes:
            //   1. Cook-All processed our slot mid-dispatch; use-mode is on
            //      cooked food now — clicking would burn good food / waste
            //      it as fuel.
            //   2. An obstructing object (log pile, NPC, scenery) sits between
            //      the cursor and the fire — the hover resolves to that.
            // In either case, cancel use-mode and let the FSM re-engage on a
            // fresh raw slot next tick.
            log.info("cook: heat-source hover mismatch — cancelling use-mode");
            dispatcher.clearSelectedWidgetTargetMode();
            return false;
        }
        return true;
    }

    private String itemName(int itemId) throws InterruptedException
    {
        String name = onClient(() -> {
            try
            {
                net.runelite.api.ItemComposition comp = client.getItemDefinition(itemId);
                String n = comp == null ? null : comp.getName();
                return n == null ? "" : n;
            }
            catch (Throwable th) { return ""; }
        });
        return name == null ? "" : name;
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
