package net.runelite.client.sequence.dispatch;

import java.awt.Polygon;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Point;
import org.junit.Test;
import static org.junit.Assert.*;

/** Sanity check that consecutive {@code sampleInsidePolygon} calls return
 *  pixels at least {@code MIN_REPEAT_PX} apart — i.e. the recent-click
 *  history actually de-duplicates clicks the way a human watching the
 *  bot would expect. */
public class PixelResolverRepeatTest
{
    @Test
    public void consecutiveSamplesNeverLandWithinSixPixels() throws Exception
    {
        Polygon hull = new Polygon(
            new int[]{100, 160, 160, 100},
            new int[]{100, 100, 160, 160},
            4);
        PixelResolver pr = new PixelResolver(null);
        Method sample = PixelResolver.class.getDeclaredMethod("sampleInsidePolygon", Polygon.class);
        sample.setAccessible(true);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 12; i++)
        {
            Point p = (Point) sample.invoke(pr, hull);
            assertNotNull("sampler returned null on iter " + i, p);
            for (String prev : seen)
            {
                String[] parts = prev.split(",");
                int dx = p.getX() - Integer.parseInt(parts[0]);
                int dy = p.getY() - Integer.parseInt(parts[1]);
                assertTrue("click " + i + " landed within 6px of a recent click: ("
                    + p.getX() + "," + p.getY() + ") vs " + prev,
                    dx * dx + dy * dy >= 36);
            }
            seen.add(p.getX() + "," + p.getY());
        }
    }
}
