package net.runelite.client.plugins.recorder.nav;

import net.runelite.client.plugins.recorder.RecorderConfig;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Verifies the factory's switch behavior in isolation: given a
 *  RecorderConfig stub it returns the right instance, falls back to
 *  V1 when V2 isn't bound (round-1 stale config), and surfaces a
 *  stable instance across calls. */
public class NavigatorFactoryTest
{
    @Test
    public void getNavigator_whenTrailV1_returnsTrailNavigator()
    {
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorImpl()).thenReturn(RecorderConfig.NavigatorImpl.TRAIL_V1);
        Navigator stub = stubNavigator("trail-v1");
        NavigatorFactory factory = new NavigatorFactory(cfg, stub, stubNavigator("worldmap-v2"));

        assertSame(stub, factory.getNavigator());
    }

    @Test
    public void getNavigator_whenWorldmapV2_returnsV2Navigator()
    {
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorImpl()).thenReturn(RecorderConfig.NavigatorImpl.WORLDMAP_V2);
        Navigator stubV1 = stubNavigator("trail-v1");
        Navigator stubV2 = stubNavigator("worldmap-v2");
        NavigatorFactory factory = new NavigatorFactory(cfg, stubV1, stubV2);

        assertSame("V2 selection must surface the bound V2 instance",
            stubV2, factory.getNavigator());
    }

    @Test
    public void getNavigator_whenWorldmapV2_andV2Null_fallsBackToV1()
    {
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorImpl()).thenReturn(RecorderConfig.NavigatorImpl.WORLDMAP_V2);
        Navigator stubV1 = stubNavigator("trail-v1");
        // Plugin built without V2 (legacy path / test setup) — config
        // pointing at V2 must NOT crash startup; fall back to V1.
        NavigatorFactory factory = new NavigatorFactory(cfg, stubV1, (Navigator) null);

        assertSame(stubV1, factory.getNavigator());
    }

    @Test
    public void getNavigator_isStableWithinSession()
    {
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorImpl()).thenReturn(RecorderConfig.NavigatorImpl.TRAIL_V1);
        NavigatorFactory factory = new NavigatorFactory(cfg, stubNavigator("trail-v1"),
            stubNavigator("worldmap-v2"));

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
        NavigatorFactory factory = new NavigatorFactory(cfg, stub, stubNavigator("worldmap-v2"));

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
