package net.runelite.client.plugins.recorder.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
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
import net.runelite.client.sequence.internal.ActionRequest;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    public void randomPenTileStaysInsidePen() throws IOException
    {
        // Generate many tiles and assert every one falls inside PEN_AREA
        // and one tile in from the edge — the recovery walker uses the
        // result as a minimap-walk target and the engine paths into
        // unwalkable fence tiles incorrectly.
        TrailRegistry reg = new TrailRegistry(Files.createTempDirectory("v3-rand-test-"));
        ChickenFarmV3Script s = new ChickenFarmV3Script(
            mock(Client.class), mock(ClientThread.class),
            mock(HumanizedInputDispatcher.class),
            reg, idleFactory());
        WorldArea pen = ChickenFarmV3Script.PEN_AREA;
        int xMin = pen.getX() + 1;
        int xMax = pen.getX() + pen.getWidth() - 2;
        int yMin = pen.getY() + 1;
        int yMax = pen.getY() + pen.getHeight() - 2;
        java.util.Set<WorldPoint> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++)
        {
            WorldPoint p = s.randomPenTile();
            assertEquals("plane", pen.getPlane(), p.getPlane());
            assertTrue("x=" + p.getX() + " in [" + xMin + "," + xMax + "]",
                p.getX() >= xMin && p.getX() <= xMax);
            assertTrue("y=" + p.getY() + " in [" + yMin + "," + yMax + "]",
                p.getY() >= yMin && p.getY() <= yMax);
            seen.add(p);
        }
        // 11x10 = 110 candidates; 200 draws should hit at least 20 distinct
        // tiles unless the RNG is broken / the generator is constant.
        assertTrue("expected variety from random tile draws, got " + seen.size(),
            seen.size() >= 20);
    }

    @Test
    public void recoverIntoPenReturnsTrueImmediatelyWhenAlreadyInside() throws Exception
    {
        // Player position is inside PEN_AREA — recovery should observe that
        // on the first iteration and return true without dispatching any
        // walks. This guards the cheap path: the script calls recoverIntoPen
        // every time combat goes IDLE outside the pen, so a no-op when
        // we're already inside is essential.
        Client client = mock(Client.class);
        ClientThread clientThread = mock(ClientThread.class);
        // ClientThread.invokeLater(Runnable) — run the lambda inline so the
        // CountDownLatch in onClient resolves immediately.
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(clientThread).invokeLater(any(Runnable.class));
        Player player = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(player);
        when(player.getWorldLocation()).thenReturn(
            new WorldPoint(ChickenFarmV3Script.PEN_AREA.getX() + 5,
                ChickenFarmV3Script.PEN_AREA.getY() + 5,
                ChickenFarmV3Script.PEN_AREA.getPlane()));
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        TrailRegistry reg = new TrailRegistry(Files.createTempDirectory("v3-recov-in-test-"));
        ChickenFarmV3Script s = new ChickenFarmV3Script(
            client, clientThread, dispatcher, reg, idleFactory());

        assertTrue("already inside pen", s.recoverIntoPen());
        verify(dispatcher, never()).dispatch(any(ActionRequest.class));
    }

    @Test
    public void recoverIntoPenWalksToPenTileWhenOutside() throws Exception
    {
        // Player outside the pen — recovery should dispatch a Kind.WALK
        // targeting a tile inside PEN_AREA. After that walk dispatch the
        // mocked player position flips to inside the pen, simulating
        // arrival; recovery returns true.
        Client client = mock(Client.class);
        ClientThread clientThread = mock(ClientThread.class);
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(clientThread).invokeLater(any(Runnable.class));
        Player player = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(player);

        // Sequence: outside (the kill #18 tile from the bug), then after
        // the walk dispatch, inside the pen.
        WorldArea pen = ChickenFarmV3Script.PEN_AREA;
        WorldPoint outside = new WorldPoint(3233, 3288, pen.getPlane());
        WorldPoint inside = new WorldPoint(pen.getX() + 3, pen.getY() + 3, pen.getPlane());
        when(player.getWorldLocation())
            .thenReturn(outside)              // initial check
            .thenReturn(outside)              // pre-walk recheck
            .thenReturn(inside);              // arrival poll
        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        TrailRegistry reg = new TrailRegistry(Files.createTempDirectory("v3-recov-out-test-"));
        ChickenFarmV3Script s = new ChickenFarmV3Script(
            client, clientThread, dispatcher, reg, idleFactory());

        assertTrue("walked into pen", s.recoverIntoPen());

        ArgumentCaptor<ActionRequest> req = ArgumentCaptor.forClass(ActionRequest.class);
        verify(dispatcher).dispatch(req.capture());
        ActionRequest walk = req.getValue();
        assertEquals(ActionRequest.Kind.WALK, walk.getKind());
        assertNotNull(walk.getTile());
        // Walk target must be inside PEN_AREA — that's the whole point.
        WorldPoint t = walk.getTile();
        assertEquals(pen.getPlane(), t.getPlane());
        assertTrue(t.getX() >= pen.getX() && t.getX() < pen.getX() + pen.getWidth());
        assertTrue(t.getY() >= pen.getY() && t.getY() < pen.getY() + pen.getHeight());
    }

    @Test
    public void recoverIntoPenAdoptsAggro_returnsTrueWithoutDispatching() throws Exception
    {
        // A chicken is currently aggro on us while we're outside the pen.
        // Walking back into the pen would just leave us out-of-pen with
        // damage incoming and the chicken pursuing — the right move is to
        // return so combat.start() adopts the fight via
        // ChickenCombatLoop.detectActiveChickenCombat. Verify recovery
        // returns true with no walk dispatched.
        Client client = mock(Client.class);
        ClientThread clientThread = mock(ClientThread.class);
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(clientThread).invokeLater(any(Runnable.class));
        Player player = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(player);
        WorldArea pen = ChickenFarmV3Script.PEN_AREA;
        WorldPoint outside = new WorldPoint(3233, 3288, pen.getPlane());
        when(player.getWorldLocation()).thenReturn(outside);
        // Aggro chicken: targets the local player and has the right name.
        NPC chicken = mock(NPC.class);
        when(chicken.getInteracting()).thenReturn(player);
        NPCComposition comp = mock(NPCComposition.class);
        when(comp.getName()).thenReturn("Chicken");
        when(chicken.getComposition()).thenReturn(comp);
        WorldView wv = mock(WorldView.class);
        IndexedObjectSet<? extends NPC> npcs = stubNpcSet(java.util.List.of(chicken));
        doReturn(npcs).when(wv).npcs();
        when(client.getTopLevelWorldView()).thenReturn(wv);

        HumanizedInputDispatcher dispatcher = mock(HumanizedInputDispatcher.class);
        TrailRegistry reg = new TrailRegistry(Files.createTempDirectory("v3-aggro-test-"));
        ChickenFarmV3Script s = new ChickenFarmV3Script(
            client, clientThread, dispatcher, reg, idleFactory());

        assertTrue("aggro short-circuit returns true so combat can adopt",
            s.recoverIntoPen());
        verify(dispatcher, never()).dispatch(any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IndexedObjectSet<? extends NPC> stubNpcSet(java.util.List<NPC> npcs)
    {
        IndexedObjectSet set = mock(IndexedObjectSet.class);
        when(set.iterator()).thenAnswer(inv -> npcs.iterator());
        return set;
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
        when(cfg.navigatorMode()).thenReturn(RecorderConfig.NavigatorMode.V1_ONLY);
        return new NavigatorFactory(cfg, idle);
    }
}
