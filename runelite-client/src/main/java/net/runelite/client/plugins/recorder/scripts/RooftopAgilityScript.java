package net.runelite.client.plugins.recorder.scripts;

import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import net.runelite.client.sequence.internal.ActionRequest;

/**
 * Rooftop Agility — Draynor v1.
 *
 * <p>Spec: {@code docs/superpowers/specs/2026-05-21-rooftop-agility-design.md}
 * (LOCKED 2026-05-21). Plan:
 * {@code docs/superpowers/plans/2026-05-21-rooftop-agility.md}.
 *
 * <p>Tick driver is {@code @Subscribe onGameTick} on the client thread —
 * the loop reads snapshot fields, decides, and enqueues at most one
 * {@link ActionRequest} per tick. It never sleeps. Multi-step input
 * flows (right-click → menu pick → cursor move → click) happen on the
 * dispatcher worker. See {@code CLAUDE.md} §"Threading model".
 */
@Slf4j
public final class RooftopAgilityScript
{
    // ─── Constants ───────────────────────────────────────────────────────────────
    static final int  DEFAULT_TARGET_LEVEL       = 20;
    static final int  UNKNOWN                    = -1;
    static final int  DEFAULT_EAT_AT_HP          = 8;
    static final long UNMAPPED_TILE_TIMEOUT_MS   = 8_000L;
    static final long MARK_PICKUP_TIMEOUT_MS     = 6_000L;
    static final long RUN_TOGGLE_THROTTLE_MS     = 2_000L;
    static final int  ITEM_MARK_OF_GRACE         = ItemID.GRACE;
    static final int  VARP_RUN                   = 173;

    // ─── Enums ───────────────────────────────────────────────────────────────────
    public enum RooftopCourseId { DRAYNOR }
    public enum State           { IDLE, RUNNING, PICKING_MARK }

    // ─── Injected dependencies ───────────────────────────────────────────────────
    private final Client                   client;
    private final ClientThread             clientThread;
    private final HumanizedInputDispatcher dispatcher;

    // ─── Run state ───────────────────────────────────────────────────────────────
    private final AtomicBoolean             running = new AtomicBoolean(false);
    private final AtomicReference<State>    state   = new AtomicReference<>(State.IDLE);
    private final AtomicReference<String>   status  = new AtomicReference<>("idle");

    // ─── Configuration (panel-set before start) ──────────────────────────────────
    private RooftopCourseId selectedCourse = RooftopCourseId.DRAYNOR;
    private int             targetLevel    = DEFAULT_TARGET_LEVEL;
    private boolean         pickupMarks    = true;
    private int             eatAtHp        = DEFAULT_EAT_AT_HP;

    // ─── Runtime ─────────────────────────────────────────────────────────────────
    private RooftopCourse course;
    private long          startedAt;
    private long          nextActionAt;
    private long          nextRunToggleAt;
    private int           runOnAtLeast;
    private boolean       lastRunOn;   // for falling-edge reseed of runOnAtLeast

    // ─── Last-click bookkeeping ──────────────────────────────────────────────────
    private RooftopNode lastClickedNode;
    private int         lastClickedStage  = UNKNOWN;
    private long        lastObstacleClickAt;

    // ─── Mark-pickup bookkeeping ─────────────────────────────────────────────────
    private WorldPoint markTileClicked;
    private long       markClickAt;
    private int        markCountBefore;

    // ─── Unmapped-tile bookkeeping ───────────────────────────────────────────────
    private WorldPoint unknownStageTile;
    private long       unknownStageSince;

    // ─── Lap tracking ────────────────────────────────────────────────────────────
    private int  lapsCompleted;
    private long lastLapCompletedAt;
    private int  marksPicked;

    public RooftopAgilityScript(Client client, ClientThread clientThread,
                                HumanizedInputDispatcher dispatcher)
    {
        this.client       = client;
        this.clientThread = clientThread;
        this.dispatcher   = dispatcher;
    }

    // ─── Public API ──────────────────────────────────────────────────────────────
    public State           state()         { return state.get(); }
    public String          status()        { return status.get(); }
    public boolean         isRunning()     { return running.get(); }
    public RooftopCourseId selectedCourse(){ return selectedCourse; }
    public int             targetLevel()   { return targetLevel; }
    public boolean         pickupMarks()   { return pickupMarks; }
    public int             eatAtHp()       { return eatAtHp; }
    public int             lapsCompleted() { return lapsCompleted; }
    public int             marksPicked()   { return marksPicked; }
    public long            startedAt()     { return startedAt; }

    public void setSelectedCourse(RooftopCourseId id) { this.selectedCourse = id; }
    public void setTargetLevel(int v)                 { this.targetLevel    = v; }
    public void setPickupMarks(boolean v)             { this.pickupMarks    = v; }
    public void setEatAtHp(int v)                     { this.eatAtHp        = v; }

    // ─── Lifecycle ───────────────────────────────────────────────────────────────

    /** Panel entry point — safe to call from the Swing EDT. All
     *  client-state reads (gameState, realSkillLevel, localPlayer,
     *  WorldPoint) are marshaled to the client thread, which is the only
     *  thread allowed to touch RuneLite scene/varbit/widget state under
     *  {@code -ea}. */
    public void start()
    {
        if (running.get())
        {
            log.info("[rooftop-agility] start() called while already running — ignored");
            return;
        }
        status.set("starting…");
        clientThread.invokeLater(this::startOnClient);
    }

    private void startOnClient()
    {
        if (running.get()) return;   // double-fire guard

        if (client.getGameState() != GameState.LOGGED_IN)
        {
            status.set("Not logged in");
            return;
        }

        RooftopCourse c = COURSES.get(selectedCourse);
        if (c == null)
        {
            status.set("No course profile for " + selectedCourse);
            log.warn("[rooftop-agility] no course profile for {}", selectedCourse);
            return;
        }
        try { validateCourse(c); }
        catch (RuntimeException ex)
        {
            status.set("Course validation failed: " + ex.getMessage());
            log.error("[rooftop-agility] validateCourse failed for {}", selectedCourse, ex);
            return;
        }
        if (targetLevel <= client.getRealSkillLevel(Skill.AGILITY))
        {
            status.set("Target level " + targetLevel + " already reached");
            return;
        }

        Player p = client.getLocalPlayer();
        if (p == null)
        {
            status.set("No local player");
            return;
        }
        WorldPoint here = p.getWorldLocation();
        boolean recoverable =
               c.validTiles.contains(here)
            || c.startTiles.contains(here)
            || c.fallTiles.contains(here)
            || c.lapEndTiles.contains(here);
        if (!recoverable)
        {
            status.set("Player not on a recoverable " + c.label + " tile (at " + here + ")");
            return;
        }

        this.course               = c;
        this.startedAt            = System.currentTimeMillis();
        this.nextActionAt         = 0L;
        this.nextRunToggleAt      = 0L;
        this.runOnAtLeast         = 20 + ThreadLocalRandom.current().nextInt(21);   // 20..40
        this.lastRunOn            = client.getVarpValue(VARP_RUN) == 1;
        this.lastClickedNode      = null;
        this.lastClickedStage     = UNKNOWN;
        this.lastObstacleClickAt  = 0L;
        this.markTileClicked      = null;
        this.markClickAt          = 0L;
        this.markCountBefore      = 0;
        this.unknownStageTile     = null;
        this.unknownStageSince    = 0L;
        this.lapsCompleted        = 0;
        this.lastLapCompletedAt   = 0L;
        this.marksPicked          = 0;
        this.state.set(State.RUNNING);
        this.status.set("Running — " + c.label);
        this.running.set(true);
        log.info("[rooftop-agility] started — course={}, target={}, eatAtHp={}, pickupMarks={}, runThreshold={}%",
            c.label, targetLevel, eatAtHp, pickupMarks, runOnAtLeast);
    }

    public void stop()
    {
        if (!running.get()) return;
        running.set(false);
        state.set(State.IDLE);
        status.set("stopped");
        log.info("[rooftop-agility] stopped — laps={}, marks={}", lapsCompleted, marksPicked);
    }

    // ─── Tick driver ─────────────────────────────────────────────────────────────

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (!running.get()) return;
        long now = System.currentTimeMillis();

        if (handleTargetLevel(now))                    return;
        if (dispatcher.isBusy())                       return;
        if (handleBlockingDialog(now))                 return;
        if (handleLowHp(now))                          return;

        if (state.get() == State.PICKING_MARK)
        {
            handlePickingMark(now);
            return;
        }

        if (maybeEnableRun(now))                       return;
        if (now < nextActionAt)                        return;

        if (isPlayerBusy())                            return;

        if (handleObstacleTimeout(now))                return;
        if (handleFallOrInvalidPosition(now))          return;
        if (handleLapEnd(now))                         return;
        if (handleUnmappedValidTile(now))              return;

        if (tryPickupReachableMark(now))               return;

        int stage = detectCurrentStage();
        // detectCurrentStage is guaranteed != UNKNOWN here because the
        // four handlers above have classified the player tile.
        clickObstacle(stage, course.nodes.get(stage), now);
    }

    // ─── Handlers: target / dialog / HP ──────────────────────────────────────────

    private boolean handleTargetLevel(long now)
    {
        if (client.getRealSkillLevel(Skill.AGILITY) < targetLevel) return false;
        status.set("Target level reached");
        log.info("[rooftop-agility] target level {} reached — stopping", targetLevel);
        running.set(false);
        state.set(State.IDLE);
        return true;
    }

    private boolean handleBlockingDialog(long now)
    {
        if (!client.isMenuOpen()) return false;
        log.info("[rooftop-agility] menu open at tick start — dispatching Escape");
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.KEY)
            .channel(ActionRequest.Channel.KEYBOARD)
            .keyCode(KeyEvent.VK_ESCAPE)
            .build());
        nextActionAt = now + 600;
        return true;
    }

    private boolean handleLowHp(long now)
    {
        int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        if (hp > eatAtHp) return false;

        ItemContainer inv = client.getItemContainer(InventoryID.INV);
        if (inv == null)
        {
            status.set("Low HP — no inventory");
            log.warn("[rooftop-agility] low HP {} but inventory container is null — stopping", hp);
            running.set(false);
            state.set(State.IDLE);
            return true;
        }

        // Locate first edible slot before deciding what to do.
        Item[] items = inv.getItems();
        int edibleSlot = -1;
        int edibleId   = -1;
        for (int slot = 0; slot < items.length; slot++)
        {
            Item it = items[slot];
            if (it == null || it.getId() <= 0) continue;
            ItemComposition comp = client.getItemDefinition(it.getId());
            if (comp == null) continue;
            String[] actions = comp.getInventoryActions();
            if (actions == null) continue;
            for (String a : actions)
            {
                if (a != null && "Eat".equalsIgnoreCase(a))
                {
                    edibleSlot = slot;
                    edibleId   = it.getId();
                    break;
                }
            }
            if (edibleSlot >= 0) break;
        }

        if (edibleSlot < 0)
        {
            status.set("Low HP and no food");
            log.info("[rooftop-agility] low HP {} <= {} and no food — stopping", hp, eatAtHp);
            running.set(false);
            state.set(State.IDLE);
            return true;
        }

        // Throttle: don't re-dispatch an eat while the previous one is still
        // in flight or the food's heal hasn't been applied yet. The tick
        // still exits (return true) because we don't want to obstacle-click
        // at low HP.
        if (now < nextActionAt)
        {
            status.set("Low HP — waiting on heal");
            return true;
        }

        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_INV_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .slot(edibleSlot)
            .verb("Eat")
            .build());
        long delay = 900L + ThreadLocalRandom.current().nextLong(500L);   // 900..1400
        nextActionAt = now + delay;
        status.set("Eating slot " + edibleSlot + " (id " + edibleId + ") at hp " + hp);
        log.info("[rooftop-agility] eating slot {} (id {}) at hp {}", edibleSlot, edibleId, hp);
        return true;
    }

    // ─── Handlers: run energy / busy ─────────────────────────────────────────────

    private boolean maybeEnableRun(long now)
    {
        boolean runOn = client.getVarpValue(VARP_RUN) == 1;

        // Falling edge: run just turned off → reseed the next threshold so
        // each off→on cycle uses a fresh roll. Spec §13.
        if (lastRunOn && !runOn)
        {
            runOnAtLeast = 20 + ThreadLocalRandom.current().nextInt(21);
            log.info("[rooftop-agility] run wore off — next toggle threshold {}%", runOnAtLeast);
        }
        lastRunOn = runOn;

        if (runOn)                              return false;
        if (now < nextRunToggleAt)              return false;

        int energyPercent = client.getEnergy() / 100;   // getEnergy() is 0..10000
        if (energyPercent < runOnAtLeast)       return false;

        Widget orb = client.getWidget(InterfaceID.Orbs.RUNBUTTON);
        if (orb == null || orb.isHidden())
        {
            orb = client.getWidget(InterfaceID.OrbsNomap.RUNBUTTON);
        }
        if (orb == null || orb.isHidden())
        {
            log.warn("[rooftop-agility] run-orb widget not visible — skipping toggle");
            nextRunToggleAt = now + RUN_TOGGLE_THROTTLE_MS;
            return false;
        }

        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_WIDGET)
            .channel(ActionRequest.Channel.MOUSE)
            .widgetId(orb.getId())
            .build());
        nextRunToggleAt = now + RUN_TOGGLE_THROTTLE_MS;
        log.info("[rooftop-agility] enabling run (energy {}% >= threshold)", energyPercent);
        return true;
    }

    private boolean isPlayerBusy()
    {
        Player p = client.getLocalPlayer();
        if (p == null) return true;
        if (p.getAnimation() != -1) return true;
        return p.getPoseAnimation() != p.getIdlePoseAnimation();
    }

    // ─── Handlers: stage / obstacle / timeout ────────────────────────────────────

    private int detectCurrentStage()
    {
        Player p = client.getLocalPlayer();
        if (p == null) return UNKNOWN;
        return course.stageByTile.getOrDefault(p.getWorldLocation(), UNKNOWN);
    }

    private Set<WorldPoint> expectedSuccessTiles(int stage)
    {
        RooftopNode n = course.nodes.get(stage);
        if (!n.successTiles.isEmpty()) return n.successTiles;
        if (stage + 1 < course.nodes.size()) return course.nodes.get(stage + 1).stageTiles;
        return course.lapEndTiles;
    }

    private void clearLastObstacle()
    {
        lastClickedNode      = null;
        lastClickedStage     = UNKNOWN;
        lastObstacleClickAt  = 0L;
    }

    private void clickObstacle(int stage, RooftopNode node, long now)
    {
        WorldPoint targetTile = findOnSceneObjectTile(node);
        if (targetTile == null)
        {
            status.set("Obstacle off-scene: " + node.label);
            nextActionAt = now + 600;
            log.warn("[rooftop-agility] obstacle {} (objectId {}) not found on scene at any of {} tiles",
                node.label, node.objectId, node.objectTiles.size());
            return;
        }

        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(targetTile)
            .verb(node.action)
            .build());

        lastClickedNode      = node;
        lastClickedStage     = stage;
        lastObstacleClickAt  = now;
        long delay = 600L + ThreadLocalRandom.current().nextLong(600L);    // 600..1200
        nextActionAt = now + delay;
        status.set("Clicked " + node.label + " (stage " + stage + ")");
        log.info("[rooftop-agility] click {} (stage {}, tile {}, verb \"{}\")",
            node.label, stage, targetTile, node.action);
    }

    /** Returns the tile of a scene GameObject whose id matches {@code node.objectId}
     *  and whose worldLocation is in {@code node.objectTiles}. Null if none. */
    private WorldPoint findOnSceneObjectTile(RooftopNode node)
    {
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();
        int baseX = client.getBaseX();
        int baseY = client.getBaseY();

        for (WorldPoint wp : node.objectTiles)
        {
            if (wp.getPlane() != plane) continue;
            int sx = wp.getX() - baseX;
            int sy = wp.getY() - baseY;
            if (sx < 0 || sy < 0 || sx >= Constants.SCENE_SIZE || sy >= Constants.SCENE_SIZE) continue;
            Tile t = tiles[plane][sx][sy];
            if (t == null) continue;
            for (GameObject go : t.getGameObjects())
            {
                if (go != null && go.getId() == node.objectId) return wp;
            }
        }
        return null;
    }

    private boolean handleObstacleTimeout(long now)
    {
        if (lastClickedNode == null) return false;
        if (now - lastObstacleClickAt < lastClickedNode.timeoutMs) return false;

        Player p = client.getLocalPlayer();
        if (p == null) return false;
        WorldPoint here = p.getWorldLocation();

        boolean finalNodeClicked =
            lastClickedNode == course.nodes.get(course.nodes.size() - 1);

        // Lap completion: final obstacle clicked + player landed on lapEndTiles.
        if (finalNodeClicked && course.lapEndTiles.contains(here))
        {
            lapsCompleted++;
            lastLapCompletedAt = now;
            log.info("[rooftop-agility] lap {} complete (landed at {})", lapsCompleted, here);
            clearLastObstacle();
            return false;
        }

        if (expectedSuccessTiles(lastClickedStage).contains(here))
        {
            clearLastObstacle();
            return false;
        }

        int stage = detectCurrentStage();
        if (stage != UNKNOWN)
        {
            clearLastObstacle();
            return false;
        }

        status.set("Obstacle timeout — recovering to start");
        log.info("[rooftop-agility] obstacle timeout on {} (tile {}) — recovering",
            lastClickedNode.label, here);
        clearLastObstacle();
        walkToNearestStartTile();
        long delay = 1_200L + ThreadLocalRandom.current().nextLong(1_300L);   // 1200..2500
        nextActionAt = now + delay;
        return true;
    }

    // ─── Handlers: fall / invalid / unmapped ─────────────────────────────────────

    private boolean handleFallOrInvalidPosition(long now)
    {
        Player p = client.getLocalPlayer();
        if (p == null) return false;
        WorldPoint here = p.getWorldLocation();

        if (course.fallTiles.contains(here))
        {
            status.set("Fell — recovering to start");
            log.info("[rooftop-agility] fell at {} — recovering", here);
            walkToNearestStartTile();
            long delay = 1_200L + ThreadLocalRandom.current().nextLong(1_300L);
            nextActionAt = now + delay;
            return true;
        }

        if (!course.validTiles.contains(here)
            && !course.startTiles.contains(here)
            && !course.lapEndTiles.contains(here))
        {
            status.set("Outside course — recovering to start");
            log.info("[rooftop-agility] off-route at {} — recovering", here);
            walkToNearestStartTile();
            long delay = 1_200L + ThreadLocalRandom.current().nextLong(1_300L);
            nextActionAt = now + delay;
            return true;
        }

        return false;
    }

    private boolean handleLapEnd(long now)
    {
        Player p = client.getLocalPlayer();
        if (p == null) return false;
        WorldPoint here = p.getWorldLocation();
        if (!course.lapEndTiles.contains(here)) return false;

        // Lap completion — count it once, immediately on landing. Spec §15:
        // every clear-on-success must check lap-end first; this is the
        // single non-timeout success path.
        boolean finalNodeClicked = lastClickedNode != null
            && lastClickedNode == course.nodes.get(course.nodes.size() - 1);
        if (finalNodeClicked)
        {
            lapsCompleted++;
            lastLapCompletedAt = now;
            log.info("[rooftop-agility] lap {} complete (landed at {})", lapsCompleted, here);
            clearLastObstacle();
        }

        // Player still on lap-end tile (just landed, or hasn't walked off yet)
        // → walk back to nearest start tile. Next tick's detectCurrentStage
        // will find stage 0 once the walk lands on a startTile.
        status.set("Lap done — walking to start");
        walkToNearestStartTile();
        long delay = 1_200L + ThreadLocalRandom.current().nextLong(1_300L);
        nextActionAt = now + delay;
        return true;
    }

    private boolean handleUnmappedValidTile(long now)
    {
        Player p = client.getLocalPlayer();
        if (p == null) return false;
        WorldPoint here = p.getWorldLocation();

        if (course.stageByTile.containsKey(here))
        {
            unknownStageTile = null;
            return false;
        }

        // We're on a validTile (fall/invalid handlers cleared their cases),
        // but no stage match.
        if (!here.equals(unknownStageTile))
        {
            unknownStageTile  = here;
            unknownStageSince = now;
        }
        if (now - unknownStageSince > UNMAPPED_TILE_TIMEOUT_MS)
        {
            status.set("Unmapped valid course tile: " + here);
            log.warn("[rooftop-agility] unmapped valid tile {} for >{} ms — stopping for data fix",
                here, UNMAPPED_TILE_TIMEOUT_MS);
            running.set(false);
            state.set(State.IDLE);
            return true;
        }
        status.set("Waiting for known stage (at " + here + ")");
        nextActionAt = now + 600;
        return true;
    }

    // ─── Marks of grace ──────────────────────────────────────────────────────────

    private boolean tryPickupReachableMark(long now)
    {
        if (!pickupMarks) return false;
        int stage = detectCurrentStage();
        if (stage == UNKNOWN) return false;

        RooftopNode node = course.nodes.get(stage);
        Player p = client.getLocalPlayer();
        if (p == null) return false;
        WorldPoint player = p.getWorldLocation();

        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();
        int baseX = client.getBaseX();
        int baseY = client.getBaseY();

        for (WorldPoint mt : node.reachableMarkTiles)
        {
            if (mt.getPlane() != player.getPlane()) continue;
            if (mt.getPlane() != plane)             continue;
            int sx = mt.getX() - baseX;
            int sy = mt.getY() - baseY;
            if (sx < 0 || sy < 0 || sx >= Constants.SCENE_SIZE || sy >= Constants.SCENE_SIZE) continue;
            Tile t = tiles[plane][sx][sy];
            if (t == null) continue;
            List<TileItem> items = t.getGroundItems();
            if (items == null) continue;
            for (TileItem ti : items)
            {
                if (ti == null || ti.getId() != ITEM_MARK_OF_GRACE) continue;

                dispatcher.dispatch(ActionRequest.builder()
                    .kind(ActionRequest.Kind.CLICK_GROUND_ITEM)
                    .channel(ActionRequest.Channel.MOUSE)
                    .tile(mt)
                    .itemId(ITEM_MARK_OF_GRACE)
                    .verb("Take")
                    .build());

                state.set(State.PICKING_MARK);
                markTileClicked = mt;
                markClickAt     = now;
                markCountBefore = inventoryCount(ITEM_MARK_OF_GRACE);
                long delay = 600L + ThreadLocalRandom.current().nextLong(400L);   // 600..1000
                nextActionAt = now + delay;
                status.set("Picking up mark at " + mt);
                log.info("[rooftop-agility] picking mark at {} from stage {}", mt, stage);
                return true;
            }
        }
        return false;
    }

    private void handlePickingMark(long now)
    {
        int nowCount = inventoryCount(ITEM_MARK_OF_GRACE);
        boolean markGone = !markStillOnTile(markTileClicked);

        if (nowCount > markCountBefore)
        {
            marksPicked++;
            log.info("[rooftop-agility] mark picked — total {}", marksPicked);
            state.set(State.RUNNING);
            markTileClicked = null;
            return;
        }
        if (markGone)
        {
            log.info("[rooftop-agility] mark tile empty (despawn or scene change) — resyncing");
            state.set(State.RUNNING);
            markTileClicked = null;
            return;
        }
        if (now - markClickAt > MARK_PICKUP_TIMEOUT_MS)
        {
            log.info("[rooftop-agility] mark pickup timeout — resyncing");
            state.set(State.RUNNING);
            markTileClicked = null;
            return;
        }
        // else: still waiting; no dispatch.
    }

    private boolean markStillOnTile(WorldPoint mt)
    {
        if (mt == null) return false;
        int plane = client.getPlane();
        if (mt.getPlane() != plane) return false;
        int sx = mt.getX() - client.getBaseX();
        int sy = mt.getY() - client.getBaseY();
        if (sx < 0 || sy < 0 || sx >= Constants.SCENE_SIZE || sy >= Constants.SCENE_SIZE) return false;
        Tile t = client.getScene().getTiles()[plane][sx][sy];
        if (t == null) return false;
        List<TileItem> items = t.getGroundItems();
        if (items == null) return false;
        for (TileItem ti : items)
        {
            if (ti != null && ti.getId() == ITEM_MARK_OF_GRACE) return true;
        }
        return false;
    }

    private int inventoryCount(int itemId)
    {
        ItemContainer inv = client.getItemContainer(InventoryID.INV);
        if (inv == null) return 0;
        int n = 0;
        for (Item it : inv.getItems())
        {
            if (it != null && it.getId() == itemId) n += it.getQuantity();
        }
        return n;
    }

    // ─── Walking ─────────────────────────────────────────────────────────────────

    private void walkToNearestStartTile()
    {
        Player p = client.getLocalPlayer();
        if (p == null) return;
        WorldPoint here = p.getWorldLocation();

        WorldPoint best = null;
        long bestDist = Long.MAX_VALUE;
        for (WorldPoint t : course.startTiles)
        {
            long dx = t.getX() - here.getX();
            long dy = t.getY() - here.getY();
            long d  = dx * dx + dy * dy;
            if (d < bestDist) { bestDist = d; best = t; }
        }
        if (best == null)
        {
            log.warn("[rooftop-agility] no startTiles in course — cannot recover");
            return;
        }
        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_TILE)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(best)
            .build());
        log.info("[rooftop-agility] walking to nearest startTile {} (sq-dist {})", best, bestDist);
    }

    // ─── Course profile data model ───────────────────────────────────────────────

    static final class RooftopCourse
    {
        final RooftopCourseId          id;
        final String                   label;
        final int                      levelReq;
        final Set<WorldPoint>          startTiles;
        final Set<WorldPoint>          validTiles;
        final Set<WorldPoint>          fallTiles;
        final Set<WorldPoint>          lapEndTiles;
        final List<RooftopNode>        nodes;
        final Map<WorldPoint, Integer> stageByTile;

        RooftopCourse(RooftopCourseId id, String label, int levelReq,
                      Set<WorldPoint> startTiles, Set<WorldPoint> validTiles,
                      Set<WorldPoint> fallTiles, Set<WorldPoint> lapEndTiles,
                      List<RooftopNode> nodes)
        {
            this.id          = id;
            this.label       = label;
            this.levelReq    = levelReq;
            this.startTiles  = Set.copyOf(startTiles);
            this.validTiles  = Set.copyOf(validTiles);
            this.fallTiles   = Set.copyOf(fallTiles);
            this.lapEndTiles = Set.copyOf(lapEndTiles);
            this.nodes       = List.copyOf(nodes);

            Map<WorldPoint, Integer> m = new HashMap<>();
            for (int i = 0; i < this.nodes.size(); i++)
            {
                for (WorldPoint t : this.nodes.get(i).stageTiles)
                {
                    Integer prev = m.put(t, i);
                    if (prev != null && prev != i)
                    {
                        throw new IllegalStateException(
                            "Duplicate stage tile " + t + " maps to stages " + prev + " and " + i);
                    }
                }
            }
            this.stageByTile = Map.copyOf(m);
        }
    }

    static final class RooftopNode
    {
        final String          label;
        final int             objectId;
        final String          action;
        final Set<WorldPoint> objectTiles;
        final Set<WorldPoint> stageTiles;
        final Set<WorldPoint> successTiles;
        final Set<WorldPoint> reachableMarkTiles;
        final long            timeoutMs;

        RooftopNode(String label, int objectId, String action,
                    Set<WorldPoint> objectTiles, Set<WorldPoint> stageTiles,
                    Set<WorldPoint> successTiles, Set<WorldPoint> reachableMarkTiles,
                    long timeoutMs)
        {
            this.label              = label;
            this.objectId           = objectId;
            this.action             = action;
            this.objectTiles        = Set.copyOf(objectTiles);
            this.stageTiles         = Set.copyOf(stageTiles);
            this.successTiles       = Set.copyOf(successTiles);
            this.reachableMarkTiles = Set.copyOf(reachableMarkTiles);
            this.timeoutMs          = timeoutMs;
        }
    }

    /** Authoring helper: tiles(x1,y1,p1, x2,y2,p2, ...). */
    static Set<WorldPoint> tiles(int... xyp)
    {
        if (xyp.length % 3 != 0)
        {
            throw new IllegalArgumentException("tiles() takes triples of (x, y, plane); got " + xyp.length);
        }
        Set<WorldPoint> out = new HashSet<>();
        for (int i = 0; i < xyp.length; i += 3)
        {
            out.add(new WorldPoint(xyp[i], xyp[i + 1], xyp[i + 2]));
        }
        return out;
    }

    // ─── Course validation ───────────────────────────────────────────────────────

    /** Throws {@link IllegalStateException} with a precise message on bad
     *  profile data. See spec §6a for the full invariant list. */
    static void validateCourse(RooftopCourse c)
    {
        if (c.nodes.isEmpty())       throw new IllegalStateException("nodes empty");
        if (c.startTiles.isEmpty())  throw new IllegalStateException("startTiles empty");
        if (c.lapEndTiles.isEmpty()) throw new IllegalStateException("lapEndTiles empty");

        Set<WorldPoint> stage0 = c.nodes.get(0).stageTiles;
        for (WorldPoint t : c.startTiles)
        {
            if (!stage0.contains(t))
            {
                throw new IllegalStateException(
                    "startTile " + t + " is not in node[0].stageTiles");
            }
        }

        for (int i = 0; i < c.nodes.size(); i++)
        {
            RooftopNode n = c.nodes.get(i);
            if (n.objectId <= 0)
                throw new IllegalStateException("node[" + i + "] objectId <= 0");
            if (n.action == null || n.action.isBlank())
                throw new IllegalStateException("node[" + i + "] blank action");
            if (n.stageTiles.isEmpty())
                throw new IllegalStateException("node[" + i + "] stageTiles empty");
            if (n.objectTiles.isEmpty())
                throw new IllegalStateException("node[" + i + "] objectTiles empty");

            for (WorldPoint t : n.stageTiles)
            {
                if (!c.validTiles.contains(t))
                    throw new IllegalStateException(
                        "stage tile " + t + " not in validTiles (node " + i + ")");
            }
            for (WorldPoint t : n.successTiles)
            {
                if (!c.validTiles.contains(t))
                    throw new IllegalStateException(
                        "successTile " + t + " not in validTiles (node " + i + ")");
                if (n.stageTiles.contains(t))
                    throw new IllegalStateException(
                        "successTile " + t + " also in stageTiles (node " + i + ")");
            }
            for (WorldPoint t : n.reachableMarkTiles)
            {
                if (!c.validTiles.contains(t))
                    throw new IllegalStateException(
                        "reachableMarkTile " + t + " not in validTiles (node " + i + ")");
            }
        }
        for (WorldPoint t : c.lapEndTiles)
        {
            if (!c.validTiles.contains(t))
                throw new IllegalStateException("lapEndTile " + t + " not in validTiles");
        }
    }

    // ─── Constants helper (scene bounds) ─────────────────────────────────────────

    private static final class Constants
    {
        static final int SCENE_SIZE = net.runelite.api.Constants.SCENE_SIZE;
    }

    // ─── COURSES map (populated when Draynor capture data is delivered) ─────────

    static final Map<RooftopCourseId, RooftopCourse> COURSES;

    static
    {
        Map<RooftopCourseId, RooftopCourse> m = new EnumMap<>(RooftopCourseId.class);

        // ── Draynor Rooftop ──────────────────────────────────────────────────────
        //
        // POPULATE WITH CAPTURED TILE DATA — see plan Task 4/5 and spec §17.
        // Until then, COURSES is empty and start() will refuse with
        // "No course profile for DRAYNOR". This is intentional: the script
        // ships without speculative tile data; the user delivers Draynor
        // groups via the tile-marker plugin + click-inspector, then we
        // paste them into the List.of(...) below and call validateCourse().
        //
        // Skeleton for the eventual paste — DO NOT uncomment until coords
        // are real:
        //
        // List<RooftopNode> draynorNodes = List.of(
        //     new RooftopNode("Rough wall",  /*objectId*/ ___, "Climb",
        //         tiles(/*draynor.object.0*/ ),
        //         tiles(/*draynor.stage.0*/  ),
        //         Set.of(),
        //         tiles(/*draynor.marks.0*/  ),
        //         4_000L),
        //     new RooftopNode("Tightrope",   /*objectId*/ ___, "Cross",
        //         tiles(/*draynor.object.1*/ ),
        //         tiles(/*draynor.stage.1*/  ),
        //         Set.of(),
        //         tiles(/*draynor.marks.1*/  ),
        //         7_000L),
        //     // ... nodes 2..6 (Tightrope, Narrow wall, Wall, Gap, Crate)
        // );
        // RooftopCourse draynor = new RooftopCourse(
        //     RooftopCourseId.DRAYNOR, "Draynor Rooftop", 1,
        //     tiles(/*draynor.start*/  ),
        //     tiles(/*draynor.valid*/  ),
        //     tiles(/*draynor.fall*/   ),
        //     tiles(/*draynor.lapend*/ ),
        //     draynorNodes);
        // validateCourse(draynor);
        // m.put(RooftopCourseId.DRAYNOR, draynor);

        COURSES = Collections.unmodifiableMap(m);
    }
}
