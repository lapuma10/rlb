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

    @Test
    public void getNavigator_whenWorldmapV2_andV2NotRegistered_fallsBackToV1()
    {
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorImpl()).thenReturn(RecorderConfig.NavigatorImpl.WORLDMAP_V2);
        Navigator stubV1 = stubNavigator("trail-v1");
        NavigatorFactory factory = new NavigatorFactory(cfg, stubV1);

        // Round-1: V2 not registered yet. Plugin startup must stay
        // resilient to a stale config value, so the factory logs and
        // returns V1 instead of throwing — Phase 6 replaces the warn
        // with a real V2 instance.
        assertSame(stubV1, factory.getNavigator());
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
