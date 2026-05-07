package net.runelite.client.plugins.recorder.nav;

import net.runelite.client.plugins.recorder.RecorderConfig;
import org.junit.Test;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phase 7: the factory always returns a {@link HybridNavigator} that
 *  reads the live mode each tick. Tests cover the factory's stability
 *  guarantees + the resilient-fallback behavior when V2 is missing. */
public class NavigatorFactoryTest
{
    @Test
    public void getNavigator_returnsStableHybrid()
    {
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorMode()).thenReturn(RecorderConfig.NavigatorMode.V1_ONLY);
        NavigatorFactory factory = new NavigatorFactory(cfg, stubNavigator("trail-v1"),
            stubNavigator("worldmap-v2"));

        Navigator a = factory.getNavigator();
        Navigator b = factory.getNavigator();
        assertNotNull(a);
        assertSame("repeated lookups must surface the same hybrid instance", a, b);
        assertSame("hybrid", a.name() == null ? "?" : a.name());
    }

    @Test
    public void getNavigator_v2Null_stillReturnsHybrid_whichDelegatesToV1() throws Exception
    {
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorMode()).thenReturn(RecorderConfig.NavigatorMode.V2_WITH_V1_FALLBACK);
        Navigator stubV1 = stubNavigator("trail-v1");
        // Plugin built without V2 (legacy / test setup) — factory must NOT
        // crash; V2 selection in HybridNavigator falls back to V1.
        NavigatorFactory factory = new NavigatorFactory(cfg, stubV1, (Navigator) null);

        Navigator nav = factory.getNavigator();
        assertNotNull(nav);
        // Tick once to confirm it doesn't throw — actual behavior is tested
        // by HybridNavigatorTest; here we just verify the factory wires
        // a usable instance.
        nav.tick(NavRequest.byTrail("any", BehaviorMode.VARIED));
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
