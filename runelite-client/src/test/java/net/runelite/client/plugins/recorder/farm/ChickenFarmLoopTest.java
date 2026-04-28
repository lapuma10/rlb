package net.runelite.client.plugins.recorder.farm;

import java.util.List;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.transport.Waypoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChickenFarmLoopTest
{
    private static final WorldArea BANK = new WorldArea(3091, 3243, 7, 5, 2);
    private static final WorldArea PEN  = new WorldArea(3232, 3293, 8, 8, 0);
    private static final List<Waypoint> ROUTE = List.of(
        Waypoint.walkArea("step1", new WorldArea(3204, 3208, 8, 8, 0)),
        Waypoint.transport(new WorldPoint(3239, 3295, 0),
            Waypoint.TransportKind.OPEN, "Open"));

    @Test
    public void inPenWithFreeSlotsKills()
    {
        assertEquals(ChickenFarmLoop.State.KILLING,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3236, 3296, 0), 5));
    }

    @Test
    public void inPenWithFullInvWalksToBank()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_BANK,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3236, 3296, 0), 0));
    }

    @Test
    public void inBankWithItemsBanks()
    {
        assertEquals(ChickenFarmLoop.State.BANKING,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3094, 3245, 2), 0));
    }

    @Test
    public void inBankWithEmptyInvWalksToPen()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_PEN,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3094, 3245, 2), 28));
    }

    @Test
    public void onPathFullInvHeadsToBank()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_BANK,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3208, 3210, 0), 0));
    }

    @Test
    public void onPathEmptyInvHeadsToPen()
    {
        assertEquals(ChickenFarmLoop.State.WALKING_TO_PEN,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(3208, 3210, 0), 28));
    }

    @Test
    public void unknownLocationAborts()
    {
        assertEquals(ChickenFarmLoop.State.ABORTED,
            ChickenFarmLoop.decideResume(BANK, PEN, ROUTE,
                new WorldPoint(2000, 2000, 0), 28));
    }
}
