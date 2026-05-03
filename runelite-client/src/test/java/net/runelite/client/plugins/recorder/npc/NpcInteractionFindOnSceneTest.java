package net.runelite.client.plugins.recorder.npc;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.sequence.dispatch.HumanizedInputDispatcher;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link NpcInteraction#findOnScene}. Covers the same
 * surface as the deleted {@code CooksAssistantScript.scanForCook}
 * (id-match, name-fallback, plane filter, range filter, onCanvas
 * reflection, no-local-player edge case), plus the new strict
 * client-thread guard.
 *
 * <p>Threading-guard mocking convention: every happy-path test
 * stubs {@code client.isClientThread() == true} in {@link #setUp}
 * so the guard inside {@code findOnScene} allows the call. The
 * single guard test ({@link #throwsWhenNotOnClientThread}) clears
 * that stub. Mockito's default for unstubbed primitive booleans is
 * {@code false}, so missing this would silently fail every test.
 */
public class NpcInteractionFindOnSceneTest
{
    private Client client;
    private ClientThread clientThread;
    private HumanizedInputDispatcher dispatcher;
    private Player local;

    @Before
    public void setUp()
    {
        client = mock(Client.class);
        clientThread = mock(ClientThread.class);
        dispatcher = mock(HumanizedInputDispatcher.class);
        local = mock(Player.class);
        when(client.getLocalPlayer()).thenReturn(local);
        when(local.getWorldLocation()).thenReturn(new WorldPoint(3221, 3219, 0));
        // Default for findOnScene tests — running ON the client thread.
        when(client.isClientThread()).thenReturn(true);
    }

    /** Wire the world-view to expose {@code npcs} as the iterable. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void mockSceneWith(NPC... npcs)
    {
        WorldView wv = mock(WorldView.class);
        IndexedObjectSet npcSet = mock(IndexedObjectSet.class);
        when(npcSet.iterator()).thenAnswer(inv -> List.of(npcs).iterator());
        when(wv.npcs()).thenReturn(npcSet);
        when(client.getTopLevelWorldView()).thenReturn(wv);
    }

    private NPC mockNpc(int id, String name, WorldPoint loc, boolean withHull)
    {
        NPC n = mock(NPC.class);
        when(n.getId()).thenReturn(id);
        when(n.getIndex()).thenReturn(id * 100 + 7);  // arbitrary, unique-per-id
        when(n.getWorldLocation()).thenReturn(loc);
        if (name != null)
        {
            NPCComposition c = mock(NPCComposition.class);
            when(c.getName()).thenReturn(name);
            when(n.getComposition()).thenReturn(c);
        }
        if (withHull)
        {
            when(n.getCanvasTilePoly()).thenReturn(new java.awt.Polygon(
                new int[]{0, 1, 2, 1}, new int[]{0, 1, 0, -1}, 4));
        }
        else
        {
            when(n.getCanvasTilePoly()).thenReturn(null);
        }
        return n;
    }

    @Test
    public void emptySceneReturnsMiss()
    {
        mockSceneWith();
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);
        NpcScan scan = npc.findOnScene(24, new int[]{4626}, "Cook");
        assertFalse(scan.found());
        assertEquals(-1, scan.npcIndex());
        assertNull(scan.tile());
        assertFalse(scan.onCanvas());
        assertTrue("expected 'scanned 0 NPCs' diagnostic, got: " + scan.diagnostic(),
            scan.diagnostic().contains("scanned 0 NPCs"));
    }

    @Test
    public void matchesByIdWhenPreferred()
    {
        WorldPoint pTile = new WorldPoint(3221, 3219, 0);
        when(local.getWorldLocation()).thenReturn(pTile);
        NPC cook    = mockNpc(4626, "Cook", new WorldPoint(3221, 3220, 0), true);
        NPC distrac = mockNpc(1234, "Bob",  new WorldPoint(3222, 3220, 0), true);
        mockSceneWith(distrac, cook);  // intentional non-cook first
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);

        NpcScan scan = npc.findOnScene(24, new int[]{4626}, null);
        assertTrue(scan.found());
        assertEquals(cook.getIndex(), scan.npcIndex());
        assertTrue(scan.onCanvas());
        assertTrue("expected matched-by=id, got: " + scan.diagnostic(),
            scan.diagnostic().contains("matched-by=id"));
    }

    @Test
    public void fallsBackToNameWhenIdMisses()
    {
        NPC cook = mockNpc(9999, "Cook", new WorldPoint(3221, 3220, 0), true);
        mockSceneWith(cook);
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);

        NpcScan scan = npc.findOnScene(24, new int[]{4626}, "Cook");
        assertTrue(scan.found());
        assertEquals(cook.getIndex(), scan.npcIndex());
        assertTrue("expected matched-by=name, got: " + scan.diagnostic(),
            scan.diagnostic().contains("matched-by=name"));
    }

    @Test
    public void nameMatchStripsMarkupAndIgnoresCase()
    {
        // Real OSRS comp names sometimes carry colour markup.
        NPC cook = mockNpc(9999, "<col=ffff00>Cook", new WorldPoint(3221, 3220, 0), true);
        mockSceneWith(cook);
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);

        // Note: lowercase wanted-name; stripped composition-name is "Cook".
        NpcScan scan = npc.findOnScene(24, new int[]{4626}, "cook");
        assertTrue(scan.found());
        assertEquals(cook.getIndex(), scan.npcIndex());
    }

    @Test
    public void planeFilterExcludesNpcsOnDifferentPlane()
    {
        // Player on plane 0, cook on plane 1 — should not match even
        // though id matches.
        when(local.getWorldLocation()).thenReturn(new WorldPoint(3221, 3219, 0));
        NPC cook = mockNpc(4626, "Cook", new WorldPoint(3221, 3220, 1), true);
        mockSceneWith(cook);
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);

        NpcScan scan = npc.findOnScene(24, new int[]{4626}, "Cook");
        assertFalse("plane mismatch must miss", scan.found());
    }

    @Test
    public void rangeFilterExcludesFarNpcs()
    {
        // Player at (3221, 3219, 0); cook 30 tiles east (Chebyshev).
        // scanRadius=24 → out of range.
        when(local.getWorldLocation()).thenReturn(new WorldPoint(3221, 3219, 0));
        NPC cook = mockNpc(4626, "Cook", new WorldPoint(3251, 3219, 0), true);
        mockSceneWith(cook);
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);

        NpcScan scan = npc.findOnScene(24, new int[]{4626}, "Cook");
        assertFalse("out-of-range must miss", scan.found());
    }

    @Test
    public void onCanvasReflectsHullPolyPresence()
    {
        NPC cookWithHull = mockNpc(4626, "Cook", new WorldPoint(3221, 3220, 0), true);
        mockSceneWith(cookWithHull);
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);
        assertTrue(npc.findOnScene(24, new int[]{4626}, null).onCanvas());

        // Re-stub same NPC with no hull — onCanvas must report false.
        when(cookWithHull.getCanvasTilePoly()).thenReturn(null);
        NpcScan scan2 = npc.findOnScene(24, new int[]{4626}, null);
        assertTrue(scan2.found());
        assertFalse(scan2.onCanvas());
        assertTrue(scan2.diagnostic().contains("hullPoly=null"));
    }

    @Test
    public void noLocalPlayerReturnsMiss()
    {
        when(client.getLocalPlayer()).thenReturn(null);
        // (Scene is irrelevant; the method short-circuits before
        // touching it.)
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);
        NpcScan scan = npc.findOnScene(24, new int[]{4626}, "Cook");
        assertFalse(scan.found());
        assertTrue("expected 'no local player' diag, got: " + scan.diagnostic(),
            scan.diagnostic().contains("no local player"));
    }

    @Test(expected = IllegalStateException.class)
    public void throwsWhenNotOnClientThread()
    {
        when(client.isClientThread()).thenReturn(false);
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);
        npc.findOnScene(24, new int[]{4626}, "Cook");
    }

    @Test
    public void diagnosticListsFirstEightNearbyOnMiss()
    {
        // 10 NPCs in range, none matching id 4626 / name "Cook".
        when(local.getWorldLocation()).thenReturn(new WorldPoint(3221, 3219, 0));
        List<NPC> npcs = new ArrayList<>();
        for (int i = 0; i < 10; i++)
        {
            // Each NPC has a unique id starting at 7000 to avoid colliding
            // with the index*100+7 formula we use for the same field.
            npcs.add(mockNpc(7000 + i, "Sheep" + i,
                new WorldPoint(3221 + i, 3219, 0), false));
        }
        mockSceneWith(npcs.toArray(new NPC[0]));
        NpcInteraction npc = new NpcInteraction(client, clientThread, dispatcher);

        NpcScan scan = npc.findOnScene(24, new int[]{4626}, "Cook");
        assertFalse(scan.found());
        String d = scan.diagnostic();
        assertTrue("expected 'scanned 10 NPCs' in diagnostic: " + d,
            d.contains("scanned 10 NPCs"));
        // First 8 names listed (Sheep0..Sheep7) — not Sheep8 / Sheep9.
        for (int i = 0; i < 8; i++)
        {
            assertTrue("expected Sheep" + i + " listed, got: " + d,
                d.contains("Sheep" + i + "(id=" + (7000 + i)));
        }
        assertFalse("Sheep8 must NOT be listed (cap of 8): " + d, d.contains("Sheep8("));
    }
}
