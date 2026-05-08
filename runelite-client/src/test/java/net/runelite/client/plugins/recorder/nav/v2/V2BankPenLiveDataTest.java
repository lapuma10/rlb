package net.runelite.client.plugins.recorder.nav.v2;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.recorder.nav.BehaviorMode;
import net.runelite.client.plugins.recorder.worldmap.MapStore;
import net.runelite.client.plugins.recorder.worldmap.RegionIds;
import net.runelite.client.plugins.recorder.worldmap.TransportEdge;
import net.runelite.client.plugins.recorder.worldmap.TransportIndex;
import net.runelite.client.plugins.recorder.worldmap.TransportIO;
import net.runelite.client.plugins.recorder.worldmap.WorldMemoryConfig;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Reproduces the live bank → pen failure offline using the user's
 *  actual recorded snapshots + transport index from
 *  ~/.runelite/recorder/worldmap/. Skipped if the data files are
 *  not present (CI / fresh checkout). Use to debug planner failures
 *  without needing to launch the live client. */
public class V2BankPenLiveDataTest
{
    private static final File WORLDMAP_ROOT = new File(
        System.getProperty("user.home") + "/.runelite/recorder/worldmap");
    private static final WorldMemoryConfig WM = new WorldMemoryConfig();

    @Test
    public void plan_bankToPen_offlineReproduction()
    {
        Assume.assumeTrue("live worldmap data not present", WORLDMAP_ROOT.isDirectory());

        // Eagerly load the 3×3 region grid around Lumbridge (rx=50, ry=50).
        MapStore store = new MapStore(WM);
        store.setRootDir(WORLDMAP_ROOT);
        int loaded = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
            {
                int regionId = ((50 + dx) << 8) | ((50 + dy) & 0xff);
                if (store.loadFromDisk(WORLDMAP_ROOT, regionId)) loaded++;
            }
        Assume.assumeTrue("at least 4 regions must load", loaded >= 4);

        // Load all transport edges.
        TransportIndex idx = new TransportIndex();
        var edges = TransportIO.readAll(WORLDMAP_ROOT);
        Assume.assumeFalse("transport index empty", edges.isEmpty());
        for (TransportEdge e : edges) idx.add(e);

        WorldPoint from = new WorldPoint(3209, 3219, 2);   // bank
        WorldPoint to = new WorldPoint(3235, 3295, 0);     // pen

        V2Planner planner = new V2Planner(store, idx, WM, new RouteHistory());
        V2Path p = planner.plan(from, to, BehaviorMode.VARIED);

        if (p.isEmpty())
        {
            // Print diagnostic for debugging.
            System.out.println("PLAN FAILED — diagnostic: " + planner.diagnose(from, to));
            // Count outgoing transports from from / on planes
            int p2_to_p0 = 0, p2_to_p1 = 0;
            for (TransportEdge e : idx.getAll())
            {
                if (e.fromTile().getPlane() == 2 && e.toTile().getPlane() == 0) p2_to_p0++;
                if (e.fromTile().getPlane() == 2 && e.toTile().getPlane() == 1) p2_to_p1++;
            }
            System.out.println("p2→p0 edges: " + p2_to_p0 + ", p2→p1 edges: " + p2_to_p1);
        }

        assertFalse("V2 must plan bank → pen with live data; live failure repro",
            p.isEmpty());
    }
}
