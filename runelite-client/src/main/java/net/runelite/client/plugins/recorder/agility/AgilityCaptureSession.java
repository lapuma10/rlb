package net.runelite.client.plugins.recorder.agility;

import java.util.ArrayList;
import java.util.List;
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
        PendingClick pc = model.pendingClick;
        if (pc == null) return;
        if (now <= pc.deadlineMs) return;

        if (p.equals(pc.sourceTile))
        {
            pc.outcome = ClickOutcome.IGNORED;
            model.pendingClick = null;
            return;
        }

        boolean onRoute =
               model.startTiles.contains(p)
            || model.approachTiles.contains(p)
            || anyKnownStageTile(p);

        pc.outcome = onRoute ? ClickOutcome.UNKNOWN : ClickOutcome.BROKEN_LAP;
        model.pendingClick = null;
        model.currentLapDirty = true;
        model.currentLapTiles.clear();
        model.currentLapObs.clear();
        model.state = LapState.OFF_COURSE;

        log.info("[agility-capture] lap broken ({}). Walk to course start.", pc.outcome);
    }

    private boolean anyKnownStageTile(WorldPoint p)
    {
        for (ObstacleObservation o : model.obstacles)
        {
            if (o.stageTiles.contains(p)) return true;
        }
        for (ObstacleObservation o : model.currentLapObs)
        {
            if (o.stageTiles.contains(p)) return true;
        }
        return false;
    }

    private long perObjectDeadline(int objectId)
    {
        for (ObstacleObservation o : model.obstacles)
        {
            if (o.objectIds.contains(objectId) && o.maxClickToXpMs > 0L)
            {
                long shrunk = Math.round(o.maxClickToXpMs * 1.5);
                return Math.max(8_000L, Math.min(12_000L, shrunk));
            }
        }
        return 12_000L;
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
        List<ObstacleSignature> sequence = new ArrayList<>();
        for (ObstacleObservation o : model.currentLapObs)
        {
            sequence.add(o.signature);
        }

        if (model.canonicalSequence == null)
        {
            model.canonicalSequence = sequence;
            mergeObsIntoModel(model.currentLapObs);
            model.validTiles.addAll(model.currentLapTiles);
            model.cleanMatchingLaps = 1;
            model.lapEndTile = lastSuccessTile(model.currentLapObs);
            log.info("[agility-capture] canonical sequence established ({} obstacles)", sequence.size());
        }
        else if (signaturesMatch(sequence, model.canonicalSequence))
        {
            mergeObsIntoModel(model.currentLapObs);
            model.validTiles.addAll(model.currentLapTiles);
            model.cleanMatchingLaps++;
            log.info("[agility-capture] matching clean lap: {}", model.cleanMatchingLaps);
        }
        else
        {
            int diff = firstDiffIndex(sequence, model.canonicalSequence);
            log.warn("[agility-capture] lap sequence mismatch at index {} — discarded", diff);
        }

        model.currentLapTiles.clear();
        model.currentLapObs.clear();
        model.state = LapState.ARMED;
        model.currentLapDirty = false;
    }

    private static boolean signaturesMatch(List<ObstacleSignature> a, List<ObstacleSignature> b)
    {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++)
        {
            if (!a.get(i).equals(b.get(i))) return false;
        }
        return true;
    }

    private static int firstDiffIndex(List<ObstacleSignature> a, List<ObstacleSignature> b)
    {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++)
        {
            if (!a.get(i).equals(b.get(i))) return i;
        }
        return a.size() == b.size() ? -1 : n;       // length mismatch — first extra index
    }

    private void mergeObsIntoModel(List<ObstacleObservation> lapObs)
    {
        for (ObstacleObservation o : lapObs)
        {
            ObstacleObservation existing = findOrCreateByOrderIndex(o.orderIndex);
            existing.objectIds.addAll(o.objectIds);
            existing.verbs.addAll(o.verbs);
            existing.objectLabels.addAll(o.objectLabels);
            existing.stageTiles.addAll(o.stageTiles);
            existing.objectTiles.addAll(o.objectTiles);
            existing.successTiles.addAll(o.successTiles);
            existing.maxClickToXpMs = Math.max(existing.maxClickToXpMs, o.maxClickToXpMs);
            existing.successCount++;
            if (existing.signature == null)
            {
                existing.signature = o.signature;       // first lap defines it
            }
        }
    }

    private ObstacleObservation findOrCreateByOrderIndex(int orderIndex)
    {
        for (ObstacleObservation existing : model.obstacles)
        {
            if (existing.orderIndex == orderIndex) return existing;
        }
        ObstacleObservation fresh = new ObstacleObservation();
        fresh.orderIndex = orderIndex;
        model.obstacles.add(fresh);
        return fresh;
    }

    private static WorldPoint lastSuccessTile(List<ObstacleObservation> lapObs)
    {
        if (lapObs.isEmpty()) return null;
        ObstacleObservation last = lapObs.get(lapObs.size() - 1);
        return last.successTiles.iterator().next();    // exactly one within a single lap
    }
}
