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
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GroundObject;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.recorder.scene.SceneScanner;
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
    /** Chebyshev tile-radius around the player searched for Mark of Grace
     *  ground items. Per-node {@code reachableMarkTiles} was the original
     *  data model but populating it requires the operator to record every
     *  spawn tile per course, and v1 ships with empty sets. A live scene
     *  scan picks up any mark inside the rendered scene that's within
     *  reach of the running path. 10 covers all Draynor inter-obstacle
     *  gaps; OSRS only spawns marks in the scene around the player so a
     *  bigger radius doesn't help. */
    static final int  MARK_SCAN_RADIUS           = 10;
    static final long RUN_TOGGLE_THROTTLE_MS     = 2_000L;
    static final int  ITEM_MARK_OF_GRACE         = ItemID.GRACE;
    static final int  VARP_RUN                   = 173;

    // ─── Enums ───────────────────────────────────────────────────────────────────
    public enum RooftopCourseId {
        DRAYNOR, AL_KHARID, VARROCK, CANIFIS, FALADOR,
        SEERS, POLLNIVNEACH, RELLEKKA, ARDOUGNE
    }
    public enum State           { IDLE, RUNNING, PICKING_MARK }

    // ─── Injected dependencies ───────────────────────────────────────────────────
    private final Client                   client;
    private final ClientThread             clientThread;
    private final HumanizedInputDispatcher dispatcher;
    private final SceneScanner             sceneScanner;
    /** Supplier of the precomputed static-collision connected components.
     *  Used by {@link #tryPickupReachableMark} to skip marks that are
     *  geometrically near (Chebyshev distance) but not actually reachable
     *  from the player's current rooftop section. Each rooftop on Draynor
     *  is its own component because the obstacle between them is a
     *  transport (Climb / Cross / Jump / etc.), not a static walkable
     *  link. Without this, the radius scan happily clicks a mark on the
     *  next rooftop and the bot loops trying to walk into a wall.
     *  May return null during the ~4-second precompute window after
     *  plugin start — caller falls back to radius-only matching. */
    private final java.util.function.Supplier<
        net.runelite.client.plugins.recorder.nav.v2.collision.ConnectivityComponents>
                                           componentsSupplier;

    /** Default Chebyshev tile-radius for obstacle scene-scans when a course
     *  doesn't override it. Per-course override lives on
     *  {@link RooftopCourse#scanRadius} — Al-Kharid in particular has
     *  longer inter-obstacle hops than Draynor and needs a wider scan. */
    static final int DEFAULT_OBSTACLE_SCAN_RADIUS = 14;
    /** Stop the script if this many consecutive failed timeouts pile up
     *  on the same stage. Each retry has a fresh camera angle + live-
     *  sampled clickbox so transient failures self-recover, but a real
     *  obstruction (object missing, verb renamed, wall changed) would
     *  otherwise loop forever. Threshold picked so an honestly-flaky
     *  obstacle still gets several chances. */
    static final int MAX_STAGE_FAILS             = 6;

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
    /** Stage index of the most recently completed obstacle. Set by
     *  {@link #clearLastObstacle()} before zeroing the active-click
     *  fields, so we still remember "what was the last obstacle I
     *  finished" after the active-click bookkeeping has been wiped.
     *  Used by {@link #detectCurrentStage()} to advance to the next
     *  stage when the player has just landed on a transport's
     *  un-mapped landing tile (Climb-up, Jump-up, Climb-down). */
    private int         lastSuccessfulStage = UNKNOWN;
    /** Connectivity component the player was in at the moment we
     *  dispatched the active click. Used by {@link #detectCurrentStage()}
     *  and {@link #handleObstacleTimeout(long)} to detect "the transport
     *  actually executed" — every agility obstacle bridges two static-
     *  collision components (the obstacle itself is a transport, not
     *  walkable terrain), so a component change after dispatch is the
     *  unambiguous signal that the click took effect. {@code -1} means
     *  "no dispatch in flight" or "components precompute not ready". */
    private int         clickDispatchComponent = -1;
    /** Consecutive obstacle-timeout recoveries on the SAME stage. Resets
     *  on a successful stage clear (see {@link #clearLastObstacleSuccess()}).
     *  Stops the script when it exceeds {@link #MAX_STAGE_FAILS}. */
    private int         stageFailCount         = 0;
    private int         stageFailStage         = UNKNOWN;

    // ─── Mark-pickup bookkeeping ─────────────────────────────────────────────────
    private WorldPoint markTileClicked;
    private long       markClickAt;
    private int        markCountBefore;

    // ─── Unmapped-tile bookkeeping ───────────────────────────────────────────────
    private WorldPoint unknownStageTile;
    private long       unknownStageSince;

    // ─── Lap-end residency bookkeeping ───────────────────────────────────────────
    // If the player is on a lapEndTile we keep walking back to start, but cap
    // the time spent there so a stuck walker doesn't loop forever.
    private WorldPoint lapEndTileSeen;
    private long       lapEndSince;

    // ─── Lap tracking ────────────────────────────────────────────────────────────
    private int  lapsCompleted;
    private long lastLapCompletedAt;
    private int  marksPicked;

    public RooftopAgilityScript(Client client, ClientThread clientThread,
                                HumanizedInputDispatcher dispatcher,
                                java.util.function.Supplier<
                                    net.runelite.client.plugins.recorder.nav.v2.collision.ConnectivityComponents>
                                    componentsSupplier)
    {
        this.client             = client;
        this.clientThread       = clientThread;
        this.dispatcher         = dispatcher;
        this.sceneScanner       = new SceneScanner(client);
        this.componentsSupplier = componentsSupplier;
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

        RooftopCourse c = courses.get(selectedCourse);
        if (c == null)
        {
            status.set("Course not configured: " + selectedCourse
                + " — drop a JSON in ~/.runelite/recorder/rooftops/");
            log.info("[rooftop-agility] no definition for {} — see RooftopCourseLoader docs",
                selectedCourse);
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
            || c.approachTiles.contains(here)
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
        this.lastClickedNode        = null;
        this.lastClickedStage       = UNKNOWN;
        this.lastSuccessfulStage    = UNKNOWN;
        this.lastObstacleClickAt    = 0L;
        this.clickDispatchComponent = -1;
        this.stageFailCount         = 0;
        this.stageFailStage         = UNKNOWN;
        this.markTileClicked      = null;
        this.markClickAt          = 0L;
        this.markCountBefore      = 0;
        this.unknownStageTile     = null;
        this.unknownStageSince    = 0L;
        this.lapEndTileSeen       = null;
        this.lapEndSince          = 0L;
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
        if (stage == UNKNOWN)
        {
            // Player isn't on a recorded stage tile and we don't have a
            // confirmed transport (component change) for an in-flight
            // click. Common reasons: walking back to start after a lap
            // (between Crate landing on plane=1 and reaching a plane=0
            // start tile), or the click is still in flight and the
            // player hasn't moved into the destination component yet.
            // Sleep one short tick — we'll re-evaluate next pass once
            // the player reaches a known tile.
            nextActionAt = now + 600;
            return;
        }
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

    /** Current static-collision component of the player tile, or {@code -1}
     *  if components aren't ready yet (the ~4-second precompute window
     *  after plugin start) or the player tile isn't in any component. */
    private int currentPlayerComponent()
    {
        if (componentsSupplier == null) return -1;
        var components = componentsSupplier.get();
        if (components == null) return -1;
        Player p = client.getLocalPlayer();
        if (p == null) return -1;
        WorldPoint here = p.getWorldLocation();
        if (here == null) return -1;
        return components.componentOf(here);
    }

    /** True iff the player's current connectivity component is different
     *  from {@link #clickDispatchComponent}. Every agility obstacle is a
     *  transport that bridges two static-collision components, so this is
     *  the reliable "the click's transport actually executed" signal —
     *  it doesn't confuse "click failed at dispatcher" (player still in
     *  the same component) with "Cross succeeded" (player on the other
     *  rooftop = different component). */
    private boolean componentChangedSinceDispatch()
    {
        if (clickDispatchComponent < 0) return false;
        int now = currentPlayerComponent();
        return now >= 0 && now != clickDispatchComponent;
    }

    private int detectCurrentStage()
    {
        Player p = client.getLocalPlayer();
        if (p == null) return UNKNOWN;
        WorldPoint here = p.getWorldLocation();
        Integer mapped = course.stageByTile.get(here);

        // Cross/Balance/Jump transports leave the player on a tile that's
        // still part of the CURRENT stage's recorded stageTiles (the
        // destination side of the rope/wall is the same group as the
        // approach side). Without this check, detectCurrentStage would
        // keep returning the just-clicked stage and the script would
        // dispatch the same click in a loop. Component change is the
        // unambiguous "we crossed" signal.
        if (mapped != null && lastClickedNode != null
            && mapped == lastClickedStage
            && componentChangedSinceDispatch())
        {
            return (lastClickedStage + 1) % course.nodes.size();
        }
        if (mapped != null) return mapped;

        // Unmapped tile. Two legitimate cases land here:
        //   1. Transport landing (Climb-up plane=0→3, Climb-down plane=3→1)
        //      — component changed, advance to next stage.
        //   2. Walking back to start after a lap (plane=1 ledge, then
        //      plane=0 courtyard, not on any startTile yet) — no click
        //      in flight, no advance.
        // The previous implementation unconditionally fell back to
        // (lastSuccessfulStage + 1), which is the source of the
        // Crate→Rough wall cascade: when Crate's click failed at the
        // dispatcher, clearLastObstacle promoted lastSuccessfulStage=6,
        // detectCurrentStage returned 0, and findObstacleTile spammed
        // "Rough wall not on scene" for as long as the player was on
        // plane=3. Now we require a real transport signal (component
        // change) before advancing.
        if (lastClickedNode != null && componentChangedSinceDispatch())
        {
            return (lastClickedStage + 1) % course.nodes.size();
        }
        return UNKNOWN;
    }

    private Set<WorldPoint> expectedSuccessTiles(int stage)
    {
        // Always next stage's tiles (or lap-end for the final node). The
        // per-node successTiles field was always Set.of() in practice —
        // removed for clarity.
        if (stage + 1 < course.nodes.size()) return course.nodes.get(stage + 1).stageTiles;
        return course.lapEndTiles;
    }

    /** Clear the active-click bookkeeping for the SUCCESS path — promotes
     *  the just-cleared stage to {@link #lastSuccessfulStage} so the next
     *  tick's {@link #detectCurrentStage()} can advance to the following
     *  stage even if the player lands on an un-recorded transport-landing
     *  tile (Climb-up/Climb-down). Call this from every success branch
     *  in {@link #handleObstacleTimeout} and {@link #handleLapEnd}. */
    private void clearLastObstacleSuccess()
    {
        if (lastClickedStage != UNKNOWN) lastSuccessfulStage = lastClickedStage;
        // A successful clear means the obstacle is fundamentally clickable —
        // reset the fail counter so a previously-flaky stage doesn't get
        // remembered as broken on the next lap.
        stageFailCount = 0;
        stageFailStage = UNKNOWN;
        clearLastObstacle();
    }

    /** Clear the active-click bookkeeping WITHOUT marking the stage as
     *  successful. Use this from recovery/fail paths — if we promote
     *  {@link #lastSuccessfulStage} when the click actually failed at
     *  dispatcher level, the next tick's detect-stage fallback advances
     *  to the next obstacle and clickObstacle spams "obstacle not found
     *  on scene" warnings for an obstacle that's on the wrong plane.
     *  The Crate-cascade-to-Rough-wall loop was exactly this bug. */
    private void clearLastObstacle()
    {
        lastClickedNode        = null;
        lastClickedStage       = UNKNOWN;
        lastObstacleClickAt    = 0L;
        clickDispatchComponent = -1;
    }

    private void clickObstacle(int stage, RooftopNode node, long now)
    {
        WorldPoint targetTile = findObstacleTile(node);
        if (targetTile == null)
        {
            status.set("Obstacle off-scene: " + node.label);
            nextActionAt = now + 600;
            log.warn("[rooftop-agility] obstacle {} (objectId {}) not found on scene within {} tiles of player",
                node.label, node.objectId, course.scanRadius);
            return;
        }

        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GAME_OBJECT)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(targetTile)
            .verb(node.action)
            .build());

        lastClickedNode        = node;
        lastClickedStage       = stage;
        lastObstacleClickAt    = now;
        clickDispatchComponent = currentPlayerComponent();
        long delay = 600L + ThreadLocalRandom.current().nextLong(600L);    // 600..1200
        nextActionAt = now + delay;
        status.set("Clicked " + node.label + " (stage " + stage + ")");
        log.info("[rooftop-agility] click {} (stage {}, tile {}, verb \"{}\")",
            node.label, stage, targetTile, node.action);
    }

    /** Returns the actual scene tile of the obstacle for {@code node} —
     *  scene-wide, by object id, across all four object kinds.
     *
     *  <p>Why scan instead of trusting {@code node.objectTiles}: walls
     *  in particular attach to one side of a tile boundary in OSRS,
     *  and the side the engine reports often disagrees with what a
     *  recorded route ended up writing down. Looking for the object
     *  on a specific tile is fragile; finding it by id within a
     *  proximity radius mirrors how a player thinks ("the wall is
     *  right there, I just look at it"). The dispatcher then re-resolves
     *  the click on whichever tile we hand it.
     *
     *  <p>Agility obstacles live across {@link GameObject} (tightropes,
     *  gaps, crates), {@link WallObject} (rough wall, narrow wall,
     *  wall), {@link GroundObject} (some platforms), and
     *  {@link DecorativeObject} (rope/ladder decorations on a few
     *  courses). {@link SceneScanner#findObjectTileById} checks all
     *  four. */
    private WorldPoint findObstacleTile(RooftopNode node)
    {
        return sceneScanner.findObjectTileById(node.objectId, course.scanRadius);
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

        // Lap completion (backstop, timeout path): final obstacle clicked +
        // player landed on lapEndTiles but the player hasn't been observed
        // on lapEnd until timeoutMs elapsed (rare — e.g. lagged frame).
        // Normally handleLapEnd counts the lap first. Either way, clearing
        // lastClickedNode here means handleLapEnd's check below skips its
        // own increment on this tick → single count guaranteed.
        if (finalNodeClicked && course.lapEndTiles.contains(here))
        {
            lapsCompleted++;
            lastLapCompletedAt = now;
            log.info("[rooftop-agility] lap {} complete via timeout backstop (landed at {})",
                lapsCompleted, here);
            clearLastObstacle();
            return false;
        }

        if (expectedSuccessTiles(lastClickedStage).contains(here))
        {
            clearLastObstacleSuccess();
            return false;
        }

        // Real success via mapped tile lookup: player landed on a tile that
        // belongs to a DIFFERENT stage.
        Integer mappedStage = course.stageByTile.get(here);
        if (mappedStage != null && mappedStage != lastClickedStage)
        {
            clearLastObstacleSuccess();
            return false;
        }

        // Real success via connectivity-component change: every agility
        // obstacle is a transport between two static-collision components,
        // so the player ending up in a different component than where we
        // dispatched the click = transport executed. Covers Cross/Balance/
        // Jump destinations that fall inside the SAME stage's stageTiles
        // (rope endpoints) and Climb-up/Climb-down landings that fall on
        // un-recorded transport tiles.
        if (componentChangedSinceDispatch())
        {
            clearLastObstacleSuccess();
            return false;
        }

        // Player moved off the clicked obstacle's own stageTiles but isn't
        // on any other stage's tile AND hasn't changed component. This is
        // the dominant rooftop case: the obstacle's timeoutMs is shorter
        // than the post-obstacle walk gap. Just clear without marking
        // success — detectCurrentStage will require an actual component
        // change before advancing. No minimap recovery on rooftops.
        boolean stillOnClickedStage = course.nodes.get(lastClickedStage)
            .stageTiles.contains(here);
        if (!stillOnClickedStage)
        {
            clearLastObstacle();
            return false;
        }

        // Player is still standing on the clicked obstacle's own stageTiles
        // after timeoutMs — click really didn't take. Count this against
        // the per-stage fail budget; if we've blown through the budget,
        // stop with a diagnostic so a genuinely broken obstacle (object
        // renamed, verb changed, scene desync) doesn't loop forever.
        int failingStage = lastClickedStage;
        if (stageFailStage != failingStage)
        {
            stageFailStage = failingStage;
            stageFailCount = 0;
        }
        stageFailCount++;
        if (stageFailCount >= MAX_STAGE_FAILS)
        {
            status.set("Stuck on " + lastClickedNode.label + " (" + stageFailCount
                + " consecutive timeouts)");
            log.warn("[rooftop-agility] stage {} ({}) failed {} times in a row at {} — stopping",
                failingStage, lastClickedNode.label, stageFailCount, here);
            clearLastObstacle();
            running.set(false);
            state.set(State.IDLE);
            return true;
        }
        status.set("Obstacle timeout — retry " + stageFailCount + "/" + MAX_STAGE_FAILS);
        log.info("[rooftop-agility] obstacle timeout on {} (tile {}, fail {}/{}) — recovering",
            lastClickedNode.label, here, stageFailCount, MAX_STAGE_FAILS);
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
            && !course.approachTiles.contains(here)
            && !course.lapEndTiles.contains(here))
        {
            // Fall detection: any mid-course drop to plane 0 that doesn't
            // land on a recorded start/lap-end/fall tile is, by elimination,
            // a fall. Without this, the in-transit guard below would say
            // "we're between obstacles, keep going" and the main tick would
            // try to click rooftop objects from ground level forever. Reset
            // progression so the standard off-route recovery fires.
            boolean fellToGround = here.getPlane() == 0
                && (lastClickedNode != null || lastSuccessfulStage != UNKNOWN);
            if (fellToGround)
            {
                log.info("[rooftop-agility] fell to ground at {} — resetting progression", here);
                clearLastObstacle();
                lastSuccessfulStage = UNKNOWN;
                // fall through to the off-route recovery branch below
            }
            // Tile membership says "off-route", but this is also the
            // shape of a transport landing zone — Climb-up lands the
            // player on a roof-top tile we never recorded, ditto
            // Jump-up and Climb-down. If we have any progression
            // record (active click in flight, or a freshly-cleared
            // successful click), keep going: detectCurrentStage()
            // will fall through to the next stage and clickObstacle()
            // will find that obstacle on the scene from wherever the
            // player ended up. The obstacle-timeout handler is the
            // backstop for genuine stalls.
            else if (lastClickedNode != null || lastSuccessfulStage != UNKNOWN)
            {
                return false;
            }
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
        if (!course.lapEndTiles.contains(here))
        {
            lapEndTileSeen = null;     // reset residency counter
            return false;
        }

        // Lap completion — count it once, immediately on landing. Spec §15:
        // every clear-on-success must check lap-end first; this is the
        // single non-timeout success path.
        //
        // Dual-increment invariant: handleObstacleTimeout's final-node branch
        // also increments lapsCompleted, but only after calling
        // clearLastObstacle(). When that branch fires first (timeout path),
        // it nulls lastClickedNode, so handleLapEnd's `finalNodeClicked`
        // check below evaluates false on the same tick — no double-count.
        boolean finalNodeClicked = lastClickedNode != null
            && lastClickedNode == course.nodes.get(course.nodes.size() - 1);
        if (finalNodeClicked)
        {
            lapsCompleted++;
            lastLapCompletedAt = now;
            log.info("[rooftop-agility] lap {} complete (landed at {})", lapsCompleted, here);
            clearLastObstacle();
        }

        // Track residency. If we've been on this lapEnd tile (or any lapEnd
        // tile) for more than 8s, the walk-to-start is failing — stop with
        // a diagnostic instead of looping forever. Covers the cold-start
        // case (operator presses Start while standing on lapEnd) and the
        // pathfinder-fails case.
        if (lapEndTileSeen == null)
        {
            lapEndTileSeen = here;
            lapEndSince    = now;
        }
        if (now - lapEndSince > UNMAPPED_TILE_TIMEOUT_MS)
        {
            status.set("Stuck on lapEndTile " + here + " — walk-to-start failing");
            log.warn("[rooftop-agility] stranded on lapEndTile {} for >{} ms — stopping",
                here, UNMAPPED_TILE_TIMEOUT_MS);
            running.set(false);
            state.set(State.IDLE);
            return true;
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

        // In-transit between obstacles: tile not in stageByTile but we
        // know which stage to look at next (active click in flight, or
        // freshly-cleared successful click). detectCurrentStage()'s
        // fallback gives the main tick the right node; no need to time
        // out on the landing zone.
        if (lastClickedNode != null || lastSuccessfulStage != UNKNOWN)
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
        Player p = client.getLocalPlayer();
        if (p == null) return false;
        WorldPoint player = p.getWorldLocation();
        int plane = client.getPlane();
        if (player.getPlane() != plane) return false;

        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int baseX = client.getBaseX();
        int baseY = client.getBaseY();

        // Components let us reject marks that are geometrically close
        // (Chebyshev) but live on a different rooftop section — the static
        // collision data treats each rooftop as its own component because
        // the obstacle is a TRANSPORT, not walkable terrain. Without this
        // filter, the bot spots a mark on the next rooftop, dispatches a
        // Take, the engine walks to the closest reachable tile (which is
        // somewhere along the edge), the mark stays put, pickup times out,
        // we re-dispatch, and we never actually do the next obstacle.
        var components = componentsSupplier != null ? componentsSupplier.get() : null;
        int playerComponent = components != null ? components.componentOf(player) : -1;

        // Scan a Chebyshev radius around the player for Mark of Grace
        // ground items on the same plane and same connected component.
        // A scene scan generalises across courses; the component filter
        // makes it safe on rooftops.
        WorldPoint best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -MARK_SCAN_RADIUS; dx <= MARK_SCAN_RADIUS; dx++)
        {
            for (int dy = -MARK_SCAN_RADIUS; dy <= MARK_SCAN_RADIUS; dy++)
            {
                int wx = player.getX() + dx;
                int wy = player.getY() + dy;
                int sx = wx - baseX;
                int sy = wy - baseY;
                if (sx < 0 || sy < 0 || sx >= Constants.SCENE_SIZE || sy >= Constants.SCENE_SIZE) continue;
                Tile t = tiles[plane][sx][sy];
                if (t == null) continue;
                List<TileItem> items = t.getGroundItems();
                if (items == null || items.isEmpty()) continue;
                boolean has = false;
                for (TileItem ti : items)
                {
                    if (ti != null && ti.getId() == ITEM_MARK_OF_GRACE) { has = true; break; }
                }
                if (!has) continue;
                // Reachability filter: same static-collision component as
                // the player. If components are not yet built (precompute
                // still running), fall back to the radius-only check.
                if (components != null && playerComponent >= 0)
                {
                    int markComponent = components.componentOf(wx, wy, plane);
                    if (markComponent != playerComponent) continue;
                }
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                if (d < bestDist)
                {
                    bestDist = d;
                    best = new WorldPoint(wx, wy, plane);
                }
            }
        }
        if (best == null) return false;

        dispatcher.dispatch(ActionRequest.builder()
            .kind(ActionRequest.Kind.CLICK_GROUND_ITEM)
            .channel(ActionRequest.Channel.MOUSE)
            .tile(best)
            .itemId(ITEM_MARK_OF_GRACE)
            .verb("Take")
            .build());

        state.set(State.PICKING_MARK);
        markTileClicked = best;
        markClickAt     = now;
        markCountBefore = inventoryCount(ITEM_MARK_OF_GRACE);
        long delay = 600L + ThreadLocalRandom.current().nextLong(400L);   // 600..1000
        nextActionAt = now + delay;
        status.set("Picking up mark at " + best);
        log.info("[rooftop-agility] picking mark at {} (dist {})", best, bestDist);
        return true;
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

        // Cross-plane minimap walks silently fail in the engine. The previous
        // behaviour fired a doomed walk to a plane=0 tile while the player
        // was stuck on a rooftop (plane=3) and the bot looked like it was
        // "recovering" — but the click did nothing. Now we only dispatch
        // the walk when a startTile exists on the player's current plane;
        // otherwise we skip and let the next stage retry / fail counter
        // make progress.
        WorldPoint best = null;
        long bestDist = Long.MAX_VALUE;
        for (WorldPoint t : course.startTiles)
        {
            if (t.getPlane() != here.getPlane()) continue;
            long dx = t.getX() - here.getX();
            long dy = t.getY() - here.getY();
            long d  = dx * dx + dy * dy;
            if (d < bestDist) { bestDist = d; best = t; }
        }
        if (best == null)
        {
            log.info("[rooftop-agility] no startTile on player's plane ({}) — skipping recovery walk",
                here.getPlane());
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
        /** Tiles where the script considers the player "ready to start a
         *  lap." Wider than {@link #startTiles}; this is the zone the
         *  detector accepts when the player walks back from a Climb-down
         *  landing or stands somewhere reasonable near the first
         *  obstacle. Used by {@link #handleFallOrInvalidPosition} as the
         *  startTiles check. */
        final Set<WorldPoint>          approachTiles;
        /** Subset of {@link #approachTiles} used as walk-to targets when
         *  the script needs to recover to the start. Should be a small
         *  precise set (1–3 tiles) — the engine pathfinder gets confused
         *  by an over-broad target. */
        final Set<WorldPoint>          startTiles;
        final Set<WorldPoint>          validTiles;
        final Set<WorldPoint>          fallTiles;
        final Set<WorldPoint>          lapEndTiles;
        final List<RooftopNode>        nodes;
        final Map<WorldPoint, Integer> stageByTile;
        /** Chebyshev tile-radius for obstacle scene-scans on this course.
         *  Falls back to {@link #DEFAULT_OBSTACLE_SCAN_RADIUS} when the
         *  JSON omits it. */
        final int                      scanRadius;

        RooftopCourse(RooftopCourseId id, String label, int levelReq,
                      Set<WorldPoint> approachTiles, Set<WorldPoint> startTiles,
                      Set<WorldPoint> validTiles, Set<WorldPoint> fallTiles,
                      Set<WorldPoint> lapEndTiles, List<RooftopNode> nodes,
                      int scanRadius)
        {
            this.id            = id;
            this.label         = label;
            this.levelReq      = levelReq;
            this.approachTiles = Set.copyOf(approachTiles);
            this.startTiles    = Set.copyOf(startTiles);
            this.validTiles    = Set.copyOf(validTiles);
            this.fallTiles     = Set.copyOf(fallTiles);
            this.lapEndTiles   = Set.copyOf(lapEndTiles);
            this.nodes         = List.copyOf(nodes);
            this.scanRadius    = scanRadius > 0 ? scanRadius : DEFAULT_OBSTACLE_SCAN_RADIUS;

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
        final long            timeoutMs;

        RooftopNode(String label, int objectId, String action,
                    Set<WorldPoint> objectTiles, Set<WorldPoint> stageTiles,
                    long timeoutMs)
        {
            this.label       = label;
            this.objectId    = objectId;
            this.action      = action;
            this.objectTiles = Set.copyOf(objectTiles);
            this.stageTiles  = Set.copyOf(stageTiles);
            this.timeoutMs   = timeoutMs;
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
        if (c.nodes.isEmpty())         throw new IllegalStateException("nodes empty");
        if (c.startTiles.isEmpty())    throw new IllegalStateException("startTiles empty");
        if (c.approachTiles.isEmpty()) throw new IllegalStateException("approachTiles empty");
        if (c.lapEndTiles.isEmpty())   throw new IllegalStateException("lapEndTiles empty");

        // startTiles must be the precise walk-to subset of the broader
        // approach zone — never disjoint from it.
        for (WorldPoint t : c.startTiles)
        {
            if (!c.approachTiles.contains(t))
            {
                throw new IllegalStateException(
                    "startTile " + t + " is not in approachTiles");
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

    // ─── COURSES map ─────────────────────────────────────────────────────────────
    //
    // Built once per script instance: hardcoded Draynor as the always-
    // present baseline, then any *.json files under
    // {@code ~/.runelite/recorder/rooftops/} layered on top (JSON wins on
    // collision so the user can override built-ins without recompiling).
    // Instance-scoped rather than static-final because the JSON loader does
    // file IO that can't run in a static initialiser at class load.

    private final Map<RooftopCourseId, RooftopCourse> courses = buildCourses();

    private static Map<RooftopCourseId, RooftopCourse> buildCourses()
    {
        Map<RooftopCourseId, RooftopCourse> m = new EnumMap<>(RooftopCourseId.class);
        buildHardcodedCourses(m);
        m.putAll(RooftopCourseLoader.loadAll());
        return Collections.unmodifiableMap(m);
    }

    private static void buildHardcodedCourses(Map<RooftopCourseId, RooftopCourse> m)
    {

        // ── Draynor Rooftop ──────────────────────────────────────────────────────
        //
        // Captured 2026-05-21 from the user's:
        //   • Recorded route at ~/.runelite/sequencer/routes/
        //       draynor_agility_rooftop_course.txt  (stage areas per obstacle)
        //   • ClickInspector log entries (objectIds + verbs + obstacle scene coords)
        //   • TransportObserver entries in worldmap/transports.json (5 obstacles
        //     with full id/verb/from/to records — Climb/Cross/Jump/Climb-down)
        //
        // Scene→world base computed from click-inspector vs. observer:
        //   baseX=3056, baseY=3200 (Draynor agility scene at capture time).
        //
        // Per-node objectTiles is advisory only — kept for documentation /
        // validateCourse, but obstacle lookup at run time goes through
        // SceneScanner.findObjectTileById(node.objectId, OBSTACLE_SCAN_RADIUS).
        // Object ids alone are enough to disambiguate (every Draynor obstacle
        // has a distinct id — the two tightropes are 11405 and 11406, etc.).

        List<RooftopNode> draynorNodes = List.of(
            new RooftopNode("Rough wall", 11404, "Climb",
                tiles(3103, 3279, 0),
                tiles(3102, 3279, 0,  3103, 3278, 0,  3103, 3279, 0,  3103, 3280, 0,
                      3104, 3278, 0,  3104, 3279, 0,  3104, 3280, 0),
                4_000L),

            new RooftopNode("Tightrope 1", 11405, "Cross",
                tiles(3098, 3277, 3),
                tiles(3098, 3277, 3,  3099, 3277, 3,  3099, 3278, 3,
                      3100, 3277, 3,  3100, 3278, 3),
                7_000L),

            new RooftopNode("Tightrope 2", 11406, "Cross",
                tiles(3092, 3276, 3),
                tiles(3090, 3276, 3,  3091, 3276, 3,  3092, 3276, 3),
                7_000L),

            new RooftopNode("Narrow wall", 11430, "Balance",
                tiles(3089, 3264, 3),
                tiles(3089, 3264, 3,  3089, 3265, 3),
                5_000L),

            new RooftopNode("Wall", 11630, "Jump-up",
                tiles(3088, 3256, 3),
                tiles(3088, 3256, 3,  3088, 3257, 3),
                4_000L),

            new RooftopNode("Gap", 11631, "Jump",
                tiles(3095, 3255, 3),
                tiles(3094, 3255, 3,  3095, 3255, 3),
                4_000L),

            // Crate — Climb-down completes the lap. Bumped timeoutMs to 6s
            // because the descent animation alone is ~3s on top of the
            // click attempt; the previous 4s margin tripped a false
            // "timeout" recovery in the middle of legitimate descents.
            new RooftopNode("Crate", 11632, "Climb-down",
                tiles(3102, 3261, 3),
                tiles(3100, 3260, 3,  3100, 3261, 3,
                      3101, 3260, 3,  3101, 3261, 3,
                      3102, 3261, 3),
                6_000L)
        );

        // validTiles = union of every stage tile + lapEnd tile.
        Set<WorldPoint> draynorValid = new java.util.HashSet<>();
        for (RooftopNode n : draynorNodes) draynorValid.addAll(n.stageTiles);
        draynorValid.add(new WorldPoint(3102, 3261, 1));   // lapEnd at plane=1

        // approachTiles — broad "stage 0 ready" zone the detector accepts.
        // startTiles — precise walk-to target for recovery walks.
        Set<WorldPoint> draynorApproach = tiles(
            3102, 3279, 0,  3103, 3278, 0,  3103, 3279, 0,  3103, 3280, 0,
            3104, 3278, 0,  3104, 3279, 0,  3104, 3280, 0);
        Set<WorldPoint> draynorStart = tiles(3103, 3279, 0);

        RooftopCourse draynor = new RooftopCourse(
            RooftopCourseId.DRAYNOR, "Draynor Rooftop", 1,
            draynorApproach,
            draynorStart,
            draynorValid,
            // fallTiles — empty for v1. The off-route branch in
            // handleFallOrInvalidPosition catches falls (player not in
            // validTiles/approachTiles/lapEndTiles) and dispatches recovery.
            Set.of(),
            // lapEndTiles — single tile where the Crate Climb-down lands the
            // player. TransportObserver logged dest at (3102, 3261, plane=1).
            tiles(3102, 3261, 1),
            draynorNodes,
            DEFAULT_OBSTACLE_SCAN_RADIUS);
        validateCourse(draynor);
        m.put(RooftopCourseId.DRAYNOR, draynor);
    }
}
