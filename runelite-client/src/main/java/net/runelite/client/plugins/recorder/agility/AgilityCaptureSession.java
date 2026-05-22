package net.runelite.client.plugins.recorder.agility;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
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
        // Task 6
    }

    @Subscribe
    public void onStatChanged(StatChanged e)
    {
        if (!active) return;
        // Task 7
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
}
