package net.runelite.client.sequence.artemis;

import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.recorder.session.AccountRng;
import net.runelite.client.plugins.recorder.session.SessionShape;
import net.runelite.client.sequence.artemis.view.InventoryView;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins {@link ArtemisImpl#readOnClient} marshaling contract: inline
 * when the caller is already on the client thread, otherwise queue via
 * {@link ClientThread#invoke} and block until completion. Centralised
 * so reads never sprinkle ad-hoc {@code clientThread.invoke(...)}
 * elsewhere.
 */
public class ArtemisImplThreadingTest
{
	private ArtemisImpl newArtemis(Client client, ClientThread clientThread)
	{
		java.util.concurrent.atomic.AtomicLong tick = new java.util.concurrent.atomic.AtomicLong(0L);
		SessionShape session = new SessionShape(tick::get, tick::get, 1_000L);
		return new ArtemisImpl(client, clientThread, new AccountRng(null), session,
			mock(ItemManager.class), null);
	}

	@Test
	public void readsRunInlineWhenCallerIsOnClientThread()
	{
		Client client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		// We are the client thread.
		when(client.isClientThread()).thenReturn(true);

		ItemContainer inv = mock(ItemContainer.class);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(inv.getItems()).thenReturn(new Item[0]);

		ArtemisImpl artemis = newArtemis(client, clientThread);

		InventoryView v = artemis.inventory();

		assertNotNull(v);
		// Critical: zero invoke() calls when caller is already on the
		// client thread — saves a marshal hop for Step.check() callers.
		verifyNoInteractions(clientThread);
	}

	@Test
	public void readsMarshalViaClientThreadInvokeWhenOffThread()
	{
		Client client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		// We are NOT the client thread.
		when(client.isClientThread()).thenReturn(false);

		// Mock invoke(Runnable) to actually run the runnable so the
		// CompletableFuture inside readOnClient completes.
		AtomicReference<Runnable> capturedRunnable = new AtomicReference<>();
		doAnswer(inv ->
		{
			Runnable r = inv.getArgument(0);
			capturedRunnable.set(r);
			r.run();
			return null;
		}).when(clientThread).invoke(any(Runnable.class));

		ItemContainer inv = mock(ItemContainer.class);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inv);
		when(inv.getItems()).thenReturn(new Item[0]);

		ArtemisImpl artemis = newArtemis(client, clientThread);

		InventoryView v = artemis.inventory();

		assertNotNull(v);
		// Must have routed through clientThread.invoke(Runnable).
		verify(clientThread, atLeastOnce()).invoke(any(Runnable.class));
		assertNotNull("a Runnable must have been queued",
			capturedRunnable.get());
	}

	@Test
	public void sessionAccessorBypassesMarshaling()
	{
		// session() is a plain field accessor — must NEVER call
		// clientThread.invoke regardless of which thread we're on.
		Client client = mock(Client.class);
		ClientThread clientThread = mock(ClientThread.class);
		when(client.isClientThread()).thenReturn(false);

		ArtemisImpl artemis = newArtemis(client, clientThread);

		assertNotNull(artemis.session());
		assertNotNull(artemis.session());
		assertNotNull(artemis.session());

		// Even called off the client thread, session() must not marshal.
		verifyNoInteractions(clientThread);
	}
}
