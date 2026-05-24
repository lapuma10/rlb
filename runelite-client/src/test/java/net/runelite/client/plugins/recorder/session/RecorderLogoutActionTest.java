package net.runelite.client.plugins.recorder.session;

import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the contract of {@link RecorderLogoutAction}:
 * <ul>
 *   <li>Returns the inner-panel logout widget id when visible.</li>
 *   <li>Falls back to the first visible side-panel logout-tab widget
 *       across the three OSRS toplevel layouts.</li>
 *   <li>Returns {@link OptionalInt#empty()} when nothing is visible.</li>
 *   <li><b>Client-thread-only:</b> throws {@link IllegalStateException}
 *       when invoked off the client thread.</li>
 * </ul>
 */
public class RecorderLogoutActionTest
{
	@Test
	public void innerLogoutWidgetVisible_returnsInnerLogoutWidgetId()
	{
		Client client = mock(Client.class);
		when(client.isClientThread()).thenReturn(true);
		Widget innerLogout = mock(Widget.class);
		when(innerLogout.isHidden()).thenReturn(false);
		when(client.getWidget(InterfaceID.Logout.LOGOUT)).thenReturn(innerLogout);

		OptionalInt result = new RecorderLogoutAction(client).nextLogoutWidgetId();

		assertTrue("expected non-empty Optional, was empty", result.isPresent());
		assertEquals(InterfaceID.Logout.LOGOUT, result.getAsInt());
	}

	@Test
	public void onlyTabWidgetVisible_returnsThatTabId()
	{
		// Inner panel hidden / absent — fall through to the side-panel
		// tab probe. The three STONE10 candidates are tried in order
		// (Toplevel, ToplevelOsm, ToplevelPreEoc); first visible wins.
		Client client = mock(Client.class);
		when(client.isClientThread()).thenReturn(true);
		when(client.getWidget(InterfaceID.Logout.LOGOUT)).thenReturn(null);
		when(client.getWidget(InterfaceID.Toplevel.STONE10)).thenReturn(null);

		Widget tab = mock(Widget.class);
		when(tab.isHidden()).thenReturn(false);
		when(client.getWidget(InterfaceID.ToplevelOsm.STONE10)).thenReturn(tab);

		OptionalInt result = new RecorderLogoutAction(client).nextLogoutWidgetId();

		assertTrue("expected non-empty Optional, was empty", result.isPresent());
		assertEquals(InterfaceID.ToplevelOsm.STONE10, result.getAsInt());
	}

	@Test
	public void nothingVisible_returnsEmpty()
	{
		// All four candidate widgets either null or hidden — no logout
		// affordance currently on screen, so return empty and let the
		// caller (LogoutStep) retry on the next tick.
		Client client = mock(Client.class);
		when(client.isClientThread()).thenReturn(true);
		// Two flavours of "not visible": null widget AND hidden widget.
		when(client.getWidget(InterfaceID.Logout.LOGOUT)).thenReturn(null);
		Widget hidden = mock(Widget.class);
		when(hidden.isHidden()).thenReturn(true);
		when(client.getWidget(InterfaceID.Toplevel.STONE10)).thenReturn(hidden);
		when(client.getWidget(InterfaceID.ToplevelOsm.STONE10)).thenReturn(null);
		when(client.getWidget(InterfaceID.ToplevelPreEoc.STONE10)).thenReturn(null);

		OptionalInt result = new RecorderLogoutAction(client).nextLogoutWidgetId();

		assertTrue("expected empty Optional, was present with id "
			+ (result.isPresent() ? result.getAsInt() : 0), result.isEmpty());
	}

	@Test
	public void calledOffClientThread_throwsIllegalStateException() throws Exception
	{
		// The contract is client-thread-only: callers (LogoutStep) MUST
		// already be on the client thread per the Step lifecycle. Any
		// off-thread caller is a programming error — surface it loudly
		// at first invocation, not at a downstream symptom.
		Client client = mock(Client.class);
		when(client.isClientThread()).thenReturn(false);
		RecorderLogoutAction action = new RecorderLogoutAction(client);

		// Run from a real worker thread so the failure mode is observed
		// as it would be in production. mock() alone would also work,
		// but a real thread also exercises the not-on-the-client-thread
		// path the contract is meant to catch.
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		Thread worker = new Thread(() ->
		{
			try
			{
				action.nextLogoutWidgetId();
				fail("expected IllegalStateException when invoked off the client thread");
			}
			catch (Throwable t)
			{
				thrown.set(t);
			}
		}, "RecorderLogoutAction-off-thread-test");
		worker.start();
		worker.join(2_000);

		assertNotNull("worker should have completed (with an exception)", thrown.get());
		assertTrue("expected IllegalStateException, was " + thrown.get().getClass().getSimpleName(),
			thrown.get() instanceof IllegalStateException);
		assertTrue("expected message to mention client thread, was: " + thrown.get().getMessage(),
			thrown.get().getMessage() != null
				&& thrown.get().getMessage().toLowerCase().contains("client thread"));
	}
}
