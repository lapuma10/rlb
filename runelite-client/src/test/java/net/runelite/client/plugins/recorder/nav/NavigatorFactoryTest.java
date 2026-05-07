package net.runelite.client.plugins.recorder.nav;

import net.runelite.client.plugins.recorder.RecorderConfig;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Verifies the factory's switch behavior in isolation: given a
 *  RecorderConfig stub, it returns the right instance, throws on V2
 *  before that's registered, and returns the same V1 instance across
 *  calls. */
public class NavigatorFactoryTest
{
    @Test
    public void getNavigator_whenTrailV1_returnsTrailNavigator()
    {
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorImpl()).thenReturn(RecorderConfig.NavigatorImpl.TRAIL_V1);
        Navigator stub = stubNavigator("trail-v1");
        NavigatorFactory factory = new NavigatorFactory(cfg, stub);

        assertSame(stub, factory.getNavigator());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void getNavigator_whenWorldmapV2_andV2NotRegistered_throws()
    {
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorImpl()).thenReturn(RecorderConfig.NavigatorImpl.WORLDMAP_V2);
        NavigatorFactory factory = new NavigatorFactory(cfg, stubNavigator("trail-v1"));

        factory.getNavigator();
    }

    @Test
    public void getNavigator_isStableWithinSession()
    {
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorImpl()).thenReturn(RecorderConfig.NavigatorImpl.TRAIL_V1);
        NavigatorFactory factory = new NavigatorFactory(cfg, stubNavigator("trail-v1"));

        Navigator a = factory.getNavigator();
        Navigator b = factory.getNavigator();

        assertSame("repeated lookups must surface the same instance", a, b);
    }

    @Test
    public void getNavigator_whenConfigReturnsNull_fallsBackToTrailV1()
    {
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorImpl()).thenReturn(null);
        Navigator stub = stubNavigator("trail-v1");
        NavigatorFactory factory = new NavigatorFactory(cfg, stub);

        assertSame(stub, factory.getNavigator());
    }

    private static Navigator stubNavigator(String name)
    {
        return new Navigator()
        {
            @Override public NavStatus tick(NavRequest request) { return NavStatus.IDLE; }
            @Override public void cancel() { }
            @Override public boolean isBusy() { return false; }
            @Override public String name() { return name; }
        };
    }
}
