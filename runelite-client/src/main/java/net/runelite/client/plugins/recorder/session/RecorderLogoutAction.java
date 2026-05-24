package net.runelite.client.plugins.recorder.session;

import java.util.Objects;
import java.util.OptionalInt;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.sequence.artemis.LogoutAction;

/**
 * Observation-only {@link LogoutAction} implementation for the recorder
 * plugin. Reads logout-widget visibility from the {@link Client} and
 * returns the id of the next widget that {@code LogoutStep} should
 * click to advance the logout sequence.
 *
 * <p><b>Threading contract — client-thread-only.</b>
 * {@link #nextLogoutWidgetId()} MUST be invoked on the client thread.
 * Off-thread calls throw {@link IllegalStateException} immediately at
 * the top of the method — failure is loud at first off-thread call, not
 * at a downstream symptom. The intended caller is {@code LogoutStep},
 * whose {@code check()} runs on the client thread per the engine's
 * {@code sequence/Step.java:29-43} lifecycle, so a marshal-and-block
 * wrapper would be unnecessary overhead.
 *
 * <p>The legacy {@code recorder/scripts/LogoutHelper.tryLogout()} bakes
 * in two unsafe patterns the {@link LogoutAction} interface Javadoc
 * (lines 14-26) explicitly bans:
 * <ul>
 *   <li>{@code clientThread.invokeLater + latch.await()} — deadlocks
 *       when called from the client thread (which Step lifecycle uses).</li>
 *   <li>{@code dispatcher.dispatch(CLICK_WIDGET)} — silently dropped
 *       inside a running dispatcher task.</li>
 * </ul>
 * This class deliberately uses neither — it only reads widget state,
 * never dispatches input, never blocks, and never marshals threads.
 *
 * <p><b>Detection order</b> (mirrors {@code LogoutHelper.tryLogout()}
 * but without the dispatch loop):
 * <ol>
 *   <li>{@link InterfaceID.Logout#LOGOUT} — the inner "Click here to
 *       logout" widget that appears after the side-panel tab opens.
 *       If visible, this is the next click target.</li>
 *   <li>The {@code STONE10} side-panel-tab id across the three OSRS
 *       toplevel layouts ({@code Toplevel}, {@code ToplevelOsm},
 *       {@code ToplevelPreEoc}). Probed in that order; the first
 *       visible candidate is the next click target.</li>
 *   <li>If nothing is visible — return {@link OptionalInt#empty()} and
 *       let the caller retry on the next tick.</li>
 * </ol>
 *
 * <p>Visibility check: a widget is "visible" if
 * {@code widget != null && !widget.isHidden()}. Same definition the
 * legacy helper uses.
 */
public final class RecorderLogoutAction implements LogoutAction
{
	/** Ordered list of side-panel logout-tab candidates. OSRS renders
	 *  exactly one toplevel layout at a time; the others return null
	 *  or hidden. Order matters only for log clarity. */
	private static final int[] STONE10_CANDIDATES = {
		InterfaceID.Toplevel.STONE10,
		InterfaceID.ToplevelOsm.STONE10,
		InterfaceID.ToplevelPreEoc.STONE10,
	};

	private final Client client;

	public RecorderLogoutAction(Client client)
	{
		this.client = Objects.requireNonNull(client, "client");
	}

	@Override
	public OptionalInt nextLogoutWidgetId()
	{
		if (!client.isClientThread())
		{
			throw new IllegalStateException(
				"RecorderLogoutAction.nextLogoutWidgetId must be called on the client thread");
		}

		Widget innerLogout = client.getWidget(InterfaceID.Logout.LOGOUT);
		if (innerLogout != null && !innerLogout.isHidden())
		{
			return OptionalInt.of(InterfaceID.Logout.LOGOUT);
		}

		for (int candidate : STONE10_CANDIDATES)
		{
			Widget tab = client.getWidget(candidate);
			if (tab != null && !tab.isHidden())
			{
				return OptionalInt.of(candidate);
			}
		}

		return OptionalInt.empty();
	}
}
