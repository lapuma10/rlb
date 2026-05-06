package net.runelite.client.plugins.recorder.worldmap;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RegionIdsTest
{
    @Test
    public void lumbridgeKitchen_packsToExpectedRegionId()
    {
        // Lumbridge cook: (3208, 3213, 0). Region: (50, 50). Expected:
        // ((50) << 8) | 50 = 12850
        assertEquals(12850, RegionIds.regionIdFor(3208, 3213));
        assertEquals(12850, RegionIds.regionIdFor(3211, 3214));
    }

    @Test
    public void differentRegions_haveDifferentIds()
    {
        int lumby = RegionIds.regionIdFor(3208, 3213);
        int varrockEast = RegionIds.regionIdFor(3253, 3420);
        assert lumby != varrockEast : "regions must collide-free";
    }
}
