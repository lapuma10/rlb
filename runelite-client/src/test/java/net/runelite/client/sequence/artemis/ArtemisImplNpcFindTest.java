package net.runelite.client.sequence.artemis;

import java.util.List;
import java.util.Optional;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.recorder.session.AccountRng;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.artemis.query.NpcQuery;
import net.runelite.client.sequence.artemis.view.NpcRef;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link ArtemisImpl#findNpc} dead-NPC filter behavior added in
 * Phase 2C.x.1.
 *
 * <p>Tier 1 Run 02
 * ({@code docs/learnings/2026-05-24-artemis-tier1-run-02.md}) F2b
 * showed the cow-killer pilot kept selecting cows during the ~5-10 s
 * death-animation window because name + interacting filters still
 * passed. Fix: {@link ArtemisImpl#matches} now rejects NPCs with
 * {@code isDead() == true}.
 *
 * <p>Heavy {@link WorldView} mock isolated to its own file so the
 * simpler {@link ArtemisImplReadsTest} stays focused on
 * inventory / player / session. Parallels
 * {@link ArtemisImplObjectFindTest} for {@code findObject}.
 */
public class ArtemisImplNpcFindTest
{
	private static final WorldPoint SELF_LOC = new WorldPoint(3258, 3260, 0);

	/** Build an ArtemisImpl whose {@code findNpc} sees the supplied
	 *  mock NPCs at known offsets east of the player. Run-on-this-thread
	 *  via {@code client.isClientThread() = true}. */
	private ArtemisImpl artemisWithNpcs(NPC... npcs)
	{
		Client client = mock(Client.class);
		ClientThread ct = mock(ClientThread.class);
		when(client.isClientThread()).thenReturn(true);
		when(client.getTickCount()).thenReturn(42);

		Player self = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(self);
		when(self.getWorldLocation()).thenReturn(SELF_LOC);

		WorldView wv = mock(WorldView.class);
		@SuppressWarnings("rawtypes")
		IndexedObjectSet npcSet = mock(IndexedObjectSet.class);
		when(npcSet.iterator()).thenAnswer(inv -> List.of(npcs).iterator());
		when(wv.npcs()).thenReturn(npcSet);
		when(client.getTopLevelWorldView()).thenReturn(wv);

		java.util.concurrent.atomic.AtomicLong tick =
			new java.util.concurrent.atomic.AtomicLong(42L);
		SessionShape session = new SessionShape(tick::get, tick::get, 1_000L);
		return new ArtemisImpl(new ArtemisDeps(client, ct, new AccountRng(null),
			session, mock(ItemManager.class), null, null, null));
	}

	/** Build a mock Cow NPC at {@code distanceEast} tiles east of the
	 *  player with the given {@code dead} state. Other fields use benign
	 *  defaults — only the dead filter is exercised here. */
	private static NPC cow(int index, boolean dead, int distanceEast)
	{
		NPC npc = mock(NPC.class);
		when(npc.getName()).thenReturn("Cow");
		when(npc.getId()).thenReturn(2790);            // NpcID.COW
		when(npc.getIndex()).thenReturn(index);
		when(npc.getWorldLocation()).thenReturn(
			new WorldPoint(SELF_LOC.getX() + distanceEast, SELF_LOC.getY(), 0));
		when(npc.getHealthRatio()).thenReturn(NpcRef.HEALTH_RATIO_UNKNOWN);
		when(npc.getInteracting()).thenReturn(null);
		when(npc.isDead()).thenReturn(dead);
		return npc;
	}

	// ── Phase 2C.x.1 dead-NPC filter ────────────────────────────────

	@Test
	public void findNpc_skipsClosestDeadCow_returnsNextLiveCow()
	{
		// Run 02 F2b shape: a dying cow at the player's near-tile would
		// be picked by ClosestWithSlack rotation without the filter. The
		// dead-filter must skip it so the live cow further away wins.
		NPC dyingClose = cow(/* index */ 2806, /* dead */ true,  /* east */ 1);
		NPC liveFar    = cow(/* index */ 2904, /* dead */ false, /* east */ 4);

		ArtemisImpl artemis = artemisWithNpcs(dyingClose, liveFar);

		Optional<NpcRef> picked = artemis.findNpc(NpcQuery.byName("Cow"));

		assertTrue("findNpc must return a candidate when at least one alive cow is in range",
			picked.isPresent());
		assertEquals("findNpc must skip the closer dead cow and return the live one",
			2904, picked.get().index());
	}

	@Test
	public void findNpc_allMatchingNpcsDead_returnsEmpty()
	{
		// Every cow in range is dead → no candidate survives the filter.
		NPC d1 = cow(2806, true, 1);
		NPC d2 = cow(2904, true, 3);
		NPC d3 = cow(2896, true, 5);

		ArtemisImpl artemis = artemisWithNpcs(d1, d2, d3);

		Optional<NpcRef> picked = artemis.findNpc(NpcQuery.byName("Cow"));

		assertFalse("findNpc must return empty when all matching NPCs are dead",
			picked.isPresent());
	}

	@Test
	public void findNpc_aliveCandidate_unaffectedByTheFilter()
	{
		// Regression guard: the filter excludes ONLY dead NPCs. A live
		// cow with isDead()==false must still resolve to a NpcRef.
		NPC alive = cow(2904, false, 2);

		ArtemisImpl artemis = artemisWithNpcs(alive);

		Optional<NpcRef> picked = artemis.findNpc(NpcQuery.byName("Cow"));

		assertTrue("alive cow must be returned", picked.isPresent());
		assertEquals(2904, picked.get().index());
		assertEquals("Cow", picked.get().name());
	}
}
