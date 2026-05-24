package net.runelite.client.sequence.artemis;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.recorder.session.AccountRng;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.Step;
import net.runelite.client.sequence.artemis.view.InvSlot;
import net.runelite.client.sequence.artemis.view.InventoryView;
import net.runelite.client.sequence.artemis.view.PlayerState;
import net.runelite.client.sequence.composite.LinearSequence;
import net.runelite.client.sequence.composite.SequencePlanBuilder;
import net.runelite.client.sequence.composite.SequencePlanBuilderImpl;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests the read methods that don't need a full scene mock —
 * inventory, player, session, plus the cross-cutting observedTick /
 * snapshot / injected-session guarantees.
 *
 * <p>NPC / object / item find tests live alongside RotationPolicySelector
 * (the selector is the bug surface; the filter logic is straight-forward
 * compose-and-pick that the selector tests already cover).
 */
public class ArtemisImplReadsTest
{
	private Client client;
	private ClientThread clientThread;
	private ItemManager itemManager;
	private SessionShape session;
	private ArtemisImpl artemis;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		clientThread = mock(ClientThread.class);
		itemManager = mock(ItemManager.class);
		// Inject SessionShape with synthetic clocks — Phase 0A.2 record.
		java.util.concurrent.atomic.AtomicLong tick = new java.util.concurrent.atomic.AtomicLong(0L);
		session = new SessionShape(tick::get, tick::get, 1_000_000L);

		// Make readOnClient run inline by reporting we're on the client
		// thread for every test.
		when(client.isClientThread()).thenReturn(true);

		AccountRng rng = new AccountRng(null);   // → NOT_LOGGED_IN_SEED, deterministic
		artemis = new ArtemisImpl(new ArtemisDeps(
			client, clientThread, rng, session, itemManager, null, null, null));
	}

	// ── inventory() ─────────────────────────────────────────────────

	@Test
	public void inventoryReturnsEmptyViewWhenContainerNull()
	{
		when(client.getItemContainer(InventoryID.INV)).thenReturn(null);

		InventoryView v = artemis.inventory();
		assertNotNull(v);
		assertEquals(0, v.slots().size());
	}

	@Test
	public void inventoryReturnsSlotsBuiltFromItemContainer()
	{
		ItemContainer inv = mock(ItemContainer.class);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(inv.getItems()).thenReturn(new Item[]
			{
				new Item(526, 5),
				new Item(-1, 0),
				new Item(1739, 3),
			});
		ItemComposition bones = mock(ItemComposition.class);
		when(bones.getName()).thenReturn("Bones");
		ItemComposition hide = mock(ItemComposition.class);
		when(hide.getName()).thenReturn("Cowhide");
		when(itemManager.getItemComposition(526)).thenReturn(bones);
		when(itemManager.getItemComposition(1739)).thenReturn(hide);

		InventoryView v = artemis.inventory();
		assertEquals(3, v.slots().size());
		assertEquals(526, v.slots().get(0).itemId());
		assertEquals(5, v.slots().get(0).quantity());
		assertEquals("Bones", v.slots().get(0).name());
		// Empty slot in the middle.
		assertEquals(InvSlot.EMPTY_ITEM_ID, v.slots().get(1).itemId());
		// Last slot present.
		assertEquals(1739, v.slots().get(2).itemId());
	}

	@Test
	public void inventoryHandlesNullItemElement()
	{
		// Real OSRS engine inserts `null` for empty slots (not Item with
		// id=-1). The previous test used id=-1; this one uses an actual
		// null in the middle slot to exercise that branch explicitly.
		ItemContainer inv = mock(ItemContainer.class);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(inv.getItems()).thenReturn(new Item[]
			{
				new Item(526, 5),
				null,
				new Item(1739, 3),
			});
		ItemComposition bones = mock(ItemComposition.class);
		when(bones.getName()).thenReturn("Bones");
		ItemComposition hide = mock(ItemComposition.class);
		when(hide.getName()).thenReturn("Cowhide");
		when(itemManager.getItemComposition(526)).thenReturn(bones);
		when(itemManager.getItemComposition(1739)).thenReturn(hide);

		InventoryView v = artemis.inventory();
		assertEquals(3, v.slots().size());
		assertEquals(526, v.slots().get(0).itemId());
		// The literal-null middle slot must be normalised to EMPTY_ITEM_ID.
		assertEquals(InvSlot.EMPTY_ITEM_ID, v.slots().get(1).itemId());
		assertEquals(1739, v.slots().get(2).itemId());
	}

	@Test
	public void inventoryIsSnapshotNotBacking()
	{
		// Mutate the underlying Item[] AFTER inventory() returns; the
		// returned InventoryView must NOT reflect the mutation.
		ItemContainer inv = mock(ItemContainer.class);
		Item[] items = new Item[] { new Item(526, 5) };
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(inv.getItems()).thenReturn(items);
		ItemComposition c = mock(ItemComposition.class);
		when(c.getName()).thenReturn("Bones");
		when(itemManager.getItemComposition(anyInt())).thenReturn(c);

		InventoryView snapshot = artemis.inventory();

		// Engine "stacks more" into the underlying array — snapshot must
		// not see this.
		items[0] = new Item(526, 9999);

		assertEquals("inventory() must return a snapshot decoupled from the underlying Item[]",
			5, snapshot.count(526));
	}

	// ── player() ────────────────────────────────────────────────────

	@Test
	public void playerReturnsSafeDefaultsWhenLocalPlayerNull()
	{
		when(client.getLocalPlayer()).thenReturn(null);

		PlayerState p = artemis.player();
		assertNotNull(p);
		assertEquals(true, p.idle());
		// No exception, no NPE — safe behaviour pre-login.
	}

	@Test
	public void playerReadsAllPlayerStateFields()
	{
		Player lp = mock(Player.class);
		WorldPoint loc = new WorldPoint(3221, 3219, 2);
		when(client.getLocalPlayer()).thenReturn(lp);
		when(lp.getWorldLocation()).thenReturn(loc);
		when(lp.getAnimation()).thenReturn(7);
		when(lp.getInteracting()).thenReturn(null);
		when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(99);
		when(client.getBoostedSkillLevel(Skill.PRAYER)).thenReturn(43);
		when(client.getEnergy()).thenReturn(7400);

		PlayerState p = artemis.player();
		assertEquals(loc, p.loc());
		assertEquals(2, p.plane());
		assertEquals(7, p.animation());
		assertEquals(99, p.hp());
		assertEquals(43, p.prayer());
		assertEquals(7400, p.energy());
		// animation != -1 → not idle.
		assertEquals(false, p.idle());
	}

	// ── session() — injected, not per-call ──────────────────────────

	@Test
	public void sessionReturnsTheInjectedInstanceEveryCall()
	{
		SessionShape a = artemis.session();
		SessionShape b = artemis.session();
		SessionShape c = artemis.session();
		assertSame("session() must return the injected SessionShape — never a new one per call",
			session, a);
		assertSame(session, b);
		assertSame(session, c);
	}

	// ── observedTick population ─────────────────────────────────────

	@Test
	public void inventoryReadsDoNotAdvanceTickClock()
	{
		// inventory() does not need observedTick (InventoryView has no
		// staleness field), but it should not call getTickCount needlessly.
		ItemContainer inv = mock(ItemContainer.class);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(inv.getItems()).thenReturn(new Item[0]);

		artemis.inventory();

		verify(client, times(0)).getTickCount();
	}

	@Test
	public void readsHonourClientThreadCheck()
	{
		// When we report the current thread IS the client thread, the
		// readOnClient helper must run inline — NEVER calls
		// clientThread.invoke. Inventory exercise of the helper.
		ItemContainer inv = mock(ItemContainer.class);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(inv.getItems()).thenReturn(new Item[0]);

		artemis.inventory();
		artemis.player();
		artemis.session();

		// session() is a plain accessor — no clientThread.invoke ever.
		// inventory() + player() run on this thread (we set ourself as
		// the client thread above) — also no clientThread.invoke.
		verifyNoInteractions(clientThread);
	}

	// ── plan(...) wiring (Phase 1A.4e) ──────────────────────────────

	@Test
	public void planReturnsBuilderThatProducesNamedLinearSequence()
	{
		SequencePlanBuilder builder = artemis.plan("smoke-test-plan");
		assertNotNull(builder);
		assertTrue("plan(...) must return SequencePlanBuilderImpl",
			builder instanceof SequencePlanBuilderImpl);

		Step root = builder.root();
		assertTrue("root() must produce a LinearSequence", root instanceof LinearSequence);
		assertEquals("smoke-test-plan", ((LinearSequence) root).name());
	}
}
