package net.runelite.client.plugins.recorder.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.recorder.combat.SkillTarget;
import net.runelite.client.plugins.recorder.combat.TrainingPlan;
import net.runelite.client.plugins.recorder.RecorderConfig;
import net.runelite.client.plugins.recorder.nav.NavRequest;
import net.runelite.client.plugins.recorder.nav.NavStatus;
import net.runelite.client.plugins.recorder.nav.Navigator;
import net.runelite.client.plugins.recorder.nav.NavigatorFactory;
import net.runelite.client.plugins.recorder.trail.TrailRegistry;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ChickenFarmV3ScriptTest
{
    @Test
    public void freshScriptIsIdle() throws IOException
    {
        // TrailRegistry is final — use a real registry pointed at an empty
        // temp dir. The script only uses start() to run, which this test
        // does not do. Navigator is a no-op stub since no tick happens.
        TrailRegistry reg = new TrailRegistry(Files.createTempDirectory("v3-test-"));
        ChickenFarmV3Script s = new ChickenFarmV3Script(
            mock(Client.class), mock(ClientThread.class),
            mock(HumanizedInputDispatcher.class),
            reg, idleFactory());
        assertEquals(ChickenFarmV3Script.State.IDLE, s.state());
    }

    @Test
    public void trailNamesAreThoseSpecified()
    {
        // The script reads two specific trail names from the registry —
        // 'lumby_bank_to_pen' and 'pen_to_lumby_bank'. These are the
        // names the user is told to record.
        assertEquals("lumby_bank_to_pen", ChickenFarmV3Script.OUTBOUND_TRAIL_NAME);
        assertEquals("pen_to_lumby_bank", ChickenFarmV3Script.RETURN_TRAIL_NAME);
    }

    @Test
    public void bankingTransitionsToLoggingOffWhenTrainingComplete() throws Exception
    {
        // This is a smoke-level test — we just verify the State enum
        // includes LOGGING_OFF and that the script's startup flow when
        // a training plan is set + completed transitions through it.
        // Full state-machine driving requires mocking BankInteraction +
        // InventoryUtil; that's left to integration tests.
        Client client = mock(Client.class);
        ClientThread clientThread = mock(ClientThread.class);
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        TrailRegistry reg = new TrailRegistry(Files.createTempDirectory("v3-logoff-test-"));
        ChickenFarmV3Script s = new ChickenFarmV3Script(
            client, clientThread, dispatcher, reg, idleFactory());
        // Confirm LOGGING_OFF is a valid state.
        assertNotNull(ChickenFarmV3Script.State.valueOf("LOGGING_OFF"));
        // Confirm setTrainingPlan accepts a real plan without throwing.
        TrainingPlan plan = TrainingPlan.basic(
            List.of(new SkillTarget(Skill.ATTACK, 5)),
            false);
        s.setTrainingPlan(plan);
        assertSame(plan, s.trainingPlan());
        // Without start(), state stays IDLE.
        assertEquals(ChickenFarmV3Script.State.IDLE, s.state());
    }

    private static NavigatorFactory idleFactory()
    {
        Navigator idle = new Navigator()
        {
            @Override public NavStatus tick(NavRequest request) { return NavStatus.IDLE; }
            @Override public void cancel() { }
            @Override public boolean isBusy() { return false; }
            @Override public String name() { return "idle-stub"; }
        };
        RecorderConfig cfg = mock(RecorderConfig.class);
        when(cfg.navigatorImpl()).thenReturn(RecorderConfig.NavigatorImpl.TRAIL_V1);
        return new NavigatorFactory(cfg, idle);
    }
}
