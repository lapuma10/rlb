package net.runelite.client.plugins.recorder.worldmap;

import java.util.List;
import java.util.Optional;
import org.junit.Test;
import net.runelite.api.coords.WorldPoint;
import static org.junit.Assert.*;

public class EntityIndexTest
{
    @Test
    public void findNpcsByName_returnsAllSightings()
    {
        EntityIndex idx = new EntityIndex();
        idx.recordNpcSighting(4626, "Cook", new WorldPoint(3208, 3213, 0), 1000);
        idx.recordNpcSighting(4626, "Cook", new WorldPoint(3211, 3214, 0), 2000);
        idx.recordNpcSighting(0,    "Goblin", new WorldPoint(3252, 3253, 0), 3000);

        List<EntitySighting> cooks = idx.findNpcsByName("Cook");
        assertEquals(1, cooks.size());           // single id-keyed entry, updated
        assertEquals(2, cooks.get(0).seenCount);
        assertEquals(2000, cooks.get(0).lastSeenAt);
        assertEquals(new WorldPoint(3211, 3214, 0), cooks.get(0).lastTile);
    }

    @Test
    public void nearestNpc_breaksTiesByRecency()
    {
        EntityIndex idx = new EntityIndex();
        // Two different Cook NPCs at different tiles.
        idx.recordNpcSighting(4626, "Cook", new WorldPoint(3208, 3213, 0), 1000);
        idx.recordNpcSighting(9999, "Cook", new WorldPoint(3300, 3300, 0), 2000);

        Optional<EntitySighting> nearest = idx.nearestNpc("Cook",
            new WorldPoint(3210, 3215, 0));
        assertTrue(nearest.isPresent());
        assertEquals(4626, nearest.get().id);    // closer NPC wins
    }

    @Test
    public void unknownName_returnsEmpty()
    {
        EntityIndex idx = new EntityIndex();
        assertTrue(idx.findNpcsByName("Nonexistent").isEmpty());
    }

    @Test
    public void findNpcsByName_dedupesRepeatRecordsOfSameId()
    {
        // Pass-3 P1: byName tracks every record() call. The findByName
        // dedupe step (added 2026-05-07) ensures three sightings of the
        // same Cook id surface as ONE entry, not three. A regression
        // that drops the dedupe makes nearestNpc / findNpcsByName
        // double-count the same entity.
        EntityIndex idx = new EntityIndex();
        WorldPoint t1 = new WorldPoint(3208, 3213, 0);
        WorldPoint t2 = new WorldPoint(3211, 3214, 0);
        WorldPoint t3 = new WorldPoint(3215, 3215, 0);
        idx.recordNpcSighting(4626, "Cook", t1, 1L);
        idx.recordNpcSighting(4626, "Cook", t2, 2L);
        idx.recordNpcSighting(4626, "Cook", t3, 3L);
        List<EntitySighting> cooks = idx.findNpcsByName("Cook");
        assertEquals("three sightings of one id collapse to one entry", 1, cooks.size());
        assertEquals(t3, cooks.get(0).lastTile);
        assertEquals(3, cooks.get(0).seenCount);
    }

    @Test
    public void findObjectsByName_dedupesRepeatRecordsOfSameId()
    {
        EntityIndex idx = new EntityIndex();
        WorldPoint t1 = new WorldPoint(3185, 3438, 0);
        WorldPoint t2 = new WorldPoint(3185, 3439, 0);
        idx.recordObjectSighting(10583, "Bank booth", t1, 1L);
        idx.recordObjectSighting(10583, "Bank booth", t2, 2L);
        List<EntitySighting> booths = idx.findObjectsByName("Bank booth");
        assertEquals("repeat object sightings collapse to one entry", 1, booths.size());
    }
}
