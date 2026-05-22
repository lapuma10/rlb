package net.runelite.client.plugins.recorder.agility;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.scripts.RooftopAgilityScript.RooftopCourseId;

@Slf4j
public class AgilityCaptureSession
{
    private final Client client;
    private final EventBus eventBus;
    private CaptureModel model;        // (re)built on start()
    private boolean active = false;

    public AgilityCaptureSession(Client client, EventBus eventBus)
    {
        this.client = client;
        this.eventBus = eventBus;
    }

    public void start(RooftopCourseId id, String label, int agilityLevelReq, int expectedObstacleCount)
    {
        if (active)
        {
            log.warn("[agility-capture] start() called while already active — ignoring");
            return;
        }
        model = new CaptureModel(id, label, agilityLevelReq, expectedObstacleCount);
        active = true;
        eventBus.register(this);
        log.info("[agility-capture] started id={} label='{}' level={} expectedCount={}", id, label, agilityLevelReq, expectedObstacleCount);
    }

    public void stop()
    {
        if (!active) return;
        eventBus.unregister(this);
        active = false;
        log.info("[agility-capture] stopped");
    }

    public boolean isActive() { return active; }
    public CaptureModel getModel() { return model; }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked e)
    {
        if (!active) return;

        // §6.1 — only game-object actions; ignore EXAMINE and everything else
        MenuEntry entry = e.getMenuEntry();
        MenuAction type = entry.getType();
        boolean isObjectClick =
               type == MenuAction.GAME_OBJECT_FIRST_OPTION
            || type == MenuAction.GAME_OBJECT_SECOND_OPTION
            || type == MenuAction.GAME_OBJECT_THIRD_OPTION
            || type == MenuAction.GAME_OBJECT_FOURTH_OPTION
            || type == MenuAction.GAME_OBJECT_FIFTH_OPTION;
        if (!isObjectClick) return;

        // §6.1 — two-click race guard: discard lap and reset
        if (model.pendingClick != null)
        {
            model.currentLapDirty = true;
            model.currentLapTiles.clear();
            model.currentLapObs.clear();
            model.pendingClick = null;
            model.state = LapState.OFF_COURSE;
            log.info("[agility-capture] two clicks queued — lap discarded. Walk to start.");
            return;
        }

        // Resolve world tile from menu entry (mirrors ClickResolver.java:111-113)
        int objectId   = entry.getIdentifier();
        String verb    = entry.getOption();
        int worldX     = client.getBaseX() + entry.getParam0();
        int worldY     = client.getBaseY() + entry.getParam1();
        int plane      = client.getPlane();
        WorldPoint objectTile = new WorldPoint(worldX, worldY, plane);

        // Capture source tile (player position at click time)
        if (client.getLocalPlayer() == null) return;
        WorldPoint sourceTile = client.getLocalPlayer().getWorldLocation();

        // Build PendingClick and store on model
        long now        = System.currentTimeMillis();
        long deadlineMs = now + perObjectDeadline(objectId);
        long xpBefore   = client.getSkillExperience(Skill.AGILITY);

        model.pendingClick = new PendingClick(objectId, verb, objectTile, sourceTile,
                                               now, deadlineMs, xpBefore);
    }

    @Subscribe
    public void onStatChanged(StatChanged e)
    {
        if (!active) return;

        // §7.1 — filter + validate
        if (e.getSkill() != Skill.AGILITY) return;
        PendingClick pc = model.pendingClick;
        if (pc == null) return;
        if (pc.outcome != ClickOutcome.PENDING) return;

        long now = System.currentTimeMillis();
        if (now > pc.deadlineMs) return;                  // expired; Task 8 will sweep
        if (e.getXp() <= pc.xpBefore) return;             // not an XP increase (drain or replay)

        if (client.getLocalPlayer() == null) return;
        WorldPoint successTile = client.getLocalPlayer().getWorldLocation();
        WorldPoint sourceTile  = pc.sourceTile;

        // §7.1 — build per-lap observation
        ObstacleObservation obs = new ObstacleObservation();
        obs.orderIndex = model.currentLapObs.size();
        obs.objectIds.add(pc.objectId);
        obs.verbs.add(pc.verb);
        obs.objectLabels.add(safeObjectLabel(pc.objectId));
        obs.stageTiles.add(sourceTile);
        obs.objectTiles.add(pc.objectTile);
        obs.successTiles.add(successTile);
        obs.maxClickToXpMs = now - pc.clickAtMs;
        obs.successCount   = 1;
        obs.signature      = new ObstacleSignature(pc.objectId, pc.objectTile, pc.verb);
        model.currentLapObs.add(obs);

        // Ensure stage + success tiles land in the lap tile buffer (spec §7.1 lines 259-263)
        model.currentLapTiles.add(sourceTile);
        model.currentLapTiles.add(successTile);

        pc.outcome = ClickOutcome.SUCCESS;
        model.pendingClick = null;

        // §7.1 — ARMED → IN_LAP transition; first-ever SUCCESS captures startTile + approachTiles
        if (model.state == LapState.ARMED)
        {
            model.state = LapState.IN_LAP;
            if (model.startTiles.isEmpty())               // first SUCCESS this session
            {
                model.startTiles.add(sourceTile);
                for (CaptureModel.Sample s : model.approachRing)
                {
                    model.approachTiles.add(s.p);
                }
            }
        }

        // §7.1 — lap completion trigger
        if (model.currentLapObs.size() == model.expectedObstacleCount)
        {
            handleLapComplete();
        }
    }

    @Subscribe
    public void onGameTick(GameTick e)
    {
        if (!active) return;

        if (client.getLocalPlayer() == null) return;
        WorldPoint p = client.getLocalPlayer().getWorldLocation();
        long now = System.currentTimeMillis();

        if (model.obstacles.isEmpty())
        {
            model.approachRing.addLast(new CaptureModel.Sample(now, p));
            while (!model.approachRing.isEmpty() && now - model.approachRing.peekFirst().t > 10_000L)
            {
                model.approachRing.pollFirst();
            }
        }

        if (model.state == LapState.IN_LAP)
        {
            model.currentLapTiles.add(p);
        }

        maybeExpirePendingClick(now, p);
    }

    private void maybeExpirePendingClick(long now, WorldPoint p)
    {
        // Task 8 — IGNORED / BROKEN_LAP / UNKNOWN classifier.
    }

    private long perObjectDeadline(int objectId)
    {
        return 12_000L;       // default; per-object shrink lands in Task 8
    }

    private String safeObjectLabel(int objectId)
    {
        try
        {
            ObjectComposition c = client.getObjectDefinition(objectId);
            if (c == null) return "";
            String name = c.getName();
            return name == null ? "" : name;
        }
        catch (Exception ex)
        {
            return "";
        }
    }

    private void handleLapComplete()
    {
        // Task 9 — canonical sequence comparison + merge.
    }
}
