package net.runelite.client.plugins.recorder.worldmap;

import java.util.List;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransportIndexTest
{
    @Test
    public void addingNewEdge_storesIt()
    {
        TransportIndex idx = new TransportIndex();
        idx.add(sampleEdge(3208, 3216, "Open", 1530, 1_000L));

        assertEquals(1, idx.size());
        assertEquals(1, idx.getAll().iterator().next().seenCount());
    }

    @Test
    public void addingSameKey_bumpsSeenCount_doesNotDuplicate()
    {
        TransportIndex idx = new TransportIndex();
        idx.add(sampleEdge(3208, 3216, "Open", 1530, 1_000L));
        idx.add(sampleEdge(3208, 3216, "Open", 1530, 2_000L));
        idx.add(sampleEdge(3208, 3216, "Open", 1530, 3_000L));

        assertEquals("merge keeps a single entry", 1, idx.size());
        TransportEdge e = idx.getAll().iterator().next();
        assertEquals("seenCount accumulates across observations", 3, e.seenCount());
        assertEquals("lastSeenAtMs tracks the latest observation",
            3_000L, e.lastSeenAtMs());
    }

    @Test
    public void differentObjectIdAtSameTile_storedSeparately()
    {
        TransportIndex idx = new TransportIndex();
        idx.add(sampleEdge(3208, 3216, "Open", 1530, 1_000L));
        idx.add(sampleEdge(3208, 3216, "Open", 1531, 1_000L));

        assertEquals(2, idx.size());
    }

    @Test
    public void getOutgoing_returnsEdgesFromMatchingTile()
    {
        TransportIndex idx = new TransportIndex();
        idx.add(sampleEdge(3208, 3216, "Open", 1530, 1_000L));
        idx.add(sampleEdge(3208, 3216, "Climb-up", 16671, 1_000L));
        idx.add(sampleEdge(3300, 3300, "Open", 1530, 1_000L));

        List<TransportEdge> out = idx.getOutgoing(new WorldPoint(3208, 3216, 0));

        assertEquals(2, out.size());
    }

    @Test
    public void replaceAll_clearsThenInserts()
    {
        TransportIndex idx = new TransportIndex();
        idx.add(sampleEdge(3208, 3216, "Open", 1530, 1_000L));

        idx.replaceAll(List.of(
            sampleEdge(9999, 9999, "Climb-up", 16671, 5_000L)));

        assertEquals(1, idx.size());
        TransportEdge e = idx.getAll().iterator().next();
        assertEquals("Climb-up", e.verb());
    }

    private static TransportEdge sampleEdge(int x, int y, String verb,
                                            int objectId, long timestampMs)
    {
        WorldPoint from = new WorldPoint(x, y, 0);
        WorldPoint to = new WorldPoint(x, y + 1, 0);
        return new TransportEdge(
            from, to,
            objectId, "Door", verb,
            x, y, "GAME_OBJECT_FIRST_OPTION",
            from, from.getRegionID(),
            1, timestampMs, 1_200L);
    }
}
