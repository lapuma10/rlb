package net.runelite.client.sequence.artemis;

import java.util.Optional;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.recorder.session.AccountRng;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.artemis.query.WidgetQuery;
import net.runelite.client.sequence.artemis.view.WidgetRef;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link ArtemisImpl#findWidget} visibility-gate behaviour: when
 * {@code requireVisible} is true (default), a hidden widget must NOT
 * resolve — preventing the §1 click-on-stale-widget bug class.
 */
public class ArtemisImplWidgetTest
{
	private Client client;
	private ArtemisImpl artemis;

	@Before
	public void setUp()
	{
		client = mock(Client.class);
		ClientThread ct = mock(ClientThread.class);
		when(client.isClientThread()).thenReturn(true);
		java.util.concurrent.atomic.AtomicLong tick = new java.util.concurrent.atomic.AtomicLong(50L);
		SessionShape session = new SessionShape(tick::get, tick::get, 1_000L);
		when(client.getTickCount()).thenReturn(50);
		artemis = new ArtemisImpl(client, ct, new AccountRng(null), session,
			mock(ItemManager.class), null);
	}

	@Test
	public void findWidgetReturnsEmptyWhenClientReturnsNull()
	{
		when(client.getWidget(123)).thenReturn(null);
		Optional<WidgetRef> ref = artemis.findWidget(WidgetQuery.byId(123));
		assertFalse(ref.isPresent());
	}

	@Test
	public void findWidgetReturnsRefForVisibleLeafWidget()
	{
		Widget w = mock(Widget.class);
		when(client.getWidget(456)).thenReturn(w);
		when(w.isHidden()).thenReturn(false);
		when(w.getItemId()).thenReturn(526);   // Bones

		Optional<WidgetRef> ref = artemis.findWidget(WidgetQuery.byId(456));
		assertTrue(ref.isPresent());
		assertEquals(456, ref.get().widgetId());
		assertEquals(526, ref.get().itemId());
		assertEquals(50L, ref.get().observedTick());
	}

	@Test
	public void findWidgetReturnsEmptyWhenRequireVisibleAndHidden()
	{
		Widget w = mock(Widget.class);
		when(client.getWidget(789)).thenReturn(w);
		when(w.isHidden()).thenReturn(true);    // ← hidden in parent chain

		Optional<WidgetRef> ref = artemis.findWidget(WidgetQuery.byId(789));
		assertFalse("requireVisible=true (default) + hidden widget must yield empty Optional",
			ref.isPresent());
	}

	@Test
	public void findWidgetReturnsRefWhenAnyVisibilityAcceptedEvenIfHidden()
	{
		Widget w = mock(Widget.class);
		when(client.getWidget(789)).thenReturn(w);
		when(w.isHidden()).thenReturn(true);
		when(w.getItemId()).thenReturn(-1);

		// .anyVisibility() opts out of the visibility gate.
		Optional<WidgetRef> ref = artemis.findWidget(WidgetQuery.byId(789).anyVisibility());
		assertTrue("anyVisibility() must bypass the requireVisible gate",
			ref.isPresent());
	}

	@Test
	public void findWidgetWithChildSlotResolvesViaParent()
	{
		Widget parent = mock(Widget.class);
		Widget child = mock(Widget.class);
		when(client.getWidget(100)).thenReturn(parent);
		when(parent.getChild(7)).thenReturn(child);
		when(child.isHidden()).thenReturn(false);
		when(child.getItemId()).thenReturn(1739);

		Optional<WidgetRef> ref = artemis.findWidget(WidgetQuery.byId(100).child(7));
		assertTrue(ref.isPresent());
		assertEquals(Integer.valueOf(7), ref.get().childSlot());
		assertEquals(1739, ref.get().itemId());
	}

	@Test
	public void findWidgetReturnsEmptyWhenChildSlotMissing()
	{
		Widget parent = mock(Widget.class);
		when(client.getWidget(100)).thenReturn(parent);
		when(parent.getChild(99)).thenReturn(null);

		Optional<WidgetRef> ref = artemis.findWidget(WidgetQuery.byId(100).child(99));
		assertFalse(ref.isPresent());
	}

	@Test
	public void widgetRefCarriesObservedTickFromClientGetTickCount()
	{
		Widget w = mock(Widget.class);
		when(client.getWidget(456)).thenReturn(w);
		when(w.isHidden()).thenReturn(false);
		when(client.getTickCount()).thenReturn(12345);

		Optional<WidgetRef> ref = artemis.findWidget(WidgetQuery.byId(456));
		assertNotNull(ref.get());
		assertEquals("observedTick must be populated from client.getTickCount() per spec §8",
			12345L, ref.get().observedTick());
	}
}
